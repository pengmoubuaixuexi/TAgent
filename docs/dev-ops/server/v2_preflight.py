"""Read-only v2 readiness checks. Does not start services, migrate data or print secrets."""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import ipaddress
import json
from pathlib import Path
import re
import ssl
import subprocess
import zipfile


def env_values(path: Path) -> dict[str, str]:
    result = {}
    if not path.is_file():
        return result
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)", line)
        if match:
            value = match[2].strip()
            if len(value) > 1 and value[0] == value[-1] and value[0] in "'\"":
                value = value[1:-1]
            result[match[1]] = value
    return result


def master_key_error(values: dict[str, str]) -> str | None:
    value = values.get("MODEL_SECRET_MASTER_KEY", "")
    try:
        decoded = base64.b64decode(value, validate=True)
        if len(decoded) == 32 and len(set(decoded)) >= 8:
            return None
    except ValueError:
        pass
    return "MODEL_SECRET_MASTER_KEY must be a random Base64-encoded 32-byte key; preserve it separately from database backups"


def valid_domain(value: str) -> bool:
    if not value or value.endswith((".example", ".invalid", ".test", ".localhost")) or "YOUR_" in value:
        return False
    try:
        ipaddress.ip_address(value)
        return False
    except ValueError:
        pass
    return bool(re.fullmatch(r"(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}", value))


def inspect_compose(config: dict) -> list[str]:
    errors = []
    services = config.get("services", {})
    for name, service in services.items():
        for port in service.get("ports", []):
            if not isinstance(port, dict):
                errors.append(f"Compose port for {name} could not be validated; use normalized config JSON")
                continue
            if name == "nginx" and int(port.get("target", 0)) in {80, 443}:
                continue
            if port.get("host_ip") not in {"127.0.0.1", "::1"}:
                errors.append(f"Service {name} publishes a port beyond loopback; remove public backend/database access before HTTPS launch")
    app = services.get("app", {})
    environment = app.get("environment", {})
    if not isinstance(environment, dict) or str(environment.get("SESSION_COOKIE_SECURE", "")).lower() != "true":
        errors.append("Rendered app environment requires SESSION_COOKIE_SECURE=true")
    if isinstance(environment, dict):
        error = master_key_error(environment)
        if error:
            errors.append("Rendered app environment: " + error)
    volume_targets = {volume.get("target") for volume in app.get("volumes", []) if isinstance(volume, dict)}
    if "/app/data" not in volume_targets:
        errors.append("Application /app/data needs a persistent volume for knowledge sources and attachments")
    for service in ("mysql", "vector_db", "elasticsearch"):
        if service not in services:
            errors.append(f"Required service {service} is absent")
    return errors


def inspect_jar(path: Path, *, sanitized: bool) -> list[str]:
    if not path.is_file():
        return ["Application JAR is missing"]
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            errors = []
            required = {"BOOT-INF/classes/static/knowledge.html", "BOOT-INF/classes/static/models.html", "BOOT-INF/classes/static/user-agents.html"}
            if not required <= names:
                errors.append("Application JAR lacks required v2 pages; verify build provenance")
            if sanitized and any("BOOT-INF/classes/application-" + profile + ".yml" in names for profile in ("dev", "test", "prod")):
                errors.append("Deployable sanitized JAR still contains private development/test/production profiles")
            return errors
    except (OSError, zipfile.BadZipFile):
        return ["Application JAR cannot be read as a valid archive"]


def inspect_certificate(path: Path, domain: str) -> list[str]:
    if not path.is_file():
        return ["TLS certificate is missing"]
    try:
        cert = ssl._ssl._test_decode_cert(str(path))
        end = ssl.cert_time_to_seconds(cert["notAfter"])
        start = ssl.cert_time_to_seconds(cert["notBefore"])
        now = datetime.now(timezone.utc).timestamp()
        if not start <= now < end:
            return ["TLS certificate is not currently valid"]
        names = [name.lower() for kind, name in cert.get("subjectAltName", []) if kind == "DNS"]
        def matches(name):
            return name == domain.lower() or (name.startswith("*.") and domain.lower().endswith(name[1:]) and domain.count(".") == name.count("."))
        if not any(matches(name) for name in names):
            return ["TLS certificate Subject Alternative Name does not cover DOMAIN"]
        if end - now < 14 * 86400:
            return ["TLS certificate expires in less than 14 days; arrange renewal before launch"]
        return []
    except Exception:
        return ["TLS certificate cannot be decoded; no key/certificate content was printed"]


def check(root: Path, jar: Path, compose: bool = True, sanitized: bool = False) -> dict:
    errors, checks = [], []
    values = env_values(root / ".env")
    for key in ("DOMAIN", "MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "POSTGRES_PASSWORD", "GRAFANA_ADMIN_PASSWORD", "EMBEDDING_BASE_URL", "EMBEDDING_API_KEY"):
        value = values.get(key, "")
        if not value or "REPLACE_" in value or ".example" in value:
            errors.append("Configure " + key + " in .env")
    if not valid_domain(values.get("DOMAIN", "")):
        errors.append("DOMAIN must be a configured DNS name, not an IP or placeholder")
    key_error = master_key_error(values)
    if key_error:
        errors.append(key_error)
    if values.get("SESSION_COOKIE_SECURE", "").lower() != "true":
        errors.append("HTTPS launch requires SESSION_COOKIE_SECURE=true")
    errors.extend(inspect_certificate(root / "certs/fullchain.pem", values.get("DOMAIN", "")))
    if not (root / "certs/privkey.pem").is_file():
        errors.append("TLS private key is missing; do not include it in source control")
    errors.extend(inspect_jar(jar, sanitized=sanitized))
    cloud = root / "application-cloud.yml"
    if not cloud.is_file():
        errors.append("Existing application-cloud.yml is missing; this check does not generate or overwrite it")
    else:
        content = cloud.read_text(encoding="utf-8")
        if "forward-headers-strategy: framework" not in content:
            errors.append("Review trusted reverse-proxy forwarding in application-cloud.yml")
        if re.search(r"(?m)^\s*secure:\s*false\s*$", content):
            errors.append("application-cloud.yml overrides secure cookies to false")
    if compose:
        result = subprocess.run(["docker", "compose", "--env-file", ".env", "-f", "compose.yml", "config", "--format", "json"], cwd=root, capture_output=True, text=True, check=False)
        if result.returncode:
            errors.append("Compose configuration failed validation; detailed output suppressed because it can include secrets")
        else:
            try:
                errors.extend(inspect_compose(json.loads(result.stdout)))
                checks.append("Rendered Compose inspected without printing environment values")
            except ValueError:
                errors.append("Compose did not return valid JSON")
    checks.extend(["Input files inspected read-only", "No service started, no database changed, no model contacted"])
    return {"passed": not errors, "errors": errors, "checks": checks,
            "remainingLiveChecks": ["DNS resolution and domain approval", "Certificate/private-key match via nginx -t", "HTTPS login/SSE/cancel/reconnect", "Database migration history and backup restore", "Real provider/MCP connectivity and capacity"]}


def render_nginx(domain: str, output: Path) -> None:
    if not valid_domain(domain):
        raise ValueError("Supply a valid DNS hostname; IPs and shell/configuration fragments are rejected")
    template = Path(__file__).with_name("nginx-v2.conf.template").read_text(encoding="utf-8")
    # Exclusive creation deliberately refuses to overwrite the user's current nginx.conf.
    with output.open("x", encoding="utf-8", newline="\n") as file:
        file.write(template.replace("YOUR_DOMAIN", domain.lower()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--render-nginx", type=Path, help="Create a new configuration file; never overwrite an existing file")
    parser.add_argument("--domain")
    parser.add_argument("--no-compose", action="store_true")
    parser.add_argument("--sanitized-jar", action="store_true", help="Require private profiles to have been stripped from the final image JAR")
    args = parser.parse_args()
    if args.render_nginx:
        try:
            render_nginx(args.domain or "", args.render_nginx)
        except (OSError, ValueError) as error:
            parser.error(str(error))
        print("New nginx template rendered. Not activated; run nginx -t with the intended certificate before deployment.")
        return
    if not args.jar:
        parser.error("--jar is required for readiness checks")
    result = check(args.root.resolve(), args.jar.resolve(), not args.no_compose, args.sanitized_jar)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
