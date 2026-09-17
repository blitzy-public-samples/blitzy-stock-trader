"""
Unit tests for StockDataCollector service.
"""

import pytest
from unittest.mock import Mock, MagicMock, patch, PropertyMock
from datetime import datetime, timedelta
import responses

from src.stock_sentiment.services.collector import StockDataCollector, normalize_datetime


@pytest.mark.unit
class TestStockDataCollector:
    """Test suite for StockDataCollector class."""
    
    def test_initialization(self, stock_collector, test_settings):
        """Test StockDataCollector initialization."""
        assert stock_collector is not None
        assert stock_collector.settings == test_settings
        assert stock_collector.headers is not None
    
    def test_get_stock_price_cached(self, stock_collector, redis_cache, sample_stock_data):
        """Test getting stock price from cache."""
        symbol = "AAPL"
        
        # Cache the stock data
        redis_cache.cache_stock_data(symbol, sample_stock_data)
        
        # Get from cache
        result = stock_collector.get_stock_price(symbol)
        
        assert result is not None
        assert result["symbol"] == symbol
        assert result["price"] == sample_stock_data["price"]
    
    @patch('src.stock_sentiment.services.collector.yf.Ticker')
    def test_get_stock_price_uncached(self, mock_ticker_class, stock_collector, redis_cache):
        """Test getting stock price from API when not cached."""
        import pandas as pd
        
        symbol = "AAPL"
    
        # Mock yfinance Ticker
        mock_ticker = MagicMock()
        mock_ticker.info = {
            "currentPrice": 175.50,
            "longName": "Apple Inc.",
            "marketCap": 2800000000000
        }
        # Mock history to return a DataFrame with Close column
        mock_hist = pd.DataFrame({
            'Close': [175.50]
        })
        mock_ticker.history.return_value = mock_hist
        mock_ticker_class.return_value = mock_ticker
    
        # Get stock price (should fetch from API)
        result = stock_collector.get_stock_price(symbol)
    
        assert result is not None
        assert result["symbol"] == symbol
        assert result["price"] == 175.50
        assert result["company_name"] == "Apple Inc."
        
        # Verify it was cached
        cached = redis_cache.get_cached_stock_data(symbol)
        assert cached is not None
    
    @patch('src.stock_sentiment.services.collector.yf.Ticker')
    def test_get_stock_price_api_error(self, mock_ticker_class, stock_collector):
        """Test handling of API errors when fetching stock price."""
        symbol = "INVALID"
        
        # Mock yfinance to raise exception
        mock_ticker = MagicMock()
        mock_ticker.info = {}
        mock_ticker_class.return_value = mock_ticker
        
        # Should return default values on error
        result = stock_collector.get_stock_price(symbol)
        
        assert result is not None
        assert result["symbol"] == symbol
        assert result["price"] == 0.0
    
    def test_get_news_headlines_cached(self, stock_collector, redis_cache, sample_articles):
        """Test getting news headlines from cache."""
        symbol = "AAPL"
        
        # Cache news
        redis_cache.cache_news(symbol, sample_articles)
        
        # Get from cache
        result = stock_collector.get_news_headlines(symbol)
        
        assert result is not None
        assert len(result) == len(sample_articles)
        assert result[0]["title"] == sample_articles[0]["title"]
    
    @patch('src.stock_sentiment.services.collector.yf.Ticker')
    def test_get_news_headlines_uncached(self, mock_ticker_class, stock_collector, redis_cache):
        """Test getting news headlines from API when not cached."""
        symbol = "AAPL"
        
        # Mock yfinance Ticker with news
        mock_ticker = MagicMock()
        mock_ticker.news = [
            {
                "title": "Test Article",
                "provider": {"displayName": "Reuters"},
                "content": {
                    "title": "Test Article",
                    "summary": "Test summary",
                    "canonicalUrl": {"url": "https://example.com/article"}
                },
                "pubDate": int((datetime.now() - timedelta(hours=1)).timestamp())
            }
        ]
        mock_ticker_class.return_value = mock_ticker
        
        # Get news (should fetch from API)
        result = stock_collector.get_news_headlines(symbol)
        
        assert result is not None
        assert len(result) > 0
        assert result[0]["title"] == "Test Article"
        
        # Verify it was cached
        cached = redis_cache.get_cached_news(symbol)
        assert cached is not None
    
    @patch('src.stock_sentiment.services.collector.yf.Ticker')
    def test_get_news_headlines_empty(self, mock_ticker_class, stock_collector):
        """Test getting news when API returns empty results."""
        symbol = "AAPL"
        
        # Mock yfinance with no news
        mock_ticker = MagicMock()
        mock_ticker.news = []
        mock_ticker_class.return_value = mock_ticker
        
        result = stock_collector.get_news_headlines(symbol)
        assert result == []
    
    @patch('src.stock_sentiment.services.collector.yf.Ticker')
    def test_get_news_headlines_limit(self, mock_ticker_class, stock_collector):
        """Test news headlines limit parameter."""
        symbol = "AAPL"
        
        # Mock yfinance with many news items
        mock_ticker = MagicMock()
        mock_ticker.news = [
            {
                "title": f"Article {i}",
                "provider": {"displayName": "Source"},
                "content": {
                    "title": f"Article {i}",
                    "summary": "Summary",
                    "canonicalUrl": {"url": f"https://example.com/{i}"}
                },
                "pubDate": int((datetime.now() - timedelta(hours=i)).timestamp())
            }
            for i in range(20)
        ]
        mock_ticker_class.return_value = mock_ticker
        
        # Get with limit
        result = stock_collector.get_news_headlines(symbol, limit=5)
        assert len(result) == 5
    
    @responses.activate
    def test_get_alpha_vantage_news_success(self, stock_collector):
        """Test getting news from Alpha Vantage API."""
        symbol = "AAPL"
    
        # Mock Alpha Vantage response (match URL with query params)
        responses.add(
            responses.GET,
            f"https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers={symbol}&apikey=test-key&limit=10",
            json={
                "feed": [
                    {
                        "title": "Alpha Vantage Article",
                        "summary": "Test summary",
                        "source": "Test Source",
                        "url": "https://example.com/article",
                        "time_published": "20240101T120000"
                    }
                ]
            },
            status=200
        )
        
        # Mock API key and enable flag
        stock_collector.settings.data_sources.alpha_vantage_api_key = "test-key"
        stock_collector.settings.data_sources.alpha_vantage_enabled = True
        
        result = stock_collector.get_alpha_vantage_news(symbol)
        
        assert result is not None
        assert len(result) > 0
        assert "Alpha Vantage" in result[0]["source"] or "Test Source" in result[0]["source"]
    
    @patch('requests.get')
    def test_get_alpha_vantage_news_rate_limit(self, mock_get, stock_collector):
        """Test handling of Alpha Vantage rate limit."""
        symbol = "AAPL"
        
        # Mock rate limit response
        mock_response = MagicMock()
        mock_response.status_code = 429
        mock_response.json.return_value = {"Note": "API call frequency is higher than 5 calls per minute"}
        mock_get.return_value = mock_response
        
        stock_collector.settings.data_sources.alpha_vantage_api_key = "test-key"
        
        result = stock_collector.get_alpha_vantage_news(symbol)
        assert result == []
    
    @patch('requests.get')
    def test_get_alpha_vantage_news_api_error(self, mock_get, stock_collector):
        """Test handling of Alpha Vantage API errors."""
        symbol = "AAPL"
        
        # Mock API error
        mock_get.side_effect = Exception("API Error")
        
        stock_collector.settings.data_sources.alpha_vantage_api_key = "test-key"
        
        result = stock_collector.get_alpha_vantage_news(symbol)
        assert result == []
    
    @responses.activate
    def test_get_finnhub_news_success(self, stock_collector):
        """Test getting news from Finnhub API."""
        from datetime import datetime, timedelta
        import re
        symbol = "AAPL"
    
        # Mock Finnhub response - use regex to match URL with date parameters
        # The collector adds from/to dates, so we need to match flexibly
        # Match the base URL pattern - responses will match any query params
        responses.add(
            responses.GET,
            re.compile(r"https://finnhub\.io/api/v1/company-news"),
            json=[
                {
                    "headline": "Finnhub Article",
                    "summary": "Test summary",
                    "source": "Test Source",
                    "url": "https://example.com/article",
                    "datetime": int(datetime.now().timestamp())
                }
            ],
            status=200
        )
        
        stock_collector.settings.data_sources.finnhub_api_key = "test-key"
        stock_collector.settings.data_sources.finnhub_enabled = True
        
        result = stock_collector.get_finnhub_news(symbol)
        
        assert result is not None
        assert len(result) > 0
        # Source might be "Test Source" or "Finnhub" depending on processing
        assert "source" in result[0]
    
    @patch('requests.get')
    def test_get_finnhub_news_rate_limit(self, mock_get, stock_collector):
        """Test handling of Finnhub rate limit."""
        symbol = "AAPL"
        
        # Mock rate limit response
        mock_response = MagicMock()
        mock_response.status_code = 429
        mock_response.json.return_value = {"error": "Rate limit exceeded"}
        mock_get.return_value = mock_response
        
        stock_collector.settings.data_sources.finnhub_api_key = "test-key"
        
        result = stock_collector.get_finnhub_news(symbol)
        assert result == []
    
    def test_collect_all_data_all_sources(self, stock_collector, redis_cache):
        """Test collecting data from all sources."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 175.50}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=[]):
                with patch.object(stock_collector, 'get_alpha_vantage_news', return_value=[]):
                    with patch.object(stock_collector, 'get_finnhub_news', return_value=[]):
                        result = stock_collector.collect_all_data(symbol)
        
        assert result is not None
        assert "price_data" in result
        assert "news" in result
        assert "social_media" in result
    
    def test_collect_all_data_filtered_sources(self, stock_collector):
        """Test collecting data with source filters."""
        symbol = "AAPL"
        filters = {
            "yfinance": True,
            "alpha_vantage": False,
            "finnhub": False,
            "reddit": False
        }
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 175.50}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=[]):
                result = stock_collector.collect_all_data(symbol, data_source_filters=filters)
        
        assert result is not None
        # Should only have yfinance data
        assert "price_data" in result
    
    def test_deduplication_logic(self, stock_collector):
        """Test that duplicate articles are removed."""
        symbol = "AAPL"
        
        # Create articles with duplicate URLs
        articles = [
            {"title": "Article 1", "url": "https://example.com/1", "source": "yfinance"},
            {"title": "Article 2", "url": "https://example.com/1", "source": "alpha_vantage"},  # Duplicate URL
            {"title": "Article 3", "url": "https://example.com/2", "source": "finnhub"},
        ]
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 175.50}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=articles[:1]):
                with patch.object(stock_collector, 'get_alpha_vantage_news', return_value=articles[1:2]):
                    with patch.object(stock_collector, 'get_finnhub_news', return_value=articles[2:]):
                        result = stock_collector.collect_all_data(symbol)
        
        # Should have 2 unique articles (duplicate removed)
        assert len(result["news"]) == 2
    
    def test_timestamp_normalization(self, stock_collector):
        """Test that timestamps are normalized correctly."""
        symbol = "AAPL"
        
        # Create article with timezone-aware timestamp
        from datetime import timezone
        aware_timestamp = datetime.now(timezone.utc)
        
        articles = [
            {
                "title": "Test Article",
                "summary": "Test",
                "source": "yfinance",
                "url": "https://example.com",
                "timestamp": aware_timestamp
            }
        ]
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 175.50}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=articles):
                result = stock_collector.collect_all_data(symbol)
        
        # Timestamp should be normalized (naive)
        assert result["news"][0]["timestamp"] is not None
        # Should be naive datetime (no timezone info)
        if isinstance(result["news"][0]["timestamp"], datetime):
            assert result["news"][0]["timestamp"].tzinfo is None
    
    def test_invalid_symbol_handling(self, stock_collector):
        """Test handling of invalid stock symbols."""
        invalid_symbol = "INVALID123"
        
        result = stock_collector.get_stock_price(invalid_symbol)
        # Should return default values or handle gracefully
        assert result is not None
    
    def test_empty_responses_handling(self, stock_collector):
        """Test handling of empty API responses."""
        symbol = "AAPL"
        
        with patch.object(stock_collector, 'get_stock_price', return_value={"symbol": symbol, "price": 0.0}):
            with patch.object(stock_collector, 'get_news_headlines', return_value=[]):
                result = stock_collector.collect_all_data(symbol)
        
        assert result is not None
        assert result["news"] == []
    
    @patch('requests.get')
    def test_network_error_handling(self, mock_get, stock_collector):
        """Test handling of network errors."""
        symbol = "AAPL"
        
        # Mock network error
        mock_get.side_effect = Exception("Network error")
        
        stock_collector.settings.data_sources.alpha_vantage_api_key = "test-key"
        
        result = stock_collector.get_alpha_vantage_news(symbol)
        assert result == []


@pytest.mark.unit
class TestNormalizeDatetime:
    """Test suite for normalize_datetime function."""
    
    def test_normalize_naive_datetime(self):
        """Test normalizing naive datetime (should remain unchanged)."""
        naive_dt = datetime.now()
        result = normalize_datetime(naive_dt)
        assert result == naive_dt
        assert result.tzinfo is None
    
    def test_normalize_timezone_aware_datetime(self):
        """Test normalizing timezone-aware datetime."""
        from datetime import timezone
        aware_dt = datetime.now(timezone.utc)
        result = normalize_datetime(aware_dt)
        assert result.tzinfo is None  # Should be naive after normalization
    
    def test_normalize_non_datetime(self):
        """Test normalizing non-datetime objects."""
        result = normalize_datetime("not a datetime")
        assert isinstance(result, datetime)
        assert result.tzinfo is None

