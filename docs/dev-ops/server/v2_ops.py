"""Local v2 migration/restore tooling. Never auto-detects or connects to a business database.

Only Python's standard library and the explicit isolated Docker test container are used.
SQL and credentials are never printed; history uses normalized UTF-8/LF SHA-256 checksums.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from contextlib import contextmanager

CONTAINER = "tagent-v2-tests-mysql"
TEST_DB = "tagent_workspace_isolation_test"
RESTORE_DB = "tagent_v2_restore_test"
HISTORY = "tagent_schema_history"
STEPS = "tagent_schema_step"
MIGRATIONS = Path(__file__).resolve().parents[1] / "sql-migrations"


class OpsError(RuntimeError):
    pass


class DatabaseError(OpsError):
    def __init__(self, code: str | None):
        self.code = code
        super().__init__("MySQL statement failed (code " + (code or "unknown transport/process failure") + "); no SQL or credentials printed")


def normalized(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")


def checksum(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def literal(value: str) -> str:
    # Hex UTF-8 literals avoid SQL mode dependent backslash/string quoting.
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)" if value else "''"


def split_sql(text: str) -> list[str]:
    """Split ordinary MySQL scripts; reject DELIMITER procedures rather than guessing boundaries."""
    if re.search(r"(?im)^\s*DELIMITER\b", text):
        raise OpsError("DELIMITER scripts require a dedicated reviewed runner")
    pieces, current, quote, index = [], [], None, 0
    while index < len(text):
        char = text[index]
        if quote:
            current.append(char)
            if char == "\\" and index + 1 < len(text):
                index += 1
                current.append(text[index])
            elif char == quote:
                if index + 1 < len(text) and text[index + 1] == quote:
                    index += 1
                    current.append(text[index])
                else:
                    quote = None
        elif char in "'\"`":
            quote = char
            current.append(char)
        elif char == "#" or (text.startswith("--", index) and (index + 2 == len(text) or text[index + 2].isspace())):
            newline = text.find("\n", index)
            index = len(text) if newline == -1 else newline
            current.append("\n")
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            if end < 0:
                raise OpsError("Unclosed SQL comment")
            if text.startswith("/*!", index):
                current.append(text[index:end + 2])
            else:
                current.append(" ")
            index = end + 1
        elif char == ";":
            statement = "".join(current).strip()
            if statement:
                pieces.append(statement)
            current.clear()
        else:
            current.append(char)
        index += 1
    if quote:
        raise OpsError("Unclosed SQL string")
    if "".join(current).strip():
        pieces.append("".join(current).strip())
    for statement in pieces:
        if re.match(r"(?is)^(?:USE|SOURCE|CONNECT|\\|CREATE\s+DATABASE|DROP\s+DATABASE)\b", statement):
            raise OpsError("Migration cannot change the selected database or source external files")
    return pieces


@dataclass(frozen=True)
class Migration:
    version: int
    name: str
    text: str
    digest: str

    @property
    def statements(self) -> list[str]:
        return split_sql(self.text)


def load_migrations(directory: Path, first: int = 64, last: int = 69) -> list[Migration]:
    result, versions = [], set()
    for path in sorted(directory.glob("V*.sql")):
        found = re.fullmatch(r"V(\d+)__[A-Za-z0-9_]+\.sql", path.name)
        if not found or not first <= int(found[1]) <= last:
            continue
        version = int(found[1])
        if version in versions:
            raise OpsError(f"Duplicate migration version V{version:03}")
        versions.add(version)
        text = normalized(path)
        result.append(Migration(version, path.name, text, checksum(text)))
    if versions != set(range(first, last + 1)):
        raise OpsError("Selected migration range has missing versions")
    for migration in result:
        migration.statements
    return sorted(result, key=lambda item: item.version)


class DockerDatabase:
    """Fail closed: only the named test container/database, never arbitrary host/Compose selection."""
    def __init__(self, database: str = TEST_DB, container: str = CONTAINER):
        if container != CONTAINER or database not in {TEST_DB, RESTORE_DB}:
            raise OpsError("Only the explicit tagent v2 isolated test container/database is allowed")
        context = subprocess.run(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], capture_output=True, text=True, check=False)
        endpoint = os.environ.get("DOCKER_HOST") or context.stdout.strip()
        if context.returncode or not endpoint.startswith(("npipe://", "unix://")):
            raise OpsError("Only a local Docker named pipe or Unix socket is allowed; remote contexts are refused")
        self.database = database
        self.container = container
        probe = subprocess.run(["docker", "inspect", "--format", "{{json .}}", container], capture_output=True, text=True, check=False)
        if probe.returncode:
            raise OpsError("The isolated test MySQL container is not available")
        metadata = json.loads(probe.stdout)
        if metadata.get("Name") != "/" + CONTAINER or not metadata.get("State", {}).get("Running"):
            raise OpsError("Container identity or running state does not match the test fixture")
        # The shell command is static. The password stays inside the fixture container environment.
        self.prefix = ["docker", "exec", "-i", container, "sh", "-c"]

    def execute(self, sql: str, *, select_db: bool = True, timeout: int = 120) -> str:
        command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names --default-character-set=utf8mb4 --binary-mode'
        if select_db:
            command += " --database=" + self.database
        try:
            completed = subprocess.run(self.prefix + [command], input=sql.encode("utf-8"), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise DatabaseError(None) from None
        if completed.returncode:
            message = completed.stderr.decode("utf-8", errors="replace")
            match = re.search(r"(?m)^ERROR (\d+) \(", message)
            raise DatabaseError(match[1] if match else None)
        return completed.stdout.decode("utf-8").strip()

    def dump(self, destination: Path) -> None:
        if destination.exists():
            raise OpsError("Backup destination exists; refusing to overwrite it")
        destination.parent.mkdir(parents=True, exist_ok=True)
        command = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers '
                   '--events --hex-blob --set-gtid-purged=OFF --column-statistics=0 --default-character-set=utf8mb4 ' + self.database)
        handle, raw_name = tempfile.mkstemp(prefix=".mysql-", dir=destination.parent)
        try:
            with os.fdopen(handle, "wb") as output:
                result = subprocess.run(self.prefix + [command], stdout=output, stderr=subprocess.PIPE, timeout=180)
            if result.returncode:
                raise OpsError("MySQL backup failed; incomplete output was discarded")
            with open(raw_name, "rb") as source, open(destination, "xb") as target:
                os.chmod(destination, 0o600)
                with gzip.GzipFile(fileobj=target, mode="wb", mtime=0) as compressed:
                    import shutil
                    shutil.copyfileobj(source, compressed)
        finally:
            Path(raw_name).unlink(missing_ok=True)


class Migrator:
    def __init__(self, db):
        self.db = db

    def initialize(self):
        self.db.execute(f"""
            CREATE TABLE IF NOT EXISTS {HISTORY} (
              version INT PRIMARY KEY,name VARCHAR(255) NOT NULL,checksum CHAR(64) NOT NULL,
              state VARCHAR(16) NOT NULL,attempts INT NOT NULL DEFAULT 0,error_code VARCHAR(32) NULL,
              updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3));
            CREATE TABLE IF NOT EXISTS {STEPS} (
              version INT NOT NULL,ordinal INT NOT NULL,checksum CHAR(64) NOT NULL,
              applied_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(version,ordinal));
        """)

    def history(self) -> dict[int, dict]:
        output = self.db.execute(f"SELECT version,name,checksum,state,attempts,error_code FROM {HISTORY} ORDER BY version;")
        records = {}
        for line in output.splitlines():
            row = line.split("\t")
            records[int(row[0])] = dict(name=row[1], checksum=row[2], state=row[3], attempts=int(row[4]), error=row[5])
        return records

    def validate_history(self, migrations: list[Migration]) -> dict[int, dict]:
        history = self.history()
        selected = {migration.version: migration for migration in migrations}
        for version, row in history.items():
            if version not in selected:
                continue
            migration = selected[version]
            if row["name"] != migration.name or row["checksum"] != migration.digest:
                raise OpsError(f"V{version:03} checksum/name changed after recording; add a new migration instead")
        return history

    def baseline(self, migrations: list[Migration]):
        """Explicitly adopt already executed scripts, only after structural and V065 data checks."""
        self.initialize()
        history = self.validate_history(migrations)
        for migration in migrations:
            if migration.version in history:
                if history[migration.version]["state"] not in {"APPLIED", "BASELINED"}:
                    raise OpsError("Cannot baseline an incomplete migration; inspect the recorded failure")
                continue
            self.verify_schema(migration)
            self.db.execute(f"INSERT INTO {HISTORY}(version,name,checksum,state) VALUES({migration.version},"
                            f"{literal(migration.name)},{literal(migration.digest)},'BASELINED');")

    def verify_schema(self, migration: Migration):
        # Parse only the simple CREATE TABLE forms in V064–V069, not arbitrary SQL DDL.
        declarations = re.findall(r"(?is)CREATE TABLE (?:IF NOT EXISTS )?([a-z_][a-z0-9_]*)\s*\((.*?)\)\s*ENGINE", migration.text)
        if not declarations:
            raise OpsError(f"V{migration.version:03} has no supported baseline schema contract")
        for table, body in declarations:
            columns = set(re.findall(r"(?m)^\s*([a-z_][a-z0-9_]*)\s+(?:VARCHAR|CHAR|TEXT|MEDIUMTEXT|LONGTEXT|JSON|INT|TINYINT|BIGINT|TIMESTAMP|DATETIME|DATE|DECIMAL)\b", body, re.I))
            actual = set(self.db.execute("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=" + literal(table) + ";").splitlines())
            if not columns or not columns <= actual:
                raise OpsError(f"V{migration.version:03} table {table} is missing required columns; cannot baseline")
        if migration.version == 64:
            count = self.db.execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_client_api' AND column_name='secret_ref';")
            if count != "1":
                raise OpsError("V064 ai_client_api.secret_ref is absent")
        if migration.version == 65:
            count = self.db.execute("SELECT COUNT(*) FROM ai_agent a LEFT JOIN ai_agent_version v ON v.agent_id=a.agent_id AND v.version=a.workspace_version WHERE a.workspace_config IS NOT NULL AND a.source_agent_id IS NULL AND (v.agent_id IS NULL OR v.owner_user_id<>a.owner_user_id);")
            if count != "0":
                raise OpsError("V065 existing Agent configurations have not all been versioned")

    def apply(self, migrations: list[Migration], retry_failed: bool = False):
        self.initialize()
        history = self.validate_history(migrations)
        for migration in migrations:
            row = history.get(migration.version)
            if row and row["state"] in {"APPLIED", "BASELINED"}:
                continue
            if row and (row["state"] != "FAILED" or not retry_failed or row["error"] in {"NULL", "UNKNOWN"}):
                raise OpsError(f"V{migration.version:03} is incomplete; only an identified SQL failure supports --retry-failed; unknown interruption requires inspection")
            completed = {}
            if row:
                for line in self.db.execute(f"SELECT ordinal,checksum FROM {STEPS} WHERE version={migration.version};").splitlines():
                    number, digest = line.split("\t")
                    completed[int(number)] = digest
            script = [f"INSERT INTO {HISTORY}(version,name,checksum,state,attempts) VALUES({migration.version},{literal(migration.name)},{literal(migration.digest)},'RUNNING',1) ON DUPLICATE KEY UPDATE state='RUNNING',attempts=attempts+1,error_code=NULL,updated_at=CURRENT_TIMESTAMP(3)"]
            for ordinal, statement in enumerate(migration.statements, 1):
                digest = checksum(statement)
                if ordinal in completed and completed[ordinal] != digest:
                    raise OpsError("Statement checkpoint checksum mismatch")
                session_statement = bool(re.match(r"(?is)^(SET|PREPARE|DEALLOCATE|SELECT)\b", statement))
                if ordinal in completed and not session_statement:
                    continue
                script.append(statement)
                if ordinal not in completed:
                    script.append(f"INSERT INTO {STEPS}(version,ordinal,checksum) VALUES({migration.version},{ordinal},{literal(digest)})")
            script.append(f"UPDATE {HISTORY} SET state='APPLIED',error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE version={migration.version}")
            try:
                self.db.execute(";\n".join(script) + ";\n")
            except DatabaseError as error:
                # A transport failure may have happened after a DDL commit but before its checkpoint.
                # Never silently replay that unknown statement.
                self.db.execute(f"UPDATE {HISTORY} SET state='FAILED',error_code={literal(error.code or 'UNKNOWN')},updated_at=CURRENT_TIMESTAMP(3) WHERE version={migration.version};")
                raise
            history[migration.version] = {"state": "APPLIED"}


@contextmanager
def exclusive_operations():
    """Kernel-managed lock releases on process exit; no stale lock-file removal or parallel DDL."""
    path = Path(tempfile.gettempdir()) / "tagent-v2-isolated-operations.lock"
    with open(path, "a+b") as handle:
        handle.seek(0, 2)
        if handle.tell() == 0:
            handle.write(b"0"); handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            raise OpsError("Another isolated migration/backup/restore operation is running") from None
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def backup(db: DockerDatabase, directory: Path) -> Path:
    if directory.exists():
        raise OpsError("Use a new backup directory; existing backup will not be overwritten")
    directory.mkdir(parents=True, mode=0o700)
    target = directory / "mysql.sql.gz"
    db.dump(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {"format": 1, "database": db.database, "container": db.container,
                "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "files": {"mysql.sql.gz": digest}, "scope": "MySQL only; application data, PostgreSQL, Elasticsearch and encryption master key require separate backup"}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return target


def restore_test(directory: Path) -> dict:
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format") != 1 or manifest.get("database") != TEST_DB or manifest.get("container") != CONTAINER:
        raise OpsError("Restore accepts only a verified isolated test backup")
    target = directory / "mysql.sql.gz"
    if hashlib.sha256(target.read_bytes()).hexdigest() != manifest.get("files", {}).get("mysql.sql.gz"):
        raise OpsError("Backup checksum mismatch; no database changed")
    with gzip.open(target, "rt", encoding="utf-8") as source:
        sql = source.read(512 * 1024 * 1024 + 1)
    if len(sql) > 512 * 1024 * 1024 or re.search(r"(?im)^\s*(USE\s|CREATE\s+DATABASE|DROP\s+DATABASE|SOURCE\s|\\!)", sql):
        raise OpsError("Backup exceeds test limit or attempts to change database context")
    db = DockerDatabase(RESTORE_DB)
    if db.execute(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{RESTORE_DB}';", select_db=False) != "0":
        raise OpsError("Restore test database already exists; leave it intact and use an explicitly reviewed cleanup")
    db.execute(f"CREATE DATABASE {RESTORE_DB} CHARACTER SET utf8mb4;", select_db=False)
    db.execute(sql, timeout=180)
    tables = int(db.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();"))
    return {"database": RESTORE_DB, "tables": tables, "restored": True, "sourceDatabaseUnchanged": True}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["manifest", "status", "baseline", "migrate", "backup", "restore-test"])
    parser.add_argument("--migration-dir", type=Path, default=MIGRATIONS)
    parser.add_argument("--first", type=int, default=64)
    parser.add_argument("--last", type=int, default=69)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args(argv)
    if args.action == "restore-test":
        if not args.backup_dir:
            parser.error("--backup-dir is required")
        with exclusive_operations():
            print(json.dumps(restore_test(args.backup_dir), indent=2))
        return
    migrations = load_migrations(args.migration_dir, args.first, args.last)
    if args.action == "manifest":
        print(json.dumps([{"version": m.version, "name": m.name, "checksum": m.digest, "statements": len(m.statements)} for m in migrations], indent=2)); return
    with exclusive_operations():
        db = DockerDatabase()
        if args.action == "backup":
            if not args.backup_dir:
                parser.error("--backup-dir is required")
            backup(db, args.backup_dir); print("Isolated MySQL backup and checksum manifest created."); return
        migrator = Migrator(db)
        if args.action == "baseline":
            migrator.baseline(migrations)
        elif args.action == "migrate":
            migrator.apply(migrations, args.retry_failed)
        else:
            migrator.initialize()
        print(json.dumps(migrator.validate_history(migrations), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OpsError, OSError, ValueError) as error:
        print(type(error).__name__ + ": " + str(error))
        raise SystemExit(1)
