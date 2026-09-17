"""
Retry utilities with exponential backoff for resilient API calls.

This module provides retry decorators and utilities following industry
best practices for handling transient failures.
"""

import time
import random
from functools import wraps
from typing import Callable, Type, Tuple, Optional, Any
from openai import RateLimitError, APIConnectionError, APITimeoutError

from ..utils.logger import get_logger

logger = get_logger(__name__)


# Retryable exceptions for Azure OpenAI
RETRYABLE_EXCEPTIONS = (
    RateLimitError,
    APIConnectionError,
    APITimeoutError,
    ConnectionError,
    TimeoutError,
)


def retry_with_exponential_backoff(
    max_attempts: int = 3,
    initial_delay: float = 1.0,
    max_delay: float = 60.0,
    exponential_base: float = 2.0,
    jitter: bool = True,
    retryable_exceptions: Tuple[Type[Exception], ...] = RETRYABLE_EXCEPTIONS
):
    """
    Decorator for retrying functions with exponential backoff.
    
    Industry best practice: Exponential backoff with jitter prevents
    thundering herd problems and reduces server load during outages.
    
    Args:
        max_attempts: Maximum number of retry attempts (default: 3)
        initial_delay: Initial delay in seconds (default: 1.0)
        max_delay: Maximum delay in seconds (default: 60.0)
        exponential_base: Base for exponential backoff (default: 2.0)
        jitter: Whether to add random jitter to delays (default: True)
        retryable_exceptions: Tuple of exception types to retry on
        
    Returns:
        Decorated function with retry logic
        
    Example:
        >>> @retry_with_exponential_backoff(max_attempts=3)
        >>> def call_api():
        >>>     return api_client.get_data()
    """
    def decorator(func: Callable) -> Callable:
        @wraps(func)
        def wrapper(*args, **kwargs) -> Any:
            last_exception = None
            
            for attempt in range(1, max_attempts + 1):
                try:
                    return func(*args, **kwargs)
                    
                except retryable_exceptions as e:
                    last_exception = e
                    
                    if attempt == max_attempts:
                        logger.error(
                            f"Max retry attempts ({max_attempts}) reached for {func.__name__}. "
                            f"Last error: {e}"
                        )
                        raise
                    
                    # Calculate delay with exponential backoff
                    delay = min(
                        initial_delay * (exponential_base ** (attempt - 1)),
                        max_delay
                    )
                    
                    # Add jitter to prevent synchronized retries
                    if jitter:
                        jitter_amount = random.uniform(0, delay * 0.1)
                        delay += jitter_amount
                    
                    logger.warning(
                        f"Attempt {attempt}/{max_attempts} failed for {func.__name__}: {e}. "
                        f"Retrying in {delay:.2f}s..."
                    )
                    
                    time.sleep(delay)
                    
                except Exception as e:
                    # Non-retryable exception, raise immediately
                    logger.error(f"Non-retryable error in {func.__name__}: {e}")
                    raise
            
            # Should never reach here, but just in case
            if last_exception:
                raise last_exception
                
        return wrapper
    return decorator


class RetryConfig:
    """Configuration for retry behavior."""
    
    def __init__(
        self,
        max_attempts: int = 3,
        initial_delay: float = 1.0,
        max_delay: float = 60.0,
        exponential_base: float = 2.0,
        jitter: bool = True
    ):
        self.max_attempts = max_attempts
        self.initial_delay = initial_delay
        self.max_delay = max_delay
        self.exponential_base = exponential_base
        self.jitter = jitter


def is_retryable_error(exception: Exception) -> bool:
    """
    Check if an exception is retryable.
    
    Args:
        exception: Exception to check
        
    Returns:
        True if exception is retryable, False otherwise
    """
    return isinstance(exception, RETRYABLE_EXCEPTIONS)

