"""
API tests for system routes.
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
class TestSystemRoutes:
    """Test suite for system API routes."""
    
    def test_get_system_status(self, client, redis_cache, rag_service, sentiment_analyzer):
        """Test getting system status."""
        with patch('src.stock_sentiment.api.routes.system.get_all_services') as mock_services:
            mock_settings = Mock()
            mock_settings.redis.host = "localhost"
            mock_settings.redis.port = 6379
            mock_collector = Mock()
            
            mock_services.return_value = (
                mock_settings,
                redis_cache,
                rag_service,
                mock_collector,
                sentiment_analyzer
            )
            
            response = client.get("/system/status")
            
            assert response.status_code == 200
            data = response.json()
            assert "redis" in data
            assert "rag" in data
            assert "azure_openai" in data
    
    def test_get_system_status_without_services(self, client):
        """Test system status when services are unavailable."""
        with patch('src.stock_sentiment.api.routes.system.get_all_services') as mock_services:
            mock_settings = Mock()
            
            mock_services.return_value = (
                mock_settings,
                None,  # No Redis
                None,  # No RAG
                Mock(),
                None   # No analyzer
            )
            
            response = client.get("/system/status")
            
            assert response.status_code == 200
            data = response.json()
            assert data["redis"]["available"] is False
            assert data["rag"]["available"] is False
            assert data["azure_openai"]["available"] is False

