"""
Logging configuration and utilities.

This module provides centralized logging setup with proper formatting
and log levels for the application.
"""

import logging
import sys
from typing import Optional
from pathlib import Path


def setup_logger(
    name: str = "stock_sentiment",
    level: str = "INFO",
    log_file: Optional[Path] = None
) -> logging.Logger:
    """
    Set up and configure application logger.
    
    Args:
        name: Logger name
        level: Logging level (DEBUG, INFO, WARNING, ERROR, CRITICAL)
        log_file: Optional file path for file logging
        
    Returns:
        Configured logger instance
    """
    logger = logging.getLogger(name)
    logger.setLevel(getattr(logging, level.upper(), logging.INFO))
    
    # Avoid adding handlers multiple times
    if logger.handlers:
        return logger
    
    # Create formatter
    formatter = logging.Formatter(
        fmt="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"
    )
    
    # Console handler - use stderr (Streamlit captures stdout)
    console_handler = logging.StreamHandler(sys.stderr)
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(formatter)
    logger.addHandler(console_handler)
    # Prevent propagation to avoid duplicate logs
    logger.propagate = False
    
    # File handler (if specified)
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        file_handler = logging.FileHandler(log_file)
        file_handler.setLevel(logging.DEBUG)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    
    return logger


def get_logger(name: Optional[str] = None) -> logging.Logger:
    """
    Get a logger instance, setting it up if not already configured.
    
    Args:
        name: Optional logger name (defaults to "stock_sentiment")
        
    Returns:
        Logger instance with handlers configured
    """
    if name is None:
        name = "stock_sentiment"
    
    logger = logging.getLogger(name)
    
    # If logger doesn't have handlers, set it up
    if not logger.handlers:
        # Use stderr instead of stdout (Streamlit captures stdout)
        console_handler = logging.StreamHandler(sys.stderr)
        console_handler.setLevel(logging.INFO)
        
        # Create formatter with emoji support for better visibility
        formatter = logging.Formatter(
            fmt="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
            datefmt="%H:%M:%S"
        )
        console_handler.setFormatter(formatter)
        logger.addHandler(console_handler)
        logger.setLevel(logging.INFO)
        # Prevent propagation to root logger (avoids duplicate logs)
        logger.propagate = False
    
    return logger

