"""
API tests for price routes.
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
class TestPriceRoutes:
    """Test suite for price API routes."""
    
    @patch('src.stock_sentiment.api.routes.price.yf')
    def test_get_price_history_success(self, mock_yf, client):
        """Test successful price history retrieval."""
        symbol = "AAPL"
        
        # Mock yfinance Ticker with proper DataFrame
        import pandas as pd
        from datetime import datetime, timedelta
        
        mock_ticker = Mock()
        # Create a proper DataFrame with required columns
        dates = pd.date_range(end=datetime.now(), periods=5, freq='D')
        mock_hist = pd.DataFrame({
            'Open': [175.0, 176.0, 177.0, 178.0, 179.0],
            'High': [176.0, 177.0, 178.0, 179.0, 180.0],
            'Low': [174.0, 175.0, 176.0, 177.0, 178.0],
            'Close': [175.5, 176.5, 177.5, 178.5, 179.5],
            'Volume': [1000000, 1100000, 1200000, 1300000, 1400000]
        }, index=dates)
        mock_ticker.history.return_value = mock_hist
        mock_yf.Ticker.return_value = mock_ticker
        
        response = client.get(f"/price/{symbol}/history?period=1y")
        
        assert response.status_code == 200
        data = response.json()
        assert data["symbol"] == symbol
        assert len(data["data"]) > 0
    
    def test_get_price_history_invalid_symbol(self, client):
        """Test price history with invalid symbol."""
        symbol = "INVALID123"
        
        # yfinance returns 404 for invalid symbols, not 400
        # The API returns 404 when hist.empty is True
        response = client.get(f"/price/{symbol}/history")
        
        # API validates symbol format first, but yfinance may return empty data
        # So we expect either 400 (validation) or 404 (no data)
        assert response.status_code in [400, 404]
        error_data = response.json()
        assert "Invalid" in error_data.get("error", "") or "No price data" in error_data.get("error", "") or "Invalid" in error_data.get("detail", "") or "No price data" in error_data.get("detail", "")
    
    def test_get_price_history_invalid_period(self, client):
        """Test price history with invalid period."""
        symbol = "AAPL"
        
        response = client.get(f"/price/{symbol}/history?period=invalid")
        
        assert response.status_code == 400
        error_data = response.json()
        assert "Invalid period" in error_data.get("error", "") or "Invalid period" in error_data.get("detail", "")
    
    @patch('src.stock_sentiment.api.routes.price.yf')
    def test_get_price_history_error_handling(self, mock_yf, client):
        """Test error handling in price history."""
        symbol = "AAPL"
        
        # Mock yfinance to raise exception
        mock_yf.Ticker.side_effect = Exception("API Error")
        
        response = client.get(f"/price/{symbol}/history")
        
        assert response.status_code == 500

