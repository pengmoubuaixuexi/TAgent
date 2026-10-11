"""Opt-in local nginx syntax/certificate fixture, with no network or published ports."""
import datetime
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
import uuid
import v2_preflight


@unittest.skipUnless(os.environ.get("TAGENT_HTTPS_TEMPLATE_TEST") == "1", "Opt-in local Docker nginx fixture only")
class HttpsTemplateTest(unittest.TestCase):
    def test_nginx_accepts_rendered_template_and_matching_fixture_certificate(self):
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        with tempfile.TemporaryDirectory(prefix="tagent-v2-nginx-") as directory:
            root = Path(directory)
            certificate_dir = root / "certs"
            certificate_dir.mkdir()
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "agent.example.org")])
            now = datetime.datetime.now(datetime.timezone.utc)
            certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
                           .serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(days=1))
                           .not_valid_after(now+datetime.timedelta(days=30))
                           .add_extension(x509.SubjectAlternativeName([x509.DNSName("agent.example.org")]), critical=False)
                           .sign(key, hashes.SHA256()))
            (certificate_dir / "fullchain.pem").write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
            (certificate_dir / "privkey.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            self.assertEqual([], v2_preflight.inspect_certificate(certificate_dir / "fullchain.pem", "agent.example.org"))
            self.assertTrue(v2_preflight.inspect_certificate(certificate_dir / "fullchain.pem", "wrong.example.org"))
            v2_preflight.render_nginx("agent.example.org", root / "nginx.conf")
            result = subprocess.run(["docker", "run", "--rm", "--network", "none", "--pull", "never",
                                     "--mount", "type=bind,source="+str(root / "nginx.conf")+",target=/etc/nginx/conf.d/default.conf,readonly",
                                     "--mount", "type=bind,source="+str(certificate_dir)+",target=/etc/nginx/certs,readonly",
                                     "nginx:1.28-alpine", "nginx", "-t"], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("test is successful", result.stderr)
            print("PASS: rendered nginx syntax and certificate pairing in isolated --network none container; no domain activated")

            # Use a synthetic upstream inside the same network-isolated container. Authentication reads
            # happen on every workspace page and must not consume the login/password protection budget.
            config = (root / "nginx.conf").read_text(encoding="utf-8").replace("set $app http://app:8099;", "set $app http://127.0.0.1:8099;")
            config += '\nserver { listen 127.0.0.1:8099; location / { return 200 "fixture\\n"; } }\n'
            (root / "nginx.conf").write_text(config, encoding="utf-8")
            name = "tagent-nginx-auth-fixture-" + uuid.uuid4().hex[:12]
            start = subprocess.run(["docker", "run", "--rm", "-d", "--name", name, "--network", "none", "--pull", "never",
                                    "--mount", "type=bind,source="+str(root / "nginx.conf")+",target=/etc/nginx/conf.d/default.conf,readonly",
                                    "--mount", "type=bind,source="+str(certificate_dir)+",target=/etc/nginx/certs,readonly",
                                    "nginx:1.28-alpine"], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, start.returncode, start.stderr)
            try:
                ready = False
                for _ in range(20):
                    check = subprocess.run(["docker", "exec", name, "wget", "-qO-", "http://127.0.0.1:8099/"], capture_output=True, text=True, timeout=10)
                    if check.returncode == 0:
                        ready = True
                        break
                    time.sleep(.1)
                self.assertTrue(ready, "isolated synthetic upstream failed to start")
                def statuses(path, post=False):
                    command = 'i=0; while [ "$i" -lt 24 ]; do wget -S --no-check-certificate -O /dev/null --header="Host: agent.example.org" '
                    if post:
                        command += '--post-data=fixture '
                    command += 'https://127.0.0.1' + path + ' 2>&1; i=$((i+1)); done'
                    response = subprocess.run(["docker", "exec", name, "sh", "-c", command], capture_output=True, text=True, timeout=40)
                    codes = re.findall(r"(?m)^\s+HTTP/\d(?:\.\d)? (\d{3})", response.stdout + response.stderr)
                    self.assertEqual(24, len(codes), "fixture must receive exactly one response per request")
                    return codes
                for path in ("/api/v1/auth/me", "/api/v1/auth/csrf", "/api/v1/auth/admin-check"):
                    self.assertEqual(["200"] * 24, statuses(path), path + " must not be throttled by credential limits")
                for path in ("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/password"):
                    self.assertIn("429", statuses(path, post=True), path + " must retain credential attempt protection")
                self.assertEqual(["200"] * 24, statuses("/api/v1/auth/me"), "exhausted login budget must not break existing sessions")
                print("PASS: real nginx excludes session/CSRF reads from auth limits and still throttles login/register/password with 429")
            finally:
                subprocess.run(["docker", "rm", "-f", name], capture_output=True, text=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
