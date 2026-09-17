"""
Configuration management for the Stock Sentiment Analysis application.

This module handles environment variable loading, validation, and configuration
for Azure OpenAI, Redis, and application settings.
"""

from .settings import Settings, get_settings

__all__ = ["Settings", "get_settings"]

