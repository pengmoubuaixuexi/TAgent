"""Disk watchdog and recovery, using small real files and a controlled Docker API (no daemon needed)."""
import os
import shutil
import sys
import threading
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from runner.executor import Executor, tree_size
from runner.settings import Settings


class Docker:
    def __init__(self, base, mode):
        self.base, self.mode = base, mode
        self.killed = threading.Event()
        self.removed = False
        self.created = False

    def create_host_config(self, **kwargs):
        return kwargs

    def create_container(self, **kwargs):
        self.created = True
        return {"Id": "test-container"}

    def start(self, cid):
        pass

    def wait(self, cid, timeout):
        if self.mode in ("growing", "burst"):
            for i in range(3):
                (self.base / "workspace" / (str(i) + ".bin")).write_bytes(b"x" * (512 * 1024))
        if self.mode == "growing":
            assert self.killed.wait(3), "watchdog did not terminate an oversized running container"
            return {"StatusCode": 137}
        if self.mode == "low-free":
            assert self.killed.wait(3), "watchdog did not terminate on host disk pressure"
            return {"StatusCode": 137}
        if self.mode == "timeout" and not self.killed.is_set():
            raise TimeoutError()
        return {"StatusCode": 0}

    def kill(self, cid):
        self.killed.set()

    def inspect_container(self, cid):
        return {"State": {"OOMKilled": False}}

    def logs(self, cid, **kwargs):
        return b""

    def remove_container(self, cid, **kwargs):
        self.removed = True


@pytest.fixture
def make_executor(tmp_path, monkeypatch):
    monkeypatch.setenv("SANDBOX_TOKEN", "a" * 24)
    settings = replace(Settings.load(), workspace_limit_mb=1, root=str(tmp_path))
    monkeypatch.setattr(shutil, "disk_usage", lambda path: SimpleNamespace(free=10 * 1024**3))
    base = tmp_path / "runs" / "run-test"
    other = tmp_path / "runs" / "other-run"
    other.mkdir(parents=True)
    (other / "keep.txt").write_text("another run")

    def prepare(run):
        for sub in ("workspace", "output", "input", ".run"):
            (base / sub).mkdir(parents=True, exist_ok=True)
        return str(base)

    def remove_run(run):
        assert run == "run-test"
        shutil.rmtree(base)

    def make(mode="ok"):
        api = Docker(base, mode)
        dirs = SimpleNamespace(prepare=prepare, remove_run=remove_run)
        executor = Executor(settings, dirs, api)
        # POSIX ownership and output collection are exercised by the live suite, not this Windows-safe fixture.
        monkeypatch.setattr(executor, "place_inputs", lambda *args: [])
        monkeypatch.setattr(executor, "write_code", lambda root, code: None)
        monkeypatch.setattr(executor, "collect_outputs", lambda *args: {"files": [], "skipped": []})
        return executor, api, base, other

    return make


def execute(executor, kind="python"):
    payload = {"inputs": [], "kind": kind, "code": "pass", "timeout": 3,
               "convert": {"format": "pdf", "input": "report.md", "output": "report.pdf"}}
    return executor.execute("run-test", "execution-test", payload)


def test_monitor_kills_multi_file_growth_and_cleans_only_its_run(make_executor):
    executor, api, base, other = make_executor("growing")
    result = execute(executor)
    assert result["status"] == "DISK_LIMIT"
    assert api.killed.is_set() and api.removed
    assert not base.exists()
    assert (other / "keep.txt").read_text() == "another run"
    assert result["outputs"] == [] and result["workspaceBytes"] == 0
    # Same run can execute again: no permanent over-limit rejection loop.
    api.mode = "ok"
    assert execute(executor)["status"] == "OK"


@pytest.mark.parametrize("kind", ["python", "convert"])
def test_fast_burst_between_monitor_ticks_is_not_reported_as_success(make_executor, kind):
    executor, api, base, _ = make_executor("burst")
    result = execute(executor, kind)
    assert result["status"] == "DISK_LIMIT"
    assert api.removed and not base.exists()


def test_oversized_leftover_is_cleaned_before_start_then_retry_succeeds(make_executor):
    executor, api, base, _ = make_executor()
    executor.dirs.prepare("run-test")
    (base / "workspace" / "old.bin").write_bytes(b"x" * (2 * 1024 * 1024))
    assert execute(executor)["status"] == "DISK_LIMIT"
    assert not api.created and not base.exists()
    assert execute(executor)["status"] == "OK"


def test_inputs_are_counted_before_a_container_is_created(make_executor, monkeypatch):
    executor, api, base, _ = make_executor()

    def place_inputs(root, staged):
        (Path(root) / "input" / "upload.bin").write_bytes(b"x" * (2 * 1024 * 1024))
        return ["upload.bin"]

    monkeypatch.setattr(executor, "place_inputs", place_inputs)
    assert execute(executor)["status"] == "DISK_LIMIT"
    assert not api.created and not base.exists()


def test_low_free_space_during_execution_stops_the_container(make_executor, monkeypatch):
    executor, api, base, _ = make_executor("low-free")
    monkeypatch.setattr(shutil, "disk_usage", lambda path: SimpleNamespace(free=0 if api.created else 10 * 1024**3))
    assert execute(executor)["status"] == "DISK_LIMIT"
    assert api.killed.is_set() and api.removed and not base.exists()


def test_monitor_errors_fail_closed(make_executor, monkeypatch):
    executor, api, base, _ = make_executor("low-free")
    check = executor.disk_limit_reason

    def unreadable(root):
        if api.created:
            raise OSError("cannot inspect disk")
        return check(root)

    monkeypatch.setattr(executor, "disk_limit_reason", unreadable)
    assert execute(executor)["status"] == "DISK_LIMIT"
    assert api.killed.is_set() and not base.exists()


def test_normal_execution_preserves_workspace_and_timeout_still_works(make_executor):
    executor, api, base, _ = make_executor()
    executor.dirs.prepare("run-test")
    (base / "workspace" / "keep.txt").write_text("intermediate result")
    assert execute(executor)["status"] == "OK"
    assert (base / "workspace" / "keep.txt").read_text() == "intermediate result"
    api.mode = "timeout"
    assert execute(executor)["status"] == "TIMEOUT"
    assert api.killed.is_set() and api.removed


def test_tree_size_can_stop_early(tmp_path):
    (tmp_path / "a").write_bytes(b"a" * 10)
    (tmp_path / "b").write_bytes(b"b" * 10)
    assert tree_size(str(tmp_path)) == 20
    assert tree_size(str(tmp_path), stop_after=5) == 10
