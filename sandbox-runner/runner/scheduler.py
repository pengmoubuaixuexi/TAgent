"""Fixed-size worker pool with a per-user round-robin queue.

Nobody has a quota: users simply take turns. A job that cannot start within the queue timeout
finishes as BUSY, so a caller never waits forever.
"""
import threading
import time
from collections import OrderedDict, deque
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Optional


@dataclass(eq=False)
class Job:
    id: str
    user: str
    run: str
    payload: Any
    submitted: float
    deadline: float
    state: str = "queued"  # queued | running | finished
    started: Optional[float] = None
    finished: Optional[float] = None
    result: Optional[dict] = None
    extra: Dict[str, Any] = field(default_factory=dict)


class FairScheduler:
    def __init__(self, workers: int, queue_timeout: float, execute: Callable[[Job], dict],
                 clock: Callable[[], float] = time.monotonic, on_finish: Optional[Callable[[Job], None]] = None):
        self._execute = execute
        self._clock = clock
        self._queue_timeout = queue_timeout
        self._on_finish = on_finish
        self._lock = threading.Condition()
        self._queues: "OrderedDict[str, deque]" = OrderedDict()
        self._jobs: Dict[str, Job] = {}
        self._busy_runs = set()
        self._running = 0
        self._stopped = False
        self._threads = [threading.Thread(target=self._work, name="sandbox-worker-%d" % i, daemon=True)
                         for i in range(workers)]
        self._threads.append(threading.Thread(target=self._expire_loop, name="sandbox-expiry", daemon=True))
        for thread in self._threads:
            thread.start()

    # ------------------------------------------------------------------ public

    def submit(self, job_id: str, user: str, run: str, payload: Any) -> Job:
        now = self._clock()
        job = Job(id=job_id, user=user, run=run, payload=payload, submitted=now, deadline=now + self._queue_timeout)
        with self._lock:
            self._jobs[job_id] = job
            self._queues.setdefault(user, deque()).append(job)
            self._lock.notify_all()
        return job

    def snapshot(self, job_id: str) -> Optional[dict]:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            now = self._clock()
            out = {"executionId": job.id, "state": job.state,
                   "queuedMs": int(((job.started or job.finished or now) - job.submitted) * 1000)}
            if job.state == "queued":
                out["position"] = self._ahead(job)
            if job.started is not None:
                out["runningMs"] = int(((job.finished or now) - job.started) * 1000)
            if job.state == "finished":
                out["result"] = job.result
            return out

    def stats(self) -> dict:
        with self._lock:
            return {"queued": sum(len(q) for q in self._queues.values()), "running": self._running}

    def run_active(self, run: str) -> bool:
        with self._lock:
            return run in self._busy_runs or any(j.run == run for q in self._queues.values() for j in q)

    def forget(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.pop(job_id, None)

    def finished_before(self, cutoff: float):
        with self._lock:
            return [j for j in self._jobs.values() if j.state == "finished" and j.finished is not None and j.finished < cutoff]

    def stop(self):
        with self._lock:
            self._stopped = True
            self._lock.notify_all()

    # ------------------------------------------------------------------ internals

    def _ahead(self, job: Job) -> int:
        """Jobs that start before this one under round-robin service."""
        order = list(self._queues.keys())
        mine = self._queues.get(job.user)
        if mine is None or not any(queued is job for queued in mine):
            return 0
        index = next(i for i, queued in enumerate(mine) if queued is job)
        my_turn = order.index(job.user)
        ahead = index
        for turn, user in enumerate(order):
            if user == job.user:
                continue
            ahead += min(len(self._queues[user]), index + (1 if turn < my_turn else 0))
        return ahead

    def _next(self) -> Optional[Job]:
        """The next user in turn whose earliest waiting job belongs to an idle run.

        Jobs of one run share its working directory, so they run one at a time and in order.
        """
        for user in list(self._queues.keys()):
            queue = self._queues[user]
            for index, job in enumerate(queue):
                if job.state != "queued" or job.run in self._busy_runs:
                    continue
                del queue[index]
                if queue:
                    self._queues.move_to_end(user)
                else:
                    del self._queues[user]
                return job
        return None

    def _expire(self):
        now = self._clock()
        for user in list(self._queues.keys()):
            queue = self._queues[user]
            kept = deque(j for j in queue if j.deadline > now)
            for job in queue:
                if job.deadline <= now:
                    self._finish(job, {"status": "BUSY", "message": "沙箱繁忙，排队超过 %d 秒仍未开始，请稍后重试"
                                                                   % int(self._queue_timeout)})
            if kept:
                self._queues[user] = kept
            else:
                del self._queues[user]

    def _finish(self, job: Job, result: dict):
        job.state = "finished"
        job.finished = self._clock()
        job.result = result
        if self._on_finish is not None:
            try:
                self._on_finish(job)
            except Exception:  # metrics must never break scheduling
                pass

    def _expire_loop(self):
        while True:
            with self._lock:
                if self._stopped:
                    return
                self._expire()
                self._lock.wait(timeout=0.5)

    def _work(self):
        while True:
            with self._lock:
                job = None
                while job is None:
                    if self._stopped:
                        return
                    self._expire()
                    job = self._next()
                    if job is None:
                        self._lock.wait(timeout=0.5)
                job.state = "running"
                job.started = self._clock()
                self._running += 1
                self._busy_runs.add(job.run)
            try:
                result = self._execute(job)
            except Exception as error:  # an executor bug must not lose the job
                result = {"status": "ERROR", "message": "沙箱内部错误：%s" % type(error).__name__}
            with self._lock:
                self._running -= 1
                self._busy_runs.discard(job.run)
                self._finish(job, result)
                self._lock.notify_all()
