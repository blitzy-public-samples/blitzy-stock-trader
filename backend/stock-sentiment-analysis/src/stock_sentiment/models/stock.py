"""
Stock data models and structures.

This module defines data structures for stock information. Currently, stock data
is represented as dictionaries with standardized keys for consistency across
the application. This allows flexibility while maintaining a clear data contract.

The standard stock data structure includes:
- symbol: Stock ticker symbol (e.g., "AAPL")
- price: Current stock price
- company_name: Full company name
- market_cap: Market capitalization
- timestamp: When the data was collected
"""

from typing import Dict, Any, Optional
from datetime import datetime

# Type alias for stock data dictionary
StockData = Dict[str, Any]

def create_stock_data(
    symbol: str,
    price: float,
    company_name: Optional[str] = None,
    market_cap: Optional[float] = None,
    timestamp: Optional[datetime] = None
) -> StockData:
    """
    Create a standardized stock data dictionary.
    
    Args:
        symbol: Stock ticker symbol (e.g., "AAPL")
        price: Current stock price
        company_name: Full company name (optional)
        market_cap: Market capitalization (optional)
        timestamp: When the data was collected (optional, defaults to now)
        
    Returns:
        Dictionary with standardized stock data structure
    """
    if timestamp is None:
        timestamp = datetime.now()
    
    return {
        "symbol": symbol.upper(),
        "price": price,
        "company_name": company_name or symbol,
        "market_cap": market_cap,
        "timestamp": timestamp.isoformat() if isinstance(timestamp, datetime) else timestamp
    }
