import json
import locale
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest
from unittest.mock import patch
from datetime import datetime
import collect_site_stats as stats


def line(path="/", status=200, method="GET", timestamp="02/Oct/2026:10:00:00 +0800", ua="Mozilla/5.0"):
    return f'192.0.2.1 - - [{timestamp}] "{method} {path} HTTP/1.1" {status} 123 "-" "{ua}"\n'


class SiteStatsTest(unittest.TestCase):
    def setUp(self):
        locale.setlocale(locale.LC_TIME, "C")

    def test_only_successful_homepage_requests(self):
        for path in ["/", "/index.html", "/?from=test", "/index.html?from=test"]:
            self.assertIsNotNone(stats.select_page(line(path, 304)))
        for path in ["/api/v1/agent/run", "/auth.js", "/observe.html", "/index.html/other", "/favicon.ico"]:
            self.assertIsNone(stats.select_page(line(path)))
        self.assertIsNone(stats.select_page(line(status=404)))
        self.assertIsNone(stats.select_page(line(method="POST")))

    def test_calendar_day_uses_shanghai(self):
        date, normalized = stats.select_page(line(timestamp="01/Oct/2026:16:00:00 +0000"))
        self.assertEqual("2026-10-02", date)
        self.assertIn("02/Oct/2026:00:00:00 +0800", normalized)

    def test_goaccess_visitors_and_hits_are_not_confused(self):
        result = stats.daily_counts({"visitors": {"data": [
            {"data": "20261002", "hits": {"count": 7}, "visitors": {"count": 2}}
        ]}})
        self.assertEqual({"date": "2026-10-02", "pv": 7, "uv": 2}, result["2026-10-02"])

    def test_repeat_collection_overwrites_and_excludes_management_logs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            (root / "access-logs/access.2026-10-02.log").write_text(line() + line("/api/test"), encoding="utf-8")
            (root / "access-logs/management_access.2026-10-02.log").write_text(line(), encoding="utf-8")
            def goaccess(args, **kwargs):
                self.assertEqual(1, len(Path(args[1]).read_text(encoding="utf-8").splitlines()))
                Path(args[-1]).write_text(json.dumps({"general": {"invalid_requests": 0}, "visitors": {"data": [
                    {"data": "20261002", "hits": {"count": 1}, "visitors": {"count": 1}}
                ]}}), encoding="utf-8")
                return subprocess.CompletedProcess(args, 0)
            with patch.object(stats.subprocess, "run", side_effect=goaccess):
                for _ in range(2):
                    stats.collect(root, now=datetime(2026, 10, 2, 11, tzinfo=stats.ZONE))
            report = json.loads((root / "site-stats/report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, report["days"][0]["pv"])
            self.assertNotIn("192.0.2.1", json.dumps(report))

    def test_empty_log_is_zero_but_missing_logs_do_not_replace_old_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            log = root / "access-logs/access.2026-10-02.log"
            log.touch()
            stats.collect(root, now=datetime(2026, 10, 2, tzinfo=stats.ZONE))
            report = (root / "site-stats/report.json").read_bytes()
            self.assertEqual(0, json.loads(report)["days"][0]["pv"])
            log.unlink()
            with self.assertRaises(RuntimeError):
                stats.collect(root, now=datetime(2026, 10, 2, tzinfo=stats.ZONE))
            self.assertEqual(report, (root / "site-stats/report.json").read_bytes())

    def test_malformed_logs_fail_instead_of_resetting_counts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            (root / "access-logs/access.2026-10-02.log").write_text("unexpected format\n")
            with self.assertRaises(RuntimeError):
                stats.collect(root, now=datetime(2026, 10, 2, tzinfo=stats.ZONE))

    def test_rejected_requests_do_not_block_valid_page_counts(self):
        rejected = line(status=400).replace('GET / HTTP/1.1', '-') * 31
        rejected += line(method="OPTIONS", status=400).replace("HTTP/1.1", "RTSP/1.0")
        rejected += line("*", method="OPTIONS", status=400).replace("HTTP/1.1", "RTSP/1.0")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            (root / "access-logs/access.2026-10-02.log").write_text(rejected + line(), encoding="utf-8")
            def goaccess(args, **kwargs):
                self.assertEqual(line(), Path(args[1]).read_text(encoding="utf-8"))
                Path(args[-1]).write_text(json.dumps({"general": {"invalid_requests": 0}, "visitors": {"data": [
                    {"data": "20261002", "hits": {"count": 1}, "visitors": {"count": 1}}
                ]}}), encoding="utf-8")
                return subprocess.CompletedProcess(args, 0)
            with patch.object(stats.subprocess, "run", side_effect=goaccess):
                stats.collect(root, now=datetime(2026, 10, 2, 11, tzinfo=stats.ZONE))
            report = json.loads((root / "site-stats/report.json").read_text(encoding="utf-8"))
            self.assertEqual([{"date": "2026-10-02", "pv": 1, "uv": 1}], report["days"])

    def test_unrecognized_successful_request_preserves_previous_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            (root / "site-stats").mkdir()
            report = root / "site-stats/report.json"
            report.write_text("previous report", encoding="utf-8")
            (root / "access-logs/access.2026-10-02.log").write_text(
                line().replace("HTTP/1.1", "UNKNOWN/1.0"), encoding="utf-8")
            with self.assertRaises(RuntimeError):
                stats.collect(root, now=datetime(2026, 10, 2, tzinfo=stats.ZONE))
            self.assertEqual("previous report", report.read_text(encoding="utf-8"))

    @unittest.skipUnless(shutil.which("goaccess"), "GoAccess integration test requires its binary")
    def test_real_goaccess_dedup_bots_api_filter_and_two_days(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            logs = root / "access-logs"
            logs.mkdir()
            (logs / "access.2026-10-01.log").write_text(line(timestamp="01/Oct/2026:23:00:00 +0800"))
            (logs / "access.2026-10-02.log").write_text(
                line() + line("/index.html?x=1", 304)
                + line().replace("192.0.2.1", "192.0.2.2")
                + line("/api/test") + line(status=404)
                + line(ua="Googlebot/2.1 (+http://www.google.com/bot.html)"))
            for _ in range(2):
                stats.collect(root, now=datetime(2026, 10, 2, 11, tzinfo=stats.ZONE))
            report = json.loads((root / "site-stats/report.json").read_text())
            self.assertEqual([
                {"date": "2026-10-01", "pv": 1, "uv": 1},
                {"date": "2026-10-02", "pv": 3, "uv": 2},
            ], report["days"])

    @unittest.skipUnless(shutil.which("goaccess"), "GoAccess integration test requires its binary")
    def test_real_goaccess_all_crawlers_is_zero(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "access-logs").mkdir()
            (root / "access-logs/access.2026-10-02.log").write_text(
                line(ua="Googlebot/2.1 (+http://www.google.com/bot.html)"))
            stats.collect(root, now=datetime(2026, 10, 2, 11, tzinfo=stats.ZONE))
            report = json.loads((root / "site-stats/report.json").read_text())
            self.assertEqual([{"date": "2026-10-02", "pv": 0, "uv": 0}], report["days"])


class DailyCollectionTest(unittest.TestCase):
    """Exercise cache/rollover behavior; the CLI mock records the actual input batch."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "access-logs").mkdir()
        self.batches = []
        mock = patch.object(stats.subprocess, "run", side_effect=self.goaccess)
        mock.start()
        self.addCleanup(mock.stop)

    def goaccess(self, args, **kwargs):
        records = Path(args[1]).read_text(encoding="utf-8").splitlines()
        self.batches.append(records)
        totals = {}
        for record in records:
            day, _ = stats.select_page(record)
            totals[day] = totals.get(day, 0) + 1
        Path(args[-1]).write_text(json.dumps({"general": {"invalid_requests": 0}, "visitors": {"data": [
            {"data": day.replace("-", ""), "hits": {"count": count}, "visitors": {"count": 1}}
            for day, count in totals.items()
        ]}}), encoding="utf-8")
        return subprocess.CompletedProcess(args, 0)

    def log(self, day, count=1):
        path = self.root / f"access-logs/access.2026-10-{day:02d}.log"
        path.write_text(line(timestamp=f"{day:02d}/Oct/2026:10:00:00 +0800") * count, encoding="utf-8")
        return path

    def collect(self, day):
        stats.collect(self.root, now=datetime(2026, 10, day, 11, tzinfo=stats.ZONE))
        return json.loads((self.root / "site-stats/report.json").read_text(encoding="utf-8"))

    def test_same_day_reads_only_today_and_replaces_instead_of_adding(self):
        yesterday = self.log(1, 2)
        self.log(2)
        self.collect(2)
        self.log(2, 3)
        original_open = Path.open
        def guarded_open(path, *args, **kwargs):
            if path == yesterday:
                self.fail("Completed log must not be opened")
            return original_open(path, *args, **kwargs)
        with patch.object(Path, "open", guarded_open):
            report = self.collect(2)
        self.assertEqual(3, len(self.batches[-1]))
        self.assertEqual([2, 3], [row["pv"] for row in report["days"]])
        self.assertEqual([1, 1], [row["uv"] for row in report["days"]])

    def test_rollover_and_downtime_finalize_all_unfinished_dates(self):
        self.log(1)
        self.collect(1)
        self.log(1, 2)  # Late visits after the previous sample.
        self.log(2, 3)
        self.log(3, 4)
        report = self.collect(3)
        self.assertEqual(9, len(self.batches[-1]))
        self.assertEqual([2, 3, 4], [row["pv"] for row in report["days"]])
        self.assertEqual({"2026-10-01", "2026-10-02"}, set(report["collectorState"]["finalized"]))
        self.collect(3)
        self.assertEqual(4, len(self.batches[-1]))

    def test_late_write_reopens_completed_day_and_deleted_completed_log_is_retained(self):
        old = self.log(1)
        self.log(2)
        self.collect(2)
        self.log(1, 2)
        report = self.collect(2)
        self.assertEqual([2, 1], [row["pv"] for row in report["days"]])
        old.unlink()
        self.assertEqual(report["days"], self.collect(2)["days"])

    def test_failure_does_not_advance_checkpoint_and_retry_backfills(self):
        self.log(1)
        self.collect(1)
        saved = (self.root / "site-stats/report.json").read_bytes()
        self.log(1, 2)
        self.log(2)
        with patch.object(stats.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            with self.assertRaises(RuntimeError):
                self.collect(2)
        self.assertEqual(saved, (self.root / "site-stats/report.json").read_bytes())
        self.assertEqual([2, 1], [row["pv"] for row in self.collect(2)["days"]])

    def test_legacy_report_rebuild_and_rolling_retention(self):
        self.log(1, 2)
        self.log(2)
        report_dir = self.root / "site-stats"
        report_dir.mkdir()
        (report_dir / "report.json").write_text(json.dumps({"schemaVersion": 1, "days": []}))
        self.assertEqual([2, 1], [row["pv"] for row in self.collect(2)["days"]])
        self.log(31)
        report = self.collect(31)
        self.assertEqual(["2026-10-02", "2026-10-31"], [row["date"] for row in report["days"]])
        self.assertNotIn("2026-10-01", report["collectorState"]["finalized"])

    def test_incomplete_yesterday_is_revisited_until_finished(self):
        old = self.log(1)
        old.write_text(old.read_text() + line(timestamp="01/Oct/2026:23:59:59 +0800").rstrip("\n"))
        self.log(2)
        report = self.collect(2)
        self.assertNotIn("2026-10-01", report["collectorState"]["finalized"])
        old.write_text(old.read_text() + "\n")
        report = self.collect(2)
        self.assertEqual([2, 1], [row["pv"] for row in report["days"]])
        self.assertIn("2026-10-01", report["collectorState"]["finalized"])


if __name__ == "__main__":
    unittest.main()
