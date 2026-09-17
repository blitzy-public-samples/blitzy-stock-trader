"""
API client for calling the Stock Sentiment Analysis API.

This client is used by the Streamlit dashboard to communicate with the API,
ensuring the dashboard is API-driven rather than calling services directly.
"""

import httpx
from typing import Optional, Dict, Any, List
from ..config.settings import Settings, get_settings
from ..utils.logger import get_logger

logger = get_logger(__name__)


class SentimentAPIClient:
    """
    HTTP client for Stock Sentiment Analysis API.
    
    This client handles all communication between the dashboard and the API,
    including error handling, retries, and response parsing.
    
    Attributes:
        base_url: Base URL of the API
        timeout: Request timeout in seconds
        client: httpx client instance
    """
    
    def __init__(
        self,
        base_url: Optional[str] = None,
        timeout: Optional[int] = None,
        settings: Optional[Settings] = None
    ):
        """
        Initialize the API client.
        
        Args:
            base_url: API base URL (defaults to settings or http://localhost:8000)
            timeout: Request timeout in seconds (defaults to settings value)
            settings: Application settings (uses global if not provided)
        """
        self.settings = settings or get_settings()
        self.base_url = base_url or self.settings.app.api_base_url
        self.timeout = timeout if timeout is not None else self.settings.app.api_timeout
        
        # Ensure base_url doesn't end with /
        if self.base_url.endswith('/'):
            self.base_url = self.base_url.rstrip('/')
        
        # Create httpx client with timeout
        # Use separate connect and read timeouts for better control
        # Read timeout is longer to accommodate RAG operations
        from httpx import Timeout
        timeout_config = Timeout(
            connect=10.0,  # 10 seconds to establish connection
            read=self.timeout,  # Full timeout for reading response (handles long RAG operations)
            write=10.0,  # 10 seconds for writing request
            pool=10.0  # 10 seconds to get connection from pool
        )
        self.client = httpx.Client(
            base_url=self.base_url,
            timeout=timeout_config,
            follow_redirects=True
        )
        
        logger.info(f"API Client initialized with base URL: {self.base_url}")
    
    def get_sentiment(
        self,
        symbol: str,
        detailed: bool = False,
        sources: Optional[str] = None,
        cache_enabled: bool = True
    ) -> Dict[str, Any]:
        """
        Get sentiment analysis for a stock symbol.
        
        Args:
            symbol: Stock symbol to analyze
            detailed: If True, return detailed response with full data
            sources: Comma-separated list of data sources (optional)
            cache_enabled: Enable/disable sentiment caching
        
        Returns:
            Dictionary with sentiment analysis results
        
        Raises:
            httpx.HTTPError: If API request fails
            ValueError: If response is invalid
        """
        endpoint = f"/sentiment/{symbol}"
        params = {
            "detailed": detailed,
            "cache_enabled": cache_enabled
        }
        
        if sources:
            params["sources"] = sources
        
        logger.info(f"API Client: Requesting sentiment for {symbol} (detailed={detailed})")
        
        try:
            response = self.client.get(endpoint, params=params)
            response.raise_for_status()
            
            result = response.json()
            logger.info(f"API Client: Successfully received sentiment for {symbol}")
            
            return result
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Request timeout for {symbol} after {self.timeout}s")
            raise TimeoutError(
                f"Sentiment analysis for {symbol} timed out after {self.timeout} seconds. "
                f"This may happen when RAG is used with many articles. "
                f"Try enabling sentiment cache or reducing the number of data sources."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for {symbol}: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for {symbol}: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for {symbol}: {e}")
            raise
    
    def get_health(self) -> Dict[str, Any]:
        """
        Check API health status.
        
        Returns:
            Dictionary with health status information
        
        Raises:
            httpx.HTTPError: If API request fails
        """
        endpoint = "/health"
        
        logger.debug("API Client: Checking API health")
        
        try:
            response = self.client.get(endpoint)
            response.raise_for_status()
            
            result = response.json()
            logger.debug(f"API Client: Health check result: {result.get('status', 'unknown')}")
            
            return result
            
        except httpx.HTTPStatusError as e:
            logger.warning(f"API Client: Health check failed: {e.response.status_code}")
            return {
                "status": "unhealthy",
                "services": {"error": f"HTTP {e.response.status_code}"},
                "timestamp": None
            }
        except httpx.RequestError as e:
            logger.warning(f"API Client: Health check request error: {e}")
            return {
                "status": "unreachable",
                "services": {"error": "API unreachable"},
                "timestamp": None
            }
        except Exception as e:
            logger.error(f"API Client: Health check unexpected error: {e}")
            return {
                "status": "error",
                "services": {"error": str(e)},
                "timestamp": None
            }
    
    def get_sentiment_batch(
        self,
        symbols: List[str],
        sources: Optional[str] = None,
        cache_enabled: bool = True,
        detailed: bool = False
    ) -> List[Dict[str, Any]]:
        """
        Get sentiment analysis for multiple symbols in batch.
        
        Args:
            symbols: List of stock symbols to analyze
            sources: Optional comma-separated list of data sources
            cache_enabled: Whether to use sentiment cache
            detailed: Whether to return detailed response
        
        Returns:
            List of sentiment response dictionaries
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/sentiment/batch"
        
        payload = {
            "symbols": symbols,
            "cache_enabled": cache_enabled,
            "detailed": detailed
        }
        if sources:
            payload["sources"] = sources
        
        logger.info(f"API Client: Requesting batch sentiment for {len(symbols)} symbols")
        
        try:
            response = self.client.post(endpoint, json=payload)
            response.raise_for_status()
            
            result = response.json()
            logger.info(f"API Client: Successfully received batch sentiment for {len(symbols)} symbols")
            
            return result
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Batch request timeout after {self.timeout}s")
            raise TimeoutError(
                f"Batch sentiment analysis timed out after {self.timeout} seconds. "
                f"This may happen when analyzing many symbols with RAG. "
                f"Try enabling sentiment cache or reducing the number of symbols."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for batch request: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for batch request: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for batch request: {e}")
            raise
    
    def get_price_history(
        self,
        symbol: str,
        period: str = "1y"
    ) -> Dict[str, Any]:
        """
        Get historical price data for a stock symbol.
        
        Args:
            symbol: Stock symbol
            period: Time period (1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y)
        
        Returns:
            Dictionary with symbol, period, and historical data
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/price/{symbol}/history"
        params = {"period": period}
        
        logger.info(f"API Client: Requesting price history for {symbol} (period: {period})")
        
        try:
            response = self.client.get(endpoint, params=params)
            response.raise_for_status()
            
            result = response.json()
            logger.info(f"API Client: Successfully received price history for {symbol}")
            
            return result
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Price history request timeout for {symbol}")
            raise TimeoutError(
                f"Price history request for {symbol} timed out. "
                f"Please try again."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for price history: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for price history: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for price history: {e}")
            raise
    
    def get_comparison_insights(
        self,
        comparison_data: Dict[str, Dict[str, Any]],
        comparison_sentiments: Dict[str, Dict[str, float]]
    ) -> str:
        """
        Get AI-powered comparison insights for multiple stocks.
        
        Args:
            comparison_data: Dictionary mapping symbols to their data
            comparison_sentiments: Dictionary mapping symbols to sentiment scores
        
        Returns:
            AI-generated comparison insights text
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/comparison/insights"
        
        payload = {
            "comparison_data": comparison_data,
            "comparison_sentiments": comparison_sentiments
        }
        
        logger.info(f"API Client: Requesting comparison insights for {len(comparison_sentiments)} symbols")
        
        try:
            response = self.client.post(endpoint, json=payload)
            response.raise_for_status()
            
            result = response.json()
            insights = result.get("insights", "")
            logger.info(f"API Client: Successfully received comparison insights")
            
            return insights
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Comparison insights request timeout")
            raise TimeoutError(
                f"Comparison insights request timed out. "
                f"Please try again."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for comparison insights: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for comparison insights: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for comparison insights: {e}")
            raise
    
    def get_system_status(self) -> Dict[str, Any]:
        """
        Get system health and status.
        
        Returns:
            Dictionary with system status information
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/system/status"
        
        logger.debug("API Client: Checking system status")
        
        try:
            response = self.client.get(endpoint)
            response.raise_for_status()
            
            result = response.json()
            logger.debug(f"API Client: System status check result: {result.get('redis', {}).get('connected', False)}")
            
            return result
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: System status request timeout")
            raise TimeoutError(
                f"System status request timed out. "
                f"Please try again."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for system status: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for system status: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for system status: {e}")
            raise
    
    def reset_cache_stats(self) -> bool:
        """
        Reset cache statistics.
        
        Returns:
            True if successful
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/cache/stats/reset"
        
        logger.info("API Client: Resetting cache statistics")
        
        try:
            response = self.client.post(endpoint)
            response.raise_for_status()
            
            result = response.json()
            success = result.get("success", False)
            logger.info(f"API Client: Cache statistics reset: {success}")
            
            return success
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Reset cache stats request timeout")
            raise TimeoutError(
                f"Reset cache stats request timed out. "
                f"Please try again."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for reset cache stats: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for reset cache stats: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for reset cache stats: {e}")
            raise
    
    def clear_cache(self, confirm: bool = True) -> Dict[str, Any]:
        """
        Clear all cache data.
        
        Args:
            confirm: Confirmation flag (must be True)
        
        Returns:
            Dictionary with success status and keys deleted
        
        Raises:
            TimeoutError: If request times out
            ConnectionError: If connection fails
        """
        endpoint = f"{self.base_url}/cache/clear"
        
        payload = {"confirm": confirm}
        
        logger.info("API Client: Clearing all cache data")
        
        try:
            response = self.client.post(endpoint, json=payload)
            response.raise_for_status()
            
            result = response.json()
            logger.info(f"API Client: Cache cleared: {result.get('success', False)}")
            
            return result
            
        except httpx.TimeoutException as e:
            logger.error(f"API Client: Clear cache request timeout")
            raise TimeoutError(
                f"Clear cache request timed out. "
                f"Please try again."
            ) from e
        except httpx.HTTPStatusError as e:
            logger.error(f"API Client: HTTP error for clear cache: {e.response.status_code} - {e.response.text}")
            raise
        except httpx.RequestError as e:
            logger.error(f"API Client: Request error for clear cache: {e}")
            raise ConnectionError(
                f"Failed to connect to API at {self.base_url}. "
                f"Please ensure the API server is running."
            ) from e
        except Exception as e:
            logger.error(f"API Client: Unexpected error for clear cache: {e}")
            raise
    
    def is_available(self) -> bool:
        """
        Check if API is available and responding.
        
        Returns:
            True if API is available, False otherwise
        """
        try:
            health = self.get_health()
            return health.get("status") in ["healthy", "degraded"]
        except Exception:
            return False
    
    def close(self):
        """Close the HTTP client."""
        if self.client:
            self.client.close()
            logger.debug("API Client: Closed HTTP client")
    
    def __enter__(self):
        """Context manager entry."""
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit."""
        self.close()

