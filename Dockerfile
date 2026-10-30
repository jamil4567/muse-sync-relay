FROM python:3.12-slim
WORKDIR /app
COPY relay_server.py .
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt || true
EXPOSE 8765
CMD ["python", "relay_server.py"]
