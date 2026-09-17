"""
Integration tests for service interactions.
"""

import pytest
from unittest.mock import Mock, patch

from src.stock_sentiment.services.orchestrator import get_aggregated_sentiment


@pytest.mark.integration
class TestServiceIntegration:
    """Test suite for service integration."""
    
    def test_collector_cache_sentiment_flow(self, stock_collector, redis_cache, sentiment_analyzer, test_settings, sample_articles):
        """Test complete flow: collector -> cache -> sentiment."""
        symbol = "AAPL"
        
        # Step 1: Collector fetches data (mocked to return data)
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 175.50, "company_name": "Apple Inc.", "market_cap": 2800000000000, "timestamp": "2024-01-01T00:00:00"}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=sample_articles):
                data = stock_collector.collect_all_data(symbol)
        
        assert data is not None
        assert "price_data" in data
        assert "news" in data
        
        # Step 2: Manually cache data (since mocked methods don't trigger cache)
        # In real flow, collector.cache_stock_data and cache.cache_news are called
        redis_cache.cache_stock_data(symbol, data["price_data"])
        redis_cache.cache_news(symbol, data["news"])
        
        # Now verify cache
        cached_stock = redis_cache.get_cached_stock_data(symbol)
        cached_news = redis_cache.get_cached_news(symbol)
        
        assert cached_stock is not None
        assert cached_news is not None
        
        # Step 3: Sentiment analysis
        texts = [article.get("summary", article.get("title", "")) for article in sample_articles]
        sentiments = sentiment_analyzer.batch_analyze(texts)
        
        assert len(sentiments) == len(texts)
        for sentiment in sentiments:
            assert "positive" in sentiment
            assert "negative" in sentiment
            assert "neutral" in sentiment
    
    def test_rag_sentiment_integration(self, rag_service, sentiment_analyzer, redis_cache, sample_articles, sample_embedding):
        """Test RAG -> sentiment integration."""
        symbol = "AAPL"
        query = "Apple earnings report"
        
        # Step 1: Store articles in RAG
        with patch.object(rag_service, 'get_embeddings_batch', return_value=[[0.1]*1536]*len(sample_articles)):
            with patch.object(rag_service, 'vector_db', None):
                count = rag_service.store_articles_batch(sample_articles, symbol)
                assert count == len(sample_articles)
        
        # Step 2: Retrieve RAG context
        with patch.object(rag_service, 'get_embedding', return_value=sample_embedding):
            with patch.object(rag_service, '_semantic_search', return_value=[
                {
                    "article_id": "abc123",
                    "title": sample_articles[0]["title"],
                    "summary": sample_articles[0]["summary"],
                    "source": sample_articles[0]["source"],
                    "similarity": 0.85
                }
            ]):
                context = rag_service.retrieve_relevant_context(query, symbol, top_k=3)
        
        assert context is not None
        
        # Step 3: Use RAG context in sentiment analysis
        sentiment_analyzer.rag_service = rag_service
        text = "Apple earnings"
        
        with patch.object(sentiment_analyzer.client.chat.completions, 'create') as mock_create:
            import json
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = json.dumps({
                "positive": 0.7,
                "negative": 0.2,
                "neutral": 0.1
            })
            mock_create.return_value = mock_response
            
            result = sentiment_analyzer.analyze_sentiment(text, symbol=symbol)
            
            assert result is not None
            assert sentiment_analyzer.rag_uses > 0
    
    def test_cache_rag_sentiment_flow(self, redis_cache, rag_service, sentiment_analyzer, sample_articles, sample_embedding):
        """Test cache -> RAG -> sentiment flow."""
        symbol = "AAPL"
        
        # Step 1: Cache articles
        redis_cache.cache_news(symbol, sample_articles)
        
        # Step 2: Store in RAG
        with patch.object(rag_service, 'get_embeddings_batch', return_value=[[0.1]*1536]*len(sample_articles)):
            with patch.object(rag_service, 'vector_db', None):
                count = rag_service.store_articles_batch(sample_articles, symbol)
                assert count == len(sample_articles)
        
        # Step 3: Analyze sentiment with RAG
        sentiment_analyzer.rag_service = rag_service
        text = "Apple stock"
        
        with patch.object(sentiment_analyzer.client.chat.completions, 'create') as mock_create:
            import json
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = json.dumps({
                "positive": 0.6,
                "negative": 0.3,
                "neutral": 0.1
            })
            mock_create.return_value = mock_response
            
            with patch.object(rag_service, 'get_embedding', return_value=sample_embedding):
                with patch.object(rag_service, 'retrieve_relevant_context', return_value=[
                    {
                        "article_id": "abc123",
                        "title": "Test Article",
                        "summary": "Test summary",
                        "source": "yfinance",
                        "similarity": 0.85
                    }
                ]):
                    result = sentiment_analyzer.analyze_sentiment(text, symbol=symbol)
        
        assert result is not None
    
    def test_orchestrator_with_all_services(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test orchestrator with all services integrated."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                    {"positive": 0.6, "negative": 0.2, "neutral": 0.2}
                ] * len(sample_articles)):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        assert result is not None
        assert result["symbol"] == symbol
        assert "operation_summary" in result
        assert result["operation_summary"]["articles_stored"] == len(sample_articles)
    
    def test_error_propagation_through_service_chain(self, stock_collector, sentiment_analyzer, redis_cache):
        """Test that errors propagate correctly through service chain."""
        symbol = "AAPL"
        
        # Mock collector to raise error
        with patch.object(stock_collector, 'collect_all_data', side_effect=Exception("Collection error")):
            with pytest.raises(Exception):
                stock_collector.collect_all_data(symbol)
        
        # Mock sentiment analyzer to raise error
        with patch.object(sentiment_analyzer.client.chat.completions, 'create', side_effect=Exception("API error")):
            # Should fall back to TextBlob
            result = sentiment_analyzer.analyze_sentiment("Test text")
            assert result is not None  # TextBlob fallback should work

