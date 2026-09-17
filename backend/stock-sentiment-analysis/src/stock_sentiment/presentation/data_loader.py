"""
Data loading and processing logic (API-driven).

This module now uses the API client instead of calling services directly,
ensuring the dashboard is API-driven.
"""

import streamlit as st
import time
from typing import Optional

from ..utils.logger import get_logger
from ..utils.ui_helpers import show_toast, get_error_recovery_ui
from .api_client import SentimentAPIClient

logger = get_logger(__name__)


def load_stock_data(
    symbol: str,
    api_client: SentimentAPIClient,
    settings
) -> bool:
    """
    Load and process stock data with sentiment analysis using the API.
    
    Args:
        symbol: Stock symbol to analyze
        api_client: SentimentAPIClient instance
        settings: Application settings
        
    Returns:
        True if successful, False otherwise
    """
    logger.info(f"=== Starting data load for {symbol} (API-driven) ===")
    
    # Multi-step progress bar for better UX
    progress_bar = st.progress(0)
    status_text = st.empty()
    
    try:
        # Step 1: Preparing request (20%)
        status_text.text("📊 Preparing analysis request...")
        progress_bar.progress(0.2)
        
        logger.info(f"[{symbol}] Step 1: Preparing API request...")
        
        # Get data source filters from UI
        data_source_filters = None
        sources_param = None
        if 'search_filters' in st.session_state and 'data_sources' in st.session_state.search_filters:
            data_source_filters = st.session_state.search_filters.get('data_sources')
            enabled_sources = [k for k, v in data_source_filters.items() if v]
            if enabled_sources:
                sources_param = ','.join(enabled_sources)
            else:
                # Fail-safe: at least yfinance should be requested
                sources_param = 'yfinance'
            logger.info(f"[{symbol}] Data source filters applied - Enabled: {', '.join(enabled_sources) if enabled_sources else 'yfinance'}")
        
        # Get cache setting from UI (if available in sidebar)
        cache_enabled = settings.app.cache_sentiment_enabled
        
        # Step 2: Calling API (40%)
        status_text.text("🌐 Calling sentiment analysis API...")
        progress_bar.progress(0.4)
        
        logger.info(f"[{symbol}] Step 2: Calling API with detailed=true...")
        
        # Call API with detailed response
        api_response = api_client.get_sentiment(
            symbol=symbol,
            detailed=True,
            sources=sources_param,
            cache_enabled=cache_enabled
        )
        
        logger.info(f"[{symbol}] ✅ Received API response")
        
        # Step 3: Processing response (60%)
        status_text.text("📊 Processing API response...")
        progress_bar.progress(0.6)
        
        # Extract data from API response
        data = {
            'price_data': api_response.get('price_data', {}),
            'news': api_response.get('news', []),
            'social_media': []  # Social media is included in news or separate
        }
        
        # Get sentiment scores
        news_sentiments = api_response.get('news_sentiments', [])
        social_sentiments = api_response.get('social_sentiments', [])
        
        # Get operation summary
        operation_summary = api_response.get('operation_summary', {})
        
        # Clear any previous errors
        if symbol in st.session_state.data_errors:
            del st.session_state.data_errors[symbol]
        
        # Step 4: Storing in session state (80%)
        status_text.text("💾 Storing results...")
        progress_bar.progress(0.8)
        
        st.session_state.data = data
        st.session_state.symbol = symbol
        st.session_state.news_sentiments = news_sentiments
        st.session_state.social_sentiments = social_sentiments
        st.session_state.operation_summary = operation_summary
        
        # Reset pagination when new data is loaded
        st.session_state.article_page = 1
        st.session_state.show_all_articles = False
        
        # Show data source breakdown
        if data.get('news'):
            source_breakdown = {}
            for article in data['news']:
                source = article.get('source', 'Unknown')
                # Categorize by data source
                if 'Alpha Vantage' in source:
                    source_breakdown['Alpha Vantage'] = source_breakdown.get('Alpha Vantage', 0) + 1
                elif 'Finnhub' in source:
                    source_breakdown['Finnhub'] = source_breakdown.get('Finnhub', 0) + 1
                elif 'Reddit' in source or 'r/' in source:
                    source_breakdown['Reddit'] = source_breakdown.get('Reddit', 0) + 1
                else:
                    source_breakdown['Yahoo Finance'] = source_breakdown.get('Yahoo Finance', 0) + 1
            
            if source_breakdown:
                breakdown_text = " | ".join([f"{k}: {v}" for k, v in source_breakdown.items()])
                logger.info(f"App: Data source breakdown - {breakdown_text}")
        
        # Step 5: Complete (100%)
        status_text.text("✅ Analysis complete!")
        progress_bar.progress(1.0)
        
        # Log final summary
        logger.info(f"[{symbol}] === Operation Summary (from API) ===")
        logger.info(f"[{symbol}] Redis used: {operation_summary.get('redis_used', False)}")
        logger.info(f"[{symbol}]   - Stock cached: {operation_summary.get('stock_cached', False)}")
        logger.info(f"[{symbol}]   - News cached: {operation_summary.get('news_cached', False)}")
        logger.info(f"[{symbol}]   - Sentiment: {operation_summary.get('sentiment_cache_hits', 0)} hits, {operation_summary.get('sentiment_cache_misses', 0)} misses")
        logger.info(f"[{symbol}] RAG used: {operation_summary.get('rag_used', False)}")
        if operation_summary.get('rag_used'):
            logger.info(f"[{symbol}]   - RAG queries: {operation_summary.get('rag_queries', 0)}")
            logger.info(f"[{symbol}]   - Articles found: {operation_summary.get('rag_articles_found', 0)}")
        logger.info(f"[{symbol}] Articles stored in RAG: {operation_summary.get('articles_stored', 0)}")
        logger.info(f"[{symbol}] === End Summary ===")
        
        # Show success toast
        time.sleep(0.3)  # Brief pause to show completion
        progress_bar.empty()
        status_text.empty()
        
        show_toast(f"✅ Successfully analyzed {symbol}!", "success")
        
    except TimeoutError as e:
        progress_bar.empty()
        status_text.empty()
        
        error_msg = str(e)
        logger.error(f"API timeout error: {e}")
        
        st.error(f"⏱️ **Request Timeout**")
        st.warning(error_msg)
        st.info(
            "💡 **Tips to reduce analysis time:**\n"
            "- Enable sentiment caching in the sidebar\n"
            "- Reduce the number of data sources\n"
            "- The analysis is processing many articles with RAG, which takes time"
        )
        
        st.session_state.data_errors[symbol] = error_msg
        
        if get_error_recovery_ui(error_msg, retry_key=f"retry_api_{symbol}"):
            st.rerun()
        
        st.stop()
        return False
        
    except ConnectionError as e:
        progress_bar.empty()
        status_text.empty()
        
        error_msg = str(e)
        logger.error(f"API connection error: {e}")
        
        st.error(f"🔌 **API Connection Error**")
        st.warning(error_msg)
        st.info(
            "💡 **To fix this:**\n"
            f"- Ensure the API server is running: `python -m stock_sentiment.api`\n"
            f"- Check the API URL in settings: {api_client.base_url if api_client else 'N/A'}\n"
            f"- Verify the API is accessible at the configured URL"
        )
        
        st.session_state.data_errors[symbol] = error_msg
        
        if get_error_recovery_ui(error_msg, retry_key=f"retry_api_{symbol}"):
            st.rerun()
        
        st.stop()
        return False
        
    except Exception as e:
        progress_bar.empty()
        status_text.empty()
        
        error_msg = f"Failed to analyze sentiment: {str(e)}"
        logger.error(f"API call error: {e}", exc_info=True)
        
        st.error(f"❌ **Analysis Error**")
        st.warning(error_msg)
        
        st.session_state.data_errors[symbol] = error_msg
        
        if get_error_recovery_ui(error_msg, retry_key=f"retry_api_{symbol}"):
            st.rerun()
        
        # Show partial data if available
        if 'data' in st.session_state and st.session_state.data:
            st.warning("⚠️ Some data was collected but sentiment analysis failed. Showing available data.")
        else:
            st.stop()
        return False
    
    st.session_state.load_data = False
    st.session_state.title_shown = False  # Show title again after loading
    # Force rerun to update UI with new data
    st.rerun()
    return True
