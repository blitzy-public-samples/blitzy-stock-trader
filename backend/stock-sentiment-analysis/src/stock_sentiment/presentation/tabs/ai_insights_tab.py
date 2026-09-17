"""
AI insights tab component.
"""

import streamlit as st
import pandas as pd
import plotly.express as px


def render_ai_insights_tab(news_sentiments, current_symbol):
    """Render the AI insights tab."""
    st.header(f"🤖 AI-Powered Insights")
    
    # Aggregate sentiment
    if news_sentiments:
        df = pd.DataFrame(news_sentiments)
        news_agg = df.mean().to_dict()
    else:
        news_agg = {'positive': 0, 'negative': 0, 'neutral': 1}
    
    # Overall sentiment analysis
    net_sentiment = news_agg['positive'] - news_agg['negative']
    sentiment_label = (
        'Positive' if net_sentiment > 0.1
        else 'Negative' if net_sentiment < -0.1
        else 'Neutral'
    )
    perception = (
        'generally positive' if net_sentiment > 0.1
        else 'generally negative' if net_sentiment < -0.1
        else 'relatively neutral'
    )
    
    # Insight card
    st.markdown(
        f"""
        <div style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
                    padding: 2rem; border-radius: 12px; color: white; margin-bottom: 2rem;'>
            <h3 style='color: white; margin: 0 0 1rem 0;'>📊 Overall Sentiment Analysis</h3>
            <div style='display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem;'>
                <div style='text-align: center;'>
                    <div style='font-size: 2rem; font-weight: 700;'>{news_agg['positive']:.1%}</div>
                    <div style='opacity: 0.9;'>Positive</div>
                </div>
                <div style='text-align: center;'>
                    <div style='font-size: 2rem; font-weight: 700;'>{news_agg['negative']:.1%}</div>
                    <div style='opacity: 0.9;'>Negative</div>
                </div>
                <div style='text-align: center;'>
                    <div style='font-size: 2rem; font-weight: 700;'>{news_agg['neutral']:.1%}</div>
                    <div style='opacity: 0.9;'>Neutral</div>
                </div>
            </div>
            <div style='margin-top: 1.5rem; padding-top: 1.5rem; border-top: 1px solid rgba(255,255,255,0.3);'>
                <p style='margin: 0; font-size: 1.1rem;'>
                    <strong>Overall Sentiment:</strong> {sentiment_label} ({net_sentiment:+.2%})
                </p>
                <p style='margin: 0.5rem 0 0 0; opacity: 0.9;'>
                    Market perception of {current_symbol} is {perception}.
                </p>
            </div>
        </div>
        """,
        unsafe_allow_html=True
    )
    
    # Key insights
    st.subheader("🔍 Key Insights")
    
    insights = []
    if news_agg['positive'] > 0.5:
        insights.append(("✅", f"News sentiment is predominantly positive ({news_agg['positive']:.1%})", "success"))
    elif news_agg['negative'] > 0.5:
        insights.append(("⚠️", f"News sentiment is predominantly negative ({news_agg['negative']:.1%})", "warning"))
    
    if net_sentiment > 0.2:
        insights.append(("📈", "Strong positive sentiment overall - Bullish outlook", "success"))
    elif net_sentiment < -0.2:
        insights.append(("📉", "Strong negative sentiment overall - Bearish outlook", "error"))
    else:
        insights.append(("⚖️", "Sentiment is relatively balanced", "info"))
    
    for icon, text, alert_type in insights:
        if alert_type == "success":
            st.success(f"{icon} {text}")
        elif alert_type == "warning":
            st.warning(f"{icon} {text}")
        elif alert_type == "error":
            st.error(f"{icon} {text}")
        else:
            st.info(f"{icon} {text}")
    
    # Sentiment breakdown chart
    st.subheader("📈 Detailed Sentiment Breakdown")
    news_sentiment_df = pd.DataFrame({
        'Sentiment': ['Positive', 'Negative', 'Neutral'],
        'Score': [news_agg['positive'], news_agg['negative'], news_agg['neutral']]
    })
    
    fig_comparison = px.bar(
        news_sentiment_df,
        x='Sentiment',
        y='Score',
        labels={'Score': 'Score', 'Sentiment': 'Sentiment Type'},
        color='Sentiment',
        color_discrete_map={
            'Positive': '#2ecc71',
            'Negative': '#e74c3c',
            'Neutral': '#95a5a6'
        },
        text='Score'
    )
    fig_comparison.update_traces(
        texttemplate='%{text:.1%}',
        textposition='outside',
        marker_line_color='white',
        marker_line_width=2
    )
    fig_comparison.update_layout(
        title="News Sentiment Breakdown",
        height=400,
        showlegend=False,
        plot_bgcolor='rgba(0,0,0,0)',
        paper_bgcolor='rgba(0,0,0,0)',
        yaxis=dict(tickformat='.0%', range=[0, 1])
    )
    st.plotly_chart(fig_comparison, width='stretch', key="ai_sentiment_breakdown")

