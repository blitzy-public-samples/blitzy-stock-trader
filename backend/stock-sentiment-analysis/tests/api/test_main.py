"""
API tests for main app.
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
class TestMainApp:
    """Test suite for main FastAPI app."""
    
    def test_root_endpoint(self, client):
        """Test root endpoint."""
        response = client.get("/")
        
        assert response.status_code == 200
        data = response.json()
        assert "description" in data or "name" in data
    
    def test_health_endpoint_healthy(self, client):
        """Test health endpoint when all services are healthy."""
        with patch('src.stock_sentiment.api.main.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = Mock()
            mock_redis_cache.client = Mock()
            mock_redis_cache.client.ping.return_value = True
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
            
            response = client.get("/health")
            
            assert response.status_code == 200
            data = response.json()
            assert data["status"] == "healthy"
    
    def test_health_endpoint_degraded(self, client):
        """Test health endpoint when some services are unavailable."""
        with patch('src.stock_sentiment.api.main.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = None  # Redis unavailable
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
            
            response = client.get("/health")
            
            assert response.status_code == 200
            data = response.json()
            assert data["status"] in ["healthy", "degraded"]
    
    def test_health_endpoint_unhealthy(self, client):
        """Test health endpoint when critical services are unavailable."""
        with patch('src.stock_sentiment.api.main.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_redis_cache = None
            mock_rag_service = None
            mock_collector = None
            mock_analyzer = None  # Critical service unavailable
            
            mock_services.return_value = (
                mock_settings,
                mock_redis_cache,
                mock_rag_service,
                mock_collector,
                mock_analyzer
            )
            
            response = client.get("/health")
            
            assert response.status_code == 200
            data = response.json()
            assert data["status"] in ["degraded", "unhealthy"]
    
    def test_error_handling_middleware(self, client):
        """Test error handling middleware."""
        # Test with invalid endpoint
        response = client.get("/nonexistent")
        
        assert response.status_code == 404
    
    def test_cors_middleware(self, client):
        """Test CORS middleware."""
        response = client.options(
            "/health",
            headers={
                "Origin": "http://localhost:3000",
                "Access-Control-Request-Method": "GET"
            }
        )
        
        # CORS should be configured
        assert response.status_code in [200, 204]
    
    def test_request_logging_middleware(self, client):
        """Test request logging middleware."""
        response = client.get("/health")
        
        # Should log request (we can't easily test logging, but should not error)
        assert response.status_code == 200

