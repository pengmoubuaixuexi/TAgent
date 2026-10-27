"""Runs /sandbox/.run/main.py for one execution.

/sandbox/output is emptied first, so it only holds files produced by this execution.
Every file the code writes is capped by RLIMIT_FSIZE; the container itself has no network.
"""
import importlib.abc
import importlib.util
import os
import resource
import runpy
import shutil
import sys
import traceback

limit = int(os.environ.get("SANDBOX_FILE_LIMIT_BYTES", str(64 * 1024 * 1024)))
resource.setrlimit(resource.RLIMIT_FSIZE, (limit, limit))


class CappedWriter:
    """Keeps printed output within what the container log keeps, so the head is never rotated away."""

    def __init__(self, stream, cap):
        self.stream, self.left, self.cut = stream, cap, False

    def write(self, text):
        if self.left <= 0:
            if not self.cut:
                self.cut = True
                self.stream.write("\n[输出过多，后面的内容已截断]\n")
            return len(text)
        piece = text[: self.left]
        self.left -= len(piece)
        self.stream.write(piece)
        return len(text)

    def flush(self):
        self.stream.flush()

    def __getattr__(self, name):
        return getattr(self.stream, name)


stdio_cap = int(os.environ.get("SANDBOX_STDIO_LIMIT_CHARS", "1000000"))
sys.stdout = CappedWriter(sys.stdout, stdio_cap)
sys.stderr = CappedWriter(sys.stderr, stdio_cap)

output = "/sandbox/output"
for name in os.listdir(output):
    path = os.path.join(output, name)
    if os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path, ignore_errors=True)
    else:
        try:
            os.unlink(path)
        except OSError:
            pass

# A prebuilt font cache keeps matplotlib from rebuilding it on every run.
cache = os.environ.get("MPLCONFIGDIR", "/tmp/matplotlib")
os.makedirs(cache, exist_ok=True)
for name in os.listdir("/opt/sandbox/mplcache"):
    shutil.copy(os.path.join("/opt/sandbox/mplcache", name), os.path.join(cache, name))


class _CjkFallback(importlib.abc.MetaPathFinder):
    """Code often names fonts this image lacks (SimHei, Microsoft YaHei, Noto Sans CJK SC). matplotlib
    then falls back to DejaVu Sans, which has no CJK glyphs, and every Chinese label renders as boxes.
    Fall back to the installed WenQuanYi Micro Hei instead. Applied when matplotlib is first used, so
    code that never plots does not pay for importing it."""

    def find_spec(self, name, path, target=None):
        if name != "matplotlib.font_manager":
            return None
        sys.meta_path.remove(self)
        spec = importlib.util.find_spec(name)
        if spec is not None and spec.loader is not None:
            run = spec.loader.exec_module

            def exec_module(module):
                run(module)
                module.fontManager.defaultFamily["ttf"] = "WenQuanYi Micro Hei"

            spec.loader.exec_module = exec_module
        return spec


sys.meta_path.insert(0, _CjkFallback())

MAIN = "/sandbox/.run/main.py"
os.chdir("/sandbox/workspace")
sys.path.insert(0, "/sandbox/workspace")
sys.argv = ["main.py"]
try:
    runpy.run_path(MAIN, run_name="__main__")
except SystemExit:
    raise
except BaseException as error:
    # Report from the user's code down: this wrapper and runpy are noise. A SyntaxError
    # has no such frame and prints only its own location.
    frames = error.__traceback__
    while frames is not None and frames.tb_frame.f_code.co_filename != MAIN:
        frames = frames.tb_next
    traceback.print_exception(type(error), error, frames)
    sys.exit(1)
