"""
Sidebar component for the Streamlit application.
"""

import streamlit as st
from typing import Optional, Any
from datetime import datetime, timedelta

from ...config.settings import get_settings
from ...utils.logger import get_logger
from ..api_client import SentimentAPIClient

logger = get_logger(__name__)


def render_sidebar(
    redis_cache: Optional[Any],
    rag_service: Optional[Any],
    analyzer: Optional[Any],
    settings,
    api_client: Optional[SentimentAPIClient] = None
) -> str:
    """
    Render the sidebar with all controls and status indicators.
    
    Args:
        redis_cache: RedisCache instance (for status display)
        rag_service: RAGService instance (for status display)
        analyzer: SentimentAnalyzer instance (for status display)
        settings: Application settings
        api_client: API client instance (for API mode)
        
    Returns:
        Selected stock symbol
    """
    with st.sidebar:
        # Logo/Header section
        st.markdown(
            """
            <div style='text-align: center; padding: 1rem 0; border-bottom: 2px solid #e0e0e0; margin-bottom: 1.5rem;'>
                <h2 style='color: #1f77b4; margin: 0;'>⚙️ Configuration</h2>
            </div>
            """,
            unsafe_allow_html=True
        )
        
        # Stock symbol input
        symbol = st.text_input(
            "📊 Stock Symbol",
            value=st.session_state.symbol,
            key="stock_symbol",
            help="Enter a valid stock ticker symbol (e.g., AAPL, MSFT, GOOGL)"
        ).upper()
        
        # System status indicators
        _render_system_status(api_client, redis_cache, rag_service, settings)
        
        st.markdown("<br>", unsafe_allow_html=True)
        
        # Search filters
        _render_search_filters(settings)
        
        # Sentiment cache controls
        _render_sentiment_cache_controls(settings)
        
        # Load button
        if st.button("🚀 Load Data", type="primary", width='stretch'):
            st.session_state.load_data = True
            st.session_state.symbol = symbol
            st.session_state.title_shown = False
        
        st.markdown("---")
        
        # Connection details
        _render_connection_details(api_client, redis_cache, rag_service, settings)
        
        st.markdown("---")
        
        # Summary log
        _render_summary_log()
        
        # Cache management
        _render_cache_management(api_client, redis_cache, settings)
        
        st.markdown("---")
        st.markdown(
            """
            <div style='text-align: center; padding: 1rem; color: #7f8c8d; font-size: 0.85rem;'>
                Powered by Azure OpenAI<br>
                with RAG & Redis Caching
            </div>
            """,
            unsafe_allow_html=True
        )
    
    return symbol


def _render_system_status(
    api_client: Optional[SentimentAPIClient],
    redis_cache: Optional[Any],
    rag_service: Optional[Any],
    settings
):
    """Render system status indicators."""
    st.markdown("### 🔌 System Status")
    
    # Use API client if available, otherwise fall back to direct service checks
    if api_client and settings.app.api_enabled:
        try:
            status_info = api_client.get_system_status()
            
            # Always show Redis and RAG status prominently
            status_col1, status_col2 = st.columns(2)
            
            with status_col1:
                redis_info = status_info.get('redis', {})
                redis_connected = redis_info.get('connected', False)
                
                if redis_connected:
                    st.markdown(
                        """
                        <div style='background: #d4edda; color: #155724; padding: 0.75rem; 
                                    border-radius: 8px; text-align: center; font-weight: 600;'>
                            ✅ Redis
                        </div>
                        """,
                        unsafe_allow_html=True
                    )
                else:
                    st.markdown(
                        """
                        <div style='background: #f8d7da; color: #721c24; padding: 0.75rem; 
                                    border-radius: 8px; text-align: center; font-weight: 600;'>
                            ⚠️ Redis
                        </div>
                        """,
                        unsafe_allow_html=True
                    )
            
            with status_col2:
                rag_info = status_info.get('rag', {})
                rag_enabled = rag_info.get('embeddings_enabled', False)
                
                if rag_enabled:
                    st.markdown(
                        """
                        <div style='background: #d4edda; color: #155724; padding: 0.75rem; 
                                    border-radius: 8px; text-align: center; font-weight: 600;'>
                            ✅ RAG
                        </div>
                        """,
                        unsafe_allow_html=True
                    )
                else:
                    st.markdown(
                        """
                        <div style='background: #fff3cd; color: #856404; padding: 0.75rem; 
                                    border-radius: 8px; text-align: center; font-weight: 600;'>
                            ⚠️ RAG
                        </div>
                        """,
                        unsafe_allow_html=True
                    )
            
            # Show API health status
            try:
                health = api_client.get_health()
                status = health.get('status', 'unknown')
                
                if status == 'healthy':
                    st.success("✅ API: Healthy")
                elif status == 'degraded':
                    st.warning("⚠️ API: Degraded")
                else:
                    st.error("❌ API: Unhealthy")
                
                # Show service details in expander
                with st.expander("Service Details"):
                    services = health.get('services', {})
                    for service_name, service_status in services.items():
                        if service_status == 'available':
                            st.success(f"✅ {service_name.title()}")
                        elif service_status == 'not_configured':
                            st.info(f"ℹ️ {service_name.title()} (not configured)")
                        else:
                            st.error(f"❌ {service_name.title()}: {service_status}")
            except Exception as e:
                logger.error(f"Error checking API health: {e}")
                st.error("❌ API: Unreachable")
                st.warning("Please ensure the API server is running")
                
        except Exception as e:
            logger.error(f"Error getting system status: {e}")
            st.error("❌ Failed to get system status")
    else:
        # Fallback to direct service checks (non-API mode)
        status_col1, status_col2 = st.columns(2)
        
        with status_col1:
            redis_connected = False
            if redis_cache and redis_cache.client:
                try:
                    redis_cache.client.ping()
                    redis_connected = True
                except Exception:
                    pass
            
            if redis_connected:
                st.markdown(
                    """
                    <div style='background: #d4edda; color: #155724; padding: 0.75rem; 
                                border-radius: 8px; text-align: center; font-weight: 600;'>
                        ✅ Redis
                    </div>
                    """,
                    unsafe_allow_html=True
                )
            else:
                st.markdown(
                    """
                    <div style='background: #f8d7da; color: #721c24; padding: 0.75rem; 
                                border-radius: 8px; text-align: center; font-weight: 600;'>
                        ⚠️ Redis
                    </div>
                    """,
                    unsafe_allow_html=True
                )
        
        with status_col2:
            rag_enabled = False
            if rag_service:
                try:
                    rag_enabled = rag_service.embeddings_enabled
                except Exception:
                    pass
            
            if rag_enabled:
                st.markdown(
                    """
                    <div style='background: #d4edda; color: #155724; padding: 0.75rem; 
                                border-radius: 8px; text-align: center; font-weight: 600;'>
                        ✅ RAG
                    </div>
                    """,
                    unsafe_allow_html=True
                )
            else:
                st.markdown(
                    """
                    <div style='background: #fff3cd; color: #856404; padding: 0.75rem; 
                                border-radius: 8px; text-align: center; font-weight: 600;'>
                        ⚠️ RAG
                    </div>
                    """,
                    unsafe_allow_html=True
                )


def _render_search_filters(settings):
    """Render search filters section."""
    # Initialize search filters if not exists
    if 'search_filters' not in st.session_state:
        st.session_state.search_filters = {
            "date_range": None,
            "sources": None,
            "exclude_unknown": True,
            "days_back": None,
            "data_sources": {
                "yfinance": True,
                "alpha_vantage": settings.data_sources.alpha_vantage_enabled,
                "finnhub": settings.data_sources.finnhub_enabled,
                "reddit": settings.data_sources.reddit_enabled
            }
        }
    
    # Initialize data source filters based on settings
    if 'data_sources' not in st.session_state.search_filters:
        st.session_state.search_filters["data_sources"] = {
            "yfinance": True,
            "alpha_vantage": settings.data_sources.alpha_vantage_enabled,
            "finnhub": settings.data_sources.finnhub_enabled,
            "reddit": settings.data_sources.reddit_enabled
        }
    
    # Search Filters Section
    with st.expander("🔍 Search Filters", expanded=False):
        # Date Range Filter
        st.subheader("📅 Date Range")
        use_date_filter = st.checkbox("Filter by date", value=False, key="use_date_filter")
        
        date_range = None
        days_back = None
        date_option = None
        
        if use_date_filter:
            date_option = st.radio(
                "Date range",
                ["Last 3 days", "Last 7 days", "Last 30 days", "Custom range"],
                horizontal=False,
                key="date_option"
            )
            
            if date_option == "Custom range":
                start_date = st.date_input(
                    "Start date",
                    value=datetime.now().date() - timedelta(days=7),
                    key="start_date"
                )
                end_date = st.date_input(
                    "End date",
                    value=datetime.now().date(),
                    key="end_date"
                )
                if start_date and end_date:
                    date_range = (
                        datetime.combine(start_date, datetime.min.time()),
                        datetime.combine(end_date, datetime.max.time())
                    )
            else:
                days_map = {"Last 3 days": 3, "Last 7 days": 7, "Last 30 days": 30}
                days_back = days_map[date_option]
        
        # Source Filter
        st.subheader("📰 News Sources")
        use_source_filter = st.checkbox("Filter by source", value=False, key="use_source_filter")
        
        selected_sources = None
        exclude_unknown = st.checkbox("Exclude 'Unknown' sources", value=True, key="exclude_unknown")
        
        if use_source_filter:
            source_options = ["Trusted Only", "Custom"]
            source_choice = st.radio(
                "Source filter",
                source_options,
                horizontal=False,
                key="source_choice"
            )
            
            if source_choice == "Trusted Only":
                selected_sources = ["Reuters", "Bloomberg", "CNBC", "Wall Street Journal"]
            elif source_choice == "Custom":
                available_sources = ["Reuters", "Bloomberg", "CNBC", "Wall Street Journal", "Yahoo Finance", "MarketWatch"]
                if st.session_state.data and 'news' in st.session_state.data:
                    news_sources = set()
                    for article in st.session_state.data.get('news', []):
                        source = article.get('source', '')
                        if source and source != 'Unknown':
                            news_sources.add(source)
                    if news_sources:
                        available_sources = sorted(list(news_sources))
                
                selected_sources = st.multiselect(
                    "Select sources",
                    available_sources,
                    default=available_sources[:3] if available_sources else [],
                    key="selected_sources"
                )
        
        # Data Source Filter
        st.subheader("📡 Data Sources")
        st.markdown("Enable/disable data sources to see how sentiment varies by source type")
        
        data_source_filters = st.session_state.search_filters.get("data_sources", {
            "yfinance": True,
            "alpha_vantage": settings.data_sources.alpha_vantage_enabled,
            "finnhub": settings.data_sources.finnhub_enabled,
            "reddit": settings.data_sources.reddit_enabled
        })
        
        # Show available sources based on configuration
        col1, col2 = st.columns(2)
        
        with col1:
            st.checkbox(
                "Yahoo Finance (yfinance)",
                value=True,
                disabled=True,
                help="Primary data source - always enabled"
            )
            
            alpha_vantage_available = settings.data_sources.alpha_vantage_enabled and settings.data_sources.alpha_vantage_api_key
            data_source_filters["alpha_vantage"] = st.checkbox(
                "Alpha Vantage",
                value=data_source_filters.get("alpha_vantage", alpha_vantage_available),
                disabled=not alpha_vantage_available,
                help="Financial news API (500 calls/day free tier)" if alpha_vantage_available else "Not configured - set DATA_SOURCE_ALPHA_VANTAGE_API_KEY in .env",
                key="filter_alpha_vantage"
            )
        
        with col2:
            finnhub_available = settings.data_sources.finnhub_enabled and settings.data_sources.finnhub_api_key
            data_source_filters["finnhub"] = st.checkbox(
                "Finnhub",
                value=data_source_filters.get("finnhub", finnhub_available),
                disabled=not finnhub_available,
                help="Company news API (60 calls/minute free tier)" if finnhub_available else "Not configured - set DATA_SOURCE_FINNHUB_API_KEY in .env",
                key="filter_finnhub"
            )
            
            reddit_available = settings.data_sources.reddit_enabled and settings.data_sources.reddit_client_id
            data_source_filters["reddit"] = st.checkbox(
                "Reddit",
                value=data_source_filters.get("reddit", reddit_available),
                disabled=not reddit_available,
                help="Social media sentiment from Reddit posts" if reddit_available else "Not configured - set DATA_SOURCE_REDDIT_CLIENT_ID in .env",
                key="filter_reddit"
            )
        
        # Store filters in session state
        st.session_state.search_filters = {
            "date_range": date_range if use_date_filter else None,
            "sources": selected_sources if use_source_filter else None,
            "exclude_unknown": exclude_unknown,
            "days_back": days_back if use_date_filter and date_option and date_option != "Custom range" else None,
            "data_sources": data_source_filters
        }


def _render_sentiment_cache_controls(settings):
    """Render sentiment cache TTL controls."""
    with st.expander("⚙️ Sentiment Cache Settings", expanded=False):
        st.markdown("**Control sentiment caching to test RAG functionality**")
        st.markdown("*When sentiment cache is disabled, RAG will be used for every analysis*")
        
        # Enable/disable sentiment cache
        cache_enabled = st.checkbox(
            "Enable Sentiment Caching",
            value=settings.app.cache_sentiment_enabled,
            key="sentiment_cache_enabled",
            help="Disable to force RAG usage for every sentiment analysis"
        )
        
        # Update settings if changed
        if cache_enabled != settings.app.cache_sentiment_enabled:
            settings.app.cache_sentiment_enabled = cache_enabled
            logger.info(f"Sentiment cache {'enabled' if cache_enabled else 'disabled'}")
        
        if cache_enabled:
            # TTL control
            ttl_hours = settings.app.cache_ttl_sentiment / 3600
            new_ttl_hours = st.slider(
                "Cache TTL (hours)",
                min_value=0.1,
                max_value=168.0,  # 7 days
                value=float(ttl_hours),
                step=0.1,
                format="%.1f",
                key="sentiment_cache_ttl",
                help="Time-to-live for sentiment cache. Lower values = more RAG usage"
            )
            
            new_ttl_seconds = int(new_ttl_hours * 3600)
            if new_ttl_seconds != settings.app.cache_ttl_sentiment:
                settings.app.cache_ttl_sentiment = new_ttl_seconds
                logger.info(f"Sentiment cache TTL updated to {new_ttl_hours:.1f} hours ({new_ttl_seconds}s)")
            
            st.info(f"Current TTL: {ttl_hours:.1f} hours ({settings.app.cache_ttl_sentiment}s)")
        else:
            st.warning("⚠️ Sentiment caching is disabled - RAG will be used for all analyses")


def _render_connection_details(
    api_client: Optional[SentimentAPIClient],
    redis_cache: Optional[Any],
    rag_service: Optional[Any],
    settings
):
    """Render connection details for troubleshooting."""
    with st.expander("🔍 Connection Details", expanded=False):
        # If API mode, show API connection details
        if api_client and settings.app.api_enabled:
            st.markdown("### API Connection")
            st.code(f"API URL: {api_client.base_url}")
            try:
                health = api_client.get_health()
                st.success(f"✅ API Status: {health.get('status', 'unknown')}")
                st.json(health.get('services', {}))
                
                # Show system status details
                status_info = api_client.get_system_status()
                st.markdown("### System Status Details")
                st.json(status_info)
            except Exception as e:
                st.error(f"❌ API: Connection failed - {e}")
                st.info("Please ensure the API server is running at the configured URL")
        else:
            # Show direct service connections (fallback for non-API mode)
            st.markdown("### Redis Connection")
            if redis_cache:
                if redis_cache.client:
                    try:
                        redis_cache.client.ping()
                        st.success("✅ Redis: Connected and responding")
                        try:
                            info = redis_cache.client.info('server')
                            st.code(f"Redis Version: {info.get('redis_version', 'Unknown')}")
                        except:
                            pass
                    except Exception as e:
                        st.error(f"❌ Redis: Connection failed - {e}")
                else:
                    st.warning("⚠️ Redis: Client not initialized")
                    if settings.is_redis_available():
                        st.info("Redis config exists but connection failed. Check your .env file.")
                    else:
                        st.info("Redis not configured in .env file")
            else:
                st.warning("⚠️ Redis: Cache instance not created")
            
            st.markdown("### RAG Service")
            if rag_service:
                st.success(f"✅ RAG Service: Initialized")
                st.code(f"Embeddings Enabled: {rag_service.embeddings_enabled}")
                if hasattr(rag_service, 'embedding_deployment'):
                    st.code(f"Embedding Model: {rag_service.embedding_deployment or 'Not configured'}")
            else:
                st.warning("⚠️ RAG Service: Not initialized")
                if settings.is_rag_available():
                    st.info("RAG config exists but service failed to initialize. Check embedding deployment.")
                else:
                    st.info("RAG not configured (missing embedding deployment in .env)")
        
        st.markdown("### Configuration Check")
        st.code(f"Redis Available: {settings.is_redis_available()}")
        st.code(f"RAG Available: {settings.is_rag_available()}")
        st.code(f"Azure OpenAI Available: {settings.is_azure_openai_available()}")


def _render_summary_log():
    """Render summary log of last operation."""
    if 'operation_summary' in st.session_state and st.session_state.operation_summary:
        summary = st.session_state.operation_summary
        st.markdown("### 📋 Operation Summary (Last Request)")
        
        with st.expander("View Details", expanded=True):
            # Redis usage
            redis_used = summary.get('redis_used', False)
            redis_status = "✅ Used" if redis_used else "❌ Not Used"
            st.markdown(f"**Redis Cache:** {redis_status}")
            if redis_used:
                st.markdown(f"  - Stock data: {'✅ Cached' if summary.get('stock_cached') else '🔄 Fresh'}")
                st.markdown(f"  - News data: {'✅ Cached' if summary.get('news_cached') else '🔄 Fresh'}")
                sentiment_hits = summary.get('sentiment_cache_hits', 0)
                sentiment_misses = summary.get('sentiment_cache_misses', 0)
                total_sentiment = sentiment_hits + sentiment_misses
                if total_sentiment > 0:
                    hit_rate = (sentiment_hits / total_sentiment) * 100
                    st.markdown(f"  - Sentiment: {sentiment_hits} cached, {sentiment_misses} fresh ({hit_rate:.0f}% hit rate)")
            
            # RAG usage
            rag_used = summary.get('rag_used', False)
            rag_status = "✅ Used" if rag_used else "❌ Not Used"
            st.markdown(f"**RAG Service:** {rag_status}")
            if rag_used:
                rag_queries = summary.get('rag_queries', 0)
                rag_articles_found = summary.get('rag_articles_found', 0)
                st.markdown(f"  - RAG queries made: {rag_queries}")
                st.markdown(f"  - Articles retrieved: {rag_articles_found}")
            
            # Articles stored
            articles_stored = summary.get('articles_stored', 0)
            if articles_stored > 0:
                st.markdown(f"**Articles Stored in RAG:** {articles_stored}")
            
            # Summary
            st.markdown("---")
            if redis_used and rag_used:
                st.success("✅ Both Redis and RAG were used in this operation")
            elif redis_used:
                st.info("ℹ️ Only Redis was used (sentiment was cached)")
            elif rag_used:
                st.info("ℹ️ Only RAG was used (sentiment cache disabled or expired)")
            else:
                st.warning("⚠️ Neither Redis nor RAG was used")


def _render_cache_management(
    api_client: Optional[SentimentAPIClient],
    redis_cache: Optional[Any],
    settings
):
    """Render cache management buttons."""
    cache_col1, cache_col2 = st.columns(2)
    with cache_col1:
        if st.button("🔄 Reset Cache Stats", width='stretch', help="Reset cache statistics counters (hits/misses)"):
            if api_client and settings.app.api_enabled:
                try:
                    success = api_client.reset_cache_stats()
                    if success:
                        st.success("Cache statistics reset!")
                        st.rerun()
                    else:
                        st.error("Failed to reset cache statistics")
                except Exception as e:
                    st.error(f"Error resetting cache stats: {e}")
            elif redis_cache:
                redis_cache.reset_cache_stats()
                st.success("Cache statistics reset!")
                st.rerun()
            else:
                st.warning("Cache management not available")
    
    with cache_col2:
        if 'confirm_clear_cache' not in st.session_state:
            st.session_state.confirm_clear_cache = False
        
        if st.session_state.confirm_clear_cache:
            confirm_col1, confirm_col2 = st.columns(2)
            with confirm_col1:
                if st.button("✅ Confirm", width='stretch', type="primary"):
                    if api_client and settings.app.api_enabled:
                        try:
                            result = api_client.clear_cache(confirm=True)
                            if result.get('success'):
                                st.success("All cache data cleared!")
                                st.session_state.confirm_clear_cache = False
                                st.rerun()
                            else:
                                st.error("Failed to clear cache. Check logs for details.")
                                st.session_state.confirm_clear_cache = False
                        except Exception as e:
                            st.error(f"Error clearing cache: {e}")
                            st.session_state.confirm_clear_cache = False
                    elif redis_cache and redis_cache.client:
                        if redis_cache.clear_all_cache():
                            st.success("All cache data cleared!")
                            st.session_state.confirm_clear_cache = False
                            st.rerun()
                        else:
                            st.error("Failed to clear cache. Check logs for details.")
                            st.session_state.confirm_clear_cache = False
                    else:
                        st.warning("Cache management not available")
                        st.session_state.confirm_clear_cache = False
            with confirm_col2:
                if st.button("❌ Cancel", width='stretch'):
                    st.session_state.confirm_clear_cache = False
                    st.rerun()
        else:
            if st.button("🗑️ Clear All Cache", width='stretch', help="Clear all cached data from Redis"):
                if (api_client and settings.app.api_enabled) or (redis_cache and redis_cache.client):
                    st.session_state.confirm_clear_cache = True
                    st.warning("⚠️ This will delete ALL cached data. Please confirm.")
                    st.rerun()
                else:
                    st.warning("Cache management not available")

