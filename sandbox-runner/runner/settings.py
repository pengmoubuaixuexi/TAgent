"""Runner configuration from environment variables. Every limit has a safe default."""
import os
from dataclasses import dataclass


def _int(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(os.environ.get(name, default))
    except ValueError:
        value = default
    return max(low, min(high, value))


@dataclass(frozen=True)
class Settings:
    token: str
    image: str
    volume: str
    root: str
    workers: int
    queue_timeout: int
    exec_timeout: int
    max_exec_timeout: int
    memory_mb: int
    pids: int
    cpus: float
    tmp_mb: int
    file_limit_mb: int
    workspace_limit_mb: int
    max_outputs: int
    max_output_file_mb: int
    max_output_total_mb: int
    max_log_chars: int
    max_input_total_mb: int
    result_ttl: int
    run_ttl: int
    min_free_disk_mb: int = 512
    # Each check walks the run directory; the per-file size limit still applies between checks.
    disk_check_interval_ms: int = 500

    @staticmethod
    def load() -> "Settings":
        token = os.environ.get("SANDBOX_TOKEN", "")
        if len(token) < 24:
            raise SystemExit("SANDBOX_TOKEN must be set (at least 24 characters)")
        return Settings(
            token=token,
            image=os.environ.get("SANDBOX_IMAGE", "tagent-sandbox-python:1"),
            volume=os.environ.get("SANDBOX_VOLUME", "tagent-sandbox-work"),
            root=os.environ.get("SANDBOX_ROOT", "/sandbox-root"),
            workers=_int("SANDBOX_WORKERS", 3, 1, 16),
            queue_timeout=_int("SANDBOX_QUEUE_TIMEOUT_SECONDS", 60, 1, 600),
            exec_timeout=_int("SANDBOX_EXEC_TIMEOUT_SECONDS", 60, 1, 600),
            max_exec_timeout=_int("SANDBOX_MAX_EXEC_TIMEOUT_SECONDS", 120, 1, 900),
            memory_mb=_int("SANDBOX_MEMORY_MB", 512, 64, 8192),
            pids=_int("SANDBOX_PIDS", 128, 16, 4096),
            cpus=float(_int("SANDBOX_CPU_MILLIS", 1000, 100, 8000)) / 1000.0,
            tmp_mb=_int("SANDBOX_TMP_MB", 64, 8, 1024),
            file_limit_mb=_int("SANDBOX_FILE_LIMIT_MB", 64, 1, 1024),
            workspace_limit_mb=_int("SANDBOX_WORKSPACE_LIMIT_MB", 512, 16, 8192),
            max_outputs=_int("SANDBOX_MAX_OUTPUTS", 20, 1, 100),
            max_output_file_mb=_int("SANDBOX_MAX_OUTPUT_FILE_MB", 10, 1, 200),
            max_output_total_mb=_int("SANDBOX_MAX_OUTPUT_TOTAL_MB", 20, 1, 500),
            max_log_chars=_int("SANDBOX_MAX_LOG_CHARS", 20000, 1000, 500000),
            max_input_total_mb=_int("SANDBOX_MAX_INPUT_TOTAL_MB", 120, 1, 2048),
            result_ttl=_int("SANDBOX_RESULT_TTL_SECONDS", 900, 60, 86400),
            run_ttl=_int("SANDBOX_RUN_TTL_SECONDS", 7200, 300, 604800),
            min_free_disk_mb=_int("SANDBOX_MIN_FREE_DISK_MB", 512, 64, 16384),
            disk_check_interval_ms=_int("SANDBOX_DISK_CHECK_INTERVAL_MS", 500, 50, 5000),
        )
