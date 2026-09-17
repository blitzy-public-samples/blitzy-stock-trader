"""
Redis cache service for caching API responses and data.

This module provides a Redis-based caching layer to reduce API calls
and improve application performance. It handles caching of:
- Sentiment analysis results
- Stock price data
- News articles
- Article embeddings for RAG
"""

import json
import hashlib
from typing import Optional, Any, Dict, List
import redis
from redis import Redis

from ..config.settings import Settings, get_settings
from ..utils.logger import get_logger

logger = get_logger(__name__)


class CacheStats:
    """
    Cache statistics tracker that persists in Redis.
    
    This class tracks cache hits, misses, and other statistics
    across app reloads by storing them in Redis.
    """
    
    STATS_KEY = "cache:stats"
    
    @staticmethod
    def get_stats(redis_client) -> Dict[str, int]:
        """
        Get cache statistics from Redis.
        
        Args:
            redis_client: Redis client instance
            
        Returns:
            Dictionary with statistics
        """
        if not redis_client:
            return {'cache_hits': 0, 'cache_misses': 0, 'cache_sets': 0}
        
        try:
            stats_data = redis_client.get(CacheStats.STATS_KEY)
            if stats_data:
                return json.loads(stats_data)
        except Exception as e:
            logger.error(f"Error getting cache stats: {e}")
        
        return {'cache_hits': 0, 'cache_misses': 0, 'cache_sets': 0}
    
    @staticmethod
    def increment_hit(redis_client):
        """Increment cache hit counter."""
        if not redis_client:
            return
        try:
            stats = CacheStats.get_stats(redis_client)
            stats['cache_hits'] = stats.get('cache_hits', 0) + 1
            redis_client.set(CacheStats.STATS_KEY, json.dumps(stats))
        except Exception as e:
            logger.error(f"Error incrementing cache hit: {e}")
    
    @staticmethod
    def increment_miss(redis_client):
        """Increment cache miss counter."""
        if not redis_client:
            return
        try:
            stats = CacheStats.get_stats(redis_client)
            stats['cache_misses'] = stats.get('cache_misses', 0) + 1
            redis_client.set(CacheStats.STATS_KEY, json.dumps(stats))
        except Exception as e:
            logger.error(f"Error incrementing cache miss: {e}")
    
    @staticmethod
    def increment_set(redis_client):
        """Increment cache set counter."""
        if not redis_client:
            return
        try:
            stats = CacheStats.get_stats(redis_client)
            stats['cache_sets'] = stats.get('cache_sets', 0) + 1
            redis_client.set(CacheStats.STATS_KEY, json.dumps(stats))
        except Exception as e:
            logger.error(f"Error incrementing cache set: {e}")
    
    @staticmethod
    def reset(redis_client):
        """Reset all cache statistics."""
        if not redis_client:
            return
        try:
            redis_client.set(CacheStats.STATS_KEY, json.dumps({
                'cache_hits': 0,
                'cache_misses': 0,
                'cache_sets': 0
            }))
        except Exception as e:
            logger.error(f"Error resetting cache stats: {e}")


class RedisCache:
    """
    Redis cache utility for caching API responses and data.
    
    This class provides a high-level interface for Redis caching operations,
    with automatic serialization/deserialization and key generation.
    
    Attributes:
        client: Redis client instance (None if connection failed)
        settings: Application settings instance
        last_tier_used: Track which cache was used (for UI display)
        
    Example:
        >>> cache = RedisCache()
        >>> cache.set("key", {"data": "value"}, ttl=3600)
        >>> value = cache.get("key")
    """
    
    def __init__(self, settings: Optional[Settings] = None):
        """
        Initialize Redis connection.
        
        Args:
            settings: Optional settings instance (uses global if not provided)
            
        Note:
            If Redis connection fails, the client will be None and caching
            will be disabled gracefully without raising exceptions.
        """
        self.settings = settings or get_settings()
        self.client: Optional[Redis] = None
        self.last_tier_used: Optional[str] = None  # Track last cache tier used (for UI)
        
        if not self.settings.is_redis_available():
            logger.warning("Redis configuration not available - caching disabled")
            return
        
        try:
            redis_config = self.settings.redis
            
            # Use exact same connection approach as master branch (proven to work)
            # Connection pooling can be added later as an enhancement if needed
            self.client = redis.Redis(
                host=redis_config.host,
                port=redis_config.port,
                password=redis_config.password,
                ssl=redis_config.ssl,
                ssl_cert_reqs=None,
                decode_responses=True,
                socket_connect_timeout=self.settings.app.redis_connect_timeout,
                socket_timeout=self.settings.app.redis_socket_timeout
            )
            
            # Test connection
            self.client.ping()
            logger.info(f"Redis connected to {redis_config.host}:{redis_config.port}")
        except redis.ConnectionError as conn_err:
            logger.error(f"Redis connection failed: {conn_err}")
            logger.warning(
                "Connection failed. Possible causes:\n"
                "1. Firewall rule not saved/applied (wait 1-2 minutes after saving)\n"
                "2. IP address changed (check current IP with: curl ifconfig.me)\n"
                "3. Connection coming from different IP (VPN, proxy, corporate network)\n"
                "4. Firewall rule format incorrect\n"
                "\n"
                "Troubleshooting:\n"
                "1. Run 'make test-redis' to check your current IP\n"
                "2. Verify IP in Azure Portal matches your current IP\n"
                "3. Wait 1-2 minutes after saving firewall rules\n"
                "4. Check if you're behind VPN/proxy\n"
                "5. Try 'Allow access from all networks' temporarily to test"
            )
            self.client = None
        except redis.TimeoutError as timeout_err:
            logger.error(f"Redis connection timeout: {timeout_err}")
            logger.warning(
                "Connection timeout. Possible causes:\n"
                "1. Firewall blocking (even if IP is whitelisted, wait 1-2 min after saving)\n"
                "2. Network latency/firewall rules not propagated yet\n"
                "3. Azure Redis service issues\n"
                "\n"
                "Troubleshooting:\n"
                "1. Wait 1-2 minutes after adding firewall rule (Azure needs time to apply)\n"
                "2. Run 'make test-redis' to verify your current IP\n"
                "3. Check Azure Portal -> Redis -> Networking -> Verify rule is saved\n"
                "4. Try 'Allow access from all networks' temporarily to test\n"
                "\n"
                "The app will continue without Redis caching."
            )
            self.client = None
        except Exception as e:
            logger.error(f"Could not connect to Redis: {e}")
            self.client = None
    
    def _generate_key(self, prefix: str, *args) -> str:
        """
        Generate a cache key from prefix and arguments.
        
        Args:
            prefix: Key prefix (e.g., "sentiment", "stock")
            *args: Additional arguments to include in key
            
        Returns:
            MD5 hash of the key string
            
        Note:
            Keys are hashed for consistency and to avoid special character issues.
            The same inputs will always generate the same key, ensuring cache
            persistence across app reloads.
        """
        # Normalize inputs to ensure consistent key generation
        normalized_args = [str(arg).upper().strip() for arg in args]
        key_string = f"{prefix}:{':'.join(normalized_args)}"
        key_hash = hashlib.md5(key_string.encode()).hexdigest()
        return key_hash
    
    def get(self, key: str) -> Optional[Any]:
        """
        Get value from cache.
        
        Args:
            key: Cache key
            
        Returns:
            Cached value or None if not found/error
        """
        if not self.client:
            return None
        
        try:
            # Check if key exists and get TTL
            ttl = self.client.ttl(key)
            value = self.client.get(key)
            if value:
                # Track hit in Redis stats
                CacheStats.increment_hit(self.client)
                self.last_tier_used = "Redis"
                return json.loads(value)
            else:
                # Track miss in Redis stats
                CacheStats.increment_miss(self.client)
                self.last_tier_used = "MISS"
            return None
        except json.JSONDecodeError as e:
            logger.error(f"Error decoding cached value for key {key}: {e}")
            return None
        except Exception as e:
            logger.error(f"Error getting from cache: {e}")
            return None
    
    def set(self, key: str, value: Any, ttl: int = 3600) -> bool:
        """
        Set value in cache with TTL.
        
        Args:
            key: Cache key
            value: Value to cache (must be JSON serializable)
            ttl: Time to live in seconds (default: 1 hour)
            
        Returns:
            True if successful, False otherwise
        """
        if not self.client:
            logger.warning(f"Cache client not available, cannot cache key: {key[:20]}...")
            return False
        
        try:
            serialized = json.dumps(value, default=str)
            result = self.client.setex(key, ttl, serialized)
            if result:
                # Track set in Redis stats
                CacheStats.increment_set(self.client)
            else:
                logger.warning(f"Failed to cache value for key: {key[:20]}...")
            return bool(result)
        except (TypeError, ValueError) as e:
            logger.error(f"Error serializing value for cache: {e}")
            return False
        except Exception as e:
            logger.error(f"Error setting cache: {e}")
            return False
    
    def delete(self, key: str) -> bool:
        """
        Delete key from cache.
        
        Args:
            key: Cache key to delete
            
        Returns:
            True if deleted, False otherwise
        """
        if not self.client:
            return False
        
        try:
            return bool(self.client.delete(key))
        except Exception as e:
            logger.error(f"Error deleting from cache: {e}")
            return False
    
    def cache_sentiment(
        self, 
        text: str, 
        sentiment: Dict[str, float], 
        ttl: Optional[int] = None
    ) -> bool:
        """
        Cache sentiment analysis result.
        
        Args:
            text: Original text that was analyzed
            sentiment: Sentiment scores dictionary
            ttl: Time to live in seconds (default: from settings)
            
        Returns:
            True if cached successfully, False if caching is disabled
        """
        # Check if sentiment caching is enabled
        if not self.settings.app.cache_sentiment_enabled:
            return False
        
        if ttl is None:
            ttl = self.settings.app.cache_ttl_sentiment
        
        key = self._generate_key("sentiment", text)
        return self.set(key, sentiment, ttl)
    
    def get_cached_sentiment(self, text: str) -> Optional[Dict[str, float]]:
        """
        Get cached sentiment analysis result.
        
        Args:
            text: Original text that was analyzed
            
        Returns:
            Cached sentiment scores or None (or None if sentiment caching is disabled)
        """
        # Check if sentiment caching is enabled
        if not self.settings.app.cache_sentiment_enabled:
            return None
        
        key = self._generate_key("sentiment", text)
        return self.get(key)
    
    def cache_stock_data(
        self, 
        symbol: str, 
        data: Dict, 
        ttl: Optional[int] = None
    ) -> bool:
        """
        Cache stock price data.
        
        Args:
            symbol: Stock ticker symbol
            data: Stock data dictionary
            ttl: Time to live in seconds (default: from settings)
            
        Returns:
            True if cached successfully
        """
        if ttl is None:
            ttl = self.settings.app.cache_ttl_stock
        
        key = self._generate_key("stock", symbol)
        return self.set(key, data, ttl)
    
    def get_cached_stock_data(self, symbol: str) -> Optional[Dict]:
        """
        Get cached stock price data.
        
        Args:
            symbol: Stock ticker symbol
            
        Returns:
            Cached stock data or None
        """
        key = self._generate_key("stock", symbol)
        return self.get(key)
    
    def cache_news(
        self, 
        symbol: str, 
        news: List[Dict], 
        ttl: Optional[int] = None
    ) -> bool:
        """
        Cache news articles.
        
        Args:
            symbol: Stock ticker symbol
            news: List of news article dictionaries
            ttl: Time to live in seconds (default: from settings)
            
        Returns:
            True if cached successfully
        """
        if ttl is None:
            ttl = self.settings.app.cache_ttl_news
        
        key = self._generate_key("news", symbol)
        return self.set(key, news, ttl)
    
    def get_cached_news(self, symbol: str) -> Optional[List[Dict]]:
        """
        Get cached news articles.
        
        Args:
            symbol: Stock ticker symbol
            
        Returns:
            Cached news articles or None
        """
        key = self._generate_key("news", symbol)
        return self.get(key)
    
    def store_article_embedding(
        self, 
        article_id: str, 
        embedding: List[float], 
        metadata: Dict,
        ttl: int = 604800  # 7 days
    ) -> bool:
        """
        Store article embedding for RAG retrieval.
        
        Args:
            article_id: Unique article identifier
            embedding: Vector embedding
            metadata: Article metadata dictionary
            ttl: Time to live in seconds (default: 7 days)
            
        Returns:
            True if stored successfully
        """
        if not self.client:
            return False
        
        try:
            embedding_key = f"embedding:{article_id}"
            metadata_key = f"article:{article_id}"
            
            self.client.setex(embedding_key, ttl, json.dumps(embedding))
            self.client.setex(metadata_key, ttl, json.dumps(metadata, default=str))
            return True
        except Exception as e:
            logger.error(f"Error storing embedding: {e}")
            return False
    
    def get_cache_stats(self) -> Dict[str, int]:
        """
        Get cache statistics from Redis.
        
        Returns:
            Dictionary with cache statistics (hits, misses, sets)
        """
        if not self.client:
            return {'cache_hits': 0, 'cache_misses': 0, 'cache_sets': 0}
        return CacheStats.get_stats(self.client)
    
    def reset_cache_stats(self):
        """Reset cache statistics in Redis."""
        if self.client:
            CacheStats.reset(self.client)
            logger.info("Cache statistics reset")
    
    def clear_all_cache(self) -> bool:
        """
        Clear all cached data from Redis.
        
        This will remove:
        - Stock data cache
        - News cache
        - Sentiment cache
        - RAG embeddings and metadata
        - All other cached data
        
        Returns:
            True if successful, False otherwise
        """
        if not self.client:
            logger.warning("Cannot clear cache: Redis client not available")
            return False
        
        try:
            # Use FLUSHDB to clear all keys in current database
            # This is faster than deleting keys individually
            self.client.flushdb()
            logger.info("All Redis cache data cleared")
            return True
        except Exception as e:
            logger.error(f"Error clearing cache: {e}")
            return False
    
    def clear_cache_by_pattern(self, pattern: str) -> int:
        """
        Clear cache keys matching a pattern.
        
        Args:
            pattern: Redis key pattern (e.g., "stock:*", "news:*", "sentiment:*")
            
        Returns:
            Number of keys deleted
        """
        if not self.client:
            return 0
        
        try:
            deleted_count = 0
            cursor = 0
            
            # Use SCAN to safely iterate over keys matching pattern
            while True:
                cursor, keys = self.client.scan(cursor=cursor, match=pattern, count=100)
                if keys:
                    deleted_count += self.client.delete(*keys)
                if cursor == 0:
                    break
            
            logger.info(f"Cleared {deleted_count} keys matching pattern: {pattern}")
            return deleted_count
        except Exception as e:
            logger.error(f"Error clearing cache by pattern {pattern}: {e}")
            return 0
    
    def get_article_embedding(self, article_id: str) -> Optional[tuple]:
        """
        Get article embedding and metadata.
        
        Args:
            article_id: Unique article identifier
            
        Returns:
            Tuple of (embedding, metadata) or None
        """
        if not self.client:
            return None
        
        try:
            embedding_key = f"embedding:{article_id}"
            metadata_key = f"article:{article_id}"
            
            embedding_data = self.client.get(embedding_key)
            metadata_data = self.client.get(metadata_key)
            
            if embedding_data and metadata_data:
                embedding = json.loads(embedding_data)
                metadata = json.loads(metadata_data)
                return embedding, metadata
            return None
        except Exception as e:
            logger.error(f"Error getting embedding: {e}")
            return None

