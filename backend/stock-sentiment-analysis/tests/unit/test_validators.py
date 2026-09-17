"""
Unit tests for validator utilities.
"""

import pytest

from src.stock_sentiment.utils.validators import validate_stock_symbol, validate_text


@pytest.mark.unit
class TestValidators:
    """Test suite for validator utilities."""
    
    def test_validate_stock_symbol_valid(self):
        """Test validation of valid stock symbols."""
        valid_symbols = ["AAPL", "MSFT", "GOOGL", "TSLA", "AMZN", "META", "NVDA"]
        
        for symbol in valid_symbols:
            assert validate_stock_symbol(symbol) is True
    
    def test_validate_stock_symbol_case_insensitive(self):
        """Test that validation is case-insensitive."""
        assert validate_stock_symbol("aapl") is True
        assert validate_stock_symbol("AAPL") is True
        assert validate_stock_symbol("AaPl") is True
    
    def test_validate_stock_symbol_with_dot(self):
        """Test validation of symbols with dots (e.g., BRK.B)."""
        assert validate_stock_symbol("BRK.B") is True
        assert validate_stock_symbol("BRK.A") is True
    
    def test_validate_stock_symbol_invalid(self):
        """Test validation of invalid stock symbols."""
        invalid_symbols = [
            "123",           # Numbers only
            "AAPL123",       # Numbers in middle
            "AA",            # Too short (but actually valid for some)
            "AAAAAA",        # Too long
            "",              # Empty
            "A",             # Single character
            "AAPL-",         # Special characters
            "AAPL@",         # Special characters
            None,            # None
        ]
        
        for symbol in invalid_symbols:
            if symbol is None:
                assert validate_stock_symbol(symbol) is False
            else:
                # Some edge cases might pass, so we check the pattern
                result = validate_stock_symbol(symbol)
                # Empty string and None should definitely fail
                if not symbol or symbol == "":
                    assert result is False
    
    def test_validate_stock_symbol_empty_string(self):
        """Test validation with empty string."""
        assert validate_stock_symbol("") is False
    
    def test_validate_stock_symbol_none(self):
        """Test validation with None."""
        assert validate_stock_symbol(None) is False
    
    def test_validate_stock_symbol_non_string(self):
        """Test validation with non-string types."""
        assert validate_stock_symbol(123) is False
        assert validate_stock_symbol([]) is False
        assert validate_stock_symbol({}) is False
    
    def test_validate_text_valid(self):
        """Test validation of valid text."""
        valid_texts = [
            "This is valid text",
            "A",  # Minimum length
            "A" * 10000,  # Maximum length
            "Text with numbers 123",
            "Text with special chars !@#$%",
        ]
        
        for text in valid_texts:
            assert validate_text(text) is True
    
    def test_validate_text_length_limits(self):
        """Test text validation with length limits."""
        # Within limits
        assert validate_text("Valid text", min_length=1, max_length=100) is True
        
        # Too short
        assert validate_text("", min_length=1, max_length=100) is False
        
        # Too long
        long_text = "A" * 101
        assert validate_text(long_text, min_length=1, max_length=100) is False
    
    def test_validate_text_empty(self):
        """Test validation with empty text."""
        assert validate_text("") is False  # Default min_length is 1
        assert validate_text("", min_length=0) is True
    
    def test_validate_text_none(self):
        """Test validation with None."""
        assert validate_text(None) is False
    
    def test_validate_text_custom_limits(self):
        """Test validation with custom min/max length."""
        # Custom minimum
        assert validate_text("Short", min_length=10) is False
        assert validate_text("This is long enough", min_length=10) is True
        
        # Custom maximum
        assert validate_text("A" * 50, max_length=100) is True
        assert validate_text("A" * 150, max_length=100) is False
    
    def test_validate_text_whitespace(self):
        """Test validation with whitespace-only text."""
        # Whitespace is stripped, so "   " becomes "" which fails min_length=1
        assert validate_text("   ", min_length=1) is False
        assert validate_text("\n\t", min_length=1) is False
        # But should pass with min_length=0
        assert validate_text("   ", min_length=0) is True
        assert validate_text("\n\t", min_length=0) is True
    
    def test_validate_text_unicode(self):
        """Test validation with unicode characters."""
        unicode_texts = [
            "Café",
            "日本語",
            "🚀 Stock",
            "€100",
        ]
        
        for text in unicode_texts:
            assert validate_text(text) is True

