"""HTTP API of the sandbox runner. Reachable only on the internal network; every call needs the shared token."""
import hmac
import json
import os
import shutil
import threading
import time
import uuid
from contextlib import asynccontextmanager
from typing import List, Optional

import docker
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest

from .executor import KEY, Executor, RunDirs, StagedInput, safe_name
from .scheduler import FairScheduler, Job
from .settings import Settings

settings = Settings.load()
dirs = RunDirs(settings)
executor = Executor(settings, dirs, docker.APIClient(base_url=os.environ.get("DOCKER_HOST", "unix://var/run/docker.sock"),
                                                     version="auto", timeout=30))

QUEUE_WAIT = Histogram("sandbox_queue_wait_seconds", "Time from submission to start",
                       buckets=(0.1, 0.5, 1, 2, 5, 10, 20, 30, 45, 60, 120))
EXEC_TIME = Histogram("sandbox_execution_seconds", "Container run time",
                      buckets=(0.5, 1, 2, 5, 10, 20, 30, 45, 60, 90, 120))
RESULTS = Counter("sandbox_executions_total", "Finished executions by status", ["status"])
QUEUED = Gauge("sandbox_queue_depth", "Executions waiting to start")
RUNNING = Gauge("sandbox_running", "Executions running now")


def on_finish(job: Job):
    status = (job.result or {}).get("status", "ERROR")
    RESULTS.labels(status=status).inc()
    if job.started is not None:
        QUEUE_WAIT.observe(max(0.0, job.started - job.submitted))
    else:
        QUEUE_WAIT.observe(max(0.0, (job.finished or job.submitted) - job.submitted))
    staging = job.extra.get("staging")
    if staging:
        shutil.rmtree(staging, ignore_errors=True)


def execute(job: Job) -> dict:
    result = executor.execute(job.run, job.id, job.payload)
    EXEC_TIME.observe(result.get("durationMs", 0) / 1000)
    return result


scheduler = FairScheduler(settings.workers, settings.queue_timeout, execute, on_finish=on_finish)
pending_run_removals = set()
removal_lock = threading.Lock()


@asynccontextmanager
async def lifespan(_app):
    executor.cleanup_stale()
    shutil.rmtree(dirs.results, ignore_errors=True)
    shutil.rmtree(dirs.staging, ignore_errors=True)
    os.makedirs(dirs.results, mode=0o700, exist_ok=True)
    os.makedirs(dirs.staging, mode=0o700, exist_ok=True)
    threading.Thread(target=janitor, name="sandbox-janitor", daemon=True).start()
    yield
    scheduler.stop()


app = FastAPI(title="TAgent sandbox runner", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


def authorized(authorization: Optional[str] = Header(default=None)):
    expected = "Bearer " + settings.token
    if not authorization or not hmac.compare_digest(authorization.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(status_code=401, detail="unauthorized")


def janitor():
    while True:
        time.sleep(60)
        try:
            cutoff = time.monotonic() - settings.result_ttl
            for job in scheduler.finished_before(cutoff):
                scheduler.forget(job.id)
                shutil.rmtree(os.path.join(dirs.results, job.id), ignore_errors=True)
            now = time.time()
            for name in os.listdir(dirs.runs):
                path = os.path.join(dirs.runs, name)
                removable = name in pending_run_removals or now - os.lstat(path).st_mtime > settings.run_ttl
                if removable and not scheduler.run_active(name):
                    shutil.rmtree(path, ignore_errors=True)
                    with removal_lock:
                        pending_run_removals.discard(name)
        except Exception:
            pass


@app.get("/healthz")
def healthz():
    return {"ok": True, **scheduler.stats()}


@app.get("/metrics")
def metrics():
    stats = scheduler.stats()
    QUEUED.set(stats["queued"])
    RUNNING.set(stats["running"])
    return PlainTextResponse(generate_latest().decode("utf-8"), media_type=CONTENT_TYPE_LATEST)


@app.post("/v1/executions", dependencies=[Depends(authorized)])
def submit(meta: str = Form(...), code: str = Form(default=""), inputs: List[UploadFile] = File(default=[])):
    try:
        spec = json.loads(meta)
    except ValueError:
        raise HTTPException(status_code=400, detail="meta must be JSON")
    run, user, kind = spec.get("run", ""), spec.get("user", ""), spec.get("kind", "python")
    if not KEY.match(run) or not KEY.match(user):
        raise HTTPException(status_code=400, detail="invalid run or user key")
    if kind not in ("python", "convert"):
        raise HTTPException(status_code=400, detail="unsupported kind")
    if kind == "python" and (not code.strip() or len(code) > 200_000):
        raise HTTPException(status_code=400, detail="code must be 1-200000 characters")
    timeout = max(1, min(int(spec.get("timeoutSeconds") or settings.exec_timeout), settings.max_exec_timeout))
    execution_id = uuid.uuid4().hex
    staging = os.path.join(dirs.staging, execution_id)
    os.makedirs(staging, mode=0o700)
    staged, total, names = [], 0, set()
    limit = settings.max_input_total_mb * 1024 * 1024
    try:
        for upload in inputs or []:
            name = safe_name(upload.filename or "input", "input")
            stem, dot, ext = name.rpartition(".")
            unique, counter = name, 1
            while unique in names:
                counter += 1
                unique = ("%s-%d.%s" % (stem, counter, ext)) if dot else "%s-%d" % (name, counter)
            names.add(unique)
            path = os.path.join(staging, str(len(staged)))
            size = 0
            with open(path, "wb") as out:
                while True:
                    chunk = upload.file.read(1 << 20)
                    if not chunk:
                        break
                    size += len(chunk)
                    total += len(chunk)
                    if total > limit:
                        raise HTTPException(status_code=413, detail="inputs exceed %d MB" % settings.max_input_total_mb)
                    out.write(chunk)
            staged.append(StagedInput(unique, path, size))
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    convert = None
    if kind == "convert":
        convert = spec.get("convert") or {}
        if convert.get("format") not in ("docx", "pdf") or not staged:
            shutil.rmtree(staging, ignore_errors=True)
            raise HTTPException(status_code=400, detail="convert needs format docx/pdf and one input")
        convert = {"format": convert["format"], "input": staged[0].name,
                   "output": safe_name(convert.get("output") or "document." + convert["format"], "document." + convert["format"]),
                   "title": str(convert.get("title") or "")[:200]}
    job = scheduler.submit(execution_id, user, run, {"kind": kind, "code": code, "inputs": staged, "timeout": timeout,
                                                      "convert": convert})
    job.extra["staging"] = staging
    return JSONResponse(status_code=202, content=scheduler.snapshot(execution_id))


@app.get("/v1/executions/{execution_id}", dependencies=[Depends(authorized)])
def status(execution_id: str):
    snapshot = scheduler.snapshot(execution_id)
    if snapshot is None:
        raise HTTPException(status_code=404, detail="unknown execution")
    return snapshot


@app.get("/v1/executions/{execution_id}/outputs/{index}", dependencies=[Depends(authorized)])
def output(execution_id: str, index: int):
    snapshot = scheduler.snapshot(execution_id)
    if snapshot is None or snapshot.get("state") != "finished":
        raise HTTPException(status_code=404, detail="unknown execution")
    files = (snapshot.get("result") or {}).get("outputs") or []
    if index < 0 or index >= len(files):
        raise HTTPException(status_code=404, detail="unknown output")
    path = os.path.join(dirs.results, execution_id, str(index))
    if not os.path.isfile(path):
        raise HTTPException(status_code=410, detail="output expired")
    return FileResponse(path, media_type="application/octet-stream")


@app.delete("/v1/runs/{run}", dependencies=[Depends(authorized)])
def remove_run(run: str):
    if not KEY.match(run):
        raise HTTPException(status_code=400, detail="invalid run key")
    if scheduler.run_active(run):
        with removal_lock:
            pending_run_removals.add(run)
        return {"removed": False, "deferred": True}
    dirs.remove_run(run)
    return {"removed": True}
