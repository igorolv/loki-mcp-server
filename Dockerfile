# Multi-stage build: compile the fat jar with a JDK, run it on a slim JRE.
#
#   docker build -t loki-mcp-server .
#   docker run -i --rm -v ~/.loki-mcp-server:/data loki-mcp-server
#
# /data holds connections.json plus the logs and default export directory the server writes.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Resolve the Gradle distribution and dependencies first so source edits reuse this layer.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# A Windows checkout may hand us gradlew with CRLF endings and no executable bit.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/igorolv/loki-mcp-server" \
      org.opencontainers.image.description="Read-only MCP server for Grafana Loki: label discovery, bounded LogQL queries, counts and exports" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.modelcontextprotocol.server.name="io.github.igorolv/loki-mcp-server"

RUN useradd --system --create-home --uid 10001 mcp \
    && mkdir -p /data && chown mcp:mcp /data
USER mcp
WORKDIR /app

COPY --from=build --chown=mcp:mcp /src/build/libs/loki-mcp-server.jar ./loki-mcp-server.jar

ENV LOKI_MCP_DATA_DIR=/data
VOLUME ["/data"]

# stdio transport: the MCP client talks over stdin/stdout, logs go to stderr and /data/logs.
ENTRYPOINT ["java", "-jar", "/app/loki-mcp-server.jar"]
