FROM python:3.12-slim

WORKDIR /app

COPY server.py /app/server.py

ENV ORDER_SERVICE_HOST=0.0.0.0
ENV ORDER_SERVICE_PORT=8080
ENV ORDER_SERVICE_DATABASE_PATH=/data/order-service.db

RUN mkdir -p /data

VOLUME ["/data"]
EXPOSE 8080

CMD ["python", "/app/server.py"]
