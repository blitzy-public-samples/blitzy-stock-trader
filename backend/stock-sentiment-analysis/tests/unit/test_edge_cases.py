"""
Edge case and error scenario tests.
"""

import pytest
from unittest.mock import Mock, patch, MagicMock
from concurrent.futures import TimeoutError as FuturesTimeoutError
import time

from src.stock_sentiment.services.cache import RedisCache
from src.stock_sentiment.services.collector import StockDataCollector
from src.stock_sentiment.services.sentiment import SentimentAnalyzer
from src.stock_sentiment.services.rag import RAGService


@pytest.mark.unit
class TestEdgeCases:
    """Test suite for edge cases and error scenarios."""
    
    def test_network_timeout(self, sentiment_analyzer, azure_openai_client):
        """Test handling of network timeouts."""
        text = "Test text"
        
        # Mock timeout
        azure_openai_client.chat.completions.create.side_effect = FuturesTimeoutError()
        
        # Should fall back to TextBlob
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
        assert "positive" in result
    
    def test_rate_limiting(self, stock_collector):
        """Test handling of rate limiting."""
        symbol = "AAPL"
        
        # Mock rate limit response
        with patch('requests.get') as mock_get:
            mock_response = Mock()
            mock_response.status_code = 429
            mock_response.json.return_value = {"error": "Rate limit exceeded"}
            mock_get.return_value = mock_response
            
            stock_collector.settings.data_sources.alpha_vantage_api_key = "test-key"
            result = stock_collector.get_alpha_vantage_news(symbol)
            
            # Should handle gracefully
            assert result == []
    
    def test_invalid_api_responses(self, sentiment_analyzer, azure_openai_client):
        """Test handling of invalid API responses."""
        text = "Test text"
        
        # Mock invalid response
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = "Invalid JSON response"
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        # Should fall back to TextBlob
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
    
    def test_malformed_data(self, redis_cache):
        """Test handling of malformed data."""
        key = "test_key"
        
        # Set invalid JSON manually
        redis_cache.client.set(key, "invalid json {")
        
        # Should return None gracefully
        result = redis_cache.get(key)
        assert result is None
    
    def test_service_unavailability(self, test_settings):
        """Test handling when services are unavailable."""
        # Create cache without Redis
        test_settings.is_redis_available = Mock(return_value=False)
        cache = RedisCache(settings=test_settings)
        
        # Operations should handle None client gracefully
        assert cache.get("test") is None
        assert cache.set("test", {"data": "value"}) is False
    
    def test_concurrent_requests(self, redis_cache):
        """Test handling of concurrent requests."""
        import threading
        
        results = []
        
        def cache_operation(i):
            key = f"concurrent_{i}"
            value = {"data": f"value_{i}"}
            redis_cache.set(key, value)
            retrieved = redis_cache.get(key)
            results.append(retrieved == value)
        
        threads = [threading.Thread(target=cache_operation, args=(i,)) for i in range(10)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        
        assert len(results) == 10
        assert all(results)
    
    def test_large_payloads(self, sentiment_analyzer, azure_openai_client):
        """Test handling of large payloads."""
        # Create very long text
        long_text = "Apple stock " * 10000
        
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = '{"positive": 0.5, "negative": 0.3, "neutral": 0.2}'
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(long_text)
        assert result is not None
    
    def test_empty_responses(self, stock_collector):
        """Test handling of empty API responses."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 0.0}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=[]):
                data = stock_collector.collect_all_data(symbol)
                
                assert data is not None
                assert data["news"] == []
    
    def test_invalid_configurations(self, test_settings):
        """Test handling of invalid configurations."""
        # Test with missing settings
        test_settings.azure_openai.endpoint = ""
        
        # Should handle gracefully
        try:
            from src.stock_sentiment.services.sentiment import SentimentAnalyzer
            analyzer = SentimentAnalyzer(settings=test_settings)
            # May raise ValueError or handle gracefully
        except (ValueError, Exception):
            pass  # Expected behavior
    
    def test_circuit_breaker_open(self, sentiment_analyzer, azure_openai_client):
        """Test circuit breaker when open."""
        text = "Test text"
        
        # Force circuit breaker open by failing multiple times
        azure_openai_client.chat.completions.create.side_effect = Exception("API Error")
        
        # Multiple failures should open circuit breaker
        for _ in range(6):  # More than failure threshold
            try:
                sentiment_analyzer.analyze_sentiment(text)
            except:
                pass
        
        # Should fall back to TextBlob when circuit is open
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
    
    def test_partial_batch_failures(self, sentiment_analyzer, azure_openai_client):
        """Test handling of partial batch failures."""
        texts = ["Text 1", "Text 2", "Text 3"]
        
        # Mock partial failures
        call_count = [0]
        def mock_create(*args, **kwargs):
            call_count[0] += 1
            if call_count[0] == 2:
                raise Exception("API Error")
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = '{"positive": 0.5, "negative": 0.3, "neutral": 0.2}'
            return mock_response
        
        azure_openai_client.chat.completions.create.side_effect = mock_create
        
        results = sentiment_analyzer.batch_analyze(texts)
        
        assert len(results) == len(texts)
        # Failed ones should fall back to TextBlob (not necessarily neutral=1.0)
        assert results[1] is not None
        assert "positive" in results[1]
        assert "negative" in results[1]
        assert "neutral" in results[1]
    
    def test_very_long_text(self, sentiment_analyzer, azure_openai_client):
        """Test handling of very long text."""
        # Create text that might exceed token limits
        very_long_text = "Apple stock is performing well. " * 1000
        
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = '{"positive": 0.6, "negative": 0.2, "neutral": 0.2}'
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(very_long_text)
        assert result is not None
    
    def test_special_characters_in_text(self, sentiment_analyzer, azure_openai_client):
        """Test handling of special characters."""
        text = "Apple's stock (AAPL) is up 5%! 🚀 #stocks @market"
        
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = '{"positive": 0.7, "negative": 0.1, "neutral": 0.2}'
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
    
    def test_unicode_characters(self, sentiment_analyzer, azure_openai_client):
        """Test handling of unicode characters."""
        text = "Café stock 日本語 €100"
        
        mock_response = Mock()
        mock_response.choices = [Mock()]
        mock_response.choices[0].message = Mock()
        mock_response.choices[0].message.content = '{"positive": 0.5, "negative": 0.3, "neutral": 0.2}'
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
    
    def test_none_values(self, redis_cache):
        """Test handling of None values."""
        # Setting None should work
        result = redis_cache.set("none_test", None)
        assert result is True
        
        # Getting None should return None
        retrieved = redis_cache.get("none_test")
        assert retrieved is None
    
    def test_empty_strings(self, redis_cache):
        """Test handling of empty strings."""
        # Empty string key
        result = redis_cache.get("")
        assert result is None
        
        # Empty string value
        result = redis_cache.set("empty_value", "")
        assert isinstance(result, bool)
    
    def test_connection_retry(self, sentiment_analyzer, azure_openai_client):
        """Test retry logic on connection failures."""
        from openai import APIConnectionError
        
        text = "Test text"
        
        # Mock transient failures then success (using retryable exception)
        call_count = [0]
        def mock_create(*args, **kwargs):
            call_count[0] += 1
            if call_count[0] < 3:
                # APIConnectionError constructor requires request parameter
                mock_request = MagicMock()
                error = APIConnectionError(message="Connection error", request=mock_request)
                raise error
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = '{"positive": 0.6, "negative": 0.2, "neutral": 0.2}'
            return mock_response
        
        azure_openai_client.chat.completions.create.side_effect = mock_create
        
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
        assert call_count[0] == 3  # Should have retried

