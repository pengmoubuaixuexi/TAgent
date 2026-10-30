"""One execution = one fresh container, removed afterwards.

The runner and the execution containers share one named volume. Each run gets a directory
`runs/<run>` in it; an execution container mounts only that directory (volume subpath) at
/sandbox, so it can never see another run. Inputs and the code are written by the runner as
root and are read-only to the sandbox user (uid 1000).
"""
import hashlib
import os
import re
import shutil
import stat
import threading
import time
from dataclasses import dataclass
from typing import List, Optional

from .settings import Settings

SANDBOX_UID = 1000
NAME_SAFE = re.compile(r"[^0-9A-Za-z一-鿿._ ()\-]")
KEY = re.compile(r"^[a-z0-9-]{8,64}$")


def safe_name(name: str, fallback: str = "file") -> str:
    base = os.path.basename((name or "").replace("\\", "/")).strip()
    base = NAME_SAFE.sub("_", base).strip(" .")
    if not base or base in (".", ".."):
        base = fallback
    if len(base) > 120:
        stem, dot, ext = base.rpartition(".")
        base = (stem[:100] + "." + ext[:15]) if dot else base[:120]
    return base


@dataclass
class StagedInput:
    name: str
    path: str
    size: int


class RunDirs:
    """Directory layout inside the shared volume, as seen by the runner."""

    def __init__(self, settings: Settings):
        self.root = settings.root
        self.runs = os.path.join(self.root, "runs")
        self.results = os.path.join(self.root, "results")
        self.staging = os.path.join(self.root, "staging")
        for path in (self.runs, self.results, self.staging):
            os.makedirs(path, mode=0o700, exist_ok=True)
            os.chmod(path, 0o711 if path == self.runs else 0o700)

    def run(self, run: str) -> str:
        if not KEY.match(run):
            raise ValueError("invalid run key")
        return os.path.join(self.runs, run)

    def prepare(self, run: str) -> str:
        base = self.run(run)
        if not os.path.isdir(base):
            os.makedirs(base, mode=0o755, exist_ok=True)
        os.chmod(base, 0o755)
        for sub, owner, mode in (("workspace", SANDBOX_UID, 0o755), ("output", SANDBOX_UID, 0o755),
                                 ("input", 0, 0o555), (".run", 0, 0o555)):
            path = os.path.join(base, sub)
            if os.path.islink(path):
                raise RuntimeError("unexpected link in run directory")
            os.makedirs(path, exist_ok=True)
            os.chown(path, owner, owner)
            os.chmod(path, mode)
        return base

    def remove_run(self, run: str):
        shutil.rmtree(self.run(run), ignore_errors=True)


def tree_size(path: str, stop_after: Optional[int] = None) -> int:
    total = 0
    for current, dirs, files in os.walk(path, followlinks=False):
        for name in files:
            try:
                total += os.lstat(os.path.join(current, name)).st_size
                if stop_after is not None and total > stop_after:
                    return total
            except OSError:
                pass
    return total


def clip(text: str, limit: int) -> (str, bool):
    if len(text) <= limit:
        return text, False
    head = int(limit * 0.6)
    tail = limit - head
    omitted = len(text) - head - tail
    return text[:head] + "\n…（中间省略 %d 个字符）…\n" % omitted + text[-tail:], True


class Executor:
    def __init__(self, settings: Settings, dirs: RunDirs, docker_api):
        self.settings = settings
        self.dirs = dirs
        self.api = docker_api

    def execute(self, run: str, execution_id: str, payload: dict) -> dict:
        base = self.dirs.prepare(run)
        os.utime(base)

        def disk_rejection(reason, result=None):
            # Only this run's disposable state is removed, after its container has stopped.
            self.dirs.remove_run(run)
            out = result if result is not None else {"status": "DISK_LIMIT", "message": reason}
            out.update(outputs=[], skippedOutputs=[], inputs=[], workspaceBytes=0)
            return out

        reason = self.disk_limit_reason(base)
        if reason:
            return disk_rejection(reason)
        inputs = self.place_inputs(base, payload["inputs"])
        reason = self.disk_limit_reason(base)
        if reason:
            return disk_rejection(reason)
        if payload["kind"] == "python":
            self.write_code(base, payload["code"])
            command = ["python", "-X", "utf8", "/opt/sandbox/entry.py"]
        else:
            convert = payload["convert"]
            command = ["python", "-X", "utf8", "/opt/sandbox/convert.py", convert["format"], convert["input"],
                       convert["output"], convert.get("title", "")]
        result = self.run_container(base, run, execution_id, command, payload["timeout"])
        if result["status"] == "DISK_LIMIT":
            return disk_rejection(result["message"], result)
        collected = self.collect_outputs(base, execution_id)
        result.update(outputs=collected["files"], skippedOutputs=collected["skipped"], inputs=inputs,
                      workspaceBytes=tree_size(os.path.join(base, "workspace")))
        message = describe(result["status"], self.settings, payload["timeout"])
        if message:
            result["message"] = message
        return result

    # ------------------------------------------------------------------ files

    def place_inputs(self, base: str, staged: List[StagedInput]) -> List[str]:
        """Copies staged inputs into input/ as root-owned read-only files."""
        target_dir = os.path.join(base, "input")
        os.chmod(target_dir, 0o755)
        placed = []
        try:
            for item in staged:
                name = safe_name(item.name, "input")
                target = os.path.join(target_dir, name)
                if os.path.lexists(target):
                    if os.path.islink(target) or not os.path.isfile(target):
                        raise RuntimeError("unexpected entry in input directory")
                    os.chmod(target, 0o644)
                shutil.copyfile(item.path, target, follow_symlinks=False)
                os.chown(target, 0, 0)
                os.chmod(target, 0o444)
                placed.append(name)
        finally:
            os.chmod(target_dir, 0o555)
        return placed

    def write_code(self, base: str, code: str):
        run_dir = os.path.join(base, ".run")
        os.chmod(run_dir, 0o755)
        try:
            path = os.path.join(run_dir, "main.py")
            if os.path.lexists(path):
                os.chmod(path, 0o644)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(code)
            os.chown(path, 0, 0)
            os.chmod(path, 0o444)
        finally:
            os.chmod(run_dir, 0o555)

    def collect_outputs(self, base: str, execution_id: str) -> dict:
        """Copies regular files from output/ into the result directory. Links and specials are ignored."""
        source = os.path.join(base, "output")
        target = os.path.join(self.dirs.results, execution_id)
        os.makedirs(target, mode=0o700, exist_ok=True)
        files, skipped, total = [], [], 0
        max_file = self.settings.max_output_file_mb * 1024 * 1024
        max_total = self.settings.max_output_total_mb * 1024 * 1024
        root_fd = os.open(source, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0))
        try:
            for current, dirs, names in os.walk(source, followlinks=False):
                dirs[:] = sorted(d for d in dirs if not os.path.islink(os.path.join(current, d)))
                depth = os.path.relpath(current, source).count(os.sep)
                if depth > 2:
                    dirs[:] = []
                for name in sorted(names):
                    path = os.path.join(current, name)
                    relative = os.path.relpath(path, source).replace(os.sep, "/")
                    info = os.lstat(path)
                    if not stat.S_ISREG(info.st_mode):
                        skipped.append({"name": relative, "reason": "不是普通文件"})
                        continue
                    if len(files) >= self.settings.max_outputs:
                        skipped.append({"name": relative, "reason": "输出文件超过 %d 个" % self.settings.max_outputs})
                        continue
                    if info.st_size > max_file:
                        skipped.append({"name": relative, "reason": "单个文件超过 %d MB" % self.settings.max_output_file_mb})
                        continue
                    if total + info.st_size > max_total:
                        skipped.append({"name": relative, "reason": "输出总量超过 %d MB" % self.settings.max_output_total_mb})
                        continue
                    digest = hashlib.sha256()
                    index = len(files)
                    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
                    with os.fdopen(fd, "rb") as src, open(os.path.join(target, str(index)), "wb") as dst:
                        copied = 0
                        while True:
                            chunk = src.read(1 << 16)
                            if not chunk:
                                break
                            copied += len(chunk)
                            if copied > max_file:
                                raise RuntimeError("output grew while copying")
                            digest.update(chunk)
                            dst.write(chunk)
                    total += copied
                    files.append({"index": index, "name": relative, "size": copied, "sha256": digest.hexdigest()})
        finally:
            os.close(root_fd)
        return {"files": files, "skipped": skipped}

    # ------------------------------------------------------------------ containers

    def disk_limit_reason(self, base: str) -> Optional[str]:
        limit = self.settings.workspace_limit_mb * 1024 * 1024
        if tree_size(base, stop_after=limit) > limit:
            return "本次任务临时目录超过 %d MB，已停止执行并清理，可重新上传所需文件后重试" % self.settings.workspace_limit_mb
        if shutil.disk_usage(base).free < self.settings.min_free_disk_mb * 1024 * 1024:
            return "沙箱磁盘剩余空间不足，已停止执行并清理本次任务临时目录，请稍后重试"
        return None

    def run_container(self, base: str, run: str, execution_id: str, command: List[str], timeout: int) -> dict:
        s = self.settings
        host_config = self.api.create_host_config(
            network_mode="none",
            read_only=True,
            mem_limit="%dm" % s.memory_mb,
            memswap_limit="%dm" % s.memory_mb,
            pids_limit=s.pids,
            nano_cpus=int(s.cpus * 1_000_000_000),
            cpu_shares=256,
            cap_drop=["ALL"],
            security_opt=["no-new-privileges:true"],
            tmpfs={"/tmp": "rw,nosuid,nodev,size=%dm,mode=1777" % s.tmp_mb},
            mounts=[{"Type": "volume", "Source": s.volume, "Target": "/sandbox", "ReadOnly": False,
                     "VolumeOptions": {"NoCopy": True, "Subpath": "runs/" + run}}],
            # The entry script caps printed output well below this, so the log is never rotated.
            log_config={"Type": "json-file", "Config": {"max-size": "16m", "max-file": "1"}},
        )
        container = self.api.create_container(
            image=s.image,
            command=command,
            user="%d:%d" % (SANDBOX_UID, SANDBOX_UID),
            working_dir="/sandbox/workspace",
            environment={"HOME": "/tmp", "MPLBACKEND": "Agg", "MPLCONFIGDIR": "/tmp/matplotlib",
                         "PYTHONDONTWRITEBYTECODE": "1", "PYTHONUNBUFFERED": "1", "PYTHONIOENCODING": "utf-8",
                         "SANDBOX_FILE_LIMIT_BYTES": str(s.file_limit_mb * 1024 * 1024)},
            network_disabled=True,
            host_config=host_config,
            labels={"tagent.sandbox": "1", "tagent.run": run, "tagent.execution": execution_id},
        )
        cid = container["Id"]
        started = time.monotonic()
        timed_out = False
        monitor_stop = threading.Event()
        disk_failure = []

        def monitor_disk():
            while not monitor_stop.wait(s.disk_check_interval_ms / 1000.0):
                try:
                    reason = self.disk_limit_reason(base)
                except OSError:
                    reason = "无法检查沙箱磁盘空间，已停止执行并清理本次任务临时目录"
                if reason:
                    disk_failure.append(reason)
                    try:
                        self.api.kill(cid)
                    except Exception:
                        # The final force removal below also stops it if it exited or kill failed.
                        pass
                    return

        monitor = threading.Thread(target=monitor_disk, name="sandbox-disk-" + execution_id, daemon=True)
        try:
            self.api.start(cid)
            monitor.start()
            try:
                outcome = self.api.wait(cid, timeout=timeout)
                exit_code = int(outcome.get("StatusCode", -1))
            except Exception:  # requests ReadTimeout / ConnectionError while waiting
                timed_out = True
                try:
                    self.api.kill(cid)
                except Exception:
                    pass
                try:
                    self.api.wait(cid, timeout=10)
                except Exception:
                    pass
                exit_code = -1
            duration = time.monotonic() - started
            state = self.api.inspect_container(cid).get("State", {})
            oom = bool(state.get("OOMKilled"))
            stdout = self.api.logs(cid, stdout=True, stderr=False).decode("utf-8", "replace")
            stderr = self.api.logs(cid, stdout=False, stderr=True).decode("utf-8", "replace")
        finally:
            monitor_stop.set()
            if monitor.ident is not None:
                monitor.join()
            try:
                self.api.remove_container(cid, force=True)
            except Exception:
                pass
        # Fast bursts may finish between monitor ticks; do not collect or retain oversized outputs.
        if not disk_failure:
            try:
                reason = self.disk_limit_reason(base)
            except OSError:
                reason = "无法检查沙箱磁盘空间，已停止执行并清理本次任务临时目录"
            if reason:
                disk_failure.append(reason)
        stdout, out_cut = clip(stdout, s.max_log_chars)
        stderr, err_cut = clip(stderr, s.max_log_chars)
        out_cut = out_cut or "[输出过多，后面的内容已截断]" in stdout
        err_cut = err_cut or "[输出过多，后面的内容已截断]" in stderr
        if disk_failure:
            status = "DISK_LIMIT"
        elif timed_out:
            status = "TIMEOUT"
        elif oom or exit_code == 137:
            status = "OOM" if oom else "KILLED"
        else:
            status = "OK" if exit_code == 0 else "ERROR"
        result = {"status": status, "exitCode": exit_code, "durationMs": int(duration * 1000),
                  "stdout": stdout, "stderr": stderr, "stdoutTruncated": out_cut, "stderrTruncated": err_cut}
        if disk_failure:
            result["message"] = disk_failure[0]
        return result

    def cleanup_stale(self):
        """Containers left by a previous runner process."""
        try:
            for container in self.api.containers(all=True, filters={"label": "tagent.sandbox=1"}):
                try:
                    self.api.remove_container(container["Id"], force=True)
                except Exception:
                    pass
        except Exception:
            pass


def describe(status: str, settings: Settings, timeout: int) -> Optional[str]:
    return {
        "TIMEOUT": "执行超过 %d 秒，已强制停止" % timeout,
        "OOM": "内存超过 %d MB，已被终止" % settings.memory_mb,
        "KILLED": "进程被系统终止（可能是内存或进程数超限）",
        "ERROR": "代码运行出错，见 stderr",
    }.get(status)
