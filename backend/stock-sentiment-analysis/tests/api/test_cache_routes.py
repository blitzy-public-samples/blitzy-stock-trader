"""
API tests for cache routes.
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
class TestCacheRoutes:
    """Test suite for cache API routes."""
    
    def test_get_cache_stats(self, client, redis_cache):
        """Test getting cache statistics."""
        with patch('src.stock_sentiment.api.routes.cache.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            # Mock cache stats
            with patch.object(redis_cache, 'get_cache_stats', return_value={
                "cache_hits": 100,
                "cache_misses": 50,
                "cache_sets": 75
            }):
                response = client.get("/cache/stats")
                
                assert response.status_code == 200
                data = response.json()
                assert "cache_hits" in data
                assert "cache_misses" in data
    
    def test_reset_cache_stats(self, client, redis_cache):
        """Test resetting cache statistics."""
        with patch('src.stock_sentiment.api.routes.cache.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch.object(redis_cache, 'reset_cache_stats'):
                response = client.post("/cache/stats/reset")
                
                assert response.status_code == 200
                data = response.json()
                assert data["success"] is True
    
    def test_clear_cache(self, client, redis_cache):
        """Test clearing cache."""
        with patch('src.stock_sentiment.api.routes.cache.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_rag_service = Mock()
            mock_collector = Mock()
            mock_analyzer = Mock()
            
            mock_services.return_value = (
                mock_settings,
                redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            with patch.object(redis_cache, 'clear_all_cache', return_value=True):
                with patch.object(redis_cache.client, 'info', return_value={'db0': {'keys': 10}}):
                    response = client.post("/cache/clear", json={"confirm": True})
                    
                    assert response.status_code == 200
                    data = response.json()
                    assert data["success"] is True
    
    def test_clear_cache_no_confirm(self, client):
        """Test clearing cache without confirmation."""
        response = client.post("/cache/clear", json={"confirm": False})
        
        assert response.status_code == 400

