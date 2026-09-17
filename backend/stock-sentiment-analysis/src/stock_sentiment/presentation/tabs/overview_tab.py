"""
Overview tab component.
"""

import streamlit as st
import pandas as pd
import plotly.express as px


def render_overview_tab(data, news_sentiments, social_sentiments, current_symbol):
    """Render the overview tab."""
    # Aggregate sentiment scores
    def aggregate_sentiments(sentiments):
        """Aggregate sentiment scores from multiple analyses."""
        if not sentiments:
            return {'positive': 0, 'negative': 0, 'neutral': 1}
        df = pd.DataFrame(sentiments)
        return df.mean().to_dict()

    news_agg = aggregate_sentiments(news_sentiments)
    price_data = data.get('price_data', {})
    company_name = price_data.get('company_name', current_symbol)

    # Hero section with key metrics
    st.markdown(
        f"""
        <div style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
                    padding: 2rem; border-radius: 12px; color: white; margin-bottom: 2rem;'>
            <div style='display: flex; justify-content: space-between; align-items: center;'>
                <div>
                    <h2 style='color: white; margin: 0;'>{company_name}</h2>
                    <p style='margin: 0.5rem 0 0 0; opacity: 0.9; font-size: 1.1rem;'>{current_symbol}</p>
                </div>
                <div style='text-align: right;'>
                    <h1 style='color: white; margin: 0; border: none;'>${price_data.get('price', 0):.2f}</h1>
                </div>
            </div>
        </div>
        """,
        unsafe_allow_html=True
    )
    
    # Key metrics in a grid
    col1, col2, col3, col4 = st.columns(4)
    
    with col1:
        market_cap = price_data.get('market_cap', 0)
        if market_cap > 0:
            st.metric("Market Cap", f"${market_cap/1e9:.2f}B", help="Total market capitalization")
        else:
            st.metric("Market Cap", "N/A")
    
    with col2:
        net_sentiment = news_agg['positive'] - news_agg['negative']
        delta_color = "normal" if abs(net_sentiment) < 0.1 else ("inverse" if net_sentiment < 0 else "normal")
        st.metric(
            "Net Sentiment",
            f"{net_sentiment:+.2%}",
            delta=f"{'Positive' if net_sentiment > 0 else 'Negative' if net_sentiment < 0 else 'Neutral'}",
            delta_color=delta_color,
            help="Overall sentiment score from news analysis"
        )
    
    with col3:
        st.metric("Positive", f"{news_agg['positive']:.1%}", help="Positive sentiment percentage")
    
    with col4:
        st.metric("Negative", f"{news_agg['negative']:.1%}", help="Negative sentiment percentage")
    
    st.markdown("<br>", unsafe_allow_html=True)
    
    # Sentiment visualization
    st.subheader("📊 Sentiment Breakdown")
    news_sentiment_df = pd.DataFrame({
        'Sentiment': ['Positive', 'Negative', 'Neutral'],
        'Score': [news_agg['positive'], news_agg['negative'], news_agg['neutral']]
    })
    
    fig_news = px.bar(
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
    fig_news.update_traces(
        texttemplate='%{text:.1%}',
        textposition='outside',
        marker_line_color='white',
        marker_line_width=2
    )
    fig_news.update_layout(
        showlegend=False,
        height=400,
        plot_bgcolor='rgba(0,0,0,0)',
        paper_bgcolor='rgba(0,0,0,0)',
        font=dict(size=12),
        yaxis=dict(tickformat='.0%', range=[0, 1]),
        clickmode='event+select'
    )
    fig_news.update_traces(hovertemplate="<b>%{x}</b><br>Score: %{y:.1%}<extra></extra>")
    st.plotly_chart(fig_news, width='stretch', key="overview_sentiment_chart", on_select="rerun")
    
    # Quick insights
    st.markdown("---")
    st.subheader("💡 Quick Insights")
    
    insights_col1, insights_col2 = st.columns(2)
    
    with insights_col1:
        if news_agg['positive'] > 0.5:
            st.success(f"✅ **Strong Positive Sentiment** ({news_agg['positive']:.1%})")
        elif news_agg['negative'] > 0.5:
            st.error(f"⚠️ **Strong Negative Sentiment** ({news_agg['negative']:.1%})")
        else:
            st.info("⚖️ **Balanced Sentiment** - Mixed market perception")
    
    with insights_col2:
        if net_sentiment > 0.2:
            st.success("📈 **Bullish Outlook** - Positive market sentiment")
        elif net_sentiment < -0.2:
            st.warning("📉 **Bearish Outlook** - Negative market sentiment")
        else:
            st.info("📊 **Neutral Outlook** - Balanced market sentiment")
    
    if not data.get('social_media'):
        st.info(
            "ℹ️ **Note:** Social media sentiment data requires API integration. "
            "Currently using news articles for sentiment analysis."
        )

