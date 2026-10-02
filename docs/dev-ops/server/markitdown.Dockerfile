FROM python:3.12-slim
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" markitdown-mcp==0.0.1a7
RUN useradd --create-home worker
USER worker
CMD ["markitdown-mcp", "--http", "--host", "0.0.0.0", "--port", "3001"]
