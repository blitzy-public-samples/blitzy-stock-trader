"""
API response models using Pydantic.
"""

from pydantic import BaseModel, Field, ConfigDict
from typing import Optional, Dict, Any, List
from datetime import datetime


class SentimentResponse(BaseModel):
    """
    Response model for sentiment analysis endpoint.
    
    Attributes:
        symbol: Stock symbol analyzed
        positive: Aggregated positive sentiment score (0.0 to 1.0)
        negative: Aggregated negative sentiment score (0.0 to 1.0)
        neutral: Aggregated neutral sentiment score (0.0 to 1.0)
        net_sentiment: Net sentiment (positive - negative, -1.0 to 1.0)
        dominant_sentiment: Dominant sentiment label ("positive", "negative", or "neutral")
        timestamp: ISO format timestamp of analysis
        sources_analyzed: Number of articles analyzed
    """
    symbol: str = Field(..., description="Stock symbol analyzed")
    positive: float = Field(..., ge=0.0, le=1.0, description="Positive sentiment score")
    negative: float = Field(..., ge=0.0, le=1.0, description="Negative sentiment score")
    neutral: float = Field(..., ge=0.0, le=1.0, description="Neutral sentiment score")
    net_sentiment: float = Field(..., ge=-1.0, le=1.0, description="Net sentiment (positive - negative)")
    dominant_sentiment: str = Field(..., description="Dominant sentiment label")
    timestamp: str = Field(..., description="ISO format timestamp")
    sources_analyzed: int = Field(..., ge=0, description="Number of articles analyzed")
    
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "symbol": "AAPL",
                "positive": 0.65,
                "negative": 0.20,
                "neutral": 0.15,
                "net_sentiment": 0.45,
                "dominant_sentiment": "positive",
                "timestamp": "2024-12-20T10:30:00",
                "sources_analyzed": 15
            }
        }
    )


class DetailedSentimentResponse(SentimentResponse):
    """
    Detailed response model for sentiment analysis endpoint (dashboard use).
    
    Extends SentimentResponse with additional fields for dashboard display.
    
    Attributes:
        price_data: Stock price and company information
        news: List of news articles
        news_sentiments: Individual sentiment scores for news articles
        social_sentiments: Individual sentiment scores for social media posts
        operation_summary: Operation statistics for display
    """
    price_data: Optional[Dict[str, Any]] = Field(None, description="Stock price and company data")
    news: Optional[List[Dict[str, Any]]] = Field(None, description="List of news articles")
    news_sentiments: Optional[List[Dict[str, float]]] = Field(None, description="Individual sentiment scores for news")
    social_sentiments: Optional[List[Dict[str, float]]] = Field(None, description="Individual sentiment scores for social media")
    operation_summary: Optional[Dict[str, Any]] = Field(None, description="Operation statistics")
    
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "symbol": "AAPL",
                "positive": 0.65,
                "negative": 0.20,
                "neutral": 0.15,
                "net_sentiment": 0.45,
                "dominant_sentiment": "positive",
                "timestamp": "2024-12-20T10:30:00",
                "sources_analyzed": 15,
                "price_data": {
                    "symbol": "AAPL",
                    "price": 175.50,
                    "company_name": "Apple Inc.",
                    "market_cap": 2800000000000,
                    "timestamp": "2024-12-20T10:30:00"
                },
                "news": [
                    {
                        "title": "Apple Reports Record Earnings",
                        "summary": "Apple Inc. reported record-breaking quarterly earnings...",
                        "source": "Yahoo Finance",
                        "url": "https://...",
                        "timestamp": "2024-12-20T09:00:00"
                    }
                ],
                "news_sentiments": [
                    {"positive": 0.8, "negative": 0.1, "neutral": 0.1}
                ],
                "social_sentiments": [],
                "operation_summary": {
                    "redis_used": True,
                    "rag_used": True,
                    "sources_analyzed": 15
                }
            }
        }
    )


class ErrorResponse(BaseModel):
    """
    Error response model.
    
    Attributes:
        error: Error message
        detail: Optional detailed error information
        timestamp: ISO format timestamp of error
    """
    error: str = Field(..., description="Error message")
    detail: Optional[str] = Field(None, description="Detailed error information")
    timestamp: str = Field(default_factory=lambda: datetime.now().isoformat(), description="ISO format timestamp")
    
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "error": "Invalid stock symbol",
                "detail": "Symbol 'INVALID' not found",
                "timestamp": "2024-12-20T10:30:00"
            }
        }
    )


class HealthResponse(BaseModel):
    """
    Health check response model.
    
    Attributes:
        status: Overall health status ("healthy" or "degraded")
        services: Dictionary of service health statuses
        timestamp: ISO format timestamp
    """
    status: str = Field(..., description="Overall health status")
    services: Dict[str, str] = Field(..., description="Service health statuses")
    timestamp: str = Field(default_factory=lambda: datetime.now().isoformat(), description="ISO format timestamp")
    
    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "status": "healthy",
                "services": {
                    "redis": "available",
                    "rag": "available",
                    "azure_openai": "available"
                },
                "timestamp": "2024-12-20T10:30:00"
            }
        }
    )

