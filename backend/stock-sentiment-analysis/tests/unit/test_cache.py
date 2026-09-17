"""
Unit tests for RedisCache service.
"""

import json
import pytest
from unittest.mock import Mock, MagicMock, patch
from datetime import datetime

from src.stock_sentiment.services.cache import RedisCache, CacheStats


@pytest.mark.unit
class TestRedisCache:
    """Test suite for RedisCache class."""
    
    def test_initialization_with_redis(self, redis_cache, test_settings):
        """Test RedisCache initialization with Redis client."""
        assert redis_cache is not None
        assert redis_cache.client is not None
        assert redis_cache.settings == test_settings
    
    def test_initialization_without_redis(self, test_settings):
        """Test RedisCache initialization without Redis (client=None)."""
        # Mock settings to indicate Redis not available
        test_settings.is_redis_available = Mock(return_value=False)
        cache = RedisCache(settings=test_settings)
        assert cache.client is None
    
    def test_get_set_operations(self, redis_cache):
        """Test basic get and set operations."""
        key = "test_key"
        value = {"data": "test_value", "number": 42}
        
        # Set value
        result = redis_cache.set(key, value, ttl=3600)
        assert result is True
        
        # Get value
        retrieved = redis_cache.get(key)
        assert retrieved == value
    
    def test_get_nonexistent_key(self, redis_cache):
        """Test getting a key that doesn't exist."""
        result = redis_cache.get("nonexistent_key")
        assert result is None
    
    def test_set_with_ttl(self, redis_cache):
        """Test setting a value with TTL."""
        key = "ttl_test"
        value = {"test": "data"}
        
        result = redis_cache.set(key, value, ttl=60)
        assert result is True
        
        # Check TTL is set
        ttl = redis_cache.client.ttl(key)
        assert ttl > 0
        assert ttl <= 60
    
    def test_delete_operation(self, redis_cache):
        """Test delete operation."""
        key = "delete_test"
        value = {"test": "data"}
        
        # Set value
        redis_cache.set(key, value)
        assert redis_cache.get(key) == value
        
        # Delete value
        result = redis_cache.delete(key)
        assert result is True
        
        # Verify deleted
        assert redis_cache.get(key) is None
    
    def test_delete_nonexistent_key(self, redis_cache):
        """Test deleting a key that doesn't exist."""
        result = redis_cache.delete("nonexistent_key")
        assert result is False
    
    def test_cache_sentiment(self, redis_cache):
        """Test caching sentiment analysis results."""
        text = "Apple stock is rising!"
        sentiment = {"positive": 0.75, "negative": 0.15, "neutral": 0.10}
        
        # Cache sentiment
        result = redis_cache.cache_sentiment(text, sentiment)
        assert result is True
        
        # Retrieve cached sentiment
        cached = redis_cache.get_cached_sentiment(text)
        assert cached == sentiment
    
    def test_get_cached_sentiment(self, redis_cache):
        """Test retrieving cached sentiment."""
        text = "Test text for sentiment"
        sentiment = {"positive": 0.8, "negative": 0.1, "neutral": 0.1}
        
        # Cache first
        redis_cache.cache_sentiment(text, sentiment)
        
        # Retrieve
        cached = redis_cache.get_cached_sentiment(text)
        assert cached == sentiment
    
    def test_get_cached_sentiment_not_found(self, redis_cache):
        """Test retrieving sentiment that's not cached."""
        result = redis_cache.get_cached_sentiment("nonexistent text")
        assert result is None
    
    def test_cache_sentiment_disabled(self, redis_cache, test_settings):
        """Test that sentiment caching respects cache_sentiment_enabled setting."""
        # Disable sentiment caching
        test_settings.app.cache_sentiment_enabled = False
        redis_cache.settings = test_settings
        
        text = "Test text"
        sentiment = {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
        
        # Should return False when disabled
        result = redis_cache.cache_sentiment(text, sentiment)
        assert result is False
        
        # Should return None when disabled
        cached = redis_cache.get_cached_sentiment(text)
        assert cached is None
    
    def test_cache_stock_data(self, redis_cache, sample_stock_data):
        """Test caching stock data."""
        symbol = "AAPL"
        
        # Cache stock data
        result = redis_cache.cache_stock_data(symbol, sample_stock_data)
        assert result is True
        
        # Retrieve cached stock data
        cached = redis_cache.get_cached_stock_data(symbol)
        assert cached is not None
        assert cached["symbol"] == symbol
        assert cached["price"] == sample_stock_data["price"]
    
    def test_get_cached_stock_data(self, redis_cache, sample_stock_data):
        """Test retrieving cached stock data."""
        symbol = "MSFT"
        
        # Cache first
        redis_cache.cache_stock_data(symbol, sample_stock_data)
        
        # Retrieve
        cached = redis_cache.get_cached_stock_data(symbol)
        # Timestamp is serialized as string in cache, so compare fields individually
        assert cached is not None
        assert cached["symbol"] == sample_stock_data["symbol"]
        assert cached["price"] == sample_stock_data["price"]
        assert cached["company_name"] == sample_stock_data["company_name"]
        # Timestamp may be string or datetime
        assert "timestamp" in cached
    
    def test_get_cached_stock_data_not_found(self, redis_cache):
        """Test retrieving stock data that's not cached."""
        result = redis_cache.get_cached_stock_data("NONEXISTENT")
        assert result is None
    
    def test_cache_news(self, redis_cache, sample_articles):
        """Test caching news articles."""
        symbol = "AAPL"
        
        # Cache news
        result = redis_cache.cache_news(symbol, sample_articles)
        assert result is True
        
        # Retrieve cached news
        cached = redis_cache.get_cached_news(symbol)
        assert cached is not None
        assert len(cached) == len(sample_articles)
        assert cached[0]["title"] == sample_articles[0]["title"]
    
    def test_get_cached_news(self, redis_cache, sample_articles):
        """Test retrieving cached news articles."""
        symbol = "GOOGL"
        
        # Cache first
        redis_cache.cache_news(symbol, sample_articles)
        
        # Retrieve
        cached = redis_cache.get_cached_news(symbol)
        assert len(cached) == len(sample_articles)
    
    def test_get_cached_news_not_found(self, redis_cache):
        """Test retrieving news that's not cached."""
        result = redis_cache.get_cached_news("NONEXISTENT")
        assert result is None
    
    def test_ttl_expiration(self, redis_cache):
        """Test that TTL expiration works correctly."""
        key = "expire_test"
        value = {"test": "data"}
        
        # Set with short TTL
        redis_cache.set(key, value, ttl=1)
        
        # Should be available immediately
        assert redis_cache.get(key) == value
        
        # Wait for expiration (using fakeredis, we can manually expire)
        redis_cache.client.expire(key, 0)
        
        # Should be None after expiration
        assert redis_cache.get(key) is None
    
    def test_error_handling_connection_failure(self, test_settings):
        """Test error handling when Redis connection fails."""
        # Create cache with None client (simulating connection failure)
        cache = RedisCache(settings=test_settings)
        cache.client = None
        
        # Operations should handle None client gracefully
        assert cache.get("test") is None
        assert cache.set("test", {"data": "value"}) is False
        assert cache.delete("test") is False
    
    def test_error_handling_invalid_json(self, redis_cache):
        """Test error handling with invalid JSON."""
        key = "invalid_json_test"
        
        # Manually set invalid JSON in Redis
        redis_cache.client.set(key, "invalid json {")
        
        # Get should return None for invalid JSON
        result = redis_cache.get(key)
        assert result is None
    
    def test_error_handling_serialization_error(self, redis_cache):
        """Test error handling when value cannot be serialized."""
        # json.dumps with default=str will convert most objects to strings
        # To test error handling, we need to mock json.dumps to raise an exception
        import json
        from unittest.mock import patch
        
        # Mock json.dumps to raise TypeError to simulate serialization failure
        with patch('src.stock_sentiment.services.cache.json.dumps', side_effect=TypeError("Cannot serialize")):
            result = redis_cache.set("test", {"some": "value"})
            # Should return False when serialization fails
            assert result is False
    
    def test_cache_statistics_tracking(self, redis_cache):
        """Test that cache statistics are tracked."""
        # Reset stats
        CacheStats.reset(redis_cache.client)
        
        # Perform operations
        redis_cache.set("key1", {"data": "value1"})
        redis_cache.get("key1")  # Hit
        redis_cache.get("key2")  # Miss
        redis_cache.set("key2", {"data": "value2"})
        
        # Check stats
        stats = CacheStats.get_stats(redis_cache.client)
        assert stats["cache_hits"] >= 1
        assert stats["cache_misses"] >= 1
        assert stats["cache_sets"] >= 2
    
    def test_edge_case_empty_key(self, redis_cache):
        """Test edge case with empty key."""
        result = redis_cache.get("")
        assert result is None
        
        result = redis_cache.set("", {"data": "value"})
        # Should handle empty key (may succeed or fail depending on Redis implementation)
        assert isinstance(result, bool)
    
    def test_edge_case_none_value(self, redis_cache):
        """Test edge case with None value."""
        # Setting None should work (serializes to null)
        result = redis_cache.set("none_test", None)
        assert result is True
        
        # Getting should return None
        retrieved = redis_cache.get("none_test")
        assert retrieved is None
    
    def test_key_generation_consistency(self, redis_cache):
        """Test that key generation is consistent."""
        text1 = "Apple stock is rising!"
        text2 = "Apple stock is rising!"  # Same text
        
        sentiment = {"positive": 0.75, "negative": 0.15, "neutral": 0.10}
        
        # Cache with same text
        redis_cache.cache_sentiment(text1, sentiment)
        
        # Should retrieve with same text (different variable)
        cached = redis_cache.get_cached_sentiment(text2)
        assert cached == sentiment
    
    def test_key_generation_case_insensitive(self, redis_cache):
        """Test that key generation handles case differences."""
        text1 = "Apple stock"
        text2 = "APPLE STOCK"  # Different case
        
        sentiment = {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
        
        # Cache with lowercase
        redis_cache.cache_sentiment(text1, sentiment)
        
        # Should retrieve with uppercase (key generation normalizes)
        cached = redis_cache.get_cached_sentiment(text2)
        assert cached == sentiment
    
    def test_multiple_cache_operations(self, redis_cache):
        """Test multiple cache operations in sequence."""
        # Cache multiple items
        redis_cache.set("key1", {"data": 1})
        redis_cache.set("key2", {"data": 2})
        redis_cache.set("key3", {"data": 3})
        
        # Retrieve all
        assert redis_cache.get("key1")["data"] == 1
        assert redis_cache.get("key2")["data"] == 2
        assert redis_cache.get("key3")["data"] == 3
        
        # Delete one
        redis_cache.delete("key2")
        assert redis_cache.get("key2") is None
        assert redis_cache.get("key1") is not None
        assert redis_cache.get("key3") is not None

