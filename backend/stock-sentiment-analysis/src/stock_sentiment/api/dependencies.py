"""
API dependencies for service initialization.

This module provides dependency injection functions for FastAPI,
similar to the initialization module but without Streamlit dependencies.
"""

from typing import Optional, Tuple
from functools import lru_cache

from ..config.settings import Settings, get_settings
from ..services.sentiment import SentimentAnalyzer
from ..services.collector import StockDataCollector
from ..services.cache import RedisCache
from ..services.rag import RAGService
from ..utils.logger import get_logger

logger = get_logger(__name__)


# Cache services as singletons (similar to @st.cache_resource)
_redis_cache: Optional[RedisCache] = None
_rag_service: Optional[RAGService] = None
_collector: Optional[StockDataCollector] = None
_analyzer: Optional[SentimentAnalyzer] = None
_settings: Optional[Settings] = None


@lru_cache(maxsize=1)
def get_settings_cached() -> Settings:
    """Get cached settings instance."""
    return get_settings()


def get_redis_cache() -> Optional[RedisCache]:
    """Get Redis cache instance (singleton)."""
    global _redis_cache
    
    if _redis_cache is None:
        try:
            settings = get_settings_cached()
            if settings.is_redis_available():
                _redis_cache = RedisCache(settings=settings)
                if _redis_cache.client:
                    logger.info("Redis cache initialized successfully")
                    return _redis_cache
            logger.warning("Redis cache not available")
            return None
        except Exception as e:
            logger.warning(f"Redis cache not available: {e}")
            return None
    
    return _redis_cache


def get_rag_service() -> Optional[RAGService]:
    """Get RAG service instance (singleton)."""
    global _rag_service
    
    if _rag_service is None:
        try:
            settings = get_settings_cached()
            cache = get_redis_cache()
            if cache and settings.is_rag_available():
                _rag_service = RAGService(settings=settings, redis_cache=cache)
                logger.info("RAG service initialized successfully")
                return _rag_service
            logger.warning("RAG service not available")
            return None
        except Exception as e:
            logger.warning(f"RAG service not available: {e}")
            return None
    
    return _rag_service


def get_collector() -> StockDataCollector:
    """Get stock data collector instance (singleton)."""
    global _collector
    
    if _collector is None:
        settings = get_settings_cached()
        cache = get_redis_cache()
        _collector = StockDataCollector(settings=settings, redis_cache=cache)
        logger.info("Stock data collector initialized successfully")
    
    return _collector


def get_analyzer() -> Optional[SentimentAnalyzer]:
    """Get sentiment analyzer instance (singleton)."""
    global _analyzer
    
    if _analyzer is None:
        try:
            settings = get_settings_cached()
            cache = get_redis_cache()
            rag_service = get_rag_service()
            _analyzer = SentimentAnalyzer(
                settings=settings,
                redis_cache=cache,
                rag_service=rag_service
            )
            logger.info("Sentiment analyzer initialized successfully")
            return _analyzer
        except ValueError as e:
            logger.error(f"Configuration Error: {e}")
            return None
        except Exception as e:
            logger.error(f"Error initializing Azure OpenAI: {e}")
            return None
    
    return _analyzer


def get_all_services() -> Tuple[
    Settings,
    Optional[RedisCache],
    Optional[RAGService],
    StockDataCollector,
    Optional[SentimentAnalyzer]
]:
    """
    Get all services initialized.
    
    Returns:
        Tuple of (settings, redis_cache, rag_service, collector, analyzer)
    """
    settings = get_settings_cached()
    redis_cache = get_redis_cache()
    rag_service = get_rag_service()
    collector = get_collector()
    analyzer = get_analyzer()
    
    if analyzer is None:
        logger.error("Failed to initialize sentiment analyzer")
    
    return settings, redis_cache, rag_service, collector, analyzer


def reset_services():
    """Reset all cached services (useful for testing)."""
    global _redis_cache, _rag_service, _collector, _analyzer, _settings
    _redis_cache = None
    _rag_service = None
    _collector = None
    _analyzer = None
    _settings = None
    get_settings_cached.cache_clear()
    logger.info("Services reset")

