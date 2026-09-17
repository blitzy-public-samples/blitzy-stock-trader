# API Quick Start Guide

## Installation

1. Install dependencies:
```bash
pip install -r requirements.txt
```

## Running the API

### Option 1: Using Python Module
```bash
python -m stock_sentiment.api
```

### Option 2: Using Uvicorn Directly
```bash
uvicorn stock_sentiment.api.main:app --host 0.0.0.0 --port 8000
```

### Option 3: Development Mode (Auto-reload)
```bash
python -m stock_sentiment.api --reload
```

## Testing the API

### 1. Health Check
```bash
curl http://localhost:8000/health
```

### 2. Get Sentiment
```bash
curl http://localhost:8000/sentiment/AAPL
```

### 3. With Data Source Filter
```bash
curl "http://localhost:8000/sentiment/MSFT?sources=yfinance,alpha_vantage"
```

### 4. Force RAG Usage
```bash
curl "http://localhost:8000/sentiment/GOOGL?cache_enabled=false"
```

## API Documentation

Once the API is running, visit:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## Docker

Build and run with Docker:
```bash
docker build -f Dockerfile.api -t sentiment-api .
docker run -p 8000:8000 --env-file .env sentiment-api
```

## Configuration

Set environment variables in `.env` file:
- `AZURE_OPENAI_ENDPOINT`
- `AZURE_OPENAI_API_KEY`
- `AZURE_OPENAI_DEPLOYMENT_NAME`
- `AZURE_OPENAI_EMBEDDING_DEPLOYMENT`
- `REDIS_HOST` (optional)
- `REDIS_PASSWORD` (optional)
- `AZURE_AI_SEARCH_ENDPOINT` (optional)
- `AZURE_AI_SEARCH_API_KEY` (optional)

See `.env.example` for all available options.

