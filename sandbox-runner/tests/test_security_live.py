"""Security and limit checks against a running runner (needs Docker).

Run with SANDBOX_URL (e.g. http://127.0.0.1:18088) and SANDBOX_TOKEN set; skipped otherwise.
"""
import concurrent.futures
import json
import os
import time
import urllib.error
import urllib.request
import uuid

import pytest

URL = os.environ.get("SANDBOX_URL")
TOKEN = os.environ.get("SANDBOX_TOKEN")
pytestmark = pytest.mark.skipif(not URL or not TOKEN, reason="needs SANDBOX_URL and SANDBOX_TOKEN")


def key(prefix):
    return (prefix + "-" + uuid.uuid4().hex)[:40]


def submit(code, run=None, user="user-00000001", inputs=(), timeout=20, kind="python", convert=None):
    boundary = "----sandbox" + uuid.uuid4().hex
    meta = {"run": run or key("run"), "user": user, "kind": kind, "timeoutSeconds": timeout}
    if convert:
        meta["convert"] = convert
    parts = [("meta", None, json.dumps(meta).encode()), ("code", None, code.encode())]
    parts += [("inputs", name, data) for name, data in inputs]
    body = b""
    for field, filename, data in parts:
        disposition = 'form-data; name="%s"' % field + ('; filename="%s"' % filename if filename else "")
        body += ("--%s\r\nContent-Disposition: %s\r\n\r\n" % (boundary, disposition)).encode("utf-8") + data + b"\r\n"
    body += ("--%s--\r\n" % boundary).encode()
    request = urllib.request.Request(URL + "/v1/executions", data=body, method="POST", headers={
        "Authorization": "Bearer " + TOKEN, "Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())["executionId"], meta["run"]


def result(execution_id, wait=120):
    deadline = time.time() + wait
    while time.time() < deadline:
        request = urllib.request.Request(URL + "/v1/executions/" + execution_id, headers={"Authorization": "Bearer " + TOKEN})
        with urllib.request.urlopen(request, timeout=30) as response:
            snapshot = json.loads(response.read())
        if snapshot["state"] == "finished":
            return snapshot["result"]
        time.sleep(0.3)
    raise AssertionError("execution did not finish")


def run(code, **kwargs):
    execution_id, run_key = submit(code, **kwargs)
    return result(execution_id), execution_id, run_key


def download(execution_id, index):
    request = urllib.request.Request("%s/v1/executions/%s/outputs/%d" % (URL, execution_id, index),
                                     headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def test_requests_without_the_token_are_rejected():
    request = urllib.request.Request(URL + "/v1/executions/whatever")
    with pytest.raises(urllib.error.HTTPError) as error:
        urllib.request.urlopen(request, timeout=10)
    assert error.value.code == 401


def test_chart_with_chinese_labels_is_collected_as_output():
    code = ("import matplotlib.pyplot as plt\n"
            "plt.plot([1, 2, 3], [3, 1, 2]); plt.title('月度销售趋势'); plt.xlabel('月份')\n"
            "plt.savefig('/output/趋势.png')\nprint('完成')\n")
    outcome, execution_id, _ = run(code)
    assert outcome["status"] == "OK", outcome
    assert "完成" in outcome["stdout"]
    assert "Glyph" not in outcome["stderr"], outcome["stderr"]  # no missing-glyph warnings for Chinese
    assert outcome["outputs"][0]["name"] == "趋势.png"
    assert download(execution_id, 0)[:8] == b"\x89PNG\r\n\x1a\n"


def test_no_network():
    code = ("import socket, urllib.request\n"
            "for target in [('8.8.8.8', 53), ('1.1.1.1', 443)]:\n"
            "    try:\n"
            "        socket.create_connection(target, timeout=3); print('CONNECTED', target)\n"
            "    except OSError as e:\n"
            "        print('blocked', type(e).__name__)\n"
            "try:\n"
            "    urllib.request.urlopen('http://example.com', timeout=3); print('CONNECTED http')\n"
            "except Exception as e:\n"
            "    print('blocked', type(e).__name__)\n")
    outcome, _, _ = run(code)
    assert "CONNECTED" not in outcome["stdout"], outcome
    assert outcome["stdout"].count("blocked") == 3


def test_read_only_paths_and_inputs_cannot_be_written():
    code = ("import os\n"
            "for path in ['/etc/hacked', '/opt/sandbox/entry.py', '/usr/local/lib/x', '/input/data.csv', '/sandbox/x']:\n"
            "    try:\n"
            "        open(path, 'w').write('x'); print('WROTE', path)\n"
            "    except OSError as e:\n"
            "        print('denied', path, e.errno)\n"
            "try:\n"
            "    os.remove('/input/data.csv'); print('REMOVED input')\n"
            "except OSError as e:\n"
            "    print('denied remove', e.errno)\n"
            "print(open('/input/data.csv').read().strip())\n"
            "print(os.getuid())\n")
    outcome, _, _ = run(code, inputs=[("data.csv", "a,b\n1,2\n".encode())])
    assert "WROTE" not in outcome["stdout"] and "REMOVED" not in outcome["stdout"], outcome["stdout"]
    assert "a,b" in outcome["stdout"]
    assert outcome["stdout"].strip().endswith("1000")


def test_input_names_cannot_traverse_and_other_runs_are_invisible():
    secret_run = key("secret")
    outcome, _, _ = run("open('/sandbox/workspace/secret.txt','w').write('top secret')\nprint('saved')", run=secret_run)
    assert outcome["status"] == "OK"
    code = ("import os\n"
            "print(sorted(os.listdir('/sandbox')))\n"
            "print(sorted(os.listdir('/input')))\n"
            "found = []\n"
            "for root, dirs, files in os.walk('/', onerror=lambda e: None):\n"
            "    if root.startswith(('/proc', '/sys')): dirs[:] = []; continue\n"
            "    if 'secret.txt' in files: found.append(root)\n"
            "print('FOUND' if found else 'not found', found)\n")
    outcome, _, _ = run(code, inputs=[("../../etc/passwd", b"evil")])
    lines = outcome["stdout"].splitlines()
    assert lines[0] == "['.run', 'input', 'output', 'workspace']", lines
    assert "passwd" in lines[1] and ".." not in lines[1]
    assert lines[2].startswith("not found"), lines


def test_workspace_is_shared_within_a_run():
    run_key = key("shared")
    first, _, _ = run("open('step1.txt','w').write('中间结果 42')", run=run_key)
    assert first["status"] == "OK"
    second, _, _ = run("print(open('step1.txt').read())", run=run_key)
    assert "中间结果 42" in second["stdout"]


def test_fork_bomb_is_contained():
    code = ("import os\n"
            "count = 0\n"
            "try:\n"
            "    while True:\n"
            "        pid = os.fork()\n"
            "        if pid == 0:\n"
            "            import time; time.sleep(30); os._exit(0)\n"
            "        count += 1\n"
            "except OSError as e:\n"
            "    print('fork limited after', count, e.errno)\n")
    outcome, _, _ = run(code, timeout=15)
    assert "fork limited after" in outcome["stdout"], outcome
    assert int(outcome["stdout"].split()[3]) < 200


def test_memory_limit_kills_the_process():
    outcome, _, _ = run("blocks = []\nwhile True:\n    blocks.append(bytearray(64 * 1024 * 1024))\n", timeout=30)
    assert outcome["status"] in ("OOM", "KILLED"), outcome


def test_timeout_kills_the_container():
    started = time.time()
    outcome, _, _ = run("while True:\n    pass\n", timeout=3)
    assert outcome["status"] == "TIMEOUT", outcome
    assert time.time() - started < 30


def test_total_disk_limit_stops_multi_file_writes_and_the_run_recovers():
    # Run against a dedicated test runner with SANDBOX_WORKSPACE_LIMIT_MB set to this small value.
    limit = int(os.environ.get("SANDBOX_TEST_WORKSPACE_LIMIT_MB", "0"))
    if not 16 <= limit <= 64:
        pytest.skip("needs a dedicated runner with a 16-64 MB workspace limit")
    code = ("import time\n"
            "for i in range(%d):\n"
            "    open('/sandbox/workspace/%%d.bin' %% i, 'wb').write(b'x' * (1024 * 1024))\n"
            "    time.sleep(0.02)\n"
            "time.sleep(5)\n") % (limit + 8)
    outcome, _, run_key = run(code, timeout=20)
    assert outcome["status"] == "DISK_LIMIT", outcome
    assert outcome["outputs"] == []
    recovered, _, _ = run("print('recovered')", run=run_key)
    assert recovered["status"] == "OK", recovered
    assert "recovered" in recovered["stdout"]


def test_huge_output_is_truncated_and_limited():
    code = ("try:\n"
            "    open('/sandbox/workspace/huge.bin','wb').write(b'0' * (80 * 1024 * 1024))\n"
            "except OSError as e:\n"
            "    print('file limit', e.errno)\n"
            "open('/output/big.bin','wb').write(b'0' * (12 * 1024 * 1024))\n"
            "open('/output/ok.txt','w').write('ok')\n"
            "import os\n"
            "os.symlink('/etc/passwd', '/output/link.txt')\n"
            "print('x' * 2_000_000)\n")
    outcome, _, _ = run(code, timeout=30)
    assert outcome["stdoutTruncated"] is True
    assert len(outcome["stdout"]) < 30000
    assert "file limit 27" in outcome["stdout"]  # EFBIG
    names = [f["name"] for f in outcome["outputs"]]
    assert names == ["ok.txt"], outcome
    skipped = {s["name"]: s["reason"] for s in outcome["skippedOutputs"]}
    assert "big.bin" in skipped and "link.txt" in skipped


def test_ten_concurrent_executions_all_finish():
    codes = ["import time; time.sleep(1); print(%d)" % i for i in range(10)]
    with concurrent.futures.ThreadPoolExecutor(10) as pool:
        submitted = list(pool.map(lambda pair: submit(pair[1], user="user-%08d" % (pair[0] % 4)), enumerate(codes)))
        outcomes = list(pool.map(lambda item: result(item[0], wait=120), submitted))
    assert all(o["status"] == "OK" for o in outcomes), outcomes


def test_markdown_converts_to_word_and_pdf():
    markdown = "# 季度报告\n\n## 一、概览\n\n收入增长 **12%**。\n\n| 地区 | 收入 |\n|---|---|\n| 华东 | 100 |\n".encode("utf-8")
    for fmt, magic in (("docx", b"PK"), ("pdf", b"%PDF")):
        execution_id, _ = submit("", kind="convert", inputs=[("report.md", markdown)],
                                 convert={"format": fmt, "output": "季度报告." + fmt, "title": "季度报告"})
        outcome = result(execution_id)
        assert outcome["status"] == "OK", outcome
        assert outcome["outputs"][0]["name"] == "季度报告." + fmt
        assert download(execution_id, 0)[:len(magic)] == magic
