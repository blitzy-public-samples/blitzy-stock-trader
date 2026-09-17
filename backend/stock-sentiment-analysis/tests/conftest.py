"""
Pytest configuration and shared fixtures for test suite.

This module provides fixtures for testing with hybrid approach:
- Default: All tests use mocks (fast, reliable, free)
- Optional: Can switch to real APIs via environment variables
"""

import os
import json
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any
from unittest.mock import Mock, MagicMock, patch, AsyncMock
import pytest

try:
    from fakeredis import FakeRedis
except ImportError:
    FakeRedis = None  # Will be handled in fixture

import redis

# Import test configuration
from tests.test_config import (
    use_real_redis,
    use_real_azure_openai,
    use_real_azure_ai_search,
    use_real_yfinance,
    use_real_alpha_vantage,
    use_real_finnhub,
    use_real_reddit,
    should_skip_real_api_test,
)

# Import application modules
from src.stock_sentiment.config.settings import Settings, get_settings
from src.stock_sentiment.services.cache import RedisCache
from src.stock_sentiment.services.collector import StockDataCollector
from src.stock_sentiment.services.sentiment import SentimentAnalyzer
from src.stock_sentiment.services.rag import RAGService
from src.stock_sentiment.services.vector_db import AzureAISearchVectorDB


# ============================================================================
# Test Data Fixtures
# ============================================================================

@pytest.fixture
def sample_stock_data() -> Dict[str, Any]:
    """Sample stock data for testing."""
    return {
        "symbol": "AAPL",
        "price": 175.50,
        "company_name": "Apple Inc.",
        "market_cap": 2800000000000,
        "timestamp": datetime.now()
    }


@pytest.fixture
def sample_articles() -> List[Dict[str, Any]]:
    """Sample news articles for testing."""
    return [
        {
            "title": "Apple Reports Strong Q4 Earnings",
            "summary": "Apple Inc. reported better-than-expected earnings for Q4, with revenue reaching $89.5 billion. The company's iPhone sales exceeded analyst expectations.",
            "source": "yfinance",
            "url": "https://example.com/apple-earnings",
            "timestamp": datetime.now() - timedelta(hours=2)
        },
        {
            "title": "Tech Stocks Rally on Apple News",
            "summary": "Technology stocks saw a significant rally following Apple's earnings announcement. Analysts are optimistic about the company's future prospects.",
            "source": "alpha_vantage",
            "url": "https://example.com/tech-rally",
            "timestamp": datetime.now() - timedelta(hours=1)
        },
        {
            "title": "Apple Stock Price Surges",
            "summary": "Apple's stock price surged 5% in after-hours trading following the earnings report.",
            "source": "finnhub",
            "url": "https://example.com/stock-surge",
            "timestamp": datetime.now() - timedelta(minutes=30)
        }
    ]


@pytest.fixture
def sample_sentiment_response() -> Dict[str, float]:
    """Sample sentiment analysis response."""
    return {
        "positive": 0.75,
        "negative": 0.15,
        "neutral": 0.10
    }


@pytest.fixture
def sample_embedding() -> List[float]:
    """Sample embedding vector (1536 dimensions)."""
    # Return a simple mock embedding (not real 1536 dims for speed)
    return [0.1] * 1536


@pytest.fixture
def sample_rag_context() -> List[Dict[str, Any]]:
    """Sample RAG context articles."""
    return [
        {
            "article_id": "abc123",
            "title": "Apple Earnings Report",
            "summary": "Apple reported strong earnings...",
            "source": "yfinance",
            "similarity": 0.85,
            "rrf_score": 0.12
        },
        {
            "article_id": "def456",
            "title": "Tech Stock Analysis",
            "summary": "Technology stocks are performing well...",
            "source": "alpha_vantage",
            "similarity": 0.78,
            "rrf_score": 0.10
        }
    ]


# ============================================================================
# Mock Fixtures
# ============================================================================

@pytest.fixture
def mock_redis_client():
    """Create a mock Redis client using fakeredis."""
    if FakeRedis is None:
        # Fallback to mock if fakeredis not available
        mock_client = MagicMock()
        mock_client.get = Mock(return_value=None)
        mock_client.set = Mock(return_value=True)
        mock_client.setex = Mock(return_value=True)
        mock_client.delete = Mock(return_value=1)
        mock_client.exists = Mock(return_value=False)
        mock_client.ttl = Mock(return_value=-1)
        mock_client.ping = Mock(return_value=True)
        mock_client.expire = Mock(return_value=True)
        return mock_client
    return FakeRedis(decode_responses=True)


@pytest.fixture
def mock_azure_openai_client():
    """Create a mock Azure OpenAI client."""
    mock_client = MagicMock()
    
    # Mock chat completion response
    mock_response = MagicMock()
    mock_response.choices = [MagicMock()]
    mock_response.choices[0].message = MagicMock()
    mock_response.choices[0].message.content = json.dumps({
        "positive": 0.75,
        "negative": 0.15,
        "neutral": 0.10
    })
    
    mock_client.chat.completions.create = MagicMock(return_value=mock_response)
    
    # Mock embeddings response
    mock_embedding_response = MagicMock()
    mock_embedding_response.data = [MagicMock()]
    mock_embedding_response.data[0].embedding = [0.1] * 1536
    
    mock_client.embeddings.create = MagicMock(return_value=mock_embedding_response)
    
    return mock_client


@pytest.fixture
def mock_yfinance_ticker():
    """Create a mock yfinance Ticker object."""
    mock_ticker = MagicMock()
    
    # Mock stock info
    mock_ticker.info = {
        "currentPrice": 175.50,
        "longName": "Apple Inc.",
        "marketCap": 2800000000000
    }
    
    # Mock news
    mock_ticker.news = [
        {
            "title": "Apple Reports Strong Q4 Earnings",
            "provider": {"displayName": "Reuters"},
            "content": {
                "title": "Apple Reports Strong Q4 Earnings",
                "summary": "Apple Inc. reported better-than-expected earnings...",
                "canonicalUrl": {"url": "https://example.com/apple-earnings"}
            },
            "pubDate": int((datetime.now() - timedelta(hours=2)).timestamp())
        }
    ]
    
    return mock_ticker


# ============================================================================
# Hybrid Fixtures (Mock/Real Switching)
# ============================================================================

@pytest.fixture
def redis_client(mock_redis_client):
    """
    Redis client fixture with hybrid support.
    
    Uses fakeredis by default, real Redis if USE_REAL_REDIS=true.
    """
    if use_real_redis():
        try:
            from src.stock_sentiment.config.settings import get_settings
            settings = get_settings()
            if settings.is_redis_available():
                real_client = redis.Redis(
                    host=settings.redis.host,
                    port=settings.redis.port,
                    password=settings.redis.password,
                    ssl=settings.redis.ssl,
                    decode_responses=True
                )
                # Test connection
                real_client.ping()
                return real_client
        except Exception as e:
            pytest.skip(f"Real Redis not available: {e}")
    
    # Default to fakeredis
    return mock_redis_client


@pytest.fixture
def test_settings():
    """Create test settings with minimal configuration."""
    # Create a minimal settings object for testing
    # This avoids requiring full .env file for tests
    settings = MagicMock(spec=Settings)
    
    # Mock Azure OpenAI settings
    settings.azure_openai = MagicMock()
    settings.azure_openai.endpoint = "https://test.openai.azure.com"
    settings.azure_openai.api_key = "test-key"
    settings.azure_openai.deployment_name = "gpt-4"
    settings.azure_openai.api_version = "2023-05-15"
    settings.azure_openai.embedding_deployment = "text-embedding-ada-002"
    
    # Mock Redis settings
    settings.redis = MagicMock()
    settings.redis.host = "localhost"
    settings.redis.port = 6379
    settings.redis.password = "test-password"
    settings.redis.ssl = False
    
    # Mock app settings
    settings.app = MagicMock()
    settings.app.cache_ttl_sentiment = 86400
    settings.app.cache_ttl_stock = 3600
    settings.app.cache_ttl_news = 7200
    settings.app.cache_ttl_rag_articles = 604800
    settings.app.cache_sentiment_enabled = True
    settings.app.rag_top_k = 3
    settings.app.rag_batch_size = 100
    settings.app.rag_similarity_threshold = 0.01
    settings.app.analysis_parallel_workers = 5
    settings.app.analysis_worker_timeout = 180
    settings.app.news_limit_default = 10
    settings.app.retry_max_attempts = 3
    settings.app.retry_initial_delay = 1
    settings.app.retry_max_delay = 10
    settings.app.retry_exponential_base = 2
    settings.app.circuit_breaker_failure_threshold = 5
    settings.app.circuit_breaker_timeout = 60
    settings.app.log_level = "INFO"
    
    # Mock data source settings
    settings.data_sources = MagicMock()
    settings.data_sources.alpha_vantage_enabled = True
    settings.data_sources.finnhub_enabled = True
    settings.data_sources.reddit_enabled = True
    
    # Mock Azure AI Search settings
    settings.azure_ai_search = MagicMock()
    settings.azure_ai_search.endpoint = "https://test.search.windows.net"
    settings.azure_ai_search.api_key = "test-key"
    settings.azure_ai_search.index_name = "test-index"
    
    # Mock methods
    settings.is_redis_available = MagicMock(return_value=True)
    settings.is_rag_available = MagicMock(return_value=True)
    settings.is_azure_ai_search_available = MagicMock(return_value=True)
    
    return settings


@pytest.fixture
def redis_cache(redis_client, test_settings):
    """
    RedisCache fixture with hybrid support.
    
    Uses fakeredis by default, real Redis if USE_REAL_REDIS=true.
    """
    cache = RedisCache(settings=test_settings)
    cache.client = redis_client
    return cache


@pytest.fixture
def azure_openai_client(mock_azure_openai_client):
    """
    Azure OpenAI client fixture with hybrid support.
    
    Uses mock by default, real client if USE_REAL_AZURE_OPENAI=true.
    """
    if use_real_azure_openai():
        if should_skip_real_api_test(
            "Azure OpenAI",
            ["AZURE_OPENAI_ENDPOINT", "AZURE_OPENAI_API_KEY"]
        ):
            pytest.skip("Real Azure OpenAI not configured")
        
        try:
            from openai import AzureOpenAI
            from src.stock_sentiment.config.settings import get_settings
            settings = get_settings()
            return AzureOpenAI(
                azure_endpoint=settings.azure_openai.endpoint,
                api_key=settings.azure_openai.api_key,
                api_version=settings.azure_openai.api_version
            )
        except Exception as e:
            pytest.skip(f"Real Azure OpenAI not available: {e}")
    
    return mock_azure_openai_client


@pytest.fixture
def stock_collector(redis_cache, test_settings):
    """StockDataCollector fixture."""
    return StockDataCollector(settings=test_settings, redis_cache=redis_cache)


@pytest.fixture
def sentiment_analyzer(redis_cache, test_settings, azure_openai_client):
    """SentimentAnalyzer fixture with mocked Azure OpenAI client."""
    # Patch the AzureOpenAI initialization
    with patch('src.stock_sentiment.services.sentiment.AzureOpenAI', return_value=azure_openai_client):
        analyzer = SentimentAnalyzer(
            settings=test_settings,
            redis_cache=redis_cache,
            rag_service=None  # Can be added separately if needed
        )
        analyzer.client = azure_openai_client
        return analyzer


@pytest.fixture
def rag_service(redis_cache, test_settings, azure_openai_client):
    """RAGService fixture with mocked Azure OpenAI client."""
    # Patch the AzureOpenAI initialization
    with patch('src.stock_sentiment.services.rag.AzureOpenAI', return_value=azure_openai_client):
        rag = RAGService(
            settings=test_settings,
            redis_cache=redis_cache,
            vector_db=None  # Can be added separately if needed
        )
        rag.client = azure_openai_client
        rag.embeddings_enabled = True
        rag.embedding_deployment = "text-embedding-ada-002"
        return rag


@pytest.fixture
def vector_db(redis_cache, test_settings):
    """AzureAISearchVectorDB fixture."""
    # For tests, we'll mock the vector DB
    # Real implementation can be added if USE_REAL_AZURE_AI_SEARCH=true
    if use_real_azure_ai_search():
        if should_skip_real_api_test(
            "Azure AI Search",
            ["AZURE_AI_SEARCH_ENDPOINT", "AZURE_AI_SEARCH_API_KEY"]
        ):
            pytest.skip("Real Azure AI Search not configured")
        
        try:
            return AzureAISearchVectorDB(
                settings=test_settings,
                redis_cache=redis_cache
            )
        except Exception as e:
            pytest.skip(f"Real Azure AI Search not available: {e}")
    
    # Return None for mocked tests (services should handle None gracefully)
    return None


# ============================================================================
# HTTP Mocking Fixtures
# ============================================================================

@pytest.fixture
def mock_http_responses():
    """Fixture for mocking HTTP responses using responses library."""
    try:
        import responses
        with responses.RequestsMock() as rsps:
            yield rsps
    except ImportError:
        # Fallback if responses not installed
        yield Mock()


@pytest.fixture
def mock_alpha_vantage_response(mock_http_responses):
    """Mock Alpha Vantage API responses."""
    try:
        import responses
        mock_http_responses.add(
            responses.GET,
            "https://www.alphavantage.co/query",
            json={
                "feed": [
                    {
                        "title": "Test Article",
                        "summary": "Test summary",
                        "source": "Test Source",
                        "url": "https://example.com/article",
                        "time_published": "20240101T120000"
                    }
                ]
            },
            status=200
        )
    except (ImportError, AttributeError):
        pass  # Skip if responses not available
    return mock_http_responses


@pytest.fixture
def mock_finnhub_response(mock_http_responses):
    """Mock Finnhub API responses."""
    try:
        import responses
        mock_http_responses.add(
            responses.GET,
            "https://finnhub.io/api/v1/company-news",
            json=[
                {
                    "headline": "Test Article",
                    "summary": "Test summary",
                    "source": "Test Source",
                    "url": "https://example.com/article",
                    "datetime": 1704110400
                }
            ],
            status=200
        )
    except (ImportError, AttributeError):
        pass  # Skip if responses not available
    return mock_http_responses


# ============================================================================
# Pytest Configuration
# ============================================================================

def pytest_configure(config):
    """Configure pytest with custom markers."""
    config.addinivalue_line(
        "markers", "unit: Unit tests (always use mocks)"
    )
    config.addinivalue_line(
        "markers", "integration: Integration tests (mocks by default)"
    )
    config.addinivalue_line(
        "markers", "integration_real: Integration tests with real APIs"
    )
    config.addinivalue_line(
        "markers", "api: API endpoint tests"
    )
    config.addinivalue_line(
        "markers", "real_api: Tests that require real APIs"
    )
    config.addinivalue_line(
        "markers", "slow: Tests that take longer (real API tests)"
    )


def pytest_collection_modifyitems(config, items):
    """Automatically mark tests based on their location."""
    for item in items:
        # Mark tests in unit/ directory
        if "unit" in str(item.fspath):
            item.add_marker(pytest.mark.unit)
        # Mark tests in integration/ directory
        elif "integration" in str(item.fspath):
            item.add_marker(pytest.mark.integration)
        # Mark tests in api/ directory
        elif "api" in str(item.fspath) and "test_" in str(item.fspath):
            item.add_marker(pytest.mark.api)

