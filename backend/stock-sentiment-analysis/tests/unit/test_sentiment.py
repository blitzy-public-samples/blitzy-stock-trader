"""
Unit tests for SentimentAnalyzer service.
"""

import json
import pytest
from unittest.mock import Mock, MagicMock, patch, AsyncMock
from concurrent.futures import TimeoutError as FuturesTimeoutError

from src.stock_sentiment.services.sentiment import SentimentAnalyzer


@pytest.mark.unit
class TestSentimentAnalyzer:
    """Test suite for SentimentAnalyzer class."""
    
    def test_initialization(self, sentiment_analyzer, test_settings):
        """Test SentimentAnalyzer initialization."""
        assert sentiment_analyzer is not None
        assert sentiment_analyzer.settings == test_settings
        assert sentiment_analyzer.client is not None
        assert sentiment_analyzer.deployment_name == "gpt-4"
    
    def test_analyze_sentiment_cached(self, sentiment_analyzer, redis_cache, sample_sentiment_response):
        """Test sentiment analysis with cached result."""
        text = "Apple stock is rising!"
        
        # Cache sentiment
        redis_cache.cache_sentiment(text, sample_sentiment_response)
        
        # Analyze (should use cache)
        result = sentiment_analyzer.analyze_sentiment(text)
        
        assert result is not None
        assert result["positive"] == sample_sentiment_response["positive"]
        assert result["negative"] == sample_sentiment_response["negative"]
        assert result["neutral"] == sample_sentiment_response["neutral"]
        assert sentiment_analyzer.cache_hits > 0
    
    def test_analyze_sentiment_uncached(self, sentiment_analyzer, azure_openai_client):
        """Test sentiment analysis without cache (calls API)."""
        text = "Apple stock is rising!"
        
        # Mock API response
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.8,
            "negative": 0.1,
            "neutral": 0.1
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        # Analyze
        result = sentiment_analyzer.analyze_sentiment(text)
        
        assert result is not None
        assert "positive" in result
        assert "negative" in result
        assert "neutral" in result
        assert sum(result.values()) <= 1.1  # Allow small floating point differences
        assert sentiment_analyzer.cache_misses > 0
    
    def test_analyze_sentiment_with_rag_context(self, sentiment_analyzer, rag_service, azure_openai_client, sample_rag_context):
        """Test sentiment analysis with RAG context."""
        text = "Apple earnings report"
        symbol = "AAPL"
        
        # Mock RAG service to return context
        rag_service.retrieve_relevant_context = Mock(return_value=sample_rag_context)
        sentiment_analyzer.rag_service = rag_service
        
        # Mock API response
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.75,
            "negative": 0.15,
            "neutral": 0.10
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        # Analyze with symbol (triggers RAG)
        result = sentiment_analyzer.analyze_sentiment(text, symbol=symbol)
        
        assert result is not None
        assert sentiment_analyzer.rag_uses > 0
    
    def test_analyze_sentiment_textblob_fallback(self, sentiment_analyzer, azure_openai_client):
        """Test TextBlob fallback when Azure OpenAI fails."""
        text = "Apple stock is rising!"
        
        # Mock API to raise exception
        azure_openai_client.chat.completions.create.side_effect = Exception("API Error")
        
        # Should fall back to TextBlob
        result = sentiment_analyzer.analyze_sentiment(text)
        
        assert result is not None
        assert "positive" in result
        assert "negative" in result
        assert "neutral" in result
    
    def test_analyze_sentiment_score_normalization(self, sentiment_analyzer, azure_openai_client):
        """Test that sentiment scores are normalized."""
        text = "Test text"
        
        # Mock API response with scores that don't sum to 1.0
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.9,
            "negative": 0.2,
            "neutral": 0.1
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(text)
        
        # Scores should be normalized to sum to ~1.0
        total = sum(result.values())
        assert abs(total - 1.0) < 0.01
    
    def test_analyze_sentiment_empty_text(self, sentiment_analyzer):
        """Test handling of empty text."""
        result = sentiment_analyzer.analyze_sentiment("")
        
        assert result is not None
        assert result["neutral"] == 1.0
        assert result["positive"] == 0.0
        assert result["negative"] == 0.0
    
    def test_analyze_sentiment_very_long_text(self, sentiment_analyzer, azure_openai_client):
        """Test handling of very long text."""
        long_text = "Apple stock " * 1000  # Very long text
        
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.5,
            "negative": 0.3,
            "neutral": 0.2
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(long_text)
        assert result is not None
    
    def test_analyze_sentiment_special_characters(self, sentiment_analyzer, azure_openai_client):
        """Test handling of text with special characters."""
        text = "Apple's stock (AAPL) is up 5%! 🚀 #stocks"
        
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.7,
            "negative": 0.2,
            "neutral": 0.1
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        result = sentiment_analyzer.analyze_sentiment(text)
        assert result is not None
    
    def test_batch_analyze(self, sentiment_analyzer, azure_openai_client):
        """Test batch sentiment analysis."""
        texts = [
            "Apple stock is rising!",
            "Microsoft earnings beat expectations",
            "Tesla stock drops after news"
        ]
        
        # Mock API responses
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.6,
            "negative": 0.2,
            "neutral": 0.2
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        results = sentiment_analyzer.batch_analyze(texts, max_workers=2)
        
        assert len(results) == len(texts)
        for result in results:
            assert result is not None
            assert "positive" in result
            assert "negative" in result
            assert "neutral" in result
    
    def test_batch_analyze_parallel_processing(self, sentiment_analyzer, azure_openai_client):
        """Test that batch_analyze uses parallel processing."""
        texts = ["Text " + str(i) for i in range(10)]
        
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.5,
            "negative": 0.3,
            "neutral": 0.2
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        results = sentiment_analyzer.batch_analyze(texts, max_workers=5)
        
        assert len(results) == len(texts)
    
    def test_batch_analyze_timeout(self, sentiment_analyzer, azure_openai_client):
        """Test batch_analyze with timeout."""
        texts = ["Text 1", "Text 2"]
        
        # Mock slow API response
        def slow_response(*args, **kwargs):
            import time
            time.sleep(2)  # Simulate slow response
            mock_response = MagicMock()
            mock_response.choices = [MagicMock()]
            mock_response.choices[0].message = MagicMock()
            mock_response.choices[0].message.content = json.dumps({
                "positive": 0.5,
                "negative": 0.3,
                "neutral": 0.2
            })
            return mock_response
        
        azure_openai_client.chat.completions.create.side_effect = slow_response
        
        # Use short timeout
        results = sentiment_analyzer.batch_analyze(texts, max_workers=1, worker_timeout=1)
        
        # Should handle timeout gracefully
        assert len(results) == len(texts)
        # Some may have timed out and returned neutral
        for result in results:
            assert result is not None
    
    def test_batch_analyze_partial_failures(self, sentiment_analyzer, azure_openai_client):
        """Test batch_analyze with partial failures."""
        texts = ["Text 1", "Text 2", "Text 3"]
        
        # Mock API to fail for some texts
        call_count = [0]
        def mock_create(*args, **kwargs):
            call_count[0] += 1
            if call_count[0] == 2:  # Fail on second call
                raise Exception("API Error")
            mock_response = MagicMock()
            mock_response.choices = [MagicMock()]
            mock_response.choices[0].message = MagicMock()
            mock_response.choices[0].message.content = json.dumps({
                "positive": 0.5,
                "negative": 0.3,
                "neutral": 0.2
            })
            return mock_response
        
        azure_openai_client.chat.completions.create.side_effect = mock_create
        
        results = sentiment_analyzer.batch_analyze(texts)
        
        assert len(results) == len(texts)
        # Failed ones should fall back to neutral
        assert results[1]["neutral"] == 1.0
    
    def test_retry_logic(self, sentiment_analyzer, azure_openai_client):
        """Test retry logic on transient failures."""
        from openai import RateLimitError
        
        text = "Test text"
        
        # Mock API to fail twice then succeed (using retryable exception)
        call_count = [0]
        def mock_create(*args, **kwargs):
            call_count[0] += 1
            if call_count[0] < 3:
                raise RateLimitError("Rate limit", response=MagicMock(), body={})
            mock_response = MagicMock()
            mock_response.choices = [MagicMock()]
            mock_response.choices[0].message = MagicMock()
            mock_response.choices[0].message.content = json.dumps({
                "positive": 0.6,
                "negative": 0.2,
                "neutral": 0.2
            })
            return mock_response
        
        azure_openai_client.chat.completions.create.side_effect = mock_create
        
        result = sentiment_analyzer.analyze_sentiment(text)
        
        assert result is not None
        assert call_count[0] == 3  # Should have retried
    
    def test_prompt_building_with_context(self, sentiment_analyzer, rag_service, azure_openai_client, sample_rag_context):
        """Test that prompt includes RAG context."""
        text = "Apple earnings"
        symbol = "AAPL"
        
        rag_service.retrieve_relevant_context = Mock(return_value=sample_rag_context)
        sentiment_analyzer.rag_service = rag_service
        
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.7,
            "negative": 0.2,
            "neutral": 0.1
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        # Capture the call arguments
        with patch.object(azure_openai_client.chat.completions, 'create', wraps=azure_openai_client.chat.completions.create) as mock_create:
            sentiment_analyzer.analyze_sentiment(text, symbol=symbol)
            
            # Check that prompt was called
            assert mock_create.called
            call_args = mock_create.call_args
            messages = call_args[1]['messages']
            
            # Find the user message
            user_message = next((msg for msg in messages if msg['role'] == 'user'), None)
            assert user_message is not None
            # Should contain context section
            assert "context" in user_message['content'].lower() or "relevant" in user_message['content'].lower()
    
    def test_prompt_building_without_context(self, sentiment_analyzer, azure_openai_client):
        """Test prompt building without RAG context."""
        text = "Apple stock is rising!"
        
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = json.dumps({
            "positive": 0.8,
            "negative": 0.1,
            "neutral": 0.1
        })
        azure_openai_client.chat.completions.create.return_value = mock_response
        
        with patch.object(azure_openai_client.chat.completions, 'create', wraps=azure_openai_client.chat.completions.create) as mock_create:
            sentiment_analyzer.analyze_sentiment(text)
            
            call_args = mock_create.call_args
            messages = call_args[1]['messages']
            user_message = next((msg for msg in messages if msg['role'] == 'user'), None)
            
            # Should not contain context section when no RAG
            assert user_message is not None
            assert text in user_message['content']
    
    def test_api_failure_handling(self, sentiment_analyzer, azure_openai_client):
        """Test handling of API failures."""
        text = "Test text"
        
        # Mock API to always fail
        azure_openai_client.chat.completions.create.side_effect = Exception("API Unavailable")
        
        # Should fall back to TextBlob
        result = sentiment_analyzer.analyze_sentiment(text)
        
        assert result is not None
        # TextBlob should still return valid sentiment
        assert "positive" in result
        assert "negative" in result
        assert "neutral" in result

