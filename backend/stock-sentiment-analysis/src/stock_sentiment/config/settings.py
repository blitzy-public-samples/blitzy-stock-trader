"""
Application settings and configuration management.

This module loads and validates environment variables, providing a centralized
configuration interface for the entire application.
"""

import os
from typing import Optional
from pathlib import Path
from dotenv import load_dotenv

# Pydantic v2 compatibility
try:
    from pydantic_settings import BaseSettings, SettingsConfigDict
    from pydantic import Field, field_validator
    PYDANTIC_V2 = True
except ImportError:
    # Pydantic v1 fallback
    try:
        from pydantic import BaseSettings, Field, validator
        PYDANTIC_V2 = False
    except ImportError:
        # Fallback to basic implementation
        from pydantic import BaseModel as BaseSettings, Field
        validator = lambda *args, **kwargs: lambda f: f
        PYDANTIC_V2 = False

# Find project root (where .env file should be)
# This file is in src/stock_sentiment/config/, so go up 3 levels to project root
_current_file = Path(__file__).resolve()
_project_root = _current_file.parent.parent.parent.parent
_env_file = _project_root / ".env"

# Load environment variables from .env file in project root
# This must happen before Pydantic BaseSettings classes are instantiated
# We load with dotenv first, then Pydantic can also read from the file
_env_loaded = False
if _env_file.exists():
    load_dotenv(dotenv_path=_env_file, override=True)
    _env_loaded = True
    # Also set as absolute path for Pydantic v2
    _env_file_abs = str(_env_file.resolve())
else:
    # Fallback: try current directory and parent directories
    load_dotenv(override=True)
    _env_file_abs = str(Path(".env").resolve()) if Path(".env").exists() else ".env"


class AzureOpenAISettings(BaseSettings):
    """Azure OpenAI configuration settings."""
    
    endpoint: str = Field(..., description="Azure OpenAI endpoint URL")
    api_key: str = Field(..., description="Azure OpenAI API key")
    deployment_name: str = Field(default="gpt-4", description="Deployment name")
    api_version: str = Field(default="2023-05-15", description="API version")
    embedding_deployment: Optional[str] = Field(
        default=None,
        description="Embedding deployment name"
    )
    
    if PYDANTIC_V2:
        # Pydantic v2 reads from os.environ (already loaded by dotenv above)
        model_config = SettingsConfigDict(
            env_prefix="AZURE_OPENAI_",
            case_sensitive=False,
            extra="ignore"
        )
        
        @field_validator("endpoint")
        @classmethod
        def validate_endpoint(cls, v):
            """Validate that endpoint is a valid URL."""
            if not v.startswith("http"):
                raise ValueError("Azure OpenAI endpoint must be a valid URL")
            return v.rstrip("/")
    else:
        @validator("endpoint")
        def validate_endpoint(cls, v):
            """Validate that endpoint is a valid URL."""
            if not v.startswith("http"):
                raise ValueError("Azure OpenAI endpoint must be a valid URL")
            return v.rstrip("/")
        
        class Config:
            """Pydantic v1 configuration."""
            env_prefix = "AZURE_OPENAI_"
            case_sensitive = False


class RedisSettings(BaseSettings):
    """Redis cache configuration settings."""
    
    host: str = Field(..., description="Redis host")
    port: int = Field(default=6380, description="Redis port")
    password: str = Field(..., description="Redis password")
    ssl: bool = Field(default=True, description="Enable SSL")
    connection_string: Optional[str] = Field(
        default=None,
        description="Redis connection string"
    )
    
    if PYDANTIC_V2:
        # Pydantic v2 reads from os.environ (already loaded by dotenv above)
        model_config = SettingsConfigDict(
            env_prefix="REDIS_",
            case_sensitive=False,
            extra="ignore"
        )
        
        @field_validator("ssl", mode="before")
        @classmethod
        def parse_ssl(cls, v):
            """Parse SSL setting from string or boolean."""
            if isinstance(v, str):
                return v.lower() in ("true", "1", "yes")
            return bool(v)
    else:
        @validator("ssl", pre=True)
        def parse_ssl(cls, v):
            """Parse SSL setting from string or boolean."""
            if isinstance(v, str):
                return v.lower() in ("true", "1", "yes")
            return bool(v)
        
        class Config:
            """Pydantic v1 configuration."""
            env_prefix = "REDIS_"
            case_sensitive = False


class AzureAISearchSettings(BaseSettings):
    """Azure AI Search configuration settings."""
    
    endpoint: str = Field(..., description="Azure AI Search endpoint URL")
    api_key: str = Field(..., description="Azure AI Search API key")
    index_name: str = Field(default="stock-articles", description="Index name")
    semantic_config_name: Optional[str] = Field(
        default=None,
        description="Semantic configuration name (optional, requires Standard tier)"
    )
    vector_dimension: int = Field(default=1536, description="Embedding vector dimension")
    
    if PYDANTIC_V2:
        model_config = SettingsConfigDict(
            env_prefix="AZURE_AI_SEARCH_",
            case_sensitive=False,
            extra="ignore"
        )
        
        @field_validator("endpoint")
        @classmethod
        def validate_endpoint(cls, v):
            """Validate that endpoint is a valid URL."""
            if not v.startswith("http"):
                raise ValueError("Azure AI Search endpoint must be a valid URL")
            return v.rstrip("/")
    else:
        @validator("endpoint")
        def validate_endpoint(cls, v):
            """Validate that endpoint is a valid URL."""
            if not v.startswith("http"):
                raise ValueError("Azure AI Search endpoint must be a valid URL")
            return v.rstrip("/")
        
        class Config:
            """Pydantic v1 configuration."""
            env_prefix = "AZURE_AI_SEARCH_"
            case_sensitive = False


class DataSourceSettings(BaseSettings):
    """Data source configuration settings."""
    
    # Reddit (PRAW) - Free, requires app registration at https://www.reddit.com/prefs/apps
    reddit_client_id: Optional[str] = Field(default=None, description="Reddit client ID")
    reddit_client_secret: Optional[str] = Field(default=None, description="Reddit client secret")
    reddit_user_agent: Optional[str] = Field(default="stock-sentiment-analysis/1.0", description="Reddit user agent")
    reddit_enabled: bool = Field(default=False, description="Enable Reddit data collection")
    reddit_limit: int = Field(default=20, description="Number of Reddit posts to fetch")
    
    # Alpha Vantage - Free tier: 500 calls/day
    alpha_vantage_api_key: Optional[str] = Field(default=None, description="Alpha Vantage API key")
    alpha_vantage_enabled: bool = Field(default=False, description="Enable Alpha Vantage news")
    
    # Finnhub - Free tier: 60 calls/minute
    finnhub_api_key: Optional[str] = Field(default=None, description="Finnhub API key")
    finnhub_enabled: bool = Field(default=False, description="Enable Finnhub news")
    
    if PYDANTIC_V2:
        model_config = SettingsConfigDict(
            env_prefix="DATA_SOURCE_",
            case_sensitive=False,
            extra="ignore"
        )
    else:
        class Config:
            """Pydantic v1 configuration."""
            env_prefix = "DATA_SOURCE_"
            case_sensitive = False


class AppSettings(BaseSettings):
    """Application-wide settings."""
    
    # Logging
    log_level: str = Field(default="INFO", description="Logging level")
    debug: bool = Field(default=False, description="Debug mode")
    
    # Cache TTLs (in seconds)
    cache_ttl_sentiment: int = Field(default=86400, description="Sentiment cache TTL")  # 24 hours
    cache_ttl_stock: int = Field(default=3600, description="Stock data cache TTL")  # 1 hour
    cache_ttl_news: int = Field(default=7200, description="News cache TTL")  # 2 hours
    cache_ttl_rag_articles: int = Field(default=604800, description="RAG article cache TTL (7 days in seconds)")
    cache_sentiment_enabled: bool = Field(default=True, description="Enable sentiment caching (disable to force RAG usage)")
    
    # Redis connection settings
    redis_connect_timeout: int = Field(default=5, description="Redis connection timeout in seconds")
    redis_socket_timeout: int = Field(default=5, description="Redis socket timeout in seconds")
    
    # RAG settings
    rag_top_k: int = Field(default=3, description="Number of similar articles to retrieve")
    rag_similarity_threshold: float = Field(default=0.01, description="Minimum similarity score for RAG retrieval (0.0-1.0). Lower values return more articles but may include less relevant ones. For RRF scores, use 0.01-0.03. For cosine similarity, use 0.3-0.7.")
    rag_batch_size: int = Field(default=100, description="Batch size for RAG embedding generation (max 2048 for Azure OpenAI)")
    rag_similarity_auto_adjust_multiplier: float = Field(default=0.8, description="Multiplier for auto-adjusting similarity threshold when too high (0.0-1.0)")
    rag_temporal_decay_days: int = Field(default=7, description="Number of days for temporal decay calculation in RAG")
    
    # Sentiment analysis settings
    sentiment_temperature: float = Field(default=0.2, description="Temperature for sentiment analysis model (0.0-2.0, lower = more consistent)")
    sentiment_max_tokens: int = Field(default=200, description="Maximum tokens for sentiment analysis response")
    sentiment_batch_size: int = Field(default=100, description="Batch size for parallel sentiment analysis")
    sentiment_max_workers: int = Field(default=5, description="Maximum parallel workers for batch sentiment analysis (deprecated, use analysis_parallel_workers)")
    analysis_parallel_workers: int = Field(default=5, description="Maximum parallel workers for sentiment analysis batches")
    analysis_worker_timeout: int = Field(default=180, description="Timeout (seconds) for each parallel sentiment task")
    
    # Retry settings
    retry_max_attempts: int = Field(default=3, description="Maximum retry attempts for API calls")
    retry_initial_delay: float = Field(default=1.0, description="Initial retry delay in seconds")
    retry_max_delay: float = Field(default=10.0, description="Maximum retry delay in seconds")
    retry_exponential_base: float = Field(default=2.0, description="Exponential base for retry backoff")
    
    # Circuit breaker settings
    circuit_breaker_failure_threshold: int = Field(default=5, description="Number of failures before opening circuit breaker")
    circuit_breaker_timeout: int = Field(default=60, description="Seconds to wait before trying half-open state")
    
    # External API timeout settings (for data sources like yfinance, Alpha Vantage, etc.)
    external_api_timeout: int = Field(default=10, description="Default timeout for external API calls in seconds")
    
    # Data collection limits
    news_limit_default: int = Field(default=10, description="Default limit for news articles per source")
    news_title_max_length: int = Field(default=200, description="Maximum length for news article titles")
    news_summary_max_length: int = Field(default=500, description="Maximum length for news article summaries")
    
    # UI display settings
    ui_articles_per_page: int = Field(default=10, description="Number of articles to display per page in UI")
    ui_show_all_articles: bool = Field(default=False, description="Show all articles by default (overrides pagination)")
    
    # API settings
    api_host: str = Field(default="0.0.0.0", description="API server host")
    api_port: int = Field(default=8000, description="API server port")
    api_reload: bool = Field(default=False, description="Enable auto-reload for development")
    api_base_url: str = Field(default="http://localhost:8000", description="API base URL for client connections")
    api_timeout: int = Field(default=180, description="API request timeout in seconds (increased for RAG operations)")
    api_enabled: bool = Field(default=True, description="Enable API mode (dashboard uses API instead of direct services)")
    
    # Vector search settings (Azure AI Search)
    vector_search_m: int = Field(default=4, description="HNSW parameter m (number of bi-directional links)")
    vector_search_ef_construction: int = Field(default=400, description="HNSW parameter efConstruction (size of dynamic candidate list)")
    vector_search_ef_search: int = Field(default=500, description="HNSW parameter efSearch (size of dynamic candidate list for search)")
    
    if PYDANTIC_V2:
        # Pydantic v2 reads from os.environ (already loaded by dotenv above)
        model_config = SettingsConfigDict(
            env_prefix="APP_",
            case_sensitive=False,
            extra="ignore"
        )
        
        @field_validator("log_level")
        @classmethod
        def validate_log_level(cls, v):
            """Validate log level."""
            valid_levels = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
            if v.upper() not in valid_levels:
                raise ValueError(f"Log level must be one of {valid_levels}")
            return v.upper()
    else:
        @validator("log_level")
        def validate_log_level(cls, v):
            """Validate log level."""
            valid_levels = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
            if v.upper() not in valid_levels:
                raise ValueError(f"Log level must be one of {valid_levels}")
            return v.upper()
        
        class Config:
            """Pydantic v1 configuration."""
            env_prefix = "APP_"
            case_sensitive = False


class Settings:
    """
    Main settings class that aggregates all configuration.
    
    This class provides a single point of access for all application settings,
    with proper validation and type checking.
    """
    
    def __init__(self):
        """Initialize settings from environment variables."""
        try:
            # For Pydantic v2, ensure env vars are available
            import os
            if PYDANTIC_V2:
                # Manually construct from environment variables
                self.azure_openai = AzureOpenAISettings(
                    endpoint=os.getenv("AZURE_OPENAI_ENDPOINT", ""),
                    api_key=os.getenv("AZURE_OPENAI_API_KEY", ""),
                    deployment_name=os.getenv("AZURE_OPENAI_DEPLOYMENT_NAME", "gpt-4"),
                    api_version=os.getenv("AZURE_OPENAI_API_VERSION", "2023-05-15"),
                    embedding_deployment=os.getenv("AZURE_OPENAI_EMBEDDING_DEPLOYMENT")
                )
            else:
                self.azure_openai = AzureOpenAISettings()
        except Exception as e:
            raise ValueError(
                f"Azure OpenAI configuration error: {e}. "
                "Please check your .env file."
            )
        
        try:
            import os
            from urllib.parse import urlparse
            if PYDANTIC_V2:
                # Manually construct from environment variables
                redis_host = os.getenv("REDIS_HOST")
                redis_password = os.getenv("REDIS_PASSWORD")
                redis_connection_string = os.getenv("REDIS_CONNECTION_STRING")
                
                # If connection string is provided, parse it
                if redis_connection_string and not (redis_host and redis_password):
                    try:
                        # Parse redis:// or rediss:// connection string
                        # Format: rediss://:password@host:port
                        parsed = urlparse(redis_connection_string)
                        redis_host = parsed.hostname
                        redis_password = parsed.password or (parsed.username if parsed.username else None)
                        redis_port = parsed.port or 6380
                        redis_ssl = parsed.scheme == "rediss"
                    except Exception as parse_err:
                        # If parsing fails, set to None (will disable Redis)
                        redis_host = None
                        redis_password = None
                        redis_port = 6380
                        redis_ssl = True
                
                if redis_host and redis_password:
                    self.redis = RedisSettings(
                        host=redis_host,
                        port=int(os.getenv("REDIS_PORT", str(redis_port if 'redis_port' in locals() else 6380))),
                        password=redis_password,
                        ssl=os.getenv("REDIS_SSL", str(redis_ssl if 'redis_ssl' in locals() else "true")).lower() in ("true", "1", "yes"),
                        connection_string=redis_connection_string
                    )
                else:
                    self.redis = None
            else:
                self.redis = RedisSettings()
        except Exception as e:
            # Redis is optional, so we allow it to fail
            import logging
            logging.getLogger(__name__).warning(f"Redis initialization failed: {e}")
            self.redis = None
        
        try:
            import os
            if PYDANTIC_V2:
                # Azure AI Search is optional
                search_endpoint = os.getenv("AZURE_AI_SEARCH_ENDPOINT")
                search_api_key = os.getenv("AZURE_AI_SEARCH_API_KEY")
                if search_endpoint and search_api_key:
                    self.azure_ai_search = AzureAISearchSettings(
                        endpoint=search_endpoint,
                        api_key=search_api_key,
                        index_name=os.getenv("AZURE_AI_SEARCH_INDEX_NAME", "stock-articles"),
                        semantic_config_name=os.getenv("AZURE_AI_SEARCH_SEMANTIC_CONFIG_NAME"),
                        vector_dimension=int(os.getenv("AZURE_AI_SEARCH_VECTOR_DIMENSION", "1536"))
                    )
                else:
                    self.azure_ai_search = None
            else:
                self.azure_ai_search = AzureAISearchSettings()
        except Exception as e:
            # Azure AI Search is optional, so we allow it to fail
                self.azure_ai_search = None
        
        try:
            import os
            if PYDANTIC_V2:
                self.data_sources = DataSourceSettings(
                    reddit_client_id=os.getenv("DATA_SOURCE_REDDIT_CLIENT_ID"),
                    reddit_client_secret=os.getenv("DATA_SOURCE_REDDIT_CLIENT_SECRET"),
                    reddit_user_agent=os.getenv("DATA_SOURCE_REDDIT_USER_AGENT", "stock-sentiment-analysis/1.0"),
                    reddit_enabled=os.getenv("DATA_SOURCE_REDDIT_ENABLED", "false").lower() in ("true", "1", "yes"),
                    reddit_limit=int(os.getenv("DATA_SOURCE_REDDIT_LIMIT", "20")),
                    alpha_vantage_api_key=os.getenv("DATA_SOURCE_ALPHA_VANTAGE_API_KEY"),
                    alpha_vantage_enabled=os.getenv("DATA_SOURCE_ALPHA_VANTAGE_ENABLED", "false").lower() in ("true", "1", "yes"),
                    finnhub_api_key=os.getenv("DATA_SOURCE_FINNHUB_API_KEY"),
                    finnhub_enabled=os.getenv("DATA_SOURCE_FINNHUB_ENABLED", "false").lower() in ("true", "1", "yes")
                )
            else:
                self.data_sources = DataSourceSettings()
        except Exception as e:
            # Data sources are optional
            # Note: logger not available here, use print or skip logging
            self.data_sources = DataSourceSettings()
        
        self.app = AppSettings()
        
        # Override api_base_url with SENTIMENT_API_URL if set (for Kubernetes deployments)
        import os
        sentiment_api_url = os.getenv("SENTIMENT_API_URL")
        if sentiment_api_url:
            self.app.api_base_url = sentiment_api_url.rstrip('/')
    
    def is_redis_available(self) -> bool:
        """Check if Redis is configured and available."""
        return self.redis is not None
    
    def is_rag_available(self) -> bool:
        """Check if RAG is configured and available."""
        return (
            self.azure_openai.embedding_deployment is not None
            and self.azure_openai.embedding_deployment != ""
        )
    
    def is_azure_openai_available(self) -> bool:
        """Check if Azure OpenAI is configured and available."""
        return (
            self.azure_openai.endpoint is not None
            and self.azure_openai.endpoint != ""
            and self.azure_openai.api_key is not None
            and self.azure_openai.api_key != ""
            and self.azure_openai.deployment_name is not None
            and self.azure_openai.deployment_name != ""
        )
    
    def is_azure_ai_search_available(self) -> bool:
        """Check if Azure AI Search is configured and available."""
        return (
            self.azure_ai_search is not None
            and self.azure_ai_search.endpoint is not None
            and self.azure_ai_search.endpoint != ""
            and self.azure_ai_search.api_key is not None
            and self.azure_ai_search.api_key != ""
        )


# Global settings instance
_settings: Optional[Settings] = None


def get_settings() -> Settings:
    """
    Get the global settings instance (singleton pattern).
    
    Returns:
        Settings: The application settings instance
        
    Raises:
        ValueError: If required configuration is missing
    """
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings

