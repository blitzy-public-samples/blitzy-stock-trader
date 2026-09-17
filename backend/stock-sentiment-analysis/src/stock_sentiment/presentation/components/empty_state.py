"""
Empty state component for when no data is loaded.
"""

import streamlit as st


def render_empty_state():
    """Render the empty state when no data is loaded."""
    st.markdown(
        """
        <div class='empty-state'>
            <h2 style='color: #7f8c8d; margin-bottom: 1rem;'>👆 Get Started</h2>
            <p style='font-size: 1.1rem; color: #95a5a6;'>
                Enter a stock symbol in the sidebar and click <strong>'Load Data'</strong> to begin analysis
            </p>
            <div style='margin-top: 2rem; padding: 2rem; background: #f8f9fa; border-radius: 10px;'>
                <h4 style='color: #34495e;'>💡 Popular Symbols</h4>
                <div style='display: flex; gap: 1rem; justify-content: center; flex-wrap: wrap; margin-top: 1rem;'>
                    <span style='padding: 0.5rem 1rem; background: white; border-radius: 6px; font-weight: 600;'>AAPL</span>
                    <span style='padding: 0.5rem 1rem; background: white; border-radius: 6px; font-weight: 600;'>MSFT</span>
                    <span style='padding: 0.5rem 1rem; background: white; border-radius: 6px; font-weight: 600;'>GOOGL</span>
                    <span style='padding: 0.5rem 1rem; background: white; border-radius: 6px; font-weight: 600;'>TSLA</span>
                    <span style='padding: 0.5rem 1rem; background: white; border-radius: 6px; font-weight: 600;'>AMZN</span>
                </div>
            </div>
        </div>
        """,
        unsafe_allow_html=True
    )

