"""Scheduling tests: users take turns, the pool size is respected, waits are bounded, a run's jobs never overlap."""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from runner.scheduler import FairScheduler  # noqa: E402


def wait_all(scheduler, ids, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if all(scheduler.snapshot(i)["state"] == "finished" for i in ids):
            return
        time.sleep(0.02)
    raise AssertionError("jobs did not finish")


def test_users_take_turns_with_one_worker():
    order, gate = [], threading.Event()

    def execute(job):
        gate.wait(5)
        order.append(job.id)
        return {"status": "OK"}

    scheduler = FairScheduler(1, 60, execute)
    try:
        # alice queues four jobs before bob and carol queue theirs; the first one occupies the worker.
        ids = [scheduler.submit("a%d" % i, "alice", "run-a%d" % i, None).id for i in range(4)]
        time.sleep(0.1)
        ids += [scheduler.submit("b0", "bob", "run-b0", None).id, scheduler.submit("c0", "carol", "run-c0", None).id]
        assert scheduler.snapshot("b0")["position"] == 1
        assert scheduler.snapshot("c0")["position"] == 2
        assert scheduler.snapshot("a3")["position"] == 4
        gate.set()
        wait_all(scheduler, ids)
        assert order[:4] == ["a0", "a1", "b0", "c0"], order
    finally:
        scheduler.stop()


def test_pool_size_is_never_exceeded_and_ten_jobs_all_finish():
    lock, running, peak = threading.Lock(), [0], [0]

    def execute(job):
        with lock:
            running[0] += 1
            peak[0] = max(peak[0], running[0])
        time.sleep(0.05)
        with lock:
            running[0] -= 1
        return {"status": "OK"}

    scheduler = FairScheduler(3, 60, execute)
    try:
        ids = [scheduler.submit("j%d" % i, "user%d" % (i % 4), "run-%d" % i, None).id for i in range(10)]
        wait_all(scheduler, ids)
        assert peak[0] == 3
        assert all(scheduler.snapshot(i)["result"]["status"] == "OK" for i in ids)
    finally:
        scheduler.stop()


def test_waiting_longer_than_the_queue_timeout_finishes_as_busy():
    release = threading.Event()

    def execute(job):
        release.wait(5)
        return {"status": "OK"}

    scheduler = FairScheduler(1, 0.3, execute)
    try:
        scheduler.submit("first", "alice", "run-1", None)
        time.sleep(0.05)
        scheduler.submit("second", "bob", "run-2", None)
        deadline = time.monotonic() + 3
        while scheduler.snapshot("second")["state"] != "finished" and time.monotonic() < deadline:
            time.sleep(0.05)
        snapshot = scheduler.snapshot("second")
        assert snapshot["state"] == "finished"
        assert snapshot["result"]["status"] == "BUSY"
        assert "沙箱繁忙" in snapshot["result"]["message"]
        release.set()
    finally:
        scheduler.stop()


def test_jobs_of_one_run_never_overlap_and_keep_their_order():
    active, overlaps, order = set(), [], []
    lock = threading.Lock()

    def execute(job):
        with lock:
            if job.run in active:
                overlaps.append(job.id)
            active.add(job.run)
        time.sleep(0.05)
        with lock:
            active.discard(job.run)
            order.append(job.id)
        return {"status": "OK"}

    scheduler = FairScheduler(3, 60, execute)
    try:
        ids = [scheduler.submit("r%d" % i, "alice", "same-run", None).id for i in range(4)]
        ids.append(scheduler.submit("other", "alice", "other-run", None).id)
        wait_all(scheduler, ids)
        assert overlaps == []
        assert [i for i in order if i.startswith("r")] == ["r0", "r1", "r2", "r3"]
    finally:
        scheduler.stop()


def test_an_executor_crash_still_finishes_the_job():
    def execute(job):
        raise RuntimeError("boom")

    scheduler = FairScheduler(1, 60, execute)
    try:
        scheduler.submit("x", "alice", "run-x", None)
        wait_all(scheduler, ["x"])
        assert scheduler.snapshot("x")["result"]["status"] == "ERROR"
    finally:
        scheduler.stop()
