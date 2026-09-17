"""
Sentiment analysis data models.

This module defines the data structures for sentiment analysis results.
"""

from dataclasses import dataclass
from typing import Dict, Optional


@dataclass
class SentimentScores:
    """
    Sentiment scores for a piece of text.
    
    Attributes:
        positive: Positive sentiment score (0.0 to 1.0)
        negative: Negative sentiment score (0.0 to 1.0)
        neutral: Neutral sentiment score (0.0 to 1.0)
        
    Note:
        Scores should sum to approximately 1.0
    """
    positive: float
    negative: float
    neutral: float
    
    def __post_init__(self):
        """Validate and normalize scores."""
        # Ensure scores are in valid range
        self.positive = max(0.0, min(1.0, self.positive))
        self.negative = max(0.0, min(1.0, self.negative))
        self.neutral = max(0.0, min(1.0, self.neutral))
        
        # Normalize to ensure they sum to 1.0
        total = self.positive + self.negative + self.neutral
        if total > 0:
            self.positive /= total
            self.negative /= total
            self.neutral /= total
        else:
            # Fallback: all neutral
            self.neutral = 1.0
            self.positive = 0.0
            self.negative = 0.0
    
    def to_dict(self) -> Dict[str, float]:
        """
        Convert to dictionary format.
        
        Returns:
            Dictionary with sentiment scores
        """
        return {
            "positive": self.positive,
            "negative": self.negative,
            "neutral": self.neutral
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, float]) -> "SentimentScores":
        """
        Create from dictionary.
        
        Args:
            data: Dictionary with positive, negative, neutral keys
            
        Returns:
            SentimentScores instance
        """
        return cls(
            positive=data.get("positive", 0.0),
            negative=data.get("negative", 0.0),
            neutral=data.get("neutral", 1.0)
        )
    
    @property
    def net_sentiment(self) -> float:
        """
        Calculate net sentiment (positive - negative).
        
        Returns:
            Net sentiment score (-1.0 to 1.0)
        """
        return self.positive - self.negative
    
    @property
    def dominant_sentiment(self) -> str:
        """
        Get the dominant sentiment label.
        
        Returns:
            "positive", "negative", or "neutral"
        """
        if self.positive > self.negative and self.positive > self.neutral:
            return "positive"
        elif self.negative > self.positive and self.negative > self.neutral:
            return "negative"
        else:
            return "neutral"



