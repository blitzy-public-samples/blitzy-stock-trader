"""
Test configuration management for hybrid testing approach.

This module provides utilities for managing test configuration,
including switching between mock and real APIs.
"""

import os
from typing import Optional


def get_env_bool(key: str, default: bool = False) -> bool:
    """Get boolean environment variable."""
    value = os.getenv(key, "").lower()
    return value in ("true", "1", "yes", "on") if value else default


def use_real_apis() -> bool:
    """Check if real APIs should be used (master switch)."""
    return get_env_bool("USE_REAL_APIS", False)


def use_real_redis() -> bool:
    """Check if real Redis should be used."""
    return get_env_bool("USE_REAL_REDIS", False) or use_real_apis()


def use_real_azure_openai() -> bool:
    """Check if real Azure OpenAI should be used."""
    return get_env_bool("USE_REAL_AZURE_OPENAI", False) or use_real_apis()


def use_real_azure_ai_search() -> bool:
    """Check if real Azure AI Search should be used."""
    return get_env_bool("USE_REAL_AZURE_AI_SEARCH", False) or use_real_apis()


def use_real_yfinance() -> bool:
    """Check if real yfinance API should be used."""
    return get_env_bool("USE_REAL_YFINANCE", False) or use_real_apis()


def use_real_alpha_vantage() -> bool:
    """Check if real Alpha Vantage API should be used."""
    return get_env_bool("USE_REAL_ALPHA_VANTAGE", False) or use_real_apis()


def use_real_finnhub() -> bool:
    """Check if real Finnhub API should be used."""
    return get_env_bool("USE_REAL_FINNHUB", False) or use_real_apis()


def use_real_reddit() -> bool:
    """Check if real Reddit API should be used."""
    return get_env_bool("USE_REAL_REDDIT", False) or use_real_apis()


def check_service_available(service_name: str, required_env_vars: list) -> bool:
    """
    Check if a service is available based on required environment variables.
    
    Args:
        service_name: Name of the service
        required_env_vars: List of required environment variable names
        
    Returns:
        True if all required env vars are set, False otherwise
    """
    for env_var in required_env_vars:
        if not os.getenv(env_var):
            return False
    return True


def should_skip_real_api_test(service_name: str, required_env_vars: list) -> bool:
    """
    Determine if a real API test should be skipped.
    
    Args:
        service_name: Name of the service
        required_env_vars: List of required environment variable names
        
    Returns:
        True if test should be skipped, False otherwise
    """
    # Skip if not configured to use real APIs
    if not use_real_apis():
        return True
    
    # Skip if required credentials are not available
    if not check_service_available(service_name, required_env_vars):
        return True
    
    return False

