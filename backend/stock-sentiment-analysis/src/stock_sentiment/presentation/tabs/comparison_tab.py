"""
Stock comparison tab component.
"""

import streamlit as st
import pandas as pd
import plotly.express as px
from typing import Optional

from ...utils.ui_helpers import show_toast
from ...utils.logger import get_logger
from ...presentation.api_client import SentimentAPIClient

logger = get_logger(__name__)


def render_comparison_tab(api_client: Optional[SentimentAPIClient]):
    """Render the stock comparison tab."""
    st.header("📊 Stock Comparison")
    st.markdown("Compare multiple stocks side-by-side with AI-powered insights")
    
    # Initialize comparison state
    if 'comparison_stocks' not in st.session_state:
        st.session_state.comparison_stocks = []
    if 'comparison_data' not in st.session_state:
        st.session_state.comparison_data = {}
    if 'comparison_sentiments' not in st.session_state:
        st.session_state.comparison_sentiments = {}
    if 'comparison_insights' not in st.session_state:
        st.session_state.comparison_insights = None
    
    current_symbol = st.session_state.get('symbol', 'AAPL')
    
    # Stock selector options
    stock_options = ['AAPL', 'MSFT', 'GOOGL', 'TSLA', 'AMZN', 'META', 'NVDA', 'NFLX', 'AMD', 'INTC', 'ORCL', 'JPM', 'V', 'WMT', 'MA', 'PG', 'UNH', 'HD', 'DIS', 'BAC']
    
    # Only use current_symbol as default if it's in the options list
    default_value = [current_symbol] if current_symbol and current_symbol in stock_options else []
    
    # Stock selector
    col1, col2 = st.columns([3, 1])
    with col1:
        compare_stocks = st.multiselect(
            "Select stocks to compare (2-5 recommended)",
            options=stock_options,
            default=default_value,
            key="compare_stocks_select",
            help="Select 2 or more stocks to compare their sentiment and performance"
        )
    
    with col2:
        if st.button("🔄 Compare", type="primary", width='stretch'):
            if len(compare_stocks) < 2:
                st.warning("⚠️ Please select at least 2 stocks to compare")
            else:
                st.session_state.comparison_stocks = compare_stocks
                st.session_state.comparison_data = {}
                st.session_state.comparison_sentiments = {}
                st.session_state.comparison_insights = None
                
                if not api_client:
                    st.error("❌ API client not available. Please ensure API mode is enabled.")
                    return
                
                # Collect data for all stocks using batch API
                progress_bar = st.progress(0)
                status_text = st.empty()
                
                try:
                    status_text.text(f"📊 Analyzing {len(compare_stocks)} stocks...")
                    progress_bar.progress(0.3)
                    
                    # Get batch sentiment analysis
                    batch_results = api_client.get_sentiment_batch(
                        symbols=compare_stocks,
                        detailed=True,
                        cache_enabled=True
                    )
                    
                    progress_bar.progress(0.7)
                    
                    # Process results
                    for result in batch_results:
                        sym = result['symbol']
                        
                        # Extract data
                        price_data = result.get('price_data', {})
                        news = result.get('news', [])
                        news_sentiments = result.get('news_sentiments', [])
                        
                        # Store comparison data
                        st.session_state.comparison_data[sym] = {
                            'price_data': price_data,
                            'news': news
                        }
                        
                        # Aggregate sentiment from individual article sentiments
                        if news_sentiments:
                            df = pd.DataFrame(news_sentiments)
                            st.session_state.comparison_sentiments[sym] = {
                                'positive': float(df['positive'].mean()),
                                'negative': float(df['negative'].mean()),
                                'neutral': float(df['neutral'].mean())
                            }
                        else:
                            # Fallback to aggregated sentiment
                            st.session_state.comparison_sentiments[sym] = {
                                'positive': result.get('positive', 0),
                                'negative': result.get('negative', 0),
                                'neutral': result.get('neutral', 0)
                            }
                    
                    progress_bar.progress(0.9)
                    
                    # Generate AI insights
                    if st.session_state.comparison_data and st.session_state.comparison_sentiments:
                        status_text.text("🤖 Generating AI comparison insights...")
                        st.session_state.comparison_insights = api_client.get_comparison_insights(
                            st.session_state.comparison_data,
                            st.session_state.comparison_sentiments
                        )
                    
                    progress_bar.progress(1.0)
                    progress_bar.empty()
                    status_text.empty()
                    
                    show_toast("✅ Comparison complete!", "success")
                    st.rerun()
                    
                except Exception as e:
                    logger.error(f"Error in comparison: {e}")
                    progress_bar.empty()
                    status_text.empty()
                    st.error(f"❌ Failed to compare stocks: {str(e)}")
    
    # Display comparison if data available
    if st.session_state.get('comparison_data') and st.session_state.get('comparison_sentiments'):
        st.markdown("---")
        
        # AI Insights Section
        if st.session_state.comparison_insights:
            st.subheader("🤖 AI-Powered Comparison Insights")
            st.markdown(
                f"""
                <div style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
                            padding: 1.5rem; border-radius: 12px; color: white; margin-bottom: 2rem;'>
                    <div style='white-space: pre-wrap;'>{st.session_state.comparison_insights}</div>
                </div>
                """,
                unsafe_allow_html=True
            )
            st.markdown("---")
        
        # Comparison metrics
        comp_df = pd.DataFrame([
            {
                'Symbol': sym,
                'Price': comp_data.get('price_data', {}).get('price', 0),
                'Market Cap': comp_data.get('price_data', {}).get('market_cap', 0) / 1e9,
                'Positive': sent['positive'],
                'Negative': sent['negative'],
                'Neutral': sent['neutral'],
                'Net Sentiment': sent['positive'] - sent['negative']
            }
            for sym, comp_data in st.session_state.comparison_data.items()
            if sym in st.session_state.comparison_sentiments
            for sent in [st.session_state.comparison_sentiments[sym]]
        ])
        
        if not comp_df.empty:
            # Comparison chart
            st.subheader("📊 Sentiment Comparison Chart")
            fig_comp = px.bar(
                comp_df,
                x='Symbol',
                y=['Positive', 'Negative', 'Neutral'],
                title="Sentiment Comparison Across Stocks",
                barmode='group',
                color_discrete_map={
                    'Positive': '#2ecc71',
                    'Negative': '#e74c3c',
                    'Neutral': '#95a5a6'
                }
            )
            fig_comp.update_layout(
                height=500,
                plot_bgcolor='rgba(0,0,0,0)',
                paper_bgcolor='rgba(0,0,0,0)',
                yaxis_title="Sentiment Score",
                xaxis_title="Stock Symbol",
                hovermode='x unified'
            )
            st.plotly_chart(fig_comp, width='stretch', key="comparison_chart")
            
            # Net Sentiment Comparison
            st.subheader("📈 Net Sentiment Comparison")
            fig_net = px.bar(
                comp_df,
                x='Symbol',
                y='Net Sentiment',
                title="Net Sentiment (Positive - Negative)",
                color='Net Sentiment',
                color_continuous_scale=['#e74c3c', '#95a5a6', '#2ecc71']
            )
            fig_net.update_layout(
                height=400,
                plot_bgcolor='rgba(0,0,0,0)',
                paper_bgcolor='rgba(0,0,0,0)',
                yaxis_title="Net Sentiment",
                xaxis_title="Stock Symbol",
                showlegend=False
            )
            fig_net.update_traces(texttemplate='%{y:+.2%}', textposition='outside')
            st.plotly_chart(fig_net, width='stretch', key="net_sentiment_chart")
            
            # Comparison table
            st.subheader("📋 Detailed Comparison Table")
            st.dataframe(
                comp_df.style.format({
                    'Price': '${:.2f}',
                    'Market Cap': '${:.2f}B',
                    'Positive': '{:.2%}',
                    'Negative': '{:.2%}',
                    'Neutral': '{:.2%}',
                    'Net Sentiment': '{:+.2%}'
                }),
                width='stretch',
                height=400
            )
    else:
        st.info("👆 Select stocks above and click 'Compare' to see the comparison analysis")

