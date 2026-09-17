"""
API tests for comparison routes.
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, Mock

from src.stock_sentiment.api.main import app


@pytest.fixture
def client():
    """Create test client."""
    return TestClient(app)


@pytest.mark.api
class TestComparisonRoutes:
    """Test suite for comparison API routes."""
    
    def test_post_comparison_compare_success(self, client):
        """Test successful comparison."""
        symbols = ["AAPL", "MSFT"]
        
        with patch('src.stock_sentiment.api.routes.comparison.get_all_services') as mock_services:
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
            
            # Mock Azure OpenAI client for insights generation
            mock_analyzer_client = Mock()
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = "Generated insights text"
            mock_analyzer_client.chat.completions.create.return_value = mock_response
            mock_analyzer.client = mock_analyzer_client
            mock_analyzer.deployment_name = "gpt-4"
            
            # Build comparison data and sentiments for /comparison/insights endpoint
            comparison_data = {s: {"price_data": {"price": 100.0}} for s in symbols}
            comparison_sentiments = {
                s: {"positive": 0.7, "negative": 0.2, "neutral": 0.1}
                for s in symbols
            }
            
            response = client.post(
                "/comparison/insights",
                json={
                    "comparison_data": comparison_data,
                    "comparison_sentiments": comparison_sentiments
                }
            )
            
            assert response.status_code == 200
            data = response.json()
            # API returns {"insights": "..."} not a list
            assert "insights" in data
            assert isinstance(data["insights"], str)
            assert len(data["insights"]) > 0
    
    def test_post_comparison_compare_invalid_symbol(self, client):
        """Test comparison with invalid symbol."""
        # /comparison/insights doesn't validate symbols, it just processes them
        # So we test with invalid comparison data structure instead
        response = client.post(
            "/comparison/insights",
            json={
                "comparison_data": {},
                "comparison_sentiments": {"AAPL": {"positive": 0.7, "negative": 0.2, "neutral": 0.1}}
            }
        )
        
        assert response.status_code == 400
        error_data = response.json()
        assert "At least 2 symbols" in error_data.get("error", "") or "At least 2 symbols" in error_data.get("detail", "")
    
    def test_post_comparison_compare_minimum_symbols(self, client):
        """Test comparison with less than minimum symbols."""
        # Only one symbol in comparison
        response = client.post(
            "/comparison/insights",
            json={
                "comparison_data": {"AAPL": {"price_data": {"price": 100.0}}},
                "comparison_sentiments": {"AAPL": {"positive": 0.7, "negative": 0.2, "neutral": 0.1}}
            }
        )
        
        assert response.status_code == 400
        error_data = response.json()
        assert "at least 2" in error_data.get("error", "").lower() or "at least 2" in error_data.get("detail", "").lower()
    
    def test_post_comparison_compare_batch(self, client):
        """Test batch comparison."""
        symbols = ["AAPL", "MSFT", "GOOGL"]
        
        with patch('src.stock_sentiment.api.routes.comparison.get_all_services') as mock_services:
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
            
            # Mock Azure OpenAI client for insights generation
            mock_analyzer_client = Mock()
            mock_response = Mock()
            mock_response.choices = [Mock()]
            mock_response.choices[0].message = Mock()
            mock_response.choices[0].message.content = "Generated insights text"
            mock_analyzer_client.chat.completions.create.return_value = mock_response
            mock_analyzer.client = mock_analyzer_client
            mock_analyzer.deployment_name = "gpt-4"
            
            # Build comparison data and sentiments for /comparison/insights endpoint
            comparison_data = {s: {"price_data": {"price": 100.0}} for s in symbols}
            comparison_sentiments = {
                s: {"positive": 0.7, "negative": 0.2, "neutral": 0.1}
                for s in symbols
            }
            
            response = client.post(
                "/comparison/insights",
                json={
                    "comparison_data": comparison_data,
                    "comparison_sentiments": comparison_sentiments
                }
            )
            
            assert response.status_code == 200
            data = response.json()
            # API returns {"insights": "..."} not a list
            assert "insights" in data
            assert isinstance(data["insights"], str)
            assert len(data["insights"]) > 0

