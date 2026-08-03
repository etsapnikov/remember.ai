# Слой без ASR: fastapi + ffmpeg. Лёгкий, поднимается за секунды.
# ASR-слой (torch/onnxruntime/GigaAM) добавляется целью `asr` — см. docker-compose.yml.
FROM python:3.12-slim AS base

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

ENV PYTHONUNBUFFERED=1 \
    ASR_BACKEND=stub \
    VAD_BACKEND=energy

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]


# --- слой распознавания ---
FROM base AS asr

COPY requirements-asr.txt ./
RUN pip install --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu \
    -r requirements-asr.txt

ENV ASR_BACKEND=gigaam \
    VAD_BACKEND=silero \
    ASR_MODEL_PATH=/models/gigaam
