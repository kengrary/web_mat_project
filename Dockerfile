FROM eclipse-temurin:21-jdk AS jdk

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV JVMIND_MCP_STATE_DIR=/home/app/.local/state/jvmind-mcp
ENV JVMIND_MCP_DUMP_DIR=/home/app/dumps

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY mcp-server/pyproject.toml mcp-server/README.md mcp-server/NOTICE mcp-server/LICENSE ./
COPY mcp-server/src ./src

RUN pip install --no-cache-dir .

USER app

# Eclipse MAT is not bundled (EPL-2.0). For heap analysis, mount MAT writable
# and pass MAT_HOME, for example:
#   docker run --rm -i -v /opt/mat:/opt/mat -e MAT_HOME=/opt/mat <image>
#
# Without MAT, MCP initialize, tools/list, and list_java_processes still work.
ENTRYPOINT ["jvmind-mat-mcp"]
