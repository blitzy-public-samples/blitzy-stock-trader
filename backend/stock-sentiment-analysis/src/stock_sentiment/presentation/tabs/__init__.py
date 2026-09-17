"""
Tab components for the Streamlit application.
"""

from .overview_tab import render_overview_tab
from .price_analysis_tab import render_price_analysis_tab
from .news_sentiment_tab import render_news_sentiment_tab
from .technical_analysis_tab import render_technical_analysis_tab
from .ai_insights_tab import render_ai_insights_tab
from .comparison_tab import render_comparison_tab

__all__ = [
    'render_overview_tab',
    'render_price_analysis_tab',
    'render_news_sentiment_tab',
    'render_technical_analysis_tab',
    'render_ai_insights_tab',
    'render_comparison_tab',
]

