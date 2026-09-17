"""
API tests for sentiment routes.
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import Mock, patch, MagicMock

from src.stock_sentiment.api.main import app


@pytest.fixture
def client():
    """Create test client."""
    return TestClient(app)


@pytest.mark.api
class TestSentimentRoutes:
    """Test suite for sentiment API routes."""
    
    def test_get_sentiment_success(self, client, sample_articles):
        """Test successful sentiment analysis."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            # Mock services
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            # Mock orchestrator
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.return_value = {
                    "symbol": symbol,
                    "positive": 0.7,
                    "negative": 0.2,
                    "neutral": 0.1,
                    "net_sentiment": 0.5,
                    "dominant_sentiment": "positive",
                    "sources_analyzed": 3,
                    "timestamp": "2024-01-01T00:00:00",
                    "operation_summary": {}
                }
                
                response = client.get(f"/sentiment/{symbol}")
                
                assert response.status_code == 200
                data = response.json()
                assert data["symbol"] == symbol
                assert "positive" in data
                assert "negative" in data
                assert "neutral" in data
    
    def test_get_sentiment_invalid_symbol(self, client):
        """Test sentiment analysis with invalid symbol."""
        symbol = "INVALID123"
        
        # API doesn't validate symbols upfront - it processes them and returns neutral sentiment
        # So we expect 200 with neutral sentiment when no data is found
        response = client.get(f"/sentiment/{symbol}")
        
        # API accepts invalid symbols and returns neutral sentiment (0 articles analyzed)
        assert response.status_code == 200
        data = response.json()
        assert data["sources_analyzed"] == 0
        assert data["dominant_sentiment"] == "neutral"
    
    def test_get_sentiment_with_source_filters(self, client):
        """Test sentiment analysis with source filters."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.return_value = {
                    "symbol": symbol,
                    "positive": 0.6,
                    "negative": 0.3,
                    "neutral": 0.1,
                    "net_sentiment": 0.3,
                    "dominant_sentiment": "positive",
                    "sources_analyzed": 2,
                    "timestamp": "2024-01-01T00:00:00",
                    "operation_summary": {}
                }
                
                response = client.get(f"/sentiment/{symbol}?sources=yfinance,alpha_vantage")
                
                assert response.status_code == 200
                # Verify filters were passed
                call_args = mock_orchestrator.call_args
                assert call_args[1]["data_source_filters"] is not None
    
    def test_get_sentiment_cache_enabled(self, client):
        """Test sentiment analysis with cache enabled."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_settings.app.cache_sentiment_enabled = True
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.return_value = {
                    "symbol": symbol,
                    "positive": 0.7,
                    "negative": 0.2,
                    "neutral": 0.1,
                    "net_sentiment": 0.5,
                    "dominant_sentiment": "positive",
                    "sources_analyzed": 3,
                    "timestamp": "2024-01-01T00:00:00",
                    "operation_summary": {}
                }
                
                response = client.get(f"/sentiment/{symbol}?cache_enabled=true")
                
                assert response.status_code == 200
    
    def test_get_sentiment_cache_disabled(self, client):
        """Test sentiment analysis with cache disabled."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            original_value = True
            mock_settings.app.cache_sentiment_enabled = original_value
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.return_value = {
                    "symbol": symbol,
                    "positive": 0.7,
                    "negative": 0.2,
                    "neutral": 0.1,
                    "net_sentiment": 0.5,
                    "dominant_sentiment": "positive",
                    "sources_analyzed": 3,
                    "timestamp": "2024-01-01T00:00:00",
                    "operation_summary": {}
                }
                
                response = client.get(f"/sentiment/{symbol}?cache_enabled=false")
                
                assert response.status_code == 200
                # Cache setting should be restored to original value after the request
                # (The API temporarily disables it during the request, then restores it in finally block)
                assert mock_settings.app.cache_sentiment_enabled == original_value
    
    def test_get_sentiment_detailed(self, client, sample_articles):
        """Test sentiment analysis with detailed response."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.return_value = {
                    "symbol": symbol,
                    "positive": 0.7,
                    "negative": 0.2,
                    "neutral": 0.1,
                    "net_sentiment": 0.5,
                    "dominant_sentiment": "positive",
                    "sources_analyzed": 3,
                    "timestamp": "2024-01-01T00:00:00",
                    "operation_summary": {},
                    "data": {
                        "price_data": {"symbol": symbol, "price": 175.50},
                        "news": sample_articles
                    },
                    "news_sentiments": [
                        {"positive": 0.7, "negative": 0.2, "neutral": 0.1}
                    ] * len(sample_articles)
                }
                
                response = client.get(f"/sentiment/{symbol}?detailed=true")
                
                assert response.status_code == 200
                data = response.json()
                assert "price_data" in data
                assert "news" in data
                assert "news_sentiments" in data
    
    def test_get_sentiment_batch(self, client):
        """Test batch sentiment analysis."""
        symbols = ["AAPL", "MSFT", "GOOGL"]
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                def mock_result(symbol, **kwargs):
                    return {
                        "symbol": symbol,
                        "positive": 0.7,
                        "negative": 0.2,
                        "neutral": 0.1,
                        "net_sentiment": 0.5,
                        "dominant_sentiment": "positive",
                        "sources_analyzed": 3,
                        "timestamp": "2024-01-01T00:00:00",
                        "operation_summary": {}
                    }
                
                mock_orchestrator.side_effect = [mock_result(s) for s in symbols]
                
                response = client.post(
                    "/sentiment/batch",
                    json={"symbols": symbols}
                )
                
                assert response.status_code == 200
                data = response.json()
                assert len(data) == len(symbols)
                assert all(item["symbol"] in symbols for item in data)
    
    def test_get_sentiment_batch_invalid_symbol(self, client):
        """Test batch sentiment analysis with invalid symbol."""
        symbols = ["AAPL", "INVALID123"]
        
        # API doesn't validate symbols upfront - it processes them
        # So we expect 200 with results for both (INVALID123 will have neutral sentiment)
        response = client.post(
            "/sentiment/batch",
            json={"symbols": symbols}
        )
        
        assert response.status_code == 200
        data = response.json()
        assert len(data) == len(symbols)
        # INVALID123 should have neutral sentiment (0 sources analyzed)
        invalid_result = next((r for r in data if r["symbol"] == "INVALID123"), None)
        assert invalid_result is not None
        assert invalid_result["sources_analyzed"] == 0
    
    def test_get_sentiment_error_handling(self, client):
        """Test error handling in sentiment analysis."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = None  # Analyzer unavailable
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            response = client.get(f"/sentiment/{symbol}")
            
            assert response.status_code == 503
            # Error handler wraps the detail, check the error field or detail
            error_data = response.json()
            assert "unavailable" in error_data.get("error", "").lower() or "unavailable" in error_data.get("detail", "").lower()
    
    def test_get_sentiment_500_error(self, client):
        """Test 500 error handling."""
        symbol = "AAPL"
        
        with patch('src.stock_sentiment.api.routes.sentiment.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch('src.stock_sentiment.api.routes.sentiment.get_aggregated_sentiment') as mock_orchestrator:
                mock_orchestrator.side_effect = Exception("Internal error")
                
                response = client.get(f"/sentiment/{symbol}")
                
                assert response.status_code == 500
                # Error handler wraps exceptions, check error or detail
                error_data = response.json()
                assert "Failed to analyze" in error_data.get("error", "") or "Failed to analyze" in error_data.get("detail", "") or "Internal error" in error_data.get("detail", "")

