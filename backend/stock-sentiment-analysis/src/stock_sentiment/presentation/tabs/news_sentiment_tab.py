"""
News and sentiment tab component.
"""

import streamlit as st
import pandas as pd
import plotly.express as px
from datetime import datetime

from ...utils.ui_helpers import filter_articles
from ...config.settings import get_settings


def render_news_sentiment_tab(data, news_sentiments, current_symbol):
    """Render the news and sentiment tab."""
    st.header(f"📰 News & Sentiment Analysis")
    
    # Aggregate sentiment
    if news_sentiments:
        df = pd.DataFrame(news_sentiments)
        news_agg = df.mean().to_dict()
    else:
        news_agg = {'positive': 0, 'negative': 0, 'neutral': 1}
    
    # Sentiment summary cards
    sentiment_col1, sentiment_col2, sentiment_col3 = st.columns(3)
    
    with sentiment_col1:
        st.markdown(
            f"""
            <div style='background: #d4edda; padding: 1.5rem; border-radius: 10px; text-align: center;'>
                <h3 style='color: #155724; margin: 0;'>{news_agg['positive']:.1%}</h3>
                <p style='color: #155724; margin: 0.5rem 0 0 0; font-weight: 600;'>Positive</p>
            </div>
            """,
            unsafe_allow_html=True
        )
    
    with sentiment_col2:
        st.markdown(
            f"""
            <div style='background: #f8d7da; padding: 1.5rem; border-radius: 10px; text-align: center;'>
                <h3 style='color: #721c24; margin: 0;'>{news_agg['negative']:.1%}</h3>
                <p style='color: #721c24; margin: 0.5rem 0 0 0; font-weight: 600;'>Negative</p>
            </div>
            """,
            unsafe_allow_html=True
        )
    
    with sentiment_col3:
        st.markdown(
            f"""
            <div style='background: #e2e3e5; padding: 1.5rem; border-radius: 10px; text-align: center;'>
                <h3 style='color: #383d41; margin: 0;'>{news_agg['neutral']:.1%}</h3>
                <p style='color: #383d41; margin: 0.5rem 0 0 0; font-weight: 600;'>Neutral</p>
            </div>
            """,
            unsafe_allow_html=True
        )
    
    st.markdown("<br>", unsafe_allow_html=True)
    
    # Sentiment over time
    if data.get('news'):
        st.subheader("📅 Sentiment Trend Over Time")
        news_df = pd.DataFrame(data['news'])
        news_df['sentiment'] = [s['positive'] - s['negative'] for s in news_sentiments]
        
        # Normalize timestamps
        def normalize_timestamp(ts):
            if isinstance(ts, datetime):
                if ts.tzinfo is not None:
                    from datetime import timezone as tz
                    return ts.astimezone(tz.utc).replace(tzinfo=None)
                return ts
            return ts
        
        news_df['timestamp'] = news_df['timestamp'].apply(normalize_timestamp)
        news_df['timestamp'] = pd.to_datetime(news_df['timestamp'])
        news_df = news_df.sort_values('timestamp')
        
        fig_news_time = px.line(
            news_df,
            x='timestamp',
            y='sentiment',
            title='News Sentiment Over Time',
            markers=True,
            color_discrete_sequence=['#1f77b4']
        )
        fig_news_time.add_hline(y=0, line_dash="dash", line_color="gray", annotation_text="Neutral")
        fig_news_time.update_layout(
            height=400,
            plot_bgcolor='rgba(0,0,0,0)',
            paper_bgcolor='rgba(0,0,0,0)',
            hovermode='x unified'
        )
        st.plotly_chart(fig_news_time, width='stretch', key="sentiment_trend")
    
    # News articles
    if data.get('news'):
        st.subheader("📰 Recent News Articles")
        
        # Search and filter section
        search_col1, search_col2, search_col3 = st.columns([3, 1, 1])
        
        with search_col1:
            search_query = st.text_input(
                "🔍 Search articles...",
                key="article_search",
                placeholder="Search by title, source, or content",
                help="Filter articles by keywords"
            )
        
        with search_col2:
            sentiment_filter = st.selectbox(
                "Sentiment",
                ["All", "Positive", "Negative", "Neutral"],
                key="sentiment_filter",
                help="Filter by sentiment"
            )
        
        with search_col3:
            sort_option = st.selectbox(
                "Sort by",
                ["Date (Newest)", "Date (Oldest)", "Sentiment (Positive)", "Sentiment (Negative)", "Source"],
                key="sort_option"
            )
        
        # Get unique sources for filter
        unique_sources = list(set([article.get('source', 'Unknown') for article in data['news']]))
        source_filter = st.multiselect(
            "Filter by Source",
            options=unique_sources,
            key="source_filter",
            help="Select sources to display"
        )
        
        # Apply filters
        filtered_articles = filter_articles(
            data['news'],
            search_query=search_query if search_query else None,
            sentiment_filter=sentiment_filter.lower() if sentiment_filter != "All" else None,
            source_filter=source_filter if source_filter else None,
            sentiments=news_sentiments
        )
        
        # Sort articles
        if sort_option == "Date (Newest)":
            filtered_articles.sort(key=lambda x: x.get('timestamp', datetime.min), reverse=True)
        elif sort_option == "Date (Oldest)":
            filtered_articles.sort(key=lambda x: x.get('timestamp', datetime.min))
        elif sort_option == "Sentiment (Positive)":
            filtered_articles.sort(
                key=lambda x: news_sentiments[data['news'].index(x)]['positive'] if x in data['news'] else 0,
                reverse=True
            )
        elif sort_option == "Sentiment (Negative)":
            filtered_articles.sort(
                key=lambda x: news_sentiments[data['news'].index(x)]['negative'] if x in data['news'] else 0,
                reverse=True
            )
        elif sort_option == "Source":
            filtered_articles.sort(key=lambda x: x.get('source', ''))
        
        # Update news_sentiments to match filtered articles
        filtered_sentiments = []
        for article in filtered_articles:
            if article in data['news']:
                idx = data['news'].index(article)
                filtered_sentiments.append(news_sentiments[idx] if idx < len(news_sentiments) else {'positive': 0, 'negative': 0, 'neutral': 1})
            else:
                filtered_sentiments.append({'positive': 0, 'negative': 0, 'neutral': 1})
        
        # Pagination controls
        total_articles = len(filtered_articles)
        settings = get_settings()
        articles_per_page = settings.app.ui_articles_per_page
        
        if 'article_page' not in st.session_state:
            st.session_state.article_page = 1
        if 'show_all_articles' not in st.session_state:
            st.session_state.show_all_articles = False
        
        if search_query or sentiment_filter != "All" or source_filter:
            st.session_state.article_page = 1
        
        # Pagination controls
        pag_col1, pag_col2, pag_col3, pag_col4 = st.columns([2, 1, 1, 1])
        
        with pag_col1:
            show_all = st.checkbox(
                "Show All Articles",
                value=st.session_state.show_all_articles,
                key="show_all_articles_checkbox",
                help="Display all articles at once (may be slow for large lists)"
            )
            st.session_state.show_all_articles = show_all
        
        if not show_all and total_articles > articles_per_page:
            total_pages = (total_articles + articles_per_page - 1) // articles_per_page
            
            with pag_col2:
                if st.button("◀ Previous", disabled=st.session_state.article_page <= 1):
                    st.session_state.article_page = max(1, st.session_state.article_page - 1)
                    st.rerun()
            
            with pag_col3:
                st.markdown(f"**Page {st.session_state.article_page} of {total_pages}**")
            
            with pag_col4:
                if st.button("Next ▶", disabled=st.session_state.article_page >= total_pages):
                    st.session_state.article_page = min(total_pages, st.session_state.article_page + 1)
                    st.rerun()
        
        # Determine which articles to display
        if show_all:
            articles_to_display = filtered_articles
            sentiments_to_display = filtered_sentiments
            start_idx = 0
            end_idx = total_articles
        else:
            start_idx = (st.session_state.article_page - 1) * articles_per_page
            end_idx = min(start_idx + articles_per_page, total_articles)
            articles_to_display = filtered_articles[start_idx:end_idx]
            sentiments_to_display = filtered_sentiments[start_idx:end_idx]
        
        # Display article count
        if search_query or sentiment_filter != "All" or source_filter:
            st.info(f"🔍 Found {total_articles} article(s) matching your filters")
        
        if show_all:
            st.markdown(f"*Showing all {total_articles} articles*")
        else:
            st.markdown(f"*Showing articles {start_idx + 1}-{end_idx} of {total_articles}*")
        
        # Display articles
        for i, article in enumerate(articles_to_display):
            sentiment = sentiments_to_display[i] if i < len(sentiments_to_display) else {
                'positive': 0, 'negative': 0, 'neutral': 1
            }
            
            # Determine sentiment badge
            if sentiment['positive'] > sentiment['negative'] and sentiment['positive'] > sentiment['neutral']:
                badge_color = "#2ecc71"
                badge_text = "🟢 Positive"
            elif sentiment['negative'] > sentiment['positive'] and sentiment['negative'] > sentiment['neutral']:
                badge_color = "#e74c3c"
                badge_text = "🔴 Negative"
            else:
                badge_color = "#95a5a6"
                badge_text = "⚪ Neutral"
            
            title = article.get('title', 'No title available')
            source = article.get('source', 'News Source')
            if source in ['Unknown', 'Unknown Source', '']:
                source = 'News Source'
            
            # Enhanced article card
            with st.expander(
                f"{badge_text} | {title[:60]}{'...' if len(title) > 60 else ''} | {source}",
                expanded=False
            ):
                # Sentiment metrics
                metric_col1, metric_col2, metric_col3 = st.columns(3)
                with metric_col1:
                    st.metric("Positive", f"{sentiment['positive']:.1%}")
                with metric_col2:
                    st.metric("Negative", f"{sentiment['negative']:.1%}")
                with metric_col3:
                    st.metric("Neutral", f"{sentiment['neutral']:.1%}")
                
                st.divider()
                
                # Article content
                if title and title != 'No title available':
                    st.markdown(f"**Title:** {title}")
                
                summary = article.get('summary', '')
                if summary:
                    st.markdown(f"**Summary:** {summary}")
                
                url = article.get('url', '')
                if url:
                    st.markdown(f"🔗 [Read full article]({url})", unsafe_allow_html=True)
                else:
                    st.info("No article link available")

