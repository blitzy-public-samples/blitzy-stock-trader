"""
Unit tests for orchestrator service.
"""

import pytest
from unittest.mock import Mock, MagicMock, patch
from datetime import datetime

from src.stock_sentiment.services.orchestrator import get_aggregated_sentiment


@pytest.mark.unit
class TestOrchestrator:
    """Test suite for orchestrator functions."""
    
    def test_get_aggregated_sentiment_full_flow(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test complete sentiment analysis flow."""
        symbol = "AAPL"
        
        # Mock collector to return data
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            # Mock RAG storage
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                # Mock sentiment analysis
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                    {"positive": 0.7, "negative": 0.2, "neutral": 0.1},
                    {"positive": 0.6, "negative": 0.3, "neutral": 0.1},
                    {"positive": 0.8, "negative": 0.1, "neutral": 0.1}
                ]):
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
        assert "positive" in result
        assert "negative" in result
        assert "neutral" in result
        assert "net_sentiment" in result
        assert "dominant_sentiment" in result
        assert "sources_analyzed" in result
        assert result["sources_analyzed"] == len(sample_articles)
    
    def test_get_aggregated_sentiment_data_collection_step(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings):
        """Test data collection step."""
        symbol = "AAPL"
        
        mock_data = {
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": [
                {"title": "Article 1", "summary": "Summary 1", "source": "yfinance"},
                {"title": "Article 2", "summary": "Summary 2", "source": "alpha_vantage"}
            ],
            "social_media": []
        }
        
        with patch.object(stock_collector, 'collect_all_data', return_value=mock_data) as mock_collect:
            with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                {"positive": 0.5, "negative": 0.3, "neutral": 0.2},
                {"positive": 0.6, "negative": 0.2, "neutral": 0.2}
            ]):
                with patch.object(rag_service, 'store_articles_batch', return_value=2):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        mock_collect.assert_called_once()
        assert result["sources_analyzed"] == 2
    
    def test_get_aggregated_sentiment_rag_storage_step(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test RAG storage step."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
            ] * len(sample_articles)):
                with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)) as mock_store:
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        mock_store.assert_called_once()
        assert result["operation_summary"]["articles_stored"] == len(sample_articles)
    
    def test_get_aggregated_sentiment_sentiment_analysis_step(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test sentiment analysis step."""
        symbol = "AAPL"
        
        sentiments = [
            {"positive": 0.7, "negative": 0.2, "neutral": 0.1},
            {"positive": 0.6, "negative": 0.3, "neutral": 0.1},
            {"positive": 0.8, "negative": 0.1, "neutral": 0.1}
        ]
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=sentiments) as mock_analyze:
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        mock_analyze.assert_called_once()
        # Aggregated sentiment should be average
        assert result["positive"] > 0.6
        assert result["negative"] < 0.3
    
    def test_get_aggregated_sentiment_aggregation_logic(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings):
        """Test sentiment aggregation logic."""
        symbol = "AAPL"
        
        # Create articles with known sentiments
        articles = [
            {"title": "Positive Article", "summary": "Great news", "source": "yfinance"},
            {"title": "Negative Article", "summary": "Bad news", "source": "alpha_vantage"},
            {"title": "Neutral Article", "summary": "Neutral news", "source": "finnhub"}
        ]
        
        sentiments = [
            {"positive": 0.9, "negative": 0.05, "neutral": 0.05},  # Very positive
            {"positive": 0.1, "negative": 0.8, "neutral": 0.1},   # Very negative
            {"positive": 0.3, "negative": 0.3, "neutral": 0.4}    # Neutral
        ]
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=sentiments):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        # Aggregated should be average
        assert result["positive"] > 0.3  # Average of 0.9, 0.1, 0.3
        assert result["negative"] > 0.3   # Average of 0.05, 0.8, 0.3
        assert result["neutral"] > 0.1    # Average of 0.05, 0.1, 0.4
        
        # Net sentiment should be calculated
        assert "net_sentiment" in result
        assert -1.0 <= result["net_sentiment"] <= 1.0
    
    def test_get_aggregated_sentiment_operation_summary_tracking(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test operation summary tracking."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                    {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
                ] * len(sample_articles)):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        assert "operation_summary" in result
        summary = result["operation_summary"]
        assert "articles_stored" in summary
        assert summary["articles_stored"] == len(sample_articles)
        assert "sources_analyzed" in result
        assert result["sources_analyzed"] == len(sample_articles)
    
    def test_get_aggregated_sentiment_no_news(self, stock_collector, sentiment_analyzer, redis_cache, test_settings):
        """Test handling when no news is found."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": [],
            "social_media": []
        }):
            result = get_aggregated_sentiment(
                symbol=symbol,
                collector=stock_collector,
                analyzer=sentiment_analyzer,
                rag_service=None,
                redis_cache=redis_cache,
                settings=test_settings
            )
        
        assert result is not None
        assert result["sources_analyzed"] == 0
        # Should return neutral sentiment when no news
        assert result["neutral"] == 1.0
        assert result["positive"] == 0.0
        assert result["negative"] == 0.0
    
    def test_get_aggregated_sentiment_all_cached(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test when all data is cached."""
        symbol = "AAPL"
        
        # Cache stock data and news
        redis_cache.cache_stock_data(symbol, {"symbol": symbol, "price": 175.50})
        redis_cache.cache_news(symbol, sample_articles)
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
            ] * len(sample_articles)):
                with patch.object(rag_service, 'store_articles_batch', return_value=0):  # All already stored
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        assert result is not None
        assert result["operation_summary"]["stock_cached"] is True
        assert result["operation_summary"]["news_cached"] is True
    
    def test_get_aggregated_sentiment_partial_failures(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test handling of partial failures."""
        symbol = "AAPL"
        
        # Mock partial failure in sentiment analysis
        sentiments = [
            {"positive": 0.5, "negative": 0.3, "neutral": 0.2},
            None,  # Failed analysis
            {"positive": 0.6, "negative": 0.2, "neutral": 0.2}
        ]
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=sentiments):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings
                    )
        
        # Should handle partial failures gracefully
        assert result is not None
        assert result["sources_analyzed"] == 2  # Only successful analyses
    
    def test_get_aggregated_sentiment_with_detailed_return(self, stock_collector, sentiment_analyzer, rag_service, redis_cache, test_settings, sample_articles):
        """Test return_detailed parameter."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": sample_articles,
            "social_media": []
        }):
            with patch.object(rag_service, 'store_articles_batch', return_value=len(sample_articles)):
                with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                    {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
                ] * len(sample_articles)):
                    result = get_aggregated_sentiment(
                        symbol=symbol,
                        collector=stock_collector,
                        analyzer=sentiment_analyzer,
                        rag_service=rag_service,
                        redis_cache=redis_cache,
                        settings=test_settings,
                        return_detailed=True
                    )
        
        assert "data" in result
        assert "news_sentiments" in result
        assert len(result["news_sentiments"]) == len(sample_articles)
    
    def test_get_aggregated_sentiment_with_source_filters(self, stock_collector, sentiment_analyzer, redis_cache, test_settings):
        """Test data source filtering."""
        symbol = "AAPL"
        filters = {
            "yfinance": True,
            "alpha_vantage": False,
            "finnhub": False,
            "reddit": False
        }
        
        filtered_articles = [
            {"title": "YFinance Article", "summary": "Summary", "source": "yfinance"}
        ]
        
        with patch.object(stock_collector, 'collect_all_data', return_value={
            "price_data": {"symbol": symbol, "price": 175.50},
            "news": filtered_articles,
            "social_media": []
        }) as mock_collect:
            with patch.object(sentiment_analyzer, 'batch_analyze', return_value=[
                {"positive": 0.5, "negative": 0.3, "neutral": 0.2}
            ]):
                result = get_aggregated_sentiment(
                    symbol=symbol,
                    collector=stock_collector,
                    analyzer=sentiment_analyzer,
                    rag_service=None,
                    redis_cache=redis_cache,
                    settings=test_settings,
                    data_source_filters=filters
                )
        
        # Should have called with filters
        mock_collect.assert_called_once_with(symbol, data_source_filters=filters)
        assert result["sources_analyzed"] == 1

