"""
Price history API routes.
"""

from fastapi import APIRouter, HTTPException, Query, Depends
from typing import Optional, Dict, Any, List
from datetime import datetime

from ..models.response import ErrorResponse
from ..dependencies import get_all_services
from ...utils.logger import get_logger
from ...utils.validators import validate_stock_symbol
import yfinance as yf

logger = get_logger(__name__)

router = APIRouter(prefix="/price", tags=["price"])


@router.get(
    "/{symbol}/history",
    responses={
        200: {"description": "Successful response"},
        400: {"model": ErrorResponse, "description": "Bad request (invalid symbol)"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Get historical price data for a stock symbol",
    description="""
    Get historical price data (OHLCV) for a stock symbol.
    
    - **symbol**: Stock ticker symbol (e.g., AAPL, MSFT, GOOGL)
    - **period**: Time period for historical data
        - Available: "1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"
        - Default: "1y"
    
    Returns historical price data with open, high, low, close, and volume.
    """
)
async def get_price_history(
    symbol: str,
    period: str = Query(
        "1y",
        description="Time period for historical data (1d, 5d, 1mo, 3mo, 6mo, 1y, 2y, 5y)"
    )
):
    """
    Get historical price data for a stock symbol.
    
    Args:
        symbol: Stock symbol to get history for
        period: Time period for historical data
    
    Returns:
        Dictionary with symbol, period, and historical data
    
    Raises:
        HTTPException: If symbol is invalid or data fetch fails
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
    
    # Validate period
    valid_periods = ["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"]
    if period not in valid_periods:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid period: {period}. Valid periods: {', '.join(valid_periods)}"
        )
    
    try:
        logger.info(f"Fetching price history for {symbol} (period: {period})")
        
        ticker = yf.Ticker(symbol)
        hist = ticker.history(period=period)
        
        if hist.empty:
            raise HTTPException(
                status_code=404,
                detail=f"No price data available for symbol {symbol}"
            )
        
        # Convert to list of dictionaries
        data = []
        for date, row in hist.iterrows():
            data.append({
                "date": date.isoformat() if hasattr(date, 'isoformat') else str(date),
                "open": float(row['Open']),
                "high": float(row['High']),
                "low": float(row['Low']),
                "close": float(row['Close']),
                "volume": int(row['Volume'])
            })
        
        logger.info(f"Successfully fetched {len(data)} data points for {symbol}")
        
        return {
            "symbol": symbol,
            "period": period,
            "data": data
        }
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error fetching price history for {symbol}: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to fetch price history for {symbol}: {str(e)}"
        )

