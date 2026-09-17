"""
Technical analysis tab component.
"""

import streamlit as st
import plotly.graph_objects as go
from typing import Optional
from datetime import datetime
import pandas as pd

from ...utils.logger import get_logger
from ...presentation.api_client import SentimentAPIClient

logger = get_logger(__name__)


def render_technical_analysis_tab(current_symbol: str, api_client: Optional[SentimentAPIClient]):
    """Render the technical analysis tab."""
    st.header(f"🔧 Technical Analysis - {current_symbol}")
    
    if not api_client:
        st.error("❌ API client not available. Please ensure API mode is enabled.")
        return
    
    try:
        period = st.selectbox(
            "📅 Analysis Period",
            ["1mo", "3mo", "6mo", "1y", "2y"],
            index=2,
            key="tech_period"
        )
        
        # Get price history from API
        price_data = api_client.get_price_history(current_symbol, period=period)
        data_points = price_data.get('data', [])
        
        if not data_points:
            st.warning("⚠️ No data available for technical analysis.")
            return
        
        # Convert to DataFrame
        hist_df = pd.DataFrame(data_points)
        hist_df['date'] = pd.to_datetime(hist_df['date'])
        hist_df = hist_df.set_index('date').sort_index()
        
        if not hist_df.empty:
            # Calculate technical indicators
            hist_df['SMA_20'] = hist_df['close'].rolling(window=20).mean()
            hist_df['SMA_50'] = hist_df['close'].rolling(window=50).mean()
            hist_df['EMA_12'] = hist_df['close'].ewm(span=12, adjust=False).mean()
            hist_df['EMA_26'] = hist_df['close'].ewm(span=26, adjust=False).mean()
            
            # RSI calculation
            delta = hist_df['close'].diff()
            gain = (delta.where(delta > 0, 0)).rolling(window=14).mean()
            loss = (-delta.where(delta < 0, 0)).rolling(window=14).mean()
            rs = gain / loss
            hist_df['RSI'] = 100 - (100 / (1 + rs))
            
            # MACD
            hist_df['MACD'] = hist_df['EMA_12'] - hist_df['EMA_26']
            hist_df['Signal'] = hist_df['MACD'].ewm(span=9, adjust=False).mean()
            
            # Enhanced price chart with moving averages
            fig = go.Figure()
            fig.add_trace(go.Scatter(
                x=hist_df.index,
                y=hist_df['close'],
                mode='lines',
                name='Close Price',
                line=dict(color='#1f77b4', width=3)
            ))
            fig.add_trace(go.Scatter(
                x=hist_df.index,
                y=hist_df['SMA_20'],
                mode='lines',
                name='SMA 20',
                line=dict(color='#ff9800', width=2, dash='dash')
            ))
            fig.add_trace(go.Scatter(
                x=hist_df.index,
                y=hist_df['SMA_50'],
                mode='lines',
                name='SMA 50',
                line=dict(color='#e74c3c', width=2, dash='dash')
            ))
            fig.update_layout(
                title=dict(text=f"{current_symbol} Price with Moving Averages", font=dict(size=20, color='#2c3e50')),
                xaxis_title="Date",
                yaxis_title="Price ($)",
                height=450,
                plot_bgcolor='rgba(0,0,0,0)',
                paper_bgcolor='rgba(0,0,0,0)',
                hovermode='x unified',
                legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1)
            )
            st.plotly_chart(fig, width='stretch', key="tech_price_ma")
            
            # RSI and MACD side by side
            tech_col1, tech_col2 = st.columns(2)
            
            with tech_col1:
                st.subheader("📊 Relative Strength Index (RSI)")
                fig_rsi = go.Figure()
                fig_rsi.add_trace(go.Scatter(
                    x=hist_df.index,
                    y=hist_df['RSI'],
                    mode='lines',
                    name='RSI',
                    line=dict(color='#9b59b6', width=2)
                ))
                fig_rsi.add_hline(y=70, line_dash="dash", line_color="red", annotation_text="Overbought (70)")
                fig_rsi.add_hline(y=30, line_dash="dash", line_color="green", annotation_text="Oversold (30)")
                fig_rsi.update_layout(
                    height=350,
                    yaxis=dict(range=[0, 100]),
                    plot_bgcolor='rgba(0,0,0,0)',
                    paper_bgcolor='rgba(0,0,0,0)',
                    showlegend=False
                )
                st.plotly_chart(fig_rsi, width='stretch', key="rsi_chart")
                
                current_rsi = hist_df['RSI'].iloc[-1]
                rsi_status = "Overbought" if current_rsi > 70 else "Oversold" if current_rsi < 30 else "Normal"
                st.metric("Current RSI", f"{current_rsi:.2f}", rsi_status)
            
            with tech_col2:
                st.subheader("📈 MACD Indicator")
                fig_macd = go.Figure()
                fig_macd.add_trace(go.Scatter(
                    x=hist_df.index,
                    y=hist_df['MACD'],
                    mode='lines',
                    name='MACD',
                    line=dict(color='#3498db', width=2)
                ))
                fig_macd.add_trace(go.Scatter(
                    x=hist_df.index,
                    y=hist_df['Signal'],
                    mode='lines',
                    name='Signal',
                    line=dict(color='#e74c3c', width=2)
                ))
                fig_macd.add_hline(y=0, line_dash="dash", line_color="gray")
                fig_macd.update_layout(
                    height=350,
                    plot_bgcolor='rgba(0,0,0,0)',
                    paper_bgcolor='rgba(0,0,0,0)',
                    hovermode='x unified',
                    legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1)
                )
                st.plotly_chart(fig_macd, width='stretch', key="macd_chart")
                
                current_macd = hist_df['MACD'].iloc[-1]
                current_signal = hist_df['Signal'].iloc[-1]
                macd_trend = "Bullish" if current_macd > current_signal else "Bearish"
                st.metric("MACD", f"{current_macd:.2f}", f"Signal: {current_signal:.2f} ({macd_trend})")
        else:
            st.warning("⚠️ No data available for technical analysis.")
    except Exception as e:
        logger.error(f"Error performing technical analysis: {e}")
        st.error(f"❌ Error performing technical analysis: {e}")

