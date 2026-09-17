"""
Circuit breaker pattern implementation for resilient API calls.

The circuit breaker prevents cascading failures by stopping requests
to a failing service and allowing it to recover.
"""

import time
from typing import Callable, Any, Optional
from enum import Enum

from ..utils.logger import get_logger

logger = get_logger(__name__)


class CircuitState(Enum):
    """Circuit breaker states."""
    CLOSED = "closed"  # Normal operation, requests allowed
    OPEN = "open"  # Service failing, requests blocked
    HALF_OPEN = "half_open"  # Testing if service recovered


class CircuitBreaker:
    """
    Circuit breaker for preventing cascading failures.
    
    The circuit breaker has three states:
    - CLOSED: Normal operation, all requests pass through
    - OPEN: Service is failing, requests are blocked immediately
    - HALF_OPEN: Testing if service has recovered
    
    Industry best practice: Prevents one failing service from bringing
    down the entire application.
    
    Attributes:
        failure_threshold: Number of failures before opening circuit
        timeout: Seconds to wait before trying half-open
        failure_count: Current number of consecutive failures
        last_failure_time: Timestamp of last failure
        state: Current circuit state
        
    Example:
        >>> breaker = CircuitBreaker(failure_threshold=5, timeout=60)
        >>> try:
        >>>     result = breaker.call(api_function, arg1, arg2)
        >>> except CircuitBreakerOpenError:
        >>>     # Service is down, use fallback
        >>>     result = fallback_function()
    """
    
    def __init__(
        self,
        failure_threshold: int = 5,
        timeout: int = 60,
        name: str = "circuit_breaker"
    ):
        """
        Initialize circuit breaker.
        
        Args:
            failure_threshold: Number of failures before opening circuit (default: 5)
            timeout: Seconds to wait before trying half-open (default: 60)
            name: Name for logging (default: "circuit_breaker")
        """
        self.failure_threshold = failure_threshold
        self.timeout = timeout
        self.name = name
        self.failure_count = 0
        self.last_failure_time: Optional[float] = None
        self.state = CircuitState.CLOSED
        self.success_count = 0  # Track successes in half-open state
    
    def call(self, func: Callable, *args, **kwargs) -> Any:
        """
        Execute function with circuit breaker protection.
        
        Args:
            func: Function to call
            *args: Positional arguments for function
            **kwargs: Keyword arguments for function
            
        Returns:
            Result from function call
            
        Raises:
            CircuitBreakerOpenError: If circuit is open
            Exception: Any exception from the function call
        """
        # Check circuit state
        if self.state == CircuitState.OPEN:
            # Check if timeout has passed
            if self.last_failure_time and (time.time() - self.last_failure_time) > self.timeout:
                logger.info(f"Circuit breaker {self.name}: Moving to HALF_OPEN state")
                self.state = CircuitState.HALF_OPEN
                self.success_count = 0
            else:
                raise CircuitBreakerOpenError(
                    f"Circuit breaker {self.name} is OPEN. "
                    f"Service is failing. Wait {self.timeout}s before retry."
                )
        
        # Try to call the function
        try:
            result = func(*args, **kwargs)
            
            # Success - reset failure count
            if self.state == CircuitState.HALF_OPEN:
                self.success_count += 1
                # Need multiple successes to close circuit
                if self.success_count >= 2:
                    logger.info(f"Circuit breaker {self.name}: Moving to CLOSED state (recovered)")
                    self.state = CircuitState.CLOSED
                    self.failure_count = 0
                    self.success_count = 0
            else:
                # Normal operation - reset on success
                self.failure_count = 0
            
            return result
            
        except Exception as e:
            # Failure - increment count
            self.failure_count += 1
            self.last_failure_time = time.time()
            
            if self.state == CircuitState.HALF_OPEN:
                # Failed during half-open - back to open
                logger.warning(f"Circuit breaker {self.name}: Failed in HALF_OPEN, moving to OPEN")
                self.state = CircuitState.OPEN
                self.success_count = 0
            elif self.failure_count >= self.failure_threshold:
                # Too many failures - open circuit
                logger.error(
                    f"Circuit breaker {self.name}: Opening circuit after {self.failure_count} failures"
                )
                self.state = CircuitState.OPEN
            
            # Re-raise the original exception
            raise
    
    def reset(self):
        """Manually reset circuit breaker to CLOSED state."""
        logger.info(f"Circuit breaker {self.name}: Manually reset to CLOSED")
        self.state = CircuitState.CLOSED
        self.failure_count = 0
        self.success_count = 0
        self.last_failure_time = None
    
    def get_state(self) -> CircuitState:
        """Get current circuit state."""
        return self.state
    
    def get_stats(self) -> dict:
        """Get circuit breaker statistics."""
        return {
            'state': self.state.value,
            'failure_count': self.failure_count,
            'success_count': self.success_count,
            'last_failure_time': self.last_failure_time,
            'time_since_last_failure': (
                time.time() - self.last_failure_time 
                if self.last_failure_time else None
            )
        }


class CircuitBreakerOpenError(Exception):
    """Exception raised when circuit breaker is open."""
    pass

