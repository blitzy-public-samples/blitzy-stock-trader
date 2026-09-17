"""
Integration tests for cache interactions.
"""

import pytest
from unittest.mock import patch


@pytest.mark.integration
class TestCacheIntegration:
    """Test suite for cache integration."""
    
    def test_cache_hit_miss_scenarios(self, redis_cache, stock_collector, sentiment_analyzer):
        """Test cache hit/miss scenarios across services."""
        symbol = "AAPL"
        text = "Apple stock is rising!"
        
        # Initial miss - should cache
        sentiment = {"positive": 0.7, "negative": 0.2, "neutral": 0.1}
        redis_cache.cache_sentiment(text, sentiment)
        
        # Second call - should hit cache
        cached = redis_cache.get_cached_sentiment(text)
        assert cached == sentiment
        
        # Stock data cache
        stock_data = {"symbol": symbol, "price": 175.50}
        redis_cache.cache_stock_data(symbol, stock_data)
        
        cached_stock = redis_cache.get_cached_stock_data(symbol)
        assert cached_stock == stock_data
    
    def test_cache_invalidation(self, redis_cache):
        """Test cache invalidation."""
        key = "test_key"
        value = {"data": "value"}
        
        # Set value
        redis_cache.set(key, value, ttl=60)
        assert redis_cache.get(key) == value
        
        # Delete (invalidate)
        redis_cache.delete(key)
        assert redis_cache.get(key) is None
    
    def test_concurrent_cache_access(self, redis_cache):
        """Test concurrent cache access."""
        import threading
        
        results = []
        
        def cache_operation(i):
            key = f"concurrent_key_{i}"
            value = {"data": f"value_{i}"}
            redis_cache.set(key, value)
            retrieved = redis_cache.get(key)
            results.append((i, retrieved == value))
        
        # Create multiple threads
        threads = [threading.Thread(target=cache_operation, args=(i,)) for i in range(10)]
        
        # Start all threads
        for thread in threads:
            thread.start()
        
        # Wait for all threads
        for thread in threads:
            thread.join()
        
        # All operations should succeed
        assert len(results) == 10
        assert all(success for _, success in results)

