"""
Price analysis tab component.
"""

import streamlit as st
import plotly.graph_objects as go
from typing import Optional
from datetime import datetime
import pandas as pd

from ...utils.logger import get_logger
from ...presentation.api_client import SentimentAPIClient

logger = get_logger(__name__)


def render_price_analysis_tab(current_symbol: str, api_client: Optional[SentimentAPIClient]):
    """Render the price analysis tab."""
    st.header(f"📈 Price Analysis - {current_symbol}")
    
    if not api_client:
        st.error("❌ API client not available. Please ensure API mode is enabled.")
        return
    
    try:
        period = st.selectbox(
            "📅 Time Period",
            ["1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y"],
            index=5,
            key="price_period"
        )
        
        # Get price history from API
        price_data = api_client.get_price_history(current_symbol, period=period)
        data_points = price_data.get('data', [])
        
        if not data_points:
            st.warning("⚠️ No price data available for this symbol.")
            return
        
        # Convert to DataFrame for easier manipulation
        hist_df = pd.DataFrame(data_points)
        hist_df['date'] = pd.to_datetime(hist_df['date'])
        hist_df = hist_df.set_index('date').sort_index()
        
        if not hist_df.empty:
            # Enhanced metrics display
            metrics_col1, metrics_col2, metrics_col3, metrics_col4 = st.columns(4)
            
            with metrics_col1:
                current_price = hist_df['close'].iloc[-1]
                prev_price = hist_df['close'].iloc[-2] if len(hist_df) > 1 else current_price
                change = current_price - prev_price
                change_pct = (change / prev_price) * 100 if prev_price > 0 else 0
                delta_color = "normal" if change >= 0 else "inverse"
                st.metric(
                    "Current Price",
                    f"${current_price:.2f}",
                    f"{change:+.2f} ({change_pct:+.2f}%)",
                    delta_color=delta_color
                )
            
            with metrics_col2:
                high = hist_df['high'].max()
                st.metric("52W High", f"${high:.2f}")
            
            with metrics_col3:
                low = hist_df['low'].min()
                st.metric("52W Low", f"${low:.2f}")
            
            with metrics_col4:
                volume = hist_df['volume'].iloc[-1]
                st.metric("Volume", f"{volume:,.0f}")
            
            st.markdown("<br>", unsafe_allow_html=True)
            
            # Enhanced price chart
            fig = go.Figure()
            fig.add_trace(go.Scatter(
                x=hist_df.index,
                y=hist_df['close'],
                mode='lines',
                name='Close Price',
                line=dict(color='#1f77b4', width=3),
                fill='tonexty',
                fillcolor='rgba(31, 119, 180, 0.1)'
            ))
            fig.update_layout(
                title=dict(
                    text=f"{current_symbol} Price Chart ({period})",
                    font=dict(size=20, color='#2c3e50')
                ),
                xaxis_title="Date",
                yaxis_title="Price ($)",
                height=500,
                hovermode='x unified',
                plot_bgcolor='rgba(0,0,0,0)',
                paper_bgcolor='rgba(0,0,0,0)',
                font=dict(size=12),
                showlegend=False
            )
            st.plotly_chart(fig, width='stretch', key="price_chart")
            
            # Volume chart
            fig_vol = go.Figure()
            fig_vol.add_trace(go.Bar(
                x=hist_df.index,
                y=hist_df['volume'],
                name='Volume',
                marker_color='rgba(31, 119, 180, 0.6)',
                marker_line_color='rgba(31, 119, 180, 0.8)',
                marker_line_width=1
            ))
            fig_vol.update_layout(
                title=dict(text="Trading Volume", font=dict(size=18, color='#2c3e50')),
                xaxis_title="Date",
                yaxis_title="Volume",
                height=300,
                plot_bgcolor='rgba(0,0,0,0)',
                paper_bgcolor='rgba(0,0,0,0)',
                showlegend=False
            )
            st.plotly_chart(fig_vol, width='stretch', key="volume_chart")
        else:
            st.warning("⚠️ No price data available for this symbol.")
    except Exception as e:
        logger.error(f"Error fetching price data: {e}")
        st.error(f"❌ Error fetching price data: {e}")

