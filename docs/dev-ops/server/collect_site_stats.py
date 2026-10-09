#!/usr/bin/env python3
"""Produce private daily homepage counts using GoAccess, without a tracking database.

Run on the Linux Docker host as root every five minutes. No credentials or raw
visitor records are written to the published aggregate report.
"""
import argparse
import json
import locale
import os
from pathlib import Path
import re
import subprocess
import tempfile
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Asia/Shanghai")
LOG = re.compile(r'^(\S+ \S+ \S+ )\[([^\]]+)\] "([A-Z]+) ([^ ]+) HTTP/[^" ]+" (\d{3}) (?:\d+|-) "(?:[^"\\]|\\.)*" "(?:[^"\\]|\\.)*"\s*$')
# Tomcat may log rejected requests as "-" or with a non-HTTP protocol.
# Recognize the full combined record before excluding these non-page errors.
ERROR_LOG = re.compile(r'^\S+ \S+ \S+ \[[^\]]+\] "(?:[^"\\]|\\.)*" [45]\d{2} (?:\d+|-) "(?:[^"\\]|\\.)*" "(?:[^"\\]|\\.)*"\s*$')
FILE = re.compile(r'access\.(\d{4}-\d{2}-\d{2})\.log$')


def select_page(line):
    """Return a normalized combined-log line; API/static/admin requests do not count."""
    match = LOG.fullmatch(line)
    if not match:
        return None
    date = datetime.strptime(match[2], "%d/%b/%Y:%H:%M:%S %z").astimezone(ZONE)
    if match[3] != "GET" or match[4].split("?", 1)[0] not in ("/", "/index.html") or match[5] not in ("200", "304"):
        return None
    normalized = line.replace("[" + match[2] + "]", "[" + date.strftime("%d/%b/%Y:%H:%M:%S %z") + "]", 1)
    return date.date().isoformat(), normalized


def daily_counts(report):
    """GoAccess visitors.data contains per-day hits (PV) and visitors (estimated UV)."""
    rows = report["visitors"]["data"]
    counts = {}
    for row in rows:
        date = datetime.strptime(row["data"], "%Y%m%d").date().isoformat()
        pv, uv = row["hits"]["count"], row["visitors"]["count"]
        if type(pv) is not int or type(uv) is not int or not 0 <= uv <= pv:
            raise ValueError("Invalid GoAccess daily counts")
        if date in counts:
            raise ValueError("Duplicate GoAccess date")
        counts[date] = {"date": date, "pv": pv, "uv": uv}
    return counts


def cached_days(path, oldest, today):
    """Counts and completed-file fingerprints are committed together in one report."""
    if not path.exists():
        return {}, {}
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
        # Upgrade the old full-scan collector with one initial rebuild.
        state = report.get("collectorState")
        if state is None:
            return {}, {}
        if report["schemaVersion"] != 1 or report["timezone"] != ZONE.key or state["version"] != 1:
            raise ValueError("Unsupported report version")
        counts = {}
        for row in report["days"]:
            day = datetime.strptime(row["date"], "%Y-%m-%d").date()
            pv, uv = row["pv"], row["uv"]
            if type(pv) is not int or type(uv) is not int or not 0 <= uv <= pv or row["date"] in counts:
                raise ValueError("Invalid cached counts")
            if oldest <= day <= today:
                counts[row["date"]] = {"date": row["date"], "pv": pv, "uv": uv}
        finalized = {}
        for day, signature in state["finalized"].items():
            if not isinstance(signature, list) or len(signature) != 2 or any(type(n) is not int or n < 0 for n in signature):
                raise ValueError("Invalid log fingerprint")
            if oldest.isoformat() <= day < today.isoformat():
                if day not in counts:
                    raise ValueError("Missing cached day")
                finalized[day] = signature
        return counts, finalized
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        raise RuntimeError("Invalid saved statistics; keep previous report") from exc


def fingerprint(path):
    stat = path.stat()
    return [stat.st_size, stat.st_mtime_ns]


def collect(data_dir, goaccess="goaccess", now=None):
    locale.setlocale(locale.LC_TIME, "C")
    now = now or datetime.now(ZONE)
    today = now.astimezone(ZONE).date()
    oldest = today - timedelta(days=29)
    logs = sorted((data_dir / "access-logs").glob("access.*.log"))
    if not logs:
        raise RuntimeError("No application access logs; check server.tomcat.accesslog configuration")
    counts, finalized = cached_days(data_dir / "site-stats/report.json", oldest, today)
    covered = set()
    available = set()
    scanned = 0
    selected = 0
    malformed = 0
    with tempfile.TemporaryDirectory(prefix="tagent-site-stats-") as temp:
        filtered = Path(temp) / "pages.log"
        with filtered.open("w", encoding="utf-8", newline="\n") as output:
            for path in logs:
                filename = FILE.fullmatch(path.name)
                if not filename:
                    continue
                day = datetime.strptime(filename[1], "%Y-%m-%d").date()
                # File date covers even zero-page days; cloud JVM/log rotation uses Asia/Shanghai.
                if oldest <= day <= today:
                    day_key = day.isoformat()
                    available.add(day_key)
                else:
                    continue
                before = fingerprint(path)
                if day < today and finalized.get(day_key) == before:
                    continue  # Only stat completed files; do not read their contents again.
                finalized.pop(day_key, None)
                covered.add(day_key)
                scanned += 1
                complete = True
                with path.open(encoding="utf-8", errors="strict") as source:
                    for line in source:
                        if not line.endswith("\n"):
                            complete = False
                            continue  # A concurrent append may leave an unfinished last record.
                        if not LOG.fullmatch(line):
                            if ERROR_LOG.fullmatch(line):
                                continue
                            malformed += 1
                            continue
                        page = select_page(line)
                        if page:
                            if page[0] != day_key:
                                raise RuntimeError("Log date differs from file date; check JVM timezone; keep previous report")
                            output.write(page[1])
                            selected += 1
                # Revisit files that were still being written, even after midnight.
                if day < today and complete and fingerprint(path) == before:
                    finalized[day_key] = before
        if malformed:
            raise RuntimeError(f"{malformed} unrecognized access log lines; keep previous report")
        if not available:
            raise RuntimeError("No access logs within the last 30 calendar days")
        if any(day not in available and day not in finalized for day in counts):
            raise RuntimeError("An unfinished day's log is missing; keep previous report")
        counts.update({day: {"date": day, "pv": 0, "uv": 0} for day in covered})
        if selected:
            raw_report = Path(temp) / "goaccess.json"
            result = subprocess.run([
                goaccess, str(filtered), "--no-global-config", "--log-format=COMBINED",
                "--ignore-crawlers", "--no-query-string", "--no-progress",
                "--max-items=1000", "-o", str(raw_report)
            ], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env={**os.environ, "LC_ALL": "C"}, timeout=240)
            if result.returncode != 0:
                raise RuntimeError("GoAccess failed; keep previous report")
            raw = json.loads(raw_report.read_text(encoding="utf-8"))
            if raw["general"].get("invalid_requests", 0):
                raise RuntimeError("GoAccess rejected records; keep previous report")
            fresh = daily_counts(raw)
            if not fresh.keys() <= covered:
                raise RuntimeError("GoAccess returned unexpected dates; keep previous report")
            counts.update(fresh)
    report = {"schemaVersion": 1, "timezone": ZONE.key,
              "generatedAt": now.astimezone(ZoneInfo("UTC")).isoformat(),
              "collectorState": {"version": 1, "finalized": finalized},
              "days": [counts[day] for day in sorted(counts)]}
    directory = data_dir / "site-stats"
    directory.mkdir(mode=0o755, parents=True, exist_ok=True)
    directory.chmod(0o755)  # The timer uses UMask=0077; the app must be able to read aggregates.
    # Readers see either the complete old report or the complete new report.
    fd, name = tempfile.mkstemp(prefix=".report-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as target:
            json.dump(report, target, ensure_ascii=False)
            target.write("\n")
        os.chmod(name, 0o644)
        os.replace(name, directory / "report.json")
    finally:
        Path(name).unlink(missing_ok=True)
    print(f"Updated site statistics: {len(counts)} days, {sum(d['pv'] for d in counts.values())} page views; scanned {scanned} daily log files")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, help="Application data directory on this host")
    parser.add_argument("--goaccess", default="goaccess")
    args = parser.parse_args()
    data_dir = args.data_dir
    if data_dir is None:
        # Use the existing Compose volume; never hardcode its project-dependent name.
        container = subprocess.check_output(["docker", "compose", "ps", "-q", "app"],
                                            cwd=Path(__file__).resolve().parent, text=True).strip()
        if not container or "\n" in container:
            raise RuntimeError("Expected one running app container")
        mounts = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .Mounts}}", container], text=True))
        sources = [m["Source"] for m in mounts if m["Destination"] == "/app/data"]
        if len(sources) != 1:
            raise RuntimeError("Cannot locate the /app/data mount")
        data_dir = Path(sources[0])
    # flock lives outside the report to serialize manual runs and the systemd timer.
    import fcntl
    lock_dir = data_dir / "site-stats"
    lock_dir.mkdir(mode=0o755, parents=True, exist_ok=True)
    lock_dir.chmod(0o755)
    with (lock_dir / ".collect.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        collect(data_dir, args.goaccess)


if __name__ == "__main__":
    main()
