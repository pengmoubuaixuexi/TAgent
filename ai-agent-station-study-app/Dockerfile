# 基础镜像，可以先执行 docker pull openjdk:17-ea-17-jdk-slim-buster
FROM registry.cn-hangzhou.aliyuncs.com/tagent/openjdk:17-ea-17-jdk-slim-buster

# 作者
LABEL maintainer="TAgent"

# 配置
ENV PARAMS=""

# 时区
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 修复 Debian Buster 软件源问题并安装 Node.js 环境
RUN echo "deb http://archive.debian.org/debian buster main" > /etc/apt/sources.list && \
    echo "deb http://archive.debian.org/debian-security buster/updates main" >> /etc/apt/sources.list && \
    echo "Acquire::Check-Valid-Until false;" > /etc/apt/apt.conf.d/99no-check-valid-until && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    npm install -g npm@latest && \
    apt-get purge -y --auto-remove curl gnupg && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# 验证 Node.js 环境（可选）
RUN node -v && npm -v

# 添加应用
ADD target/ai-agent-station-study-app.jar /ai-agent-station-study-app.jar

ENTRYPOINT ["sh","-c","java -jar $JAVA_OPTS /ai-agent-station-study-app.jar $PARAMS"]