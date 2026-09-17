"""
Orchestrator service for sentiment analysis.

This module provides the core sentiment analysis logic without Streamlit dependencies,
making it reusable for both the Streamlit dashboard and REST API.
"""

from datetime import datetime
from typing import Dict, List, Optional, Any
import time
from ..config.settings import Settings
from ..services.collector import StockDataCollector
from ..services.sentiment import SentimentAnalyzer
from ..services.rag import RAGService
from ..services.cache import RedisCache
from ..utils.logger import get_logger

logger = get_logger(__name__)


def get_aggregated_sentiment(
    symbol: str,
    collector: StockDataCollector,
    analyzer: SentimentAnalyzer,
    rag_service: Optional[RAGService] = None,
    redis_cache: Optional[RedisCache] = None,
    settings: Optional[Settings] = None,
    data_source_filters: Optional[Dict[str, bool]] = None,
    return_detailed: bool = False
) -> Dict[str, Any]:
    """
    Get aggregated sentiment analysis for a stock symbol.
    
    This function orchestrates the complete sentiment analysis pipeline:
    1. Collect stock data and news from multiple sources
    2. Store articles in RAG for context retrieval
    3. Analyze sentiment for all articles with RAG context
    4. Aggregate sentiment scores
    
    Args:
        symbol: Stock symbol to analyze (e.g., "AAPL")
        collector: StockDataCollector instance
        analyzer: SentimentAnalyzer instance
        rag_service: RAGService instance (optional)
        redis_cache: RedisCache instance (optional)
        settings: Application settings (optional)
        data_source_filters: Dictionary of data source enable/disable flags
            Example: {"yfinance": True, "alpha_vantage": False, "finnhub": True, "reddit": False}
        return_detailed: If True, also return raw data and individual sentiment scores
            (for dashboard use). Default: False (for API use).
    
    Returns:
        Dictionary containing:
            - symbol: Stock symbol
            - positive: Aggregated positive sentiment score (0.0 to 1.0)
            - negative: Aggregated negative sentiment score (0.0 to 1.0)
            - neutral: Aggregated neutral sentiment score (0.0 to 1.0)
            - net_sentiment: Net sentiment (positive - negative, -1.0 to 1.0)
            - dominant_sentiment: "positive", "negative", or "neutral"
            - sources_analyzed: Number of articles analyzed
            - timestamp: ISO format timestamp
            - operation_summary: Dictionary with operation statistics
            - data: (if return_detailed=True) Raw stock data and news articles
            - news_sentiments: (if return_detailed=True) Individual sentiment scores for news articles
            - social_sentiments: (if return_detailed=True) Individual sentiment scores for social media posts
    """
    if not settings:
        from ..config.settings import get_settings
        settings = get_settings()
    
    # Initialize operation summary for logging
    operation_summary = {
        'redis_used': False,
        'stock_cached': False,
        'news_cached': False,
        'sentiment_cache_hits': 0,
        'sentiment_cache_misses': 0,
        'rag_used': False,
        'rag_queries': 0,
        'rag_articles_found': 0,
        'articles_stored': 0
    }
    
    start_time = time.time()
    logger.info("=" * 80)
    logger.info(f"🚀 DEMO: Starting sentiment analysis for {symbol}")
    logger.info("=" * 80)
    
    # Log configuration
    logger.info(f"📋 Configuration:")
    logger.info(f"   • Sentiment cache: {'✅ ENABLED' if settings.app.cache_sentiment_enabled else '❌ DISABLED (RAG will be used)'}")
    if settings.app.cache_sentiment_enabled:
        logger.info(f"   • Cache TTL: {settings.app.cache_ttl_sentiment}s ({settings.app.cache_ttl_sentiment/3600:.1f} hours)")
    logger.info(f"   • Parallel workers: {settings.app.analysis_parallel_workers}")
    logger.info(f"   • Worker timeout: {settings.app.analysis_worker_timeout}s")
    
    # Log data source filters
    if data_source_filters:
        enabled_sources = [k for k, v in data_source_filters.items() if v]
        disabled_sources = [k for k, v in data_source_filters.items() if not v]
        logger.info(f"📡 Data Sources:")
        if enabled_sources:
            logger.info(f"   • ✅ Enabled: {', '.join(enabled_sources)}")
        if disabled_sources:
            logger.info(f"   • ❌ Disabled: {', '.join(disabled_sources)}")
    else:
        logger.info(f"📡 Data Sources: Using all enabled sources from settings")
    
    logger.info("-" * 80)
    
    try:
        # Step 1: Collect stock data and news
        step1_start = time.time()
        logger.info(f"📊 STEP 1: Collecting stock data and news for {symbol}...")
        
        # Check cache status BEFORE collection for accurate tracking
        if redis_cache:
            logger.info(f"   🔍 Checking Redis cache for stock and news data...")
            
            # Check stock data cache
            redis_cache.last_tier_used = None
            cached_stock = redis_cache.get_cached_stock_data(symbol)
            if redis_cache.last_tier_used == "Redis":
                operation_summary['redis_used'] = True
                operation_summary['stock_cached'] = True
                logger.info(f"   ✅ Stock data found in cache")
            else:
                operation_summary['redis_used'] = True  # Redis was checked
                operation_summary['stock_cached'] = False
                logger.info(f"   🔄 Stock data not in cache (will fetch)")
            
            # Check news data cache
            redis_cache.last_tier_used = None
            cached_news = redis_cache.get_cached_news(symbol)
            if redis_cache.last_tier_used == "Redis":
                operation_summary['news_cached'] = True
                logger.info(f"   ✅ News data found in cache")
            else:
                operation_summary['news_cached'] = False
                logger.info(f"   🔄 News data not in cache (will fetch)")
        
        # Collect data with source filters (collector will use cache if available)
        data = collector.collect_all_data(symbol, data_source_filters=data_source_filters)
        
        step1_time = time.time() - step1_start
        logger.info(f"   ✅ Collected {len(data.get('news', []))} news articles in {step1_time:.2f}s")
        
        # Log final cache status
        if redis_cache:
            if operation_summary['stock_cached']:
                logger.info(f"[{symbol}] ✅ Stock data retrieved from Redis cache")
            else:
                logger.info(f"[{symbol}] 🔄 Stock data fetched fresh (cache miss)")
            
            if operation_summary['news_cached']:
                logger.info(f"[{symbol}] ✅ News data retrieved from Redis cache")
            else:
                logger.info(f"[{symbol}] 🔄 News data fetched fresh (cache miss)")
        
        # Show data source breakdown
        if data.get('news'):
            source_breakdown = {}
            for article in data['news']:
                source = article.get('source', 'Unknown')
                if 'Alpha Vantage' in source:
                    source_breakdown['Alpha Vantage'] = source_breakdown.get('Alpha Vantage', 0) + 1
                elif 'Finnhub' in source:
                    source_breakdown['Finnhub'] = source_breakdown.get('Finnhub', 0) + 1
                elif 'Reddit' in source or 'r/' in source:
                    source_breakdown['Reddit'] = source_breakdown.get('Reddit', 0) + 1
                else:
                    source_breakdown['Yahoo Finance'] = source_breakdown.get('Yahoo Finance', 0) + 1
            
            if source_breakdown:
                logger.info(f"   📈 Articles by source:")
                for source_name, count in source_breakdown.items():
                    logger.info(f"      • {source_name}: {count} articles")
        
        # Step 2: Store articles in RAG
        step2_start = time.time()
        logger.info(f"💾 STEP 2: Storing articles in RAG vector database...")
        
        if rag_service and data['news']:
            article_count = len(data['news'])
            logger.info(f"   📝 Preparing {article_count} articles for RAG storage...")
            total_in_rag = rag_service.store_articles_batch(data['news'], symbol)
            operation_summary['articles_stored'] += total_in_rag
            step2_time = time.time() - step2_start
            # total_in_rag includes both existing and newly stored articles
            logger.info(f"   ✅ Total articles in RAG: {total_in_rag}/{article_count} ({step2_time:.2f}s)")
        else:
            if not rag_service:
                logger.info(f"   ⚠️ RAG service not available - skipping storage")
            elif not data['news']:
                logger.info(f"   ⚠️ No articles to store")
            step2_time = time.time() - step2_start
        
        # Also store Reddit posts in RAG if available
        if rag_service and data.get('social_media'):
            reddit_count = len(data['social_media'])
            logger.info(f"   📝 Preparing {reddit_count} Reddit posts for RAG storage...")
            reddit_articles = []
            for post in data['social_media']:
                reddit_articles.append({
                    'title': post.get('title', ''),
                    'summary': post.get('summary', ''),
                    'source': post.get('source', 'Reddit'),
                    'url': post.get('url', ''),
                    'timestamp': post.get('timestamp', datetime.now())
                })
            total_reddit_in_rag = rag_service.store_articles_batch(reddit_articles, symbol)
            operation_summary['articles_stored'] += total_reddit_in_rag
            logger.info(f"   ✅ Total Reddit posts in RAG: {total_reddit_in_rag}/{reddit_count}")
        
        # Step 3: Analyze sentiment
        step3_start = time.time()
        logger.info(f"🤖 STEP 3: Analyzing sentiment with AI...")
        
        news_texts = [
            article.get('summary', article.get('title', ''))
            for article in data['news']
        ]
        
        logger.info(f"   📄 Analyzing sentiment for {len(news_texts)} articles...")
        
        # Track sentiment cache status
        if redis_cache and settings.app.cache_sentiment_enabled:
            logger.info(f"   🔍 Checking sentiment cache for {len(news_texts)} articles...")
            cache_check_start = time.time()
            for text in news_texts:
                if text:
                    redis_cache.last_tier_used = None
                    cached_sentiment = redis_cache.get_cached_sentiment(text)
                    if redis_cache.last_tier_used == "Redis":
                        operation_summary['sentiment_cache_hits'] += 1
                    else:
                        operation_summary['sentiment_cache_misses'] += 1
            cache_check_time = time.time() - cache_check_start
            hit_rate = (operation_summary['sentiment_cache_hits'] / len(news_texts) * 100) if news_texts else 0
            logger.info(f"   📊 Cache check complete ({cache_check_time:.2f}s):")
            logger.info(f"      • ✅ Cache HITS: {operation_summary['sentiment_cache_hits']} ({hit_rate:.1f}%)")
            logger.info(f"      • 🔄 Cache MISSES: {operation_summary['sentiment_cache_misses']} ({100-hit_rate:.1f}%)")
            if operation_summary['sentiment_cache_misses'] > 0:
                logger.info(f"      • 💡 {operation_summary['sentiment_cache_misses']} articles will use RAG + LLM analysis")
        else:
            if not settings.app.cache_sentiment_enabled:
                logger.info(f"   ⚠️ Sentiment cache is DISABLED - all {len(news_texts)} analyses will use RAG + LLM")
            operation_summary['sentiment_cache_misses'] = len(news_texts)
        
        # Track RAG usage before analysis
        initial_rag_uses = getattr(analyzer, 'rag_uses', 0) if hasattr(analyzer, 'rag_uses') else 0
        initial_rag_attempts = getattr(analyzer, 'rag_attempts', 0) if hasattr(analyzer, 'rag_attempts') else 0
        
        # Batch analyze with parallel processing
        worker_count = settings.app.analysis_parallel_workers or settings.app.sentiment_max_workers
        worker_timeout = settings.app.analysis_worker_timeout
        logger.info(f"   ⚡ Starting parallel batch analysis ({worker_count} workers, {worker_timeout}s timeout per task)...")
        analysis_start = time.time()
        
        news_sentiments = analyzer.batch_analyze(
            texts=news_texts,
            symbol=symbol,
            max_workers=worker_count,
            worker_timeout=worker_timeout
        )
        
        analysis_time = time.time() - analysis_start
        logger.info(f"   ✅ Completed sentiment analysis for {len(news_texts)} articles in {analysis_time:.2f}s")
        
        # Track RAG usage after analysis
        final_rag_uses = getattr(analyzer, 'rag_uses', 0) if hasattr(analyzer, 'rag_uses') else 0
        final_rag_attempts = getattr(analyzer, 'rag_attempts', 0) if hasattr(analyzer, 'rag_attempts') else 0
        
        rag_queries_made = final_rag_attempts - initial_rag_attempts
        rag_successful = final_rag_uses - initial_rag_uses
        
        if rag_queries_made > 0:
            operation_summary['rag_used'] = True
            operation_summary['rag_queries'] = rag_queries_made
            operation_summary['rag_articles_found'] = rag_successful
            logger.info(f"   🎯 RAG Usage Summary:")
            logger.info(f"      • RAG queries made: {rag_queries_made}")
            logger.info(f"      • Articles found via RAG: {rag_successful}")
            logger.info(f"      • Success rate: {(rag_successful/rag_queries_made*100) if rag_queries_made > 0 else 0:.1f}%")
        else:
            logger.info(f"   ℹ️ RAG was not used (all sentiment was cached)")
        
        step3_time = time.time() - step3_start
        logger.info(f"   ⏱️ Step 3 total time: {step3_time:.2f}s")
        
        # Handle empty texts
        for i, text in enumerate(news_texts):
            if not text:
                news_sentiments[i] = {'positive': 0, 'negative': 0, 'neutral': 1}
        
        # Analyze social media posts separately
        social_texts = [post.get('text', '') for post in data.get('social_media', [])]
        social_sentiments = []
        if social_texts:
            logger.info(f"   📱 Analyzing sentiment for {len(social_texts)} social media posts...")
            social_sentiments = analyzer.batch_analyze(
                texts=social_texts,
                symbol=symbol,
                max_workers=worker_count,
                worker_timeout=worker_timeout
            )
            # Handle empty texts
            for i, text in enumerate(social_texts):
                if not text:
                    social_sentiments[i] = {'positive': 0, 'negative': 0, 'neutral': 1}
            logger.info(f"   ✅ Analyzed {len(social_sentiments)} social media posts")
        
        # Combine for aggregation (but keep separate for detailed return)
        all_sentiments = news_sentiments + social_sentiments
        
        # Filter out None values (failed analyses)
        all_sentiments = [s for s in all_sentiments if s is not None]
        
        # Step 4: Aggregate sentiment scores
        step4_start = time.time()
        logger.info(f"📊 STEP 4: Aggregating sentiment scores...")
        
        if not all_sentiments:
            logger.warning(f"   ⚠️ No sentiment results to aggregate")
            result = {
                'symbol': symbol,
                'positive': 0.0,
                'negative': 0.0,
                'neutral': 1.0,
                'net_sentiment': 0.0,
                'dominant_sentiment': 'neutral',
                'sources_analyzed': 0,
                'timestamp': datetime.now().isoformat(),
                'operation_summary': operation_summary
            }
            if return_detailed:
                result['data'] = data
                result['news_sentiments'] = news_sentiments
                result['social_sentiments'] = social_sentiments
            return result
        
        # Calculate aggregated scores from all sentiments
        total_positive = sum(s.get('positive', 0) for s in all_sentiments)
        total_negative = sum(s.get('negative', 0) for s in all_sentiments)
        total_neutral = sum(s.get('neutral', 0) for s in all_sentiments)
        count = len(all_sentiments)
        
        avg_positive = total_positive / count if count > 0 else 0.0
        avg_negative = total_negative / count if count > 0 else 0.0
        avg_neutral = total_neutral / count if count > 0 else 0.0
        
        # Normalize to ensure they sum to 1.0
        total = avg_positive + avg_negative + avg_neutral
        if total > 0:
            avg_positive /= total
            avg_negative /= total
            avg_neutral /= total
        
        net_sentiment = avg_positive - avg_negative
        
        # Determine dominant sentiment
        if avg_positive > avg_negative and avg_positive > avg_neutral:
            dominant_sentiment = 'positive'
        elif avg_negative > avg_positive and avg_negative > avg_neutral:
            dominant_sentiment = 'negative'
        else:
            dominant_sentiment = 'neutral'
        
        step4_time = time.time() - step4_start
        total_time = time.time() - start_time
        
        # Log final summary
        logger.info("-" * 80)
        logger.info(f"📋 DEMO SUMMARY for {symbol}")
        logger.info("-" * 80)
        logger.info(f"⏱️  Performance:")
        logger.info(f"   • Total time: {total_time:.2f}s")
        logger.info(f"   • Step 1 (Data Collection): {step1_time:.2f}s")
        logger.info(f"   • Step 2 (RAG Storage): {step2_time:.2f}s")
        logger.info(f"   • Step 3 (Sentiment Analysis): {step3_time:.2f}s")
        logger.info(f"   • Step 4 (Aggregation): {step4_time:.2f}s")
        logger.info("")
        logger.info(f"💾 Redis Cache:")
        logger.info(f"   • Redis used: {'✅ YES' if operation_summary['redis_used'] else '❌ NO'}")
        if operation_summary['redis_used']:
            logger.info(f"   • Stock data: {'✅ CACHED' if operation_summary['stock_cached'] else '🔄 FRESH'}")
            logger.info(f"   • News data: {'✅ CACHED' if operation_summary['news_cached'] else '🔄 FRESH'}")
            logger.info(f"   • Sentiment cache: {operation_summary['sentiment_cache_hits']} hits, {operation_summary['sentiment_cache_misses']} misses")
        logger.info("")
        logger.info(f"🎯 RAG (Retrieval Augmented Generation):")
        logger.info(f"   • RAG used: {'✅ YES' if operation_summary['rag_used'] else '❌ NO'}")
        if operation_summary['rag_used']:
            logger.info(f"   • RAG queries made: {operation_summary['rag_queries']}")
            logger.info(f"   • Articles found via RAG: {operation_summary['rag_articles_found']}")
        logger.info(f"   • Articles stored in RAG: {operation_summary['articles_stored']}")
        logger.info("")
        logger.info(f"📈 Results:")
        logger.info(f"   • Articles analyzed: {count}")
        logger.info(f"   • Dominant sentiment: {dominant_sentiment.upper()}")
        logger.info(f"   • Net sentiment: {net_sentiment:+.3f} ({'positive' if net_sentiment > 0 else 'negative' if net_sentiment < 0 else 'neutral'})")
        logger.info(f"   • Positive: {avg_positive:.1%} | Negative: {avg_negative:.1%} | Neutral: {avg_neutral:.1%}")
        logger.info("=" * 80)
        
        # Base result with aggregated sentiment
        result = {
            'symbol': symbol,
            'positive': round(avg_positive, 4),
            'negative': round(avg_negative, 4),
            'neutral': round(avg_neutral, 4),
            'net_sentiment': round(net_sentiment, 4),
            'dominant_sentiment': dominant_sentiment,
            'sources_analyzed': count,
            'timestamp': datetime.now().isoformat(),
            'operation_summary': operation_summary
        }
        
        # Add detailed data if requested (for dashboard)
        if return_detailed:
            result['data'] = data
            result['news_sentiments'] = news_sentiments
            result['social_sentiments'] = social_sentiments
        
        return result
        
    except Exception as e:
        logger.error(f"Error in sentiment analysis for {symbol}: {e}", exc_info=True)
        raise

