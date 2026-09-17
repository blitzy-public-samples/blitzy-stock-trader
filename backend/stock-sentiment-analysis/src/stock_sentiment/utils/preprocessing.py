"""
Text preprocessing utilities for sentiment analysis.

This module provides text cleaning and normalization functions
to improve sentiment analysis accuracy.
"""

import re
from typing import Optional
from ..utils.logger import get_logger

logger = get_logger(__name__)


# Financial abbreviations mapping
FINANCIAL_ABBREVIATIONS = {
    'Q1': 'first quarter',
    'Q2': 'second quarter',
    'Q3': 'third quarter',
    'Q4': 'fourth quarter',
    'EPS': 'earnings per share',
    'P/E': 'price to earnings ratio',
    'P/E ratio': 'price to earnings ratio',
    'ROE': 'return on equity',
    'ROI': 'return on investment',
    'EBITDA': 'earnings before interest taxes depreciation amortization',
    'IPO': 'initial public offering',
    'SEC': 'securities and exchange commission',
    'FDA': 'food and drug administration',
    'GDP': 'gross domestic product',
    'CPI': 'consumer price index',
    'Fed': 'federal reserve',
    'NYSE': 'new york stock exchange',
    'NASDAQ': 'nasdaq stock market',
    'S&P 500': 'standard and poor 500',
    'DJIA': 'dow jones industrial average',
    'YOY': 'year over year',
    'QOQ': 'quarter over quarter',
    'MOM': 'month over month',
    'CEO': 'chief executive officer',
    'CFO': 'chief financial officer',
    'CTO': 'chief technology officer',
}


def preprocess_text(text: str, expand_abbreviations: bool = True) -> str:
    """
    Preprocess text for better sentiment analysis.
    
    This function:
    - Removes HTML/XML tags
    - Normalizes whitespace
    - Removes excessive punctuation (keeps financial symbols)
    - Expands financial abbreviations
    - Handles emojis (converts to text or removes)
    - Preserves financial symbols ($, %, etc.)
    
    Args:
        text: Raw text to preprocess
        expand_abbreviations: Whether to expand financial abbreviations
        
    Returns:
        Cleaned and normalized text
        
    Example:
        >>> preprocess_text("Apple's Q4 earnings <b>surged</b> 5%! 🚀")
        "Apple's fourth quarter earnings surged 5%!"
    """
    if not text or not isinstance(text, str):
        return ""
    
    # Remove HTML/XML tags
    text = re.sub(r'<[^>]+>', '', text)
    
    # Remove URLs
    text = re.sub(r'http[s]?://(?:[a-zA-Z]|[0-9]|[$-_@.&+]|[!*\\(\\),]|(?:%[0-9a-fA-F][0-9a-fA-F]))+', '', text)
    
    # Normalize whitespace (multiple spaces/tabs/newlines to single space)
    text = re.sub(r'\s+', ' ', text)
    
    # Expand financial abbreviations if enabled
    if expand_abbreviations:
        text = _expand_abbreviations(text)
    
    # Remove excessive punctuation (keep single !, ?, ., but remove multiple)
    text = re.sub(r'[!]{2,}', '!', text)
    text = re.sub(r'[?]{2,}', '?', text)
    text = re.sub(r'[.]{3,}', '...', text)
    
    # Remove emojis (optional: could convert to text instead)
    # Using regex to remove emoji-like characters
    emoji_pattern = re.compile(
        "["
        "\U0001F600-\U0001F64F"  # emoticons
        "\U0001F300-\U0001F5FF"  # symbols & pictographs
        "\U0001F680-\U0001F6FF"  # transport & map symbols
        "\U0001F1E0-\U0001F1FF"  # flags
        "\U00002702-\U000027B0"
        "\U000024C2-\U0001F251"
        "]+", flags=re.UNICODE
    )
    text = emoji_pattern.sub('', text)
    
    # Trim and return
    return text.strip()


def _expand_abbreviations(text: str) -> str:
    """
    Expand financial abbreviations in text.
    
    Args:
        text: Text containing abbreviations
        
    Returns:
        Text with abbreviations expanded
    """
    # Sort by length (longest first) to avoid partial matches
    sorted_abbrevs = sorted(
        FINANCIAL_ABBREVIATIONS.items(),
        key=lambda x: len(x[0]),
        reverse=True
    )
    
    for abbrev, expansion in sorted_abbrevs:
        # Use word boundaries to avoid partial matches
        pattern = r'\b' + re.escape(abbrev) + r'\b'
        text = re.sub(pattern, expansion, text, flags=re.IGNORECASE)
    
    return text


def extract_key_phrases(text: str, max_phrases: int = 5) -> list[str]:
    """
    Extract key financial phrases from text.
    
    Args:
        text: Text to analyze
        max_phrases: Maximum number of phrases to return
        
    Returns:
        List of key phrases
    """
    # Common financial phrases
    financial_phrases = [
        r'earnings (?:per share|report|call)',
        r'revenue (?:growth|decline|increase)',
        r'stock (?:price|surge|drop|rally)',
        r'market (?:cap|value|share)',
        r'profit (?:margin|loss|gain)',
        r'dividend (?:yield|payment|increase)',
        r'price (?:target|forecast|prediction)',
        r'analyst (?:rating|upgrade|downgrade)',
        r'regulatory (?:approval|investigation|action)',
        r'merger (?:and acquisition|deal)',
    ]
    
    found_phrases = []
    text_lower = text.lower()
    
    for pattern in financial_phrases:
        matches = re.findall(pattern, text_lower, re.IGNORECASE)
        found_phrases.extend(matches)
        if len(found_phrases) >= max_phrases:
            break
    
    return found_phrases[:max_phrases]


def is_financial_text(text: str) -> bool:
    """
    Check if text is relevant to financial/stock market topics.
    
    Args:
        text: Text to check
        
    Returns:
        True if text appears to be financial content
    """
    if not text or len(text.strip()) < 10:
        return False
    
    financial_keywords = [
        'stock', 'share', 'earnings', 'revenue', 'profit', 'loss',
        'market', 'price', 'dividend', 'company', 'quarter', 'annual',
        'financial', 'trading', 'investment', 'analyst', 'forecast',
        'revenue', 'margin', 'growth', 'decline', 'surge', 'drop',
        'IPO', 'merger', 'acquisition', 'CEO', 'CFO', 'board'
    ]
    
    text_lower = text.lower()
    keyword_count = sum(1 for keyword in financial_keywords if keyword in text_lower)
    
    # Consider financial if at least 2 keywords found
    return keyword_count >= 2


def normalize_stock_symbol(text: str) -> Optional[str]:
    """
    Extract and normalize stock symbol from text.
    
    Args:
        text: Text that may contain stock symbols
        
    Returns:
        Normalized stock symbol (uppercase) or None
    """
    # Pattern for stock symbols (1-5 uppercase letters, possibly with dots)
    symbol_pattern = r'\b([A-Z]{1,5}(?:\.[A-Z]{1,2})?)\b'
    
    matches = re.findall(symbol_pattern, text)
    if matches:
        # Return first match, normalized
        return matches[0].upper().replace('.', '')
    
    return None

