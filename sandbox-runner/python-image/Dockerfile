# Execution image for sandboxed Python. Never has network access at run time, so every
# library the agent may use is installed here.
FROM python:3.11-slim-bookworm
ARG DEBIAN_MIRROR=http://mirrors.tuna.tsinghua.edu.cn
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN sed -i "s|http://deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk fonts-wqy-microhei pandoc \
       libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz-subset0 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt /opt/sandbox/requirements.txt
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r /opt/sandbox/requirements.txt
COPY entry.py convert.py matplotlibrc /opt/sandbox/
RUN mkdir -p /opt/sandbox/mplcache \
    && cp /opt/sandbox/matplotlibrc /opt/sandbox/mplcache/matplotlibrc \
    && MPLCONFIGDIR=/opt/sandbox/mplcache python -c "import matplotlib.pyplot" \
    && chmod -R a+rX /opt/sandbox \
    && useradd --uid 1000 --no-create-home --shell /usr/sbin/nologin sandbox \
    && mkdir /sandbox \
    && ln -s /sandbox/workspace /workspace && ln -s /sandbox/input /input && ln -s /sandbox/output /output
ENV MPLBACKEND=Agg PYTHONDONTWRITEBYTECODE=1
USER 1000:1000
WORKDIR /sandbox/workspace
CMD ["python", "-X", "utf8", "/opt/sandbox/entry.py"]
