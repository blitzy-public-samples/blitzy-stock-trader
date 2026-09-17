"""
Stock data collection service.

This module provides functionality to collect stock market data including:
- Stock prices and company information
- News articles and headlines
"""

from typing import List, Dict, Optional
from datetime import datetime, timedelta, timezone as tz
import yfinance as yf
from dateutil import parser

from ..config.settings import Settings, get_settings
from ..utils.logger import get_logger
from ..utils.validators import validate_stock_symbol, sanitize_text
from .cache import RedisCache

logger = get_logger(__name__)


def normalize_datetime(dt: datetime) -> datetime:
    """
    Normalize datetime to naive format for consistent comparison and storage.
    
    Converts timezone-aware datetimes to UTC and removes timezone info.
    Leaves naive datetimes unchanged.
    
    Args:
        dt: datetime object (may be timezone-aware or naive)
        
    Returns:
        Naive datetime object
    """
    if not isinstance(dt, datetime):
        return datetime.now()
    
    if dt.tzinfo is not None:
        # Convert timezone-aware to UTC, then make naive
        return dt.astimezone(tz.utc).replace(tzinfo=None)
    
    return dt


class StockDataCollector:
    """
    Service for collecting stock market data from free APIs.
    
    This class fetches stock data using yfinance and caches results
    in Redis for performance optimization.
    
    Attributes:
        cache: Redis cache instance (optional)
        settings: Application settings
        headers: HTTP headers for API requests
        
    Example:
        >>> settings = get_settings()
        >>> cache = RedisCache(settings=settings)
        >>> collector = StockDataCollector(settings=settings, redis_cache=cache)
        >>> data = collector.get_stock_price("AAPL")
    """
    
    def __init__(
        self,
        settings: Optional[Settings] = None,
        redis_cache: Optional[RedisCache] = None
    ):
        """
        Initialize the stock data collector.
        
        Args:
            settings: Application settings (uses global if not provided)
            redis_cache: Redis cache instance for caching
        """
        self.settings = settings or get_settings()
        self.cache = redis_cache
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
        
        logger.info("StockDataCollector initialized")
    
    def get_stock_price(self, symbol: str) -> Dict:
        """
        Fetch current stock price and basic company information.
        
        Args:
            symbol: Stock ticker symbol (e.g., "AAPL")
            
        Returns:
            Dictionary with stock data:
            - symbol: Stock ticker
            - price: Current stock price
            - company_name: Company name
            - market_cap: Market capitalization
            - timestamp: Data collection timestamp
        """
        if not validate_stock_symbol(symbol):
            logger.warning(f"Invalid stock symbol: {symbol}")
            return {
                'symbol': symbol,
                'price': 0.0,
                'company_name': symbol,
                'market_cap': 0,
                'timestamp': normalize_datetime(datetime.now())
            }
        
        # Check cache first
        if self.cache:
            cached_data = self.cache.get_cached_stock_data(symbol)
            if cached_data:
                logger.info(f"Data Collection: Stock data CACHE HIT for {symbol}")
                # Convert timestamp string back to datetime if needed
                if isinstance(cached_data.get('timestamp'), str):
                    try:
                        cached_data['timestamp'] = normalize_datetime(
                            datetime.fromisoformat(cached_data['timestamp'])
                        )
                    except ValueError:
                        cached_data['timestamp'] = normalize_datetime(datetime.now())
                elif isinstance(cached_data.get('timestamp'), datetime):
                    cached_data['timestamp'] = normalize_datetime(cached_data['timestamp'])
                return cached_data
            else:
                logger.info(f"Data Collection: Stock data CACHE MISS for {symbol}, fetching from API")
        
        try:
            logger.info(f"Data Collection: Fetching stock data for {symbol} from yfinance API")
            ticker = yf.Ticker(symbol)
            info = ticker.info
            hist = ticker.history(period="1d")
            
            if not hist.empty:
                current_price = float(hist['Close'].iloc[-1])
                result = {
                    'symbol': symbol,
                    'price': current_price,
                    'company_name': info.get('longName', symbol),
                    'market_cap': info.get('marketCap', 0) or 0,
                    'timestamp': normalize_datetime(datetime.now())
                }
                
                logger.info(f"Data Collection: Fetched stock data for {symbol} - Price: ${current_price:.2f}, Company: {result['company_name']}")
                
                # Cache the result
                if self.cache:
                    self.cache.cache_stock_data(
                        symbol,
                        result,
                        ttl=self.settings.app.cache_ttl_stock
                    )
                    logger.info(f"Data Collection: Cached stock data for {symbol} (TTL: {self.settings.app.cache_ttl_stock}s)")
                
                return result
        except Exception as e:
            logger.error(f"Error fetching stock price for {symbol}: {e}")
        
        # Return default on error
        return {
            'symbol': symbol,
            'price': 0.0,
            'company_name': symbol,
            'market_cap': 0,
            'timestamp': normalize_datetime(datetime.now())
        }
    
    def get_news_headlines(self, symbol: str, limit: Optional[int] = None) -> List[Dict]:
        """
        Fetch recent news headlines for a stock.
        
        Args:
            symbol: Stock ticker symbol
            limit: Maximum number of articles to return
            
        Returns:
            List of news article dictionaries with:
            - title: Article title
            - summary: Article summary
            - source: News source/publisher
            - url: Article URL
            - timestamp: Publication timestamp
        """
        if limit is None:
            limit = self.settings.app.news_limit_default
        
        if not validate_stock_symbol(symbol):
            logger.warning(f"Invalid stock symbol: {symbol}")
            return []
        
        # Check cache first
        if self.cache:
            cached_news = self.cache.get_cached_news(symbol)
            if cached_news:
                logger.info(f"Data Collection: News data CACHE HIT for {symbol} ({len(cached_news)} articles)")
                # Convert timestamp strings back to datetime and normalize
                for article in cached_news:
                    if isinstance(article.get('timestamp'), str):
                        try:
                            article['timestamp'] = normalize_datetime(
                                datetime.fromisoformat(article['timestamp'])
                            )
                        except ValueError:
                            article['timestamp'] = normalize_datetime(datetime.now())
                    elif isinstance(article.get('timestamp'), datetime):
                        article['timestamp'] = normalize_datetime(article['timestamp'])
                return cached_news
            else:
                logger.info(f"Data Collection: News data CACHE MISS for {symbol}, fetching from API")
        
        try:
            logger.info(f"Data Collection: Fetching news for {symbol} from yfinance API (limit={limit})")
            ticker = yf.Ticker(symbol)
            news = ticker.news
            
            if not news:
                logger.info(f"Data Collection: No news found for {symbol}")
                return []
            
            headlines = []
            for i, article in enumerate(news[:limit]):
                try:
                    # Extract data from nested structure
                    content = article.get('content', {})
                    
                    # Extract title
                    title = (
                        content.get('title') or
                        article.get('title') or
                        article.get('headline') or
                        'No title available'
                    )
                    title = sanitize_text(title)
                    
                    # Extract summary
                    summary = (
                        content.get('summary') or
                        content.get('description') or
                        article.get('summary') or
                        article.get('description') or
                        ''
                    )
                    summary = sanitize_text(summary)
                    
                    # Extract publisher
                    provider = article.get('provider', {})
                    publisher = (
                        provider.get('displayName') if isinstance(provider, dict) else None
                    ) or (
                        article.get('publisher') if isinstance(article.get('publisher'), str) else None
                    ) or article.get('source') or 'News Source'
                    
                    # Extract URL
                    url = self._extract_url(content, article)
                    
                    # Extract timestamp
                    timestamp = self._extract_timestamp(content, article)
                    timestamp = normalize_datetime(timestamp)
                    
                    headline_data = {
                        'title': title,
                        'summary': summary,
                        'source': publisher,
                        'timestamp': timestamp,
                        'url': url
                    }
                    
                    headlines.append(headline_data)
                    
                except Exception as e:
                    logger.warning(f"Error processing article {i} for {symbol}: {e}")
                    continue
            
            logger.info(f"Data Collection: Fetched {len(headlines)} news articles for {symbol}")
            
            # Cache the results
            if self.cache:
                self.cache.cache_news(
                    symbol,
                    headlines,
                    ttl=self.settings.app.cache_ttl_news
                )
                logger.info(f"Data Collection: Cached news data for {symbol} (TTL: {self.settings.app.cache_ttl_news}s)")
            
            return headlines
            
        except Exception as e:
            logger.error(f"Error fetching news for {symbol}: {e}")
            return []
    
    def _extract_url(self, content: Dict, article: Dict) -> str:
        """
        Extract article URL from nested structure.
        
        Args:
            content: Content dictionary from article
            article: Full article dictionary
            
        Returns:
            Article URL or empty string
        """
        # Try canonicalUrl from content first
        canonical_url = content.get('canonicalUrl', {})
        if isinstance(canonical_url, dict):
            url = canonical_url.get('url', '')
            if url:
                return url
        
        # Try clickThroughUrl from content
        click_through = content.get('clickThroughUrl', {})
        if isinstance(click_through, dict):
            url = click_through.get('url', '')
            if url:
                return url
        
        # Try at article level
        canonical_url = article.get('canonicalUrl', {})
        if isinstance(canonical_url, dict):
            url = canonical_url.get('url', '')
            if url:
                return url
        
        click_through = article.get('clickThroughUrl', {})
        if isinstance(click_through, dict):
            url = click_through.get('url', '')
            if url:
                return url
        
        return ''
    
    def _extract_timestamp(self, content: Dict, article: Dict) -> datetime:
        """
        Extract publication timestamp from nested structure.
        
        Args:
            content: Content dictionary from article
            article: Full article dictionary
            
        Returns:
            Datetime object or current time as fallback
        """
        # Try pubDate or displayTime from content
        timestamp_str = content.get('pubDate') or content.get('displayTime')
        if timestamp_str:
            try:
                return parser.parse(str(timestamp_str))
            except (ValueError, TypeError):
                pass
        
        # Try providerPublishTime or publishTime from article
        timestamp_val = article.get('providerPublishTime') or article.get('publishTime')
        if timestamp_val:
            try:
                if isinstance(timestamp_val, (int, float)):
                    return datetime.fromtimestamp(timestamp_val)
                else:
                    return parser.parse(str(timestamp_val))
            except (ValueError, TypeError):
                pass
        
        # Fallback to current time
        return normalize_datetime(datetime.now())
    
    def get_reddit_sentiment_data(self, symbol: str) -> List[Dict]:
        """
        Get Reddit posts about a stock symbol.
        
        Uses PRAW (Python Reddit API Wrapper) to fetch posts from relevant subreddits.
        Free to use, just requires Reddit app registration at https://www.reddit.com/prefs/apps
        
        Args:
            symbol: Stock ticker symbol
            
        Returns:
            List of Reddit post dictionaries with title, text, source, url, timestamp
        """
        if not self.settings.data_sources.reddit_enabled:
            return []
        
        if not self.settings.data_sources.reddit_client_id or not self.settings.data_sources.reddit_client_secret:
            logger.warning("Reddit credentials not configured. Set DATA_SOURCE_REDDIT_CLIENT_ID and DATA_SOURCE_REDDIT_CLIENT_SECRET in .env")
            return []
        
        try:
            import praw
            
            # Initialize Reddit client
            reddit = praw.Reddit(
                client_id=self.settings.data_sources.reddit_client_id,
                client_secret=self.settings.data_sources.reddit_client_secret,
                user_agent=self.settings.data_sources.reddit_user_agent
            )
            
            logger.info(f"Data Collection: Fetching Reddit posts for {symbol}")
            
            posts = []
            limit = self.settings.data_sources.reddit_limit
            
            # Search in relevant subreddits
            subreddits = ['stocks', 'investing', 'StockMarket', 'SecurityAnalysis', 'wallstreetbets']
            
            for subreddit_name in subreddits:
                try:
                    subreddit = reddit.subreddit(subreddit_name)
                    # Search for posts containing the symbol
                    search_query = f"{symbol} OR ${symbol}"
                    for post in subreddit.search(search_query, limit=limit // len(subreddits), sort='relevance', time_filter='week'):
                        # Skip if already collected (by URL)
                        if any(p.get('url') == post.url for p in posts):
                            continue
                        
                        # Extract post data
                        post_text = post.selftext if hasattr(post, 'selftext') else ''
                        post_title = post.title if hasattr(post, 'title') else ''
                        
                        # Combine title and text for analysis
                        full_text = f"{post_title} {post_text}".strip()
                        
                        if not full_text or len(full_text) < 20:  # Skip very short posts
                            continue
                        
                        # Convert timestamp
                        post_timestamp = datetime.fromtimestamp(post.created_utc) if hasattr(post, 'created_utc') else datetime.now()
                        post_timestamp = normalize_datetime(post_timestamp)
                        
                        posts.append({
                                'title': post_title[:self.settings.app.news_title_max_length],
                                'summary': post_text[:self.settings.app.news_summary_max_length] if post_text else post_title[:self.settings.app.news_summary_max_length],
                            'source': f'Reddit r/{subreddit_name}',
                            'url': post.url if hasattr(post, 'url') else f"https://reddit.com{post.permalink}" if hasattr(post, 'permalink') else '',
                            'timestamp': post_timestamp,
                            'platform': 'Reddit',
                            'subreddit': subreddit_name
                        })
                        
                        if len(posts) >= limit:
                            break
                    
                    if len(posts) >= limit:
                        break
                        
                except Exception as e:
                    logger.warning(f"Error fetching from r/{subreddit_name}: {e}")
                    continue
            
            logger.info(f"Data Collection: Fetched {len(posts)} Reddit posts for {symbol}")
            return posts[:limit]
            
        except ImportError:
            logger.warning("PRAW not installed. Install with: pip install praw")
            return []
        except Exception as e:
            logger.error(f"Error fetching Reddit data for {symbol}: {e}")
            return []
    
    def get_alpha_vantage_news(self, symbol: str, limit: Optional[int] = None) -> List[Dict]:
        """
        Get news from Alpha Vantage API.
        
        Free tier: 500 calls/day
        Get API key at: https://www.alphavantage.co/support/#api-key
        
        Args:
            symbol: Stock ticker symbol
            limit: Maximum number of articles to return (default: from settings)
            
        Returns:
            List of news article dictionaries
        """
        if limit is None:
            limit = self.settings.app.news_limit_default
        
        if not self.settings.data_sources.alpha_vantage_enabled:
            return []
        
        if not self.settings.data_sources.alpha_vantage_api_key:
            logger.warning("Alpha Vantage API key not configured. Set DATA_SOURCE_ALPHA_VANTAGE_API_KEY in .env")
            return []
        
        try:
            import requests
            
            logger.info(f"Data Collection: Fetching Alpha Vantage news for {symbol}")
            
            url = "https://www.alphavantage.co/query"
            params = {
                'function': 'NEWS_SENTIMENT',
                'tickers': symbol,
                'apikey': self.settings.data_sources.alpha_vantage_api_key,
                'limit': min(limit, 50)  # Alpha Vantage max is 50
            }
            
            response = requests.get(url, params=params, timeout=self.settings.app.external_api_timeout)
            response.raise_for_status()
            data = response.json()
            
            if 'Error Message' in data:
                logger.error(f"Alpha Vantage API error: {data['Error Message']}")
                return []
            
            if 'Note' in data:
                logger.warning(f"Alpha Vantage rate limit: {data['Note']}")
                return []
            
            articles = []
            feed = data.get('feed', [])
            
            for item in feed[:limit]:
                try:
                    # Extract article data
                    title = item.get('title', '')
                    summary = item.get('summary', '')
                    source = item.get('source', 'Alpha Vantage')
                    url = item.get('url', '')
                    
                    # Parse timestamp
                    timestamp_str = item.get('time_published', '')
                    if timestamp_str:
                        try:
                            # Format: 20240101T120000
                            timestamp = datetime.strptime(timestamp_str, '%Y%m%dT%H%M%S')
                        except ValueError:
                            timestamp = datetime.now()
                    else:
                        timestamp = datetime.now()
                    
                    articles.append({
                        'title': sanitize_text(title),
                        'summary': sanitize_text(summary),
                        'source': f'Alpha Vantage - {source}',
                        'url': url,
                        'timestamp': normalize_datetime(timestamp)
                    })
                except Exception as e:
                    logger.warning(f"Error processing Alpha Vantage article: {e}")
                    continue
            
            logger.info(f"Data Collection: Fetched {len(articles)} articles from Alpha Vantage for {symbol}")
            return articles
            
        except Exception as e:
            logger.error(f"Error fetching Alpha Vantage news for {symbol}: {e}")
            return []
    
    def get_finnhub_news(self, symbol: str, limit: Optional[int] = None) -> List[Dict]:
        """
        Get news from Finnhub API.
        
        Free tier: 60 calls/minute
        Get API key at: https://finnhub.io/register
        
        Args:
            symbol: Stock ticker symbol
            limit: Maximum number of articles to return (default: from settings)
            
        Returns:
            List of news article dictionaries
        """
        if limit is None:
            limit = self.settings.app.news_limit_default
        
        if not self.settings.data_sources.finnhub_enabled:
            return []
        
        if not self.settings.data_sources.finnhub_api_key:
            logger.warning("Finnhub API key not configured. Set DATA_SOURCE_FINNHUB_API_KEY in .env")
            return []
        
        try:
            import requests
            
            logger.info(f"Data Collection: Fetching Finnhub news for {symbol}")
            
            url = "https://finnhub.io/api/v1/company-news"
            params = {
                'symbol': symbol,
                'token': self.settings.data_sources.finnhub_api_key,
                'from': (datetime.now() - timedelta(days=7)).strftime('%Y-%m-%d'),
                'to': datetime.now().strftime('%Y-%m-%d')
            }
            
            response = requests.get(url, params=params, timeout=self.settings.app.external_api_timeout)
            response.raise_for_status()
            data = response.json()
            
            if isinstance(data, dict) and 'error' in data:
                logger.error(f"Finnhub API error: {data['error']}")
                return []
            
            articles = []
            for item in data[:limit]:
                try:
                    title = item.get('headline', item.get('title', ''))
                    summary = item.get('summary', '')
                    source = item.get('source', 'Finnhub')
                    url = item.get('url', '')
                    
                    # Parse timestamp
                    timestamp_val = item.get('datetime')
                    if timestamp_val:
                        try:
                            if isinstance(timestamp_val, (int, float)):
                                timestamp = datetime.fromtimestamp(timestamp_val)
                            else:
                                timestamp = parser.parse(str(timestamp_val))
                        except (ValueError, TypeError):
                            timestamp = datetime.now()
                    else:
                        timestamp = datetime.now()
                    
                    articles.append({
                        'title': sanitize_text(title),
                        'summary': sanitize_text(summary),
                        'source': f'Finnhub - {source}',
                        'url': url,
                        'timestamp': normalize_datetime(timestamp)
                    })
                except Exception as e:
                    logger.warning(f"Error processing Finnhub article: {e}")
                    continue
            
            logger.info(f"Data Collection: Fetched {len(articles)} articles from Finnhub for {symbol}")
            return articles
            
        except Exception as e:
            logger.error(f"Error fetching Finnhub news for {symbol}: {e}")
            return []
    
    def collect_all_data(self, symbol: str, data_source_filters: Optional[Dict[str, bool]] = None) -> Dict:
        """
        Collect all available data for a stock symbol from multiple sources.
        
        Args:
            symbol: Stock ticker symbol
            data_source_filters: Optional dictionary with source enable/disable flags:
                - yfinance: bool (default: True, always enabled)
                - alpha_vantage: bool (default: from settings)
                - finnhub: bool (default: from settings)
                - reddit: bool (default: from settings)
            
        Returns:
            Dictionary with:
            - price_data: Stock price and company info
            - news: List of news articles (from enabled sources)
            - social_media: List of Reddit posts (if enabled)
        """
        # Default to settings if filters not provided
        if data_source_filters is None:
            data_source_filters = {
                "yfinance": True,
                "alpha_vantage": self.settings.data_sources.alpha_vantage_enabled,
                "finnhub": self.settings.data_sources.finnhub_enabled,
                "reddit": self.settings.data_sources.reddit_enabled
            }
        
        # Log data source filter summary
        enabled_sources = [k for k, v in data_source_filters.items() if v]
        disabled_sources = [k for k, v in data_source_filters.items() if not v]
        logger.info(f"   📡 Data Source Filters Applied:")
        if enabled_sources:
            logger.info(f"      • ✅ Enabled: {', '.join(enabled_sources)}")
        if disabled_sources:
            logger.info(f"      • ❌ Disabled: {', '.join(disabled_sources)}")
        
        # Collect news from multiple sources based on filters
        all_news = []
        source_counts = {}
        
        # Primary source: yfinance (always enabled)
        if data_source_filters.get("yfinance", True):
            # Track cache status before calling get_news_headlines
            # (it checks cache internally, but we want to know the result)
            if self.cache:
                self.cache.last_tier_used = None
            
            yf_news = self.get_news_headlines(symbol)
            all_news.extend(yf_news)
            source_counts["yfinance"] = len(yf_news)
            
            # Log summary based on whether cache was used
            if self.cache and self.cache.last_tier_used == "Redis":
                logger.info(f"   ✅ Yahoo Finance: Retrieved {len(yf_news)} articles (from cache)")
            else:
                logger.info(f"   ✅ Yahoo Finance: Fetched {len(yf_news)} articles (from API)")
        else:
            source_counts["yfinance"] = 0
            logger.info("   ❌ Yahoo Finance: disabled by filter")
        
        # Additional sources if enabled in both settings and filters
        av_news = []
        if data_source_filters.get("alpha_vantage", False) and self.settings.data_sources.alpha_vantage_enabled:
            # Alpha Vantage doesn't have cache check in get_alpha_vantage_news, so always fetch
            logger.info(f"   📰 Alpha Vantage: Fetching from API...")
            av_news = self.get_alpha_vantage_news(symbol)
            all_news.extend(av_news)
            source_counts["alpha_vantage"] = len(av_news)
            logger.info(f"   ✅ Alpha Vantage: Fetched {len(av_news)} articles")
        else:
            source_counts["alpha_vantage"] = 0
            if not data_source_filters.get("alpha_vantage", False):
                logger.info("   ❌ Alpha Vantage: disabled by filter")
            elif not self.settings.data_sources.alpha_vantage_enabled:
                logger.info("   ⚠️ Alpha Vantage: not configured in settings")
        
        fh_news = []
        if data_source_filters.get("finnhub", False) and self.settings.data_sources.finnhub_enabled:
            # Finnhub doesn't have cache check in get_finnhub_news, so always fetch
            logger.info(f"   📰 Finnhub: Fetching from API...")
            fh_news = self.get_finnhub_news(symbol)
            all_news.extend(fh_news)
            source_counts["finnhub"] = len(fh_news)
            logger.info(f"   ✅ Finnhub: Fetched {len(fh_news)} articles")
        else:
            source_counts["finnhub"] = 0
            if not data_source_filters.get("finnhub", False):
                logger.info("   ❌ Finnhub: disabled by filter")
            elif not self.settings.data_sources.finnhub_enabled:
                logger.info("   ⚠️ Finnhub: not configured in settings")
        
        # Remove duplicates by URL
        seen_urls = set()
        unique_news = []
        for article in all_news:
            url = article.get('url', '')
            if url and url not in seen_urls:
                seen_urls.add(url)
                unique_news.append(article)
            elif not url:  # Include articles without URLs (like Reddit posts)
                unique_news.append(article)
        
        # Normalize all timestamps to naive format for consistency
        for article in unique_news:
            if 'timestamp' in article:
                article['timestamp'] = normalize_datetime(article['timestamp'])
        
        # Sort by timestamp (most recent first)
        unique_news.sort(key=lambda x: x.get('timestamp', datetime.min), reverse=True)
        
        duplicates_removed = len(all_news) - len(unique_news)
        if duplicates_removed > 0:
            logger.info(f"   🔄 Removed {duplicates_removed} duplicate articles (by URL)")
        
        logger.info(f"   📊 Collection Summary:")
        logger.info(f"      • Total articles collected: {len(all_news)}")
        logger.info(f"      • Unique articles: {len(unique_news)}")
        logger.info(f"      • By source: yfinance={source_counts['yfinance']}, Alpha Vantage={source_counts['alpha_vantage']}, Finnhub={source_counts['finnhub']}")
        
        # Get Reddit data if enabled
        reddit_posts = []
        if data_source_filters.get("reddit", False) and self.settings.data_sources.reddit_enabled:
            # Reddit doesn't have cache check in get_reddit_sentiment_data, so always fetch
            logger.info(f"   📰 Reddit: Fetching from API...")
            reddit_posts = self.get_reddit_sentiment_data(symbol)
            logger.info(f"   ✅ Reddit: Fetched {len(reddit_posts)} posts")
        else:
            if not data_source_filters.get("reddit", False):
                logger.info("   ❌ Reddit: disabled by filter")
            elif not self.settings.data_sources.reddit_enabled:
                logger.info("   ⚠️ Reddit: not configured in settings")
        
        return {
            'price_data': self.get_stock_price(symbol),
            'news': unique_news,
            'social_media': reddit_posts
        }

