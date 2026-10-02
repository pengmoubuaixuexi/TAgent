FROM node:22-bookworm-slim AS runtime-base
ARG DEBIAN_MIRROR=http://mirrors.tuna.tsinghua.edu.cn
RUN sed -i "s|http://deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates curl zip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Strip private profiles before copying the JAR into the final image's layers.
FROM runtime-base AS sanitize
COPY ai-agent-station-study-app/target/ai-agent-station-study-app.jar /app/app.jar
RUN zip -d /app/app.jar BOOT-INF/classes/application-dev.yml BOOT-INF/classes/application-test.yml BOOT-INF/classes/application-prod.yml

FROM runtime-base
COPY --from=sanitize /app/app.jar /app/app.jar
COPY mcp-servers /app/mcp-servers
COPY docs/dev-ops/mcp-wrappers/package*.json /app/docs/dev-ops/mcp-wrappers/
RUN cd /app/docs/dev-ops/mcp-wrappers && npm ci --omit=dev
COPY docs/dev-ops/mcp-wrappers/*.js /app/docs/dev-ops/mcp-wrappers/
RUN mkdir -p /app/data/workspace /app/.npm && chown -R node:node /app
USER node
ENV NODE_ENV=production NPM_CONFIG_CACHE=/app/.npm
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=cloud --spring.config.additional-location=file:/app/config/"]
