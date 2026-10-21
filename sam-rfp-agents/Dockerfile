FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY samagents ./samagents
COPY config.yaml .
RUN mkdir -p /app/data
VOLUME ["/app/data"]
ENV DATABASE_PATH=/app/data/opportunities.sqlite
CMD ["python", "-m", "samagents", "run"]
