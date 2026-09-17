"""
Validation utilities for input data.

This module provides validation functions for stock symbols,
text content, and other inputs.
"""

import re
from typing import Optional


def validate_stock_symbol(symbol: str) -> bool:
    """
    Validate stock ticker symbol format.
    
    Args:
        symbol: Stock ticker symbol to validate
        
    Returns:
        True if valid, False otherwise
        
    Examples:
        >>> validate_stock_symbol("AAPL")
        True
        >>> validate_stock_symbol("aapl")
        True
        >>> validate_stock_symbol("123")
        False
    """
    if not symbol or not isinstance(symbol, str):
        return False
    
    # Stock symbols are typically 1-5 uppercase letters
    # Some may include numbers or dots (e.g., BRK.B)
    pattern = r'^[A-Z]{1,5}(\.[A-Z])?$'
    return bool(re.match(pattern, symbol.upper()))


def validate_text(text: str, min_length: int = 1, max_length: int = 10000) -> bool:
    """
    Validate text content for sentiment analysis.
    
    Args:
        text: Text to validate
        min_length: Minimum text length
        max_length: Maximum text length
        
    Returns:
        True if valid, False otherwise
    """
    if not isinstance(text, str):
        return False
    
    text = text.strip()
    return min_length <= len(text) <= max_length


def sanitize_text(text: str, max_length: Optional[int] = None) -> str:
    """
    Sanitize and truncate text for processing.
    
    Args:
        text: Text to sanitize
        max_length: Optional maximum length (truncates if longer)
        
    Returns:
        Sanitized text
    """
    if not isinstance(text, str):
        return ""
    
    # Remove extra whitespace
    text = " ".join(text.split())
    
    # Truncate if needed
    if max_length and len(text) > max_length:
        text = text[:max_length].rsplit(" ", 1)[0] + "..."
    
    return text

