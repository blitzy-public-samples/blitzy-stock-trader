"""
Application initialization and service setup.
"""

import streamlit as st
from typing import Optional, Tuple

from ..config.settings import get_settings
from ..services.sentiment import SentimentAnalyzer
from ..services.collector import StockDataCollector
from ..services.cache import RedisCache
from ..services.rag import RAGService
from ..utils.logger import get_logger, setup_logger
from .api_client import SentimentAPIClient

logger = get_logger(__name__)


def initialize_settings():
    """Initialize and validate application settings."""
    try:
        settings = get_settings()
        return settings
    except ValueError as e:
        st.error(f"Configuration Error: {e}")
        st.stop()
        return None


@st.cache_resource
def get_redis_cache(_settings):
    """Get Redis cache instance."""
    try:
        if _settings.is_redis_available():
            cache = RedisCache(settings=_settings)
            if cache.client:
                return cache
        return None
    except Exception as e:
        logger.warning(f"Redis cache not available: {e}")
        return None


@st.cache_resource
def get_rag_service(_settings, _cache):
    """Get RAG service instance."""
    if _cache and _settings.is_rag_available():
        try:
            return RAGService(settings=_settings, redis_cache=_cache)
        except Exception as e:
            logger.warning(f"RAG service not available: {e}")
            return None
    return None


@st.cache_resource
def get_collector(_settings, _cache):
    """Get stock data collector instance."""
    return StockDataCollector(settings=_settings, redis_cache=_cache)


@st.cache_resource
def get_analyzer(_settings, _cache, _rag_service):
    """Get sentiment analyzer instance."""
    try:
        return SentimentAnalyzer(
            settings=_settings,
            redis_cache=_cache,
            rag_service=_rag_service
        )
    except ValueError as e:
        logger.error(f"Configuration Error: {e}")
        return None
    except Exception as e:
        logger.error(f"Error initializing Azure OpenAI: {e}")
        return None


@st.cache_resource
def get_api_client(_settings):
    """Get API client instance."""
    try:
        if _settings.app.api_enabled:
            client = SentimentAPIClient(settings=_settings)
            # Test connection
            if client.is_available():
                logger.info("API client initialized and API is available")
                return client
            else:
                logger.warning("API client initialized but API is not available")
                return client  # Return anyway, let it fail gracefully on use
        return None
    except Exception as e:
        logger.warning(f"API client not available: {e}")
        return None


def initialize_services(settings) -> Tuple[
    Optional[SentimentAPIClient],
    Optional[RedisCache],
    Optional[RAGService],
    Optional[StockDataCollector],
    Optional[SentimentAnalyzer]
]:
    """
    Initialize all application services.
    
    If API mode is enabled, returns API client. Otherwise returns direct services.
    
    Returns:
        Tuple of (api_client, redis_cache, rag_service, collector, analyzer)
        - api_client: API client if API mode enabled, None otherwise
        - Other services: For fallback or status display
    """
    # Initialize API client if API mode is enabled
    api_client = None
    if settings.app.api_enabled:
        api_client = get_api_client(settings)
        if api_client and not api_client.is_available():
            logger.warning("API is enabled but not available. Dashboard may not work correctly.")
            st.warning("⚠️ API is not available. Please ensure the API server is running.")
    
    # Initialize services for fallback or status display
    redis_cache = get_redis_cache(settings)
    rag_service = get_rag_service(settings, redis_cache)
    collector = get_collector(settings, redis_cache)
    analyzer = get_analyzer(settings, redis_cache, rag_service)
    
    # If API mode is enabled, we don't require analyzer (API handles it)
    # But we still initialize it for status display
    if not settings.app.api_enabled and analyzer is None:
        st.error("Failed to initialize sentiment analyzer. Please check your configuration.")
        st.stop()
    
    return api_client, redis_cache, rag_service, collector, analyzer


def initialize_session_state():
    """Initialize Streamlit session state variables."""
    defaults = {
        'load_data': False,
        'data': None,
        'news_sentiments': [],
        'social_sentiments': [],
        'symbol': "AAPL",
        'title_shown': False,
        'data_errors': {},
        'show_comparison': False,
        'comparison_stocks': [],
        'article_page': 1,
        'show_all_articles': False,
        'confirm_clear_cache': False
    }
    
    for key, default_value in defaults.items():
        if key not in st.session_state:
            st.session_state[key] = default_value
    
    # Initialize search filters if not exists
    if 'search_filters' not in st.session_state:
        settings = get_settings()
        st.session_state.search_filters = {
            "date_range": None,
            "sources": None,
            "exclude_unknown": True,
            "days_back": None,
            "data_sources": {
                "yfinance": True,  # Always enabled (primary source)
                "alpha_vantage": settings.data_sources.alpha_vantage_enabled,
                "finnhub": settings.data_sources.finnhub_enabled,
                "reddit": settings.data_sources.reddit_enabled
            }
        }


def setup_app():
    """Complete application setup including logger and page config."""
    # Initialize root logger at app startup
    setup_logger("stock_sentiment", level="INFO")
    logger.info("Stock Sentiment Dashboard starting up")
    
    # Page configuration with custom theme
    st.set_page_config(
        page_title="Stock Sentiment Dashboard",
        page_icon="📈",
        layout="wide",
        initial_sidebar_state="expanded"
    )

