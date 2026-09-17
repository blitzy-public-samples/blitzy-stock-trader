"""
UI helper functions for enhanced user experience.

This module provides utility functions for:
- Toast notifications
- Data export
- Search and filtering
- Error handling UI
- AI comparison insights
"""

import json
import csv
import io
from typing import List, Dict, Optional, Any
from datetime import datetime
import pandas as pd

from ..utils.logger import get_logger

logger = get_logger(__name__)


def show_toast(message: str, type: str = "success", duration: int = 3000) -> None:
    """
    Display a toast notification.
    
    Args:
        message: Message to display
        type: Type of toast (success, error, warning, info)
        duration: Duration in milliseconds
    """
    import streamlit as st
    
    colors = {
        "success": "#2ecc71",
        "error": "#e74c3c",
        "warning": "#f39c12",
        "info": "#3498db"
    }
    
    color = colors.get(type, colors["info"])
    
    st.markdown(
        f"""
        <div id="toast-{datetime.now().timestamp()}" style="
            position: fixed;
            top: 20px;
            right: 20px;
            background: {color};
            color: white;
            padding: 1rem 1.5rem;
            border-radius: 8px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
            z-index: 9999;
            animation: slideIn 0.3s ease-out;
        ">
            {message}
        </div>
        <style>
            @keyframes slideIn {{
                from {{
                    transform: translateX(400px);
                    opacity: 0;
                }}
                to {{
                    transform: translateX(0);
                    opacity: 1;
                }}
            }}
        </style>
        <script>
            setTimeout(function() {{
                var toast = document.getElementById('toast-{datetime.now().timestamp()}');
                if (toast) {{
                    toast.style.animation = 'slideOut 0.3s ease-out';
                    setTimeout(function() {{ toast.remove(); }}, 300);
                }}
            }}, {duration});
            
            @keyframes slideOut {{
                from {{
                    transform: translateX(0);
                    opacity: 1;
                }}
                to {{
                    transform: translateX(400px);
                    opacity: 0;
                }}
            }}
        </script>
        """,
        unsafe_allow_html=True
    )


def export_to_csv(data: Dict, news_sentiments: List[Dict], symbol: str) -> str:
    """
    Export data to CSV format.
    
    Args:
        data: Stock data dictionary
        news_sentiments: List of sentiment analysis results
        symbol: Stock symbol
        
    Returns:
        CSV string
    """
    output = io.StringIO()
    writer = csv.writer(output)
    
    # Header
    writer.writerow(["Stock Sentiment Analysis Report"])
    writer.writerow([f"Symbol: {symbol}"])
    writer.writerow([f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"])
    writer.writerow([])
    
    # Stock data
    price_data = data.get('price_data', {})
    writer.writerow(["Stock Information"])
    writer.writerow(["Company Name", price_data.get('company_name', 'N/A')])
    writer.writerow(["Current Price", f"${price_data.get('price', 0):.2f}"])
    writer.writerow(["Market Cap", f"${price_data.get('market_cap', 0):,.0f}"])
    writer.writerow([])
    
    # News articles with sentiment
    writer.writerow(["News Articles with Sentiment Analysis"])
    writer.writerow([
        "Title", "Source", "URL", "Timestamp",
        "Positive", "Negative", "Neutral", "Dominant Sentiment"
    ])
    
    news = data.get('news', [])
    for i, article in enumerate(news):
        sentiment = news_sentiments[i] if i < len(news_sentiments) else {
            'positive': 0, 'negative': 0, 'neutral': 1
        }
        
        # Determine dominant sentiment
        if sentiment['positive'] > sentiment['negative'] and sentiment['positive'] > sentiment['neutral']:
            dominant = "Positive"
        elif sentiment['negative'] > sentiment['positive'] and sentiment['negative'] > sentiment['neutral']:
            dominant = "Negative"
        else:
            dominant = "Neutral"
        
        timestamp = article.get('timestamp', '')
        if isinstance(timestamp, datetime):
            timestamp = timestamp.strftime('%Y-%m-%d %H:%M:%S')
        
        writer.writerow([
            article.get('title', ''),
            article.get('source', ''),
            article.get('url', ''),
            timestamp,
            f"{sentiment['positive']:.4f}",
            f"{sentiment['negative']:.4f}",
            f"{sentiment['neutral']:.4f}",
            dominant
        ])
    
    # Summary statistics
    writer.writerow([])
    writer.writerow(["Summary Statistics"])
    if news_sentiments:
        df = pd.DataFrame(news_sentiments)
        writer.writerow(["Average Positive", f"{df['positive'].mean():.4f}"])
        writer.writerow(["Average Negative", f"{df['negative'].mean():.4f}"])
        writer.writerow(["Average Neutral", f"{df['neutral'].mean():.4f}"])
        writer.writerow(["Total Articles", len(news)])
    
    return output.getvalue()


def export_to_json(data: Dict, news_sentiments: List[Dict], symbol: str) -> str:
    """
    Export data to JSON format.
    
    Args:
        data: Stock data dictionary
        news_sentiments: List of sentiment analysis results
        symbol: Stock symbol
        
    Returns:
        JSON string
    """
    export_data = {
        "symbol": symbol,
        "generated_at": datetime.now().isoformat(),
        "stock_data": data.get('price_data', {}),
        "articles": []
    }
    
    news = data.get('news', [])
    for i, article in enumerate(news):
        sentiment = news_sentiments[i] if i < len(news_sentiments) else {
            'positive': 0, 'negative': 0, 'neutral': 1
        }
        
        article_data = {
            "title": article.get('title', ''),
            "source": article.get('source', ''),
            "url": article.get('url', ''),
            "summary": article.get('summary', ''),
            "timestamp": article.get('timestamp', '').isoformat() if isinstance(article.get('timestamp'), datetime) else str(article.get('timestamp', '')),
            "sentiment": sentiment
        }
        export_data["articles"].append(article_data)
    
    # Add summary
    if news_sentiments:
        df = pd.DataFrame(news_sentiments)
        export_data["summary"] = {
            "average_positive": float(df['positive'].mean()),
            "average_negative": float(df['negative'].mean()),
            "average_neutral": float(df['neutral'].mean()),
            "total_articles": len(news)
        }
    
    return json.dumps(export_data, indent=2, default=str)


def filter_articles(
    articles: List[Dict],
    search_query: Optional[str] = None,
    sentiment_filter: Optional[str] = None,
    source_filter: Optional[List[str]] = None,
    date_range: Optional[tuple] = None,
    sentiments: Optional[List[Dict]] = None
) -> List[Dict]:
    """
    Filter articles based on various criteria.
    
    Args:
        articles: List of article dictionaries
        search_query: Text to search in title, summary, or source
        sentiment_filter: Filter by sentiment ('positive', 'negative', 'neutral')
        source_filter: List of sources to include
        date_range: Tuple of (start_date, end_date)
        sentiments: List of sentiment dictionaries (must match articles order)
        
    Returns:
        Filtered list of articles
    """
    filtered = articles
    
    # Search query filter
    if search_query:
        query_lower = search_query.lower()
        filtered = [
            article for article in filtered
            if query_lower in article.get('title', '').lower()
            or query_lower in article.get('summary', '').lower()
            or query_lower in article.get('source', '').lower()
        ]
    
    # Source filter
    if source_filter:
        filtered = [
            article for article in filtered
            if any(source in article.get('source', '') for source in source_filter)
        ]
    
    # Date range filter
    if date_range:
        start_date, end_date = date_range
        filtered = [
            article for article in filtered
            if article.get('timestamp')
            and (start_date is None or article['timestamp'] >= start_date)
            and (end_date is None or article['timestamp'] <= end_date)
        ]
    
    # Sentiment filter
    if sentiment_filter and sentiments and len(sentiments) == len(articles):
        filtered_with_sentiment = []
        for i, article in enumerate(filtered):
            # Find original index in articles list
            try:
                original_idx = articles.index(article)
                if original_idx < len(sentiments):
                    sentiment = sentiments[original_idx]
                    if sentiment_filter == 'positive' and sentiment['positive'] > max(sentiment['negative'], sentiment['neutral']):
                        filtered_with_sentiment.append(article)
                    elif sentiment_filter == 'negative' and sentiment['negative'] > max(sentiment['positive'], sentiment['neutral']):
                        filtered_with_sentiment.append(article)
                    elif sentiment_filter == 'neutral' and sentiment['neutral'] > max(sentiment['positive'], sentiment['negative']):
                        filtered_with_sentiment.append(article)
            except ValueError:
                # Article not in original list, skip sentiment filter
                filtered_with_sentiment.append(article)
        filtered = filtered_with_sentiment
    
    return filtered


def get_error_recovery_ui(error_message: str, retry_key: str = "retry") -> bool:
    """
    Display error message with retry option.
    
    Args:
        error_message: Error message to display
        retry_key: Unique key for retry button
        
    Returns:
        True if retry was clicked, False otherwise
    """
    import streamlit as st
    
    st.error(f"❌ {error_message}")
    
    col1, col2 = st.columns([1, 3])
    with col1:
        retry_clicked = st.button("🔄 Retry", key=retry_key, type="primary")
    
    with col2:
        st.info(
            "💡 **Tips:**\n"
            "- Check your internet connection\n"
            "- Verify the stock symbol is correct\n"
            "- Try again in a few moments\n"
            "- Some APIs have rate limits"
        )
    
    return retry_clicked


def generate_comparison_insights(
    comparison_data: Dict[str, Dict],
    comparison_sentiments: Dict[str, Dict],
    analyzer
) -> str:
    """
    Generate AI-powered comparison insights.
    
    Args:
        comparison_data: Dictionary mapping symbols to their data
        comparison_sentiments: Dictionary mapping symbols to sentiment scores
        analyzer: SentimentAnalyzer instance for AI insights
        
    Returns:
        AI-generated comparison insights text
    """
    try:
        # Build comparison summary
        symbols = list(comparison_sentiments.keys())
        if len(symbols) < 2:
            return "Please select at least 2 stocks to compare."
        
        # Create comparison text for AI analysis
        comparison_text = f"Comparing {len(symbols)} stocks:\n\n"
        
        for sym in symbols:
            sent = comparison_sentiments[sym]
            price_data = comparison_data[sym].get('price_data', {})
            price = price_data.get('price', 0)
            net_sentiment = sent['positive'] - sent['negative']
            
            comparison_text += f"{sym}:\n"
            comparison_text += f"  - Price: ${price:.2f}\n"
            comparison_text += f"  - Positive Sentiment: {sent['positive']:.1%}\n"
            comparison_text += f"  - Negative Sentiment: {sent['negative']:.1%}\n"
            comparison_text += f"  - Net Sentiment: {net_sentiment:+.2%}\n\n"
        
        # Use analyzer to generate insights (if it has a method for this)
        # Otherwise, create a prompt for AI analysis
        prompt = f"""You are an expert financial analyst. Analyze the following stock comparison and provide:
1. Which stock has the most positive sentiment and why
2. Which stock has the most negative sentiment and why
3. Key differences in market perception
4. Investment implications
5. Risk assessment

{comparison_text}

Provide a concise, professional analysis (3-5 paragraphs)."""
        
        # Try to use analyzer's client directly for comparison insights
        if hasattr(analyzer, 'client') and analyzer.client:
            try:
                response = analyzer.client.chat.completions.create(
                    model=analyzer.deployment_name,
                    messages=[
                        {
                            "role": "system",
                            "content": "You are an expert financial analyst specializing in stock market sentiment analysis and comparative investment analysis."
                        },
                        {
                            "role": "user",
                            "content": prompt
                        }
                    ],
                    temperature=0.7,
                    max_tokens=500
                )
                
                insights = response.choices[0].message.content
                logger.info("Generated AI comparison insights")
                return insights
            except Exception as e:
                logger.warning(f"Failed to generate AI insights: {e}, using fallback")
                return _generate_fallback_insights(comparison_sentiments, comparison_data)
        else:
            return _generate_fallback_insights(comparison_sentiments, comparison_data)
            
    except Exception as e:
        logger.error(f"Error generating comparison insights: {e}")
        return "Unable to generate comparison insights at this time."


def _generate_fallback_insights(
    comparison_sentiments: Dict[str, Dict],
    comparison_data: Dict[str, Dict]
) -> str:
    """
    Generate fallback insights without AI.
    
    Args:
        comparison_sentiments: Dictionary mapping symbols to sentiment scores
        comparison_data: Dictionary mapping symbols to their data
        
    Returns:
        Fallback insights text
    """
    symbols = list(comparison_sentiments.keys())
    
    # Find best and worst sentiment
    best_sentiment = max(symbols, key=lambda s: comparison_sentiments[s]['positive'] - comparison_sentiments[s]['negative'])
    worst_sentiment = min(symbols, key=lambda s: comparison_sentiments[s]['positive'] - comparison_sentiments[s]['negative'])
    
    best_net = comparison_sentiments[best_sentiment]['positive'] - comparison_sentiments[best_sentiment]['negative']
    worst_net = comparison_sentiments[worst_sentiment]['positive'] - comparison_sentiments[worst_sentiment]['negative']
    
    insights = f"## Comparison Analysis\n\n"
    insights += f"**Most Positive Sentiment:** {best_sentiment} (Net: {best_net:+.2%})\n"
    insights += f"- Positive: {comparison_sentiments[best_sentiment]['positive']:.1%}\n"
    insights += f"- Negative: {comparison_sentiments[best_sentiment]['negative']:.1%}\n\n"
    
    insights += f"**Most Negative Sentiment:** {worst_sentiment} (Net: {worst_net:+.2%})\n"
    insights += f"- Positive: {comparison_sentiments[worst_sentiment]['positive']:.1%}\n"
    insights += f"- Negative: {comparison_sentiments[worst_sentiment]['negative']:.1%}\n\n"
    
    insights += f"**Key Differences:**\n"
    for sym in symbols:
        net = comparison_sentiments[sym]['positive'] - comparison_sentiments[sym]['negative']
        price = comparison_data[sym].get('price_data', {}).get('price', 0)
        insights += f"- {sym}: ${price:.2f}, Net Sentiment: {net:+.2%}\n"
    
    return insights
