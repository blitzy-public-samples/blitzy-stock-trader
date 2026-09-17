"""
Sentiment analysis API routes.
"""

from fastapi import APIRouter, HTTPException, Query, Depends
from typing import Optional, Dict, Union, List
from pydantic import BaseModel, Field

from ..models.response import SentimentResponse, DetailedSentimentResponse, ErrorResponse
from ..dependencies import get_all_services
from ...services.orchestrator import get_aggregated_sentiment
from ...utils.logger import get_logger
from ...utils.validators import validate_stock_symbol

logger = get_logger(__name__)

router = APIRouter(prefix="/sentiment", tags=["sentiment"])


def parse_data_source_filters(sources: Optional[str] = None) -> Optional[Dict[str, bool]]:
    """
    Parse data source filters from query parameter.
    
    Args:
        sources: Comma-separated list of sources (e.g., "yfinance,alpha_vantage")
    
    Returns:
        Dictionary of source enable/disable flags, or None if not specified
    """
    if not sources or not sources.strip():
        # Default to only yfinance when no sources are specified
        return {
            "yfinance": True,
            "alpha_vantage": False,
            "finnhub": False,
            "reddit": False
        }
    
    # Default all sources to False, then enable specified ones
    filters = {
        "yfinance": False,
        "alpha_vantage": False,
        "finnhub": False,
        "reddit": False
    }
    
    # Parse comma-separated sources
    source_list = [s.strip().lower() for s in sources.split(",")]
    
    for source in source_list:
        if source in filters:
            filters[source] = True
        elif source == "yahoofinance" or source == "yahoo":
            filters["yfinance"] = True
        else:
            logger.warning(f"Unknown data source: {source}")
    
    return filters


@router.get(
    "/{symbol}",
    response_model=Union[SentimentResponse, DetailedSentimentResponse],
    responses={
        200: {"description": "Successful response"},
        400: {"model": ErrorResponse, "description": "Bad request (invalid symbol)"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Get sentiment analysis for a stock symbol",
    description="""
    Analyze sentiment for a stock symbol by collecting news from multiple sources,
    analyzing sentiment with AI, and returning aggregated scores.
    
    - **symbol**: Stock ticker symbol (e.g., AAPL, MSFT, GOOGL)
    - **sources**: Optional comma-separated list of data sources to use
        - Available: yfinance, alpha_vantage, finnhub, reddit
        - Default: all enabled sources
    - **cache_enabled**: Optional flag to enable/disable sentiment caching
        - Default: true (uses cache if available)
        - Set to false to force RAG usage
    - **detailed**: Optional flag to return detailed response with full data
        - Default: false (returns aggregated sentiment only)
        - Set to true to include stock data, news articles, and individual sentiment scores
    
    Returns aggregated sentiment scores (positive, negative, neutral) and metadata.
    If detailed=true, also returns stock data, news articles, and individual sentiment scores.
    """
)
async def get_sentiment(
    symbol: str,
    sources: Optional[str] = Query(
        None,
        description="Comma-separated list of data sources (yfinance,alpha_vantage,finnhub,reddit)"
    ),
    cache_enabled: Optional[bool] = Query(
        True,
        description="Enable sentiment caching (set to false to force RAG usage)"
    ),
    detailed: Optional[bool] = Query(
        False,
        description="Return detailed response with stock data, news articles, and individual sentiment scores"
    )
):
    """
    Get aggregated sentiment analysis for a stock symbol.
    
    Args:
        symbol: Stock symbol to analyze
        sources: Optional comma-separated list of data sources
        cache_enabled: Whether to use sentiment cache
    
    Returns:
        SentimentResponse with aggregated sentiment scores
    
    Raises:
        HTTPException: If symbol is invalid or analysis fails
    """
    # Validate symbol
    try:
        symbol = symbol.upper().strip()
        validate_stock_symbol(symbol)
    except ValueError as e:
        logger.warning(f"Invalid symbol '{symbol}': {e}")
        raise HTTPException(
            status_code=400,
            detail=f"Invalid stock symbol: {symbol}. {str(e)}"
        )
    
    import time
    request_start = time.time()
    
    logger.info("=" * 80)
    logger.info(f"🌐 API REQUEST: GET /sentiment/{symbol}")
    logger.info("-" * 80)
    logger.info(f"📋 Request Parameters:")
    logger.info(f"   • Symbol: {symbol}")
    logger.info(f"   • Sources filter: {sources or 'all enabled sources'}")
    logger.info(f"   • Cache enabled: {'✅ YES' if cache_enabled else '❌ NO (RAG will be used)'}")
    logger.info(f"   • Detailed response: {'✅ YES' if detailed else '❌ NO'}")
    logger.info("-" * 80)
    
    try:
        # Get all services
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if analyzer is None:
            logger.error("Sentiment analyzer not available")
            raise HTTPException(
                status_code=503,
                detail="Sentiment analyzer service unavailable. Please check configuration."
            )
        
        # Temporarily disable cache if requested
        original_cache_setting = settings.app.cache_sentiment_enabled
        if not cache_enabled:
            settings.app.cache_sentiment_enabled = False
            logger.info(f"API: Sentiment cache disabled for this request (forcing RAG usage)")
        
        try:
            # Parse data source filters
            data_source_filters = parse_data_source_filters(sources)
            
            # Get aggregated sentiment (with detailed data if requested)
            result = get_aggregated_sentiment(
                symbol=symbol,
                collector=collector,
                analyzer=analyzer,
                rag_service=rag_service,
                redis_cache=redis_cache,
                settings=settings,
                data_source_filters=data_source_filters,
                return_detailed=detailed
            )
            
            request_time = time.time() - request_start
            logger.info("-" * 80)
            logger.info(f"✅ API RESPONSE: Successfully analyzed {symbol}")
            logger.info(f"   • Dominant sentiment: {result['dominant_sentiment'].upper()}")
            logger.info(f"   • Articles analyzed: {result['sources_analyzed']}")
            logger.info(f"   • Request time: {request_time:.2f}s")
            logger.info("=" * 80)
            
            # Return detailed or simple response based on parameter
            if detailed:
                # Transform result for detailed response
                detailed_result = result.copy()
                if 'data' in result:
                    data = result['data']
                    # Extract price_data from data (collector returns it as 'price_data' key)
                    detailed_result['price_data'] = data.get('price_data', {})
                    # Extract news from data
                    detailed_result['news'] = data.get('news', [])
                    # operation_summary is already in result
                return DetailedSentimentResponse(**detailed_result)
            else:
                # Return simple response (remove detailed fields)
                simple_result = {k: v for k, v in result.items() 
                               if k not in ['data', 'news_sentiments', 'social_sentiments']}
                return SentimentResponse(**simple_result)
            
        finally:
            # Restore original cache setting
            settings.app.cache_sentiment_enabled = original_cache_setting
            
    except HTTPException:
        # Re-raise HTTP exceptions
        raise
    except Exception as e:
        logger.error(f"API: Error analyzing sentiment for {symbol}: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to analyze sentiment for {symbol}: {str(e)}"
        )


class BatchSentimentRequest(BaseModel):
    """Request model for batch sentiment analysis."""
    symbols: List[str] = Field(..., description="List of stock symbols to analyze", min_length=1)
    sources: Optional[str] = Field(None, description="Comma-separated list of data sources")
    cache_enabled: Optional[bool] = Field(True, description="Enable sentiment caching")
    detailed: Optional[bool] = Field(False, description="Return detailed response")


@router.post(
    "/batch",
    response_model=List[Union[SentimentResponse, DetailedSentimentResponse]],
    responses={
        200: {"description": "Successful response"},
        400: {"model": ErrorResponse, "description": "Bad request"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Batch sentiment analysis for multiple symbols",
    description="""
    Analyze sentiment for multiple stock symbols in a single request.
    
    - **symbols**: List of stock symbols to analyze (required, minimum 1)
    - **sources**: Optional comma-separated list of data sources
    - **cache_enabled**: Optional flag to enable/disable sentiment caching (default: true)
    - **detailed**: Optional flag to return detailed response (default: false)
    
    Returns a list of sentiment responses, one for each symbol.
    """
)
async def get_sentiment_batch(
    request: BatchSentimentRequest
):
    """
    Get aggregated sentiment analysis for multiple stock symbols.
    
    Args:
        request: Batch sentiment request with symbols and options
    
    Returns:
        List of sentiment responses
    
    Raises:
        HTTPException: If analysis fails
    """
    import time
    request_start = time.time()
    
    symbols = request.symbols
    sources = request.sources
    cache_enabled = request.cache_enabled
    detailed = request.detailed
    
    logger.info("=" * 80)
    logger.info(f"🌐 API REQUEST: POST /sentiment/batch")
    logger.info("-" * 80)
    logger.info(f"📋 Request Parameters:")
    logger.info(f"   • Symbols: {', '.join(symbols)}")
    logger.info(f"   • Sources filter: {sources or 'all enabled sources'}")
    logger.info(f"   • Cache enabled: {'✅ YES' if cache_enabled else '❌ NO (RAG will be used)'}")
    logger.info(f"   • Detailed response: {'✅ YES' if detailed else '❌ NO'}")
    logger.info("-" * 80)
    
    try:
        # Validate all symbols
        for symbol in symbols:
            try:
                symbol_upper = symbol.upper().strip()
                validate_stock_symbol(symbol_upper)
            except ValueError as e:
                logger.warning(f"Invalid symbol '{symbol}': {e}")
                raise HTTPException(
                    status_code=400,
                    detail=f"Invalid stock symbol: {symbol}. {str(e)}"
                )
        
        # Get all services
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if analyzer is None:
            logger.error("Sentiment analyzer not available")
            raise HTTPException(
                status_code=503,
                detail="Sentiment analyzer service unavailable. Please check configuration."
            )
        
        # Temporarily disable cache if requested
        original_cache_setting = settings.app.cache_sentiment_enabled
        if not cache_enabled:
            settings.app.cache_sentiment_enabled = False
            logger.info(f"API: Sentiment cache disabled for this batch request (forcing RAG usage)")
        
        try:
            # Parse data source filters
            data_source_filters = parse_data_source_filters(sources)
            
            # Process each symbol
            results = []
            for idx, symbol in enumerate(symbols, 1):
                symbol_upper = symbol.upper().strip()
                logger.info(f"Processing {symbol_upper} ({idx}/{len(symbols)})...")
                
                # Get aggregated sentiment
                result = get_aggregated_sentiment(
                    symbol=symbol_upper,
                    collector=collector,
                    analyzer=analyzer,
                    rag_service=rag_service,
                    redis_cache=redis_cache,
                    settings=settings,
                    data_source_filters=data_source_filters,
                    return_detailed=detailed
                )
                
                # Transform result for response
                if detailed:
                    detailed_result = result.copy()
                    if 'data' in result:
                        data = result['data']
                        detailed_result['price_data'] = data.get('price_data', {})
                        detailed_result['news'] = data.get('news', [])
                    results.append(DetailedSentimentResponse(**detailed_result))
                else:
                    simple_result = {k: v for k, v in result.items() 
                                   if k not in ['data', 'news_sentiments', 'social_sentiments']}
                    results.append(SentimentResponse(**simple_result))
            
            request_time = time.time() - request_start
            logger.info("-" * 80)
            logger.info(f"✅ API RESPONSE: Successfully analyzed {len(symbols)} symbols")
            logger.info(f"   • Request time: {request_time:.2f}s")
            logger.info("=" * 80)
            
            return results
            
        finally:
            # Restore original cache setting
            settings.app.cache_sentiment_enabled = original_cache_setting
            
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"API: Error in batch sentiment analysis: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to analyze sentiment for batch: {str(e)}"
        )

