import base64
import contextlib
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid
import zipfile

import v2_ops as ops
import v2_preflight as preflight


class MigrationContracts(unittest.TestCase):
    def test_splitter_handles_comments_strings_and_prepared_statements(self):
        sql = "-- comment ;\nSET @ddl='ALTER TABLE t ADD COLUMN a VARCHAR(10) DEFAULT \\'x;y\\'';\nPREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s; /* x ; */ SELECT 'a'';b';"
        parts = ops.split_sql(sql)
        self.assertEqual(5, len(parts))
        self.assertIn("x;y", parts[0])
        self.assertEqual("SELECT 'a'';b'", parts[4])
        for unsafe in ("USE other;", "DROP DATABASE other;", "SOURCE path.sql;", "DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT 1; END$$"):
            with self.assertRaises(ops.OpsError):
                ops.split_sql(unsafe)

    def test_checksums_normalize_line_endings_but_not_content_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "V064__fixture.sql"
            path.write_bytes(b"\xef\xbb\xbfSELECT 1;\r\n")
            first = ops.load_migrations(Path(folder), 64, 64)[0]
            path.write_bytes(b"SELECT 1;\n")
            self.assertEqual(first.digest, ops.load_migrations(Path(folder), 64, 64)[0].digest)
            path.write_bytes(b"SELECT 2;\n")
            self.assertNotEqual(first.digest, ops.load_migrations(Path(folder), 64, 64)[0].digest)
            (Path(folder) / "V064__duplicate.sql").write_text("SELECT 1;", encoding="utf-8")
            with self.assertRaises(ops.OpsError):
                ops.load_migrations(Path(folder), 64, 64)

    def test_changed_history_and_unknown_interruptions_cannot_be_replayed(self):
        migration = ops.Migration(64, "V064__fixture.sql", "SELECT 1;", ops.checksum("SELECT 1;"))
        db = Mock()
        db.execute.return_value = "64\tV064__fixture.sql\t" + "f" * 64 + "\tAPPLIED\t1\tNULL"
        with self.assertRaisesRegex(ops.OpsError, "checksum"):
            ops.Migrator(db).validate_history([migration])
        for state in ("RUNNING", "FAILED"):
            db.execute.return_value = f"64\t{migration.name}\t{migration.digest}\t{state}\t1\tUNKNOWN"
            with self.assertRaisesRegex(ops.OpsError, "unknown interruption"):
                ops.Migrator(db).apply([migration], retry_failed=True)

    def test_database_target_guard_and_error_output_do_not_leak(self):
        with self.assertRaises(ops.OpsError):
            ops.DockerDatabase("ai-agent-station-study")
        with self.assertRaises(ops.OpsError):
            ops.DockerDatabase(container="mysql")
        db = object.__new__(ops.DockerDatabase)
        db.database, db.prefix = ops.TEST_DB, ["synthetic-test"]
        output = subprocess.CompletedProcess([], 1, b"", b"ERROR 1146 (42S02) at line 3: SYNTHETIC_PRIVATE_PASSWORD SQL DATA")
        with patch("v2_ops.subprocess.run", return_value=output):
            with self.assertRaises(ops.DatabaseError) as caught:
                db.execute("SELECT synthetic_secret")
        self.assertEqual("1146", caught.exception.code)
        self.assertNotIn("PRIVATE", str(caught.exception))

    def test_backup_checksum_rejected_before_database_is_contacted(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "manifest.json").write_text(json.dumps({"format": 1, "database": ops.TEST_DB, "container": ops.CONTAINER,
                                                          "files": {"mysql.sql.gz": "invalid"}}), encoding="utf-8")
            (root / "mysql.sql.gz").write_bytes(b"tampered")
            with patch("v2_ops.DockerDatabase") as connection:
                with self.assertRaisesRegex(ops.OpsError, "checksum"):
                    ops.restore_test(root)
                connection.assert_not_called()

    def test_live_configuration_and_keys_are_not_silently_written_by_preflight(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / ".env").write_text("DOMAIN=example.org\nSESSION_COOKIE_SECURE=false\nMYSQL_PASSWORD=SYNTHETIC_PRIVATE\n", encoding="utf-8")
            before = (root / ".env").read_bytes()
            result = preflight.check(root, root / "missing.jar", compose=False)
            self.assertFalse(result["passed"])
            self.assertTrue(any("MASTER_KEY" in error for error in result["errors"]))
            self.assertNotIn("SYNTHETIC_PRIVATE", json.dumps(result))
            self.assertEqual(before, (root / ".env").read_bytes())
            self.assertFalse((root / "application-cloud.yml").exists())

    def test_preflight_key_domain_ports_and_persistence(self):
        valid = base64.b64encode(bytes(range(32))).decode()
        self.assertIsNone(preflight.master_key_error({"MODEL_SECRET_MASTER_KEY": valid}))
        for value in ("", "invalid base64", base64.b64encode(bytes(32)).decode(), base64.b64encode(bytes(range(16))).decode()):
            self.assertIsNotNone(preflight.master_key_error({"MODEL_SECRET_MASTER_KEY": value}))
        self.assertTrue(preflight.valid_domain("agent.example.org"))
        for value in ("192.0.2.1", "localhost", "a.test", "x;return 200;", "https://example.org"):
            self.assertFalse(preflight.valid_domain(value))
        config = {"services": {"app": {"environment": {"SESSION_COOKIE_SECURE": "true", "MODEL_SECRET_MASTER_KEY": valid},
                                       "volumes": [{"target": "/app/data"}], "ports": [{"target": 8099, "host_ip": "0.0.0.0"}]},
                               "mysql": {}, "vector_db": {}, "elasticsearch": {}, "nginx": {"ports": [{"target": 443}]}}}
        errors = preflight.inspect_compose(config)
        self.assertEqual(1, len(errors)); self.assertIn("app publishes", errors[0])
        config["services"]["app"]["ports"] = []
        self.assertEqual([], preflight.inspect_compose(config))

    def test_sanitized_jar_validation_checks_profiles_and_feature_files(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "app.jar"
            with zipfile.ZipFile(path, "w") as archive:
                for name in ("knowledge", "models", "user-agents"):
                    archive.writestr("BOOT-INF/classes/static/" + name + ".html", "fixture")
                archive.writestr("BOOT-INF/classes/application-dev.yml", "secret: synthetic")
            self.assertEqual([], preflight.inspect_jar(path, sanitized=False))
            self.assertTrue(any("private" in error for error in preflight.inspect_jar(path, sanitized=True)))

    def test_nginx_renderer_rejects_injection_and_existing_configuration(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "nginx.conf"
            with self.assertRaises(ValueError):
                preflight.render_nginx("example.org;return 200;", path)
            self.assertFalse(path.exists())
            preflight.render_nginx("agent.example.org", path)
            content = path.read_text(encoding="utf-8")
            self.assertIn("server_name agent.example.org;", content)
            self.assertIn("proxy_buffering off;", content)
            self.assertIn("proxy_set_header X-Forwarded-For $remote_addr;", content)
            with self.assertRaises(FileExistsError):
                preflight.render_nginx("other.example.org", path)
            self.assertEqual(content, path.read_text(encoding="utf-8"))

    def test_both_nginx_configs_limit_credential_mutations_without_throttling_session_reads(self):
        for name in ("nginx.conf", "nginx-v2.conf.template"):
            content = Path(__file__).with_name(name).read_text(encoding="utf-8")
            with self.subTest(config=name):
                self.assertIn('map "$request_method:$uri" $auth_rate_limit_key {', content)
                self.assertIn('default "";', content)
                self.assertIn('~^POST:/api/v1/auth/(login|register|password)/?$ $binary_remote_addr;', content)
                self.assertIn('limit_req_zone $auth_rate_limit_key ', content)
                self.assertIn('limit_req_status 429;', content)
                self.assertNotIn('limit_req_zone $binary_remote_addr ', content)


@unittest.skipUnless(os.environ.get("TAGENT_OPS_INTEGRATION") == "1", "Opt-in isolated Docker test only")
class IsolatedMysqlDrill(unittest.TestCase):
    def test_failed_migration_resumes_checkpoints_and_backup_restores(self):
        # Every created object and migration number is unique and limited to the dedicated fixture schema.
        suffix = uuid.uuid4().hex[:8]
        table, dependency = "v2_ops_probe_" + suffix, "v2_ops_dependency_" + suffix
        version = 900000 + int(suffix[:4], 16)
        db = ops.DockerDatabase()
        migrator = ops.Migrator(db)
        sql = f"CREATE TABLE {table}(id INT PRIMARY KEY,content VARCHAR(64)); INSERT INTO {table} VALUES(1,'restorable fixture'); INSERT INTO {dependency} VALUES(1);"
        migration = ops.Migration(version, f"V{version}__restore_fixture.sql", sql, ops.checksum(sql))
        with ops.exclusive_operations():
            try:
                with self.assertRaises(ops.DatabaseError) as failed:
                    migrator.apply([migration])
                self.assertEqual("1146", failed.exception.code)
                row = migrator.history()[version]
                self.assertEqual("FAILED", row["state"])
                self.assertEqual("2", db.execute(f"SELECT COUNT(*) FROM {ops.STEPS} WHERE version={version};"))
                db.execute(f"CREATE TABLE {dependency}(id INT PRIMARY KEY);")
                migrator.apply([migration], retry_failed=True)
                self.assertEqual("APPLIED", migrator.history()[version]["state"])
                self.assertEqual("1", db.execute(f"SELECT COUNT(*) FROM {table};"))
                migrator.apply([migration])  # Successful rerun skips every statement.
                changed = ops.Migration(version, migration.name, sql + " SELECT 2;", ops.checksum(sql + " SELECT 2;"))
                with self.assertRaisesRegex(ops.OpsError, "checksum"):
                    migrator.apply([changed])
                directory = Path(tempfile.gettempdir()) / ("tagent-v2-restore-drill-" + suffix)
                ops.backup(db, directory)
                restored = ops.restore_test(directory)
                destination = ops.DockerDatabase(ops.RESTORE_DB)
                self.assertEqual("1", destination.execute(f"SELECT COUNT(*) FROM {table};"))
                self.assertEqual("restorable fixture", destination.execute(f"SELECT content FROM {table} WHERE id=1;"))
                self.assertEqual("1", db.execute(f"SELECT COUNT(*) FROM {table};"))
                self.assertEqual("APPLIED", ops.Migrator(destination).history()[version]["state"])
                report = {"backup": str(directory), "restore": restored, "migrationFailure": "1146",
                          "checkpointResume": "passed", "checksumMismatchRejected": True,
                          "fixtureRowsVerified": True, "originalBusinessDatabasesTouched": False}
                (directory / "drill-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
                print("Isolated restore drill:", json.dumps(report))
            finally:
                db.execute(f"DROP TABLE IF EXISTS {dependency}; DROP TABLE IF EXISTS {table}; DELETE FROM {ops.STEPS} WHERE version={version}; DELETE FROM {ops.HISTORY} WHERE version={version};")


if __name__ == "__main__":
    unittest.main()
