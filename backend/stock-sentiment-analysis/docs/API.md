# Stock Sentiment Analysis API Documentation

## Overview

The Stock Sentiment Analysis API provides REST endpoints for AI-powered sentiment analysis of stock symbols. The API collects news from multiple sources, analyzes sentiment using Azure OpenAI GPT-4, and returns aggregated sentiment scores.

## Base URL

```
http://localhost:8000
```

For production deployments, replace `localhost:8000` with your service endpoint.

## Authentication

Currently, the API does not require authentication. For production deployments, consider adding authentication (API keys, OAuth, etc.).

## Endpoints

### 1. Root Endpoint

Get API information.

**Request:**
```http
GET /
```

**Response:**
```json
{
  "name": "Stock Sentiment Analysis API",
  "version": "2.0.0",
  "description": "REST API for AI-powered stock sentiment analysis",
  "docs": "/docs",
  "health": "/health",
  "endpoints": {
    "sentiment": "/sentiment/{symbol}",
    "health": "/health"
  }
}
```

### 2. Health Check

Check the health status of the API and its dependencies.

**Request:**
```http
GET /health
```

**Response:**
```json
{
  "status": "healthy",
  "services": {
    "redis": "available",
    "rag": "available",
    "azure_openai": "available",
    "sentiment_analyzer": "available"
  },
  "timestamp": "2024-12-20T10:30:00"
}
```

**Status Values:**
- `healthy`: All critical services are available
- `degraded`: Some optional services are unavailable, but API can still function
- `unhealthy`: Critical services are unavailable

**Service Status Values:**
- `available`: Service is working
- `not_configured`: Service is not configured (optional)
- `unavailable`: Service is configured but not working

### 3. Get Sentiment Analysis

Analyze sentiment for a stock symbol.

**Request:**
```http
GET /sentiment/{symbol}?sources=yfinance,alpha_vantage&cache_enabled=true
```

**Path Parameters:**
- `symbol` (required): Stock ticker symbol (e.g., `AAPL`, `MSFT`, `GOOGL`)

**Query Parameters:**
- `sources` (optional): Comma-separated list of data sources to use
  - Available: `yfinance`, `alpha_vantage`, `finnhub`, `reddit`
  - Default: All enabled sources
  - Example: `?sources=yfinance,alpha_vantage`
- `cache_enabled` (optional): Enable/disable sentiment caching
  - Default: `true` (uses cache if available)
  - Set to `false` to force RAG usage
  - Example: `?cache_enabled=false`

**Response:**
```json
{
  "symbol": "AAPL",
  "positive": 0.65,
  "negative": 0.20,
  "neutral": 0.15,
  "net_sentiment": 0.45,
  "dominant_sentiment": "positive",
  "timestamp": "2024-12-20T10:30:00",
  "sources_analyzed": 15
}
```

**Response Fields:**
- `symbol`: Stock symbol analyzed
- `positive`: Aggregated positive sentiment score (0.0 to 1.0)
- `negative`: Aggregated negative sentiment score (0.0 to 1.0)
- `neutral`: Aggregated neutral sentiment score (0.0 to 1.0)
- `net_sentiment`: Net sentiment (positive - negative, -1.0 to 1.0)
- `dominant_sentiment`: Dominant sentiment label ("positive", "negative", or "neutral")
- `timestamp`: ISO format timestamp of analysis
- `sources_analyzed`: Number of articles analyzed

**Error Responses:**

**400 Bad Request** - Invalid stock symbol:
```json
{
  "error": "Invalid stock symbol",
  "detail": "Symbol 'INVALID' not found",
  "timestamp": "2024-12-20T10:30:00"
}
```

**500 Internal Server Error** - Analysis failed:
```json
{
  "error": "Internal server error",
  "detail": "Failed to analyze sentiment for AAPL: Connection timeout",
  "timestamp": "2024-12-20T10:30:00"
}
```

**503 Service Unavailable** - Sentiment analyzer unavailable:
```json
{
  "error": "Service unavailable",
  "detail": "Sentiment analyzer service unavailable. Please check configuration.",
  "timestamp": "2024-12-20T10:30:00"
}
```

## Examples

### Example 1: Basic Sentiment Analysis

```bash
curl http://localhost:8000/sentiment/AAPL
```

**Response:**
```json
{
  "symbol": "AAPL",
  "positive": 0.65,
  "negative": 0.20,
  "neutral": 0.15,
  "net_sentiment": 0.45,
  "dominant_sentiment": "positive",
  "timestamp": "2024-12-20T10:30:00",
  "sources_analyzed": 15
}
```

### Example 2: With Specific Data Sources

```bash
curl "http://localhost:8000/sentiment/MSFT?sources=yfinance,alpha_vantage"
```

This will only use yfinance and Alpha Vantage as data sources.

### Example 3: Force RAG Usage (Disable Cache)

```bash
curl "http://localhost:8000/sentiment/GOOGL?cache_enabled=false"
```

This will disable sentiment caching and force RAG usage for all analyses.

### Example 4: Health Check

```bash
curl http://localhost:8000/health
```

**Response:**
```json
{
  "status": "healthy",
  "services": {
    "redis": "available",
    "rag": "available",
    "azure_openai": "available",
    "sentiment_analyzer": "available"
  },
  "timestamp": "2024-12-20T10:30:00"
}
```

## Error Codes

| Status Code | Description |
|-------------|-------------|
| 200 | Success |
| 400 | Bad Request (invalid symbol, invalid parameters) |
| 422 | Validation Error (invalid request format) |
| 500 | Internal Server Error |
| 503 | Service Unavailable (sentiment analyzer not available) |

## Rate Limiting

Currently, the API does not implement rate limiting. For production deployments, consider adding rate limiting to prevent abuse.

## Data Sources

The API supports multiple data sources:

1. **yfinance** (Primary, always enabled)
   - Stock prices, company info
   - News headlines
   - No API key required

2. **Alpha Vantage** (Optional)
   - Company news
   - Free tier: 500 calls/day
   - Requires API key: `DATA_SOURCE_ALPHA_VANTAGE_API_KEY`

3. **Finnhub** (Optional)
   - Company news
   - Free tier: 60 calls/minute
   - Requires API key: `DATA_SOURCE_FINNHUB_API_KEY`

4. **Reddit** (Optional)
   - Social media sentiment
   - Requires Reddit app registration
   - Requires: `DATA_SOURCE_REDDIT_CLIENT_ID`, `DATA_SOURCE_REDDIT_CLIENT_SECRET`

## Integration Examples

### Python

```python
import requests

# Basic sentiment analysis
response = requests.get("http://localhost:8000/sentiment/AAPL")
data = response.json()

print(f"Symbol: {data['symbol']}")
print(f"Sentiment: {data['dominant_sentiment']}")
print(f"Net Sentiment: {data['net_sentiment']}")
print(f"Articles Analyzed: {data['sources_analyzed']}")
```

### JavaScript/Node.js

```javascript
const axios = require('axios');

async function getSentiment(symbol) {
  try {
    const response = await axios.get(`http://localhost:8000/sentiment/${symbol}`);
    console.log(`Symbol: ${response.data.symbol}`);
    console.log(`Sentiment: ${response.data.dominant_sentiment}`);
    console.log(`Net Sentiment: ${response.data.net_sentiment}`);
    console.log(`Articles Analyzed: ${response.data.sources_analyzed}`);
  } catch (error) {
    console.error('Error:', error.response.data);
  }
}

getSentiment('AAPL');
```

### Java (MicroProfile Rest Client)

```java
@RegisterRestClient
@Path("/")
public interface SentimentClient {
    @GET
    @Path("/sentiment/{symbol}")
    @Produces(MediaType.APPLICATION_JSON)
    SentimentResponse getSentiment(@PathParam("symbol") String symbol);
}
```

## Performance Considerations

- **Caching**: The API uses Redis caching to reduce API calls and improve response times
- **RAG**: Retrieval Augmented Generation provides context-aware analysis but may increase latency
- **Batch Processing**: Sentiment analysis is performed in parallel batches for efficiency
- **Response Time**: Typical response time is 5-15 seconds depending on:
  - Number of articles to analyze
  - Cache hit rate
  - RAG usage
  - Network latency to data sources

## Best Practices

1. **Use Caching**: Enable caching (`cache_enabled=true`) for better performance
2. **Monitor Health**: Regularly check `/health` endpoint to ensure service availability
3. **Handle Errors**: Implement proper error handling for 500 and 503 status codes
4. **Rate Limiting**: Implement client-side rate limiting to avoid overwhelming the service
5. **Data Sources**: Use specific data sources when possible to reduce processing time

## OpenAPI Documentation

Interactive API documentation is available at:
- Swagger UI: `http://localhost:8000/docs`
- ReDoc: `http://localhost:8000/redoc`
- OpenAPI JSON: `http://localhost:8000/openapi.json`

## Support

For issues, questions, or contributions, please open an issue on GitHub.

