# Testing Guide

This guide explains how to run the test suite and configure it for real API testing.

## Running Tests

### Quick Start (Mocked Tests - Default)

Run all tests with mocks (fast, free, no API calls):

```bash
# Run all tests
pytest

# Run with coverage
pytest --cov=src/stock_sentiment --cov-report=html

# Run specific test categories
pytest -m unit              # Unit tests only
pytest -m integration       # Integration tests only
pytest -m api               # API tests only

# Run specific test file
pytest tests/unit/test_cache.py

# Run specific test
pytest tests/unit/test_cache.py::TestRedisCache::test_get_set_operations

# Verbose output
pytest -v

# Show print statements
pytest -s
```

### Running Tests with Real APIs

To test with real APIs (requires valid credentials):

```bash
# Enable all real APIs
USE_REAL_APIS=true pytest

# Enable specific services
USE_REAL_REDIS=true pytest tests/integration/test_cache_integration.py
USE_REAL_AZURE_OPENAI=true pytest tests/unit/test_sentiment.py
USE_REAL_AZURE_AI_SEARCH=true pytest tests/unit/test_vector_db.py

# Run only real API integration tests
USE_REAL_APIS=true pytest -m integration_real

# Run with timeout protection (recommended for real APIs)
pytest --timeout=300
```

### Test Markers

The test suite uses markers to categorize tests:

- `@pytest.mark.unit` - Unit tests (always use mocks)
- `@pytest.mark.integration` - Integration tests (mocks by default)
- `@pytest.mark.integration_real` - Integration tests with real APIs
- `@pytest.mark.api` - API endpoint tests
- `@pytest.mark.real_api` - Tests that require real APIs
- `@pytest.mark.slow` - Tests that take longer (real API tests)

Filter tests by marker:

```bash
pytest -m "not slow"           # Skip slow tests
pytest -m "unit or integration"  # Run unit and integration tests
```

## Environment Variables for Real API Testing

### Master Switch

Set `USE_REAL_APIS=true` to enable all real APIs, or use individual service flags:

```bash
# Enable all real APIs
export USE_REAL_APIS=true

# Or enable individual services
export USE_REAL_REDIS=true
export USE_REAL_AZURE_OPENAI=true
export USE_REAL_AZURE_AI_SEARCH=true
export USE_REAL_YFINANCE=true
export USE_REAL_ALPHA_VANTAGE=true
export USE_REAL_FINNHUB=true
export USE_REAL_REDDIT=true
```

### Required Environment Variables

For real API testing, you need to configure the following in your `.env` file:

#### Azure OpenAI (Required for sentiment analysis)

```env
AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/
AZURE_OPENAI_API_KEY=your-api-key-here
AZURE_OPENAI_DEPLOYMENT_NAME=gpt-4
AZURE_OPENAI_EMBEDDING_DEPLOYMENT=text-embedding-ada-002
AZURE_OPENAI_API_VERSION=2023-05-15
```

#### Redis (Optional but recommended)

```env
REDIS_HOST=your-redis-host.redis.cache.windows.net
REDIS_PORT=6380
REDIS_PASSWORD=your-redis-password
REDIS_SSL=true
```

#### Azure AI Search (Optional but recommended for RAG)

```env
AZURE_AI_SEARCH_ENDPOINT=https://your-search-service.search.windows.net
AZURE_AI_SEARCH_API_KEY=your-search-api-key
AZURE_AI_SEARCH_INDEX_NAME=stock-articles
```

#### Data Sources (Optional)

```env
# Alpha Vantage (Free tier: 500 calls/day)
DATA_SOURCE_ALPHA_VANTAGE_API_KEY=your-alpha-vantage-key
DATA_SOURCE_ALPHA_VANTAGE_ENABLED=true

# Finnhub (Free tier: 60 calls/minute)
DATA_SOURCE_FINNHUB_API_KEY=your-finnhub-key
DATA_SOURCE_FINNHUB_ENABLED=true

# Reddit (Free, requires app registration)
DATA_SOURCE_REDDIT_CLIENT_ID=your-reddit-client-id
DATA_SOURCE_REDDIT_CLIENT_SECRET=your-reddit-secret
DATA_SOURCE_REDDIT_USER_AGENT=stock-sentiment-analysis/1.0
DATA_SOURCE_REDDIT_ENABLED=true
```

## Test Configuration

### Creating `.env.test` (Optional)

You can create a separate `.env.test` file for test-specific configuration:

```bash
# Copy your .env file
cp .env .env.test

# Modify test-specific values if needed
# Tests will use .env by default, but you can override with environment variables
```

### pytest.ini Configuration

The test suite is configured in `pyproject.toml`:

- Test paths: `tests/`
- Markers: unit, integration, api, etc.
- Timeout: 300 seconds (5 minutes) for real API tests
- Async mode: auto

## Common Test Scenarios

### 1. Run All Tests (Fast - Mocked)

```bash
pytest
```

### 2. Run with Coverage Report

```bash
pytest --cov=src/stock_sentiment --cov-report=html --cov-report=term
# Open htmlcov/index.html in browser
```

### 3. Run Real API Integration Tests

```bash
# Make sure .env is configured with real credentials
USE_REAL_APIS=true pytest -m integration_real
```

### 4. Run Specific Service Tests

```bash
# Test Redis cache
pytest tests/unit/test_cache.py

# Test sentiment analyzer
pytest tests/unit/test_sentiment.py

# Test API endpoints
pytest tests/api/
```

### 5. Debug Failing Tests

```bash
# Run with verbose output and show print statements
pytest -v -s tests/unit/test_cache.py::TestRedisCache::test_get_set_operations

# Run with pdb debugger on failure
pytest --pdb tests/unit/test_cache.py
```

## Troubleshooting

### Tests Fail with "Service not available"

If tests fail with service unavailable errors:

1. **For mocked tests**: This shouldn't happen - check that mocks are properly configured
2. **For real API tests**: 
   - Verify `.env` file has correct credentials
   - Check that services are accessible
   - Ensure environment variables are set correctly

### Import Errors

If you see import errors:

```bash
# Install test dependencies
pip install -e ".[dev]"

# Or install manually
pip install pytest pytest-asyncio pytest-mock pytest-env pytest-timeout fakeredis responses
```

### Redis Connection Errors

For real Redis tests:

```bash
# Test Redis connection manually
python -c "import redis; r = redis.Redis(host='your-host', port=6380, password='your-password', ssl=True); r.ping()"
```

### Azure OpenAI Errors

For real Azure OpenAI tests:

```bash
# Verify credentials
python -c "from openai import AzureOpenAI; client = AzureOpenAI(azure_endpoint='your-endpoint', api_key='your-key', api_version='2023-05-15'); print('Connected')"
```

## Best Practices

1. **Default to Mocks**: Always run mocked tests first - they're faster and free
2. **Use Real APIs Sparingly**: Only use real APIs for integration tests or when debugging
3. **Set Timeouts**: Use `--timeout` flag for real API tests to prevent hanging
4. **Check Coverage**: Aim for >80% code coverage
5. **Run Before Committing**: Always run tests before committing code

## CI/CD Integration

For CI/CD pipelines, use mocked tests:

```yaml
# Example GitHub Actions
- name: Run tests
  run: |
    pip install -e ".[dev]"
    pytest --cov=src/stock_sentiment --cov-report=xml
```

For real API tests in CI, set environment secrets:

```yaml
- name: Run real API tests
  env:
    USE_REAL_APIS: true
    AZURE_OPENAI_ENDPOINT: ${{ secrets.AZURE_OPENAI_ENDPOINT }}
    AZURE_OPENAI_API_KEY: ${{ secrets.AZURE_OPENAI_API_KEY }}
    # ... other secrets
  run: pytest -m integration_real
```

