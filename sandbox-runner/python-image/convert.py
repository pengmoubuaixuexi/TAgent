"""Markdown to Word or PDF inside the sandbox: pandoc for Word, pandoc HTML + WeasyPrint for PDF.

Usage: convert.py docx|pdf <input name> <output name> [title]
Remote and local resources referenced by the document are never fetched.
"""
import os
import subprocess
import sys

fmt, source, target = sys.argv[1], sys.argv[2], sys.argv[3]
title = sys.argv[4] if len(sys.argv) > 4 else ""
src = os.path.join("/sandbox/input", os.path.basename(source))
out_dir = "/sandbox/output"
for name in os.listdir(out_dir):
    try:
        os.unlink(os.path.join(out_dir, name))
    except OSError:
        pass
out = os.path.join(out_dir, os.path.basename(target))
meta = ["--metadata", "title=" + title] if title else ["--metadata", "pagetitle=document"]

if fmt == "docx":
    subprocess.run(["pandoc", "-f", "gfm", "-t", "docx", "-o", out, src] + meta, check=True, timeout=50)
elif fmt == "pdf":
    html = subprocess.run(["pandoc", "-f", "gfm", "-t", "html5", "-s", src] + meta,
                          check=True, capture_output=True, timeout=30).stdout.decode("utf-8")
    from weasyprint import CSS, HTML

    def deny(url, *args, **kwargs):
        raise ValueError("external resources are disabled")

    css = CSS(string="""
        @page { size: A4; margin: 2cm 1.8cm; }
        html { font-size: 11pt; }
        body { font-family: "Noto Sans CJK SC", "WenQuanYi Micro Hei", sans-serif; line-height: 1.7; max-width: none; margin: 0; padding: 0; }
        h1, h2, h3 { line-height: 1.3; }
        pre, code { font-family: "Noto Sans Mono CJK SC", "WenQuanYi Micro Hei Mono", monospace; font-size: 9pt; }
        pre { white-space: pre-wrap; background: #f5f5f5; padding: 8px; }
        table { border-collapse: collapse; }
        th, td { border: 1px solid #999; padding: 4px 6px; }
        img { max-width: 100%; }
    """, url_fetcher=deny)
    HTML(string=html, url_fetcher=deny, base_url="/nonexistent/").write_pdf(out, stylesheets=[css])
else:
    sys.exit("unsupported format")
print("converted", os.path.basename(out), os.path.getsize(out))
