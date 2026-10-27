# Sandbox runner: the only component that talks to the Docker daemon. It creates execution
# containers from one fixed image with fixed limits; callers cannot choose images or options.
FROM python:3.11-slim-bookworm
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
COPY requirements.txt /opt/runner/requirements.txt
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r /opt/runner/requirements.txt
COPY runner /opt/runner/runner
WORKDIR /opt/runner
EXPOSE 8000
CMD ["uvicorn", "runner.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
