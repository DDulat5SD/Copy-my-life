# CopyMyLife AI matching service (US-26): FastAPI + ONNX Runtime on the CPU.
# Build context: this ai/ folder (Railway: Root Directory /ai). Everything is pinned: the base
# image by digest, every Python package by hash (requirements.lock.txt) and the model by
# revision and SHA-256 (model-manifest.json), so each build gives the same service.

FROM python:3.13.17-slim-bookworm@sha256:e32be0e55814a08977501ca863271d9bd02443710db8f2afaf8372e2c4508530

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# Packages first, then the model, then the code: a code change reuses both cached layers.
COPY requirements.lock.txt ./
RUN pip install --require-hashes -r requirements.lock.txt

COPY model-manifest.json download_model.py ./
RUN python download_model.py /models/multilingual-minilm

COPY ai_engine.py semantic_search.py app.py ./

RUN useradd --system --uid 10001 --no-create-home copymylife
USER copymylife

# Reachable only on Railway's private network (no public domain); every request except
# /health must carry X-AI-Token = CML_AI_TOKEN, and the service will not start without it.
ENV CML_AI_MODEL_DIR=/models/multilingual-minilm \
    CML_AI_HOST=:: \
    CML_AI_ALLOW_REMOTE=1 \
    CML_AI_THREADS=2 \
    PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('PORT', '8080'), timeout=4)"

CMD ["python", "app.py"]
