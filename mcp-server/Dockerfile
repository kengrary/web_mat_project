FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY pyproject.toml README.md NOTICE LICENSE ./
COPY src ./src

RUN pip install --no-cache-dir .

# This image contains the MCP server only. Eclipse MAT and JDK 21 must be
# available at runtime (mount MAT and set MAT_HOME) for heap analysis.
ENTRYPOINT ["jvmind-mat-mcp"]
