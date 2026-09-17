"""
Sentiment analysis service using Azure OpenAI.

This module provides AI-powered sentiment analysis with support for:
- Azure OpenAI GPT-4 for sentiment analysis
- RAG (Retrieval Augmented Generation) for context-aware analysis
- Redis caching for performance optimization
- TextBlob fallback for error handling
"""

import json
import re
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FuturesTimeoutError, as_completed
from typing import Dict, List, Optional
from openai import AzureOpenAI
from textblob import TextBlob

from ..config.settings import Settings, get_settings
from ..models.sentiment import SentimentScores
from ..utils.logger import get_logger
from ..utils.preprocessing import preprocess_text, is_financial_text
from ..utils.retry import retry_with_exponential_backoff
from ..utils.circuit_breaker import CircuitBreaker, CircuitBreakerOpenError
from .cache import RedisCache
from .rag import RAGService

logger = get_logger(__name__)


class SentimentAnalyzer:
    """
    AI-powered sentiment analyzer using Azure OpenAI.
    
    This class provides sentiment analysis capabilities with:
    - Azure OpenAI GPT-4 for high-quality sentiment analysis
    - RAG integration for context-aware analysis
    - Redis caching to reduce API calls
    - TextBlob fallback for reliability
    
    Attributes:
        client: Azure OpenAI client instance
        settings: Application settings
        cache: Redis cache instance (optional)
        rag_service: RAG service instance (optional)
        cache_hits: Counter for cache hits
        cache_misses: Counter for cache misses
        rag_uses: Counter for RAG usage
        
    Example:
        >>> settings = get_settings()
        >>> cache = RedisCache(settings=settings)
        >>> analyzer = SentimentAnalyzer(settings=settings, redis_cache=cache)
        >>> result = analyzer.analyze_sentiment("Apple stock is rising!", symbol="AAPL")
    """
    
    def __init__(
        self,
        settings: Optional[Settings] = None,
        redis_cache: Optional[RedisCache] = None,
        rag_service: Optional[RAGService] = None
    ):
        """
        Initialize the sentiment analyzer.
        
        Args:
            settings: Application settings (uses global if not provided)
            redis_cache: Redis cache instance for caching results
            rag_service: RAG service for context retrieval
            
        Raises:
            ValueError: If Azure OpenAI configuration is invalid
        """
        self.settings = settings or get_settings()
        self.cache = redis_cache
        self.rag_service = rag_service
        
        # Initialize Azure OpenAI client
        azure_config = self.settings.azure_openai
        self.client = AzureOpenAI(
            azure_endpoint=azure_config.endpoint,
            api_key=azure_config.api_key,
            api_version=azure_config.api_version
        )
        self.deployment_name = azure_config.deployment_name
        
        # Statistics
        self.cache_hits = 0
        self.cache_misses = 0
        self.rag_uses = 0  # Successful RAG uses (when articles found)
        self.rag_attempts = 0  # Total RAG attempts (even if no articles found)
        
        # Circuit breaker for Azure OpenAI API calls
        # Prevents cascading failures if API is down
        self.circuit_breaker = CircuitBreaker(
            failure_threshold=self.settings.app.circuit_breaker_failure_threshold,
            timeout=self.settings.app.circuit_breaker_timeout,
            name="azure_openai_sentiment"
        )
        
        # Initialize RAG filters (can be set via set_rag_filters)
        self._rag_filters = {}
        
        logger.info(
            f"SentimentAnalyzer initialized with deployment: {self.deployment_name}"
        )
    
    def analyze_sentiment(
        self,
        text: str,
        symbol: Optional[str] = None,
        context: Optional[List[Dict]] = None
    ) -> Dict[str, float]:
        """
        Analyze sentiment of given text using Azure OpenAI with optional RAG context.
        
        Args:
            text: Text to analyze
            symbol: Optional stock symbol for RAG context retrieval
            context: Optional additional context items
            
        Returns:
            Dictionary with sentiment scores: {'positive': float, 'negative': float, 'neutral': float}
            
        Note:
            Scores are normalized to sum to approximately 1.0
        """
        if not text or not text.strip():
            logger.warning("Empty text provided for sentiment analysis")
            return {'positive': 0.0, 'negative': 0.0, 'neutral': 1.0}
        
        # Preprocess text for better analysis (industry best practice)
        text = preprocess_text(text, expand_abbreviations=True)
        
        # Check if text is financial (optional quality check)
        if not is_financial_text(text):
            pass  # Proceed anyway, but could filter here in future
        
        # Check cache first - if sentiment is cached, skip RAG (no need for context)
        if self.cache:
            cached_result = self.cache.get_cached_sentiment(text)
            if cached_result:
                self.cache_hits += 1
                pos = cached_result.get('positive', 0)
                neg = cached_result.get('negative', 0)
                neu = cached_result.get('neutral', 0)
                dominant = 'positive' if pos > neg and pos > neu else 'negative' if neg > pos and neg > neu else 'neutral'
                logger.info(f"   ✅ Cached result (TTL: {self.settings.app.cache_ttl_sentiment}s)")
                logger.info(f"      • Result - Positive: {pos:.2f}, Negative: {neg:.2f}, Neutral: {neu:.2f}, RAG used: False, Dominant: {dominant}")
                return cached_result
            else:
                self.cache_misses += 1
                logger.info(f"   🔄 Cache MISS for text '{text[:50]}...' (proceeding with analysis)")
        
        # Retrieve relevant context using RAG if available
        rag_context = ""
        rag_used = False
        if self.rag_service and symbol:
            # Track RAG attempt (even if no articles found)
            self.rag_attempts += 1
            
            # Get filter parameters if provided (from UI)
            filter_params = getattr(self, '_rag_filters', {})
            
            # Log filter application
            if filter_params:
                filter_info = []
                if filter_params.get('date_range'):
                    start, end = filter_params.get('date_range', (None, None))
                    filter_info.append(f"date_range={start.strftime('%Y-%m-%d') if start else 'any'} to {end.strftime('%Y-%m-%d') if end else 'any'}")
                if filter_params.get('days_back'):
                    filter_info.append(f"days_back={filter_params.get('days_back')}")
                if filter_params.get('sources'):
                    filter_info.append(f"sources={', '.join(filter_params.get('sources', []))}")
                if filter_params.get('exclude_sources'):
                    filter_info.append(f"exclude={', '.join(filter_params.get('exclude_sources', []))}")
                
                if filter_info:
                    logger.info(f"Sentiment Analysis: Applying RAG filters - {', '.join(filter_info)}")
            
            logger.info(f"   🔍 Retrieving RAG context for '{text[:50]}...' (symbol={symbol})")
            
            relevant_articles = self.rag_service.retrieve_relevant_context(
                query=text,
                symbol=symbol,
                top_k=self.settings.app.rag_top_k,
                date_range=filter_params.get('date_range'),
                sources=filter_params.get('sources'),
                exclude_sources=filter_params.get('exclude_sources'),
                days_back=filter_params.get('days_back')
            )
            
            if relevant_articles:
                rag_used = True
                self.rag_uses += 1
                logger.info(f"   ✅ RAG provided {len(relevant_articles)} context articles for analysis")
                # Log top article for demo visibility
                if relevant_articles:
                    top_article = relevant_articles[0]
                    top_title = top_article.get('title', 'N/A')[:60]
                    top_score = top_article.get('rrf_score') or top_article.get('similarity', 0.0)
                    logger.info(f"      • Top article: '{top_title}...' (relevance: {top_score:.3f})")
            else:
                logger.warning(f"   ⚠️ RAG found 0 articles for {symbol} - proceeding without context")
        
        # Format RAG context with better structure and metadata
        if rag_used and relevant_articles:
            rag_context = ""
            for i, article in enumerate(relevant_articles, 1):
                title = article.get('title', 'N/A')
                summary = article.get('summary', '')[:250]  # Increased context
                source = article.get('source', 'Unknown')
                similarity = article.get('similarity', 0.0)
                
                rag_context += f"""
### Article {i} (Relevance: {similarity:.2%})
**Title:** {title}
**Source:** {source}
**Summary:** {summary}
"""
        
        # Use provided context if available
        if context:
            context_text = "\n\nAdditional context:\n"
            for i, item in enumerate(context[:3], 1):
                title = item.get('title', item.get('text', ''))
                context_text += f"{i}. {title}\n"
            rag_context += context_text
        
        # Build enhanced prompt with better RAG context formatting
        if rag_context:
            context_section = f"""
## Relevant Context from Recent News:
{rag_context}

Use this context to better understand the market sentiment and news surrounding this topic.
"""
        else:
            context_section = ""
        
        prompt = f"""You are an expert financial sentiment analyst specializing in stock market analysis.

## Task:
Analyze the sentiment of the following text about stocks/finance. Consider both the explicit sentiment and implicit market implications.

{context_section}
## Text to Analyze:
"{text}"

## Instructions:
1. Analyze the sentiment considering the provided context (if any)
2. Return ONLY a valid JSON object with no additional text
3. Provide scores between 0.0 and 1.0 for each category
4. Ensure scores sum to approximately 1.0
5. Consider market context, financial implications, and investor sentiment

## Required JSON Format:
{{
    "positive": <float>,
    "negative": <float>,
    "neutral": <float>
}}

Return only the JSON object:
"""
        
        # Enhanced system prompt with few-shot examples (industry best practice)
        # Few-shot learning significantly improves model consistency and accuracy
        system_prompt = """You are a professional financial sentiment analyzer with expertise in:
- Stock market analysis and investor sentiment
- Financial news interpretation
- Market trend analysis
- Risk assessment

## Examples of Good Analysis:

Example 1:
Text: "Apple reports record-breaking Q4 earnings, stock surges 5%"
Analysis: {"positive": 0.85, "negative": 0.05, "neutral": 0.10}
Reasoning: Strong positive indicators (record earnings, stock surge)

Example 2:
Text: "Company faces regulatory investigation, shares drop 3%"
Analysis: {"positive": 0.10, "negative": 0.75, "neutral": 0.15}
Reasoning: Negative event (investigation) with market reaction (drop)

Example 3:
Text: "Quarterly report shows mixed results, analysts neutral"
Analysis: {"positive": 0.30, "negative": 0.30, "neutral": 0.40}
Reasoning: Balanced indicators, neutral overall sentiment

## Your Task:
Analyze the sentiment following these examples. Consider:
- Explicit sentiment words
- Market reactions (price movements)
- Financial metrics mentioned
- Regulatory/legal implications
- Context from recent news (if provided)

Respond ONLY with valid JSON. No explanations, no markdown, just the JSON object."""
        
        @retry_with_exponential_backoff(
            max_attempts=self.settings.app.retry_max_attempts,
            initial_delay=self.settings.app.retry_initial_delay,
            max_delay=self.settings.app.retry_max_delay,
            exponential_base=self.settings.app.retry_exponential_base
        )
        def _call_openai_internal():
            return self.client.chat.completions.create(
                model=self.deployment_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt}
                ],
                temperature=self.settings.app.sentiment_temperature,
                max_tokens=self.settings.app.sentiment_max_tokens,
                response_format={"type": "json_object"}  # Use structured output if supported
            )
        
        try:
            
            # Use circuit breaker to prevent cascading failures
            try:
                response = self.circuit_breaker.call(_call_openai_internal)
            except CircuitBreakerOpenError:
                logger.warning(
                    "Circuit breaker is OPEN - Azure OpenAI is failing. "
                    "Using TextBlob fallback to prevent cascading failures."
                )
                return self._textblob_fallback(text)
            
            # Extract JSON from response
            content = response.choices[0].message.content.strip()
            
            # Try to parse JSON - handle both structured output and regular responses
            result = None
            try:
                # First try direct JSON parsing (for structured output)
                result = json.loads(content)
            except json.JSONDecodeError:
                # Fallback: try to extract JSON from markdown or text
                json_match = re.search(r'\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}', content, re.DOTALL)
                if json_match:
                    try:
                        result = json.loads(json_match.group())
                    except json.JSONDecodeError:
                        logger.warning("Could not parse JSON from response")
                        result = None
            
            if result:
                sentiment_scores = SentimentScores.from_dict(result)
                
                # Cache the result
                if self.cache:
                    cached = self.cache.cache_sentiment(
                        text,
                        sentiment_scores.to_dict(),
                        ttl=self.settings.app.cache_ttl_sentiment
                    )
                    if cached:
                        logger.info(f"   💾 Result cached (TTL: {self.settings.app.cache_ttl_sentiment}s)")
                
                logger.info(
                    f"Sentiment Analysis: Result - Positive: {sentiment_scores.positive:.2f}, "
                    f"Negative: {sentiment_scores.negative:.2f}, "
                    f"Neutral: {sentiment_scores.neutral:.2f}, "
                    f"RAG used: {rag_used}, Dominant: {sentiment_scores.dominant_sentiment}"
                )
                return sentiment_scores.to_dict()
            else:
                logger.warning("Could not parse JSON from Azure OpenAI response, using TextBlob fallback")
                return self._textblob_fallback(text)
        
        except json.JSONDecodeError as e:
            logger.error(f"JSON decode error: {e}, using TextBlob fallback")
            return self._textblob_fallback(text)
        except Exception as e:
            logger.error(f"Azure OpenAI error: {e}, using TextBlob fallback")
            return self._textblob_fallback(text)
    
    def _textblob_fallback(self, text: str) -> Dict[str, float]:
        """
        Fallback sentiment analysis using TextBlob.
        
        This method is used when Azure OpenAI fails or returns invalid responses.
        
        Args:
            text: Text to analyze
            
        Returns:
            Dictionary with sentiment scores
        """
        try:
            blob = TextBlob(text)
            polarity = blob.sentiment.polarity
            
            if polarity > 0.1:
                return {
                    'positive': min(polarity, 1.0),
                    'negative': 0.0,
                    'neutral': max(0.0, 1 - polarity)
                }
            elif polarity < -0.1:
                return {
                    'positive': 0.0,
                    'negative': min(abs(polarity), 1.0),
                    'neutral': max(0.0, 1 - abs(polarity))
                }
            else:
                return {'positive': 0.0, 'negative': 0.0, 'neutral': 1.0}
        except Exception as e:
            logger.error(f"TextBlob fallback error: {e}")
            return {'positive': 0.0, 'negative': 0.0, 'neutral': 1.0}
    
    def set_rag_filters(
        self,
        date_range: Optional[tuple] = None,
        sources: Optional[List[str]] = None,
        exclude_sources: Optional[List[str]] = None,
        days_back: Optional[int] = None
    ):
        """
        Set RAG filter parameters for context retrieval.
        
        Args:
            date_range: Optional tuple of (start_date, end_date) as datetime objects
            sources: Optional list of source names to include
            exclude_sources: Optional list of source names to exclude
            days_back: Optional number of days to look back
        """
        self._rag_filters = {
            'date_range': date_range,
            'sources': sources,
            'exclude_sources': exclude_sources,
            'days_back': days_back
        }
    
    def get_stats(self) -> Dict[str, int]:
        """
        Get statistics about cache and RAG usage.
        
        Returns:
            Dictionary with statistics:
            - cache_hits: Number of cache hits
            - cache_misses: Number of cache misses
            - rag_uses: Number of times RAG was successfully used (articles found)
            - rag_attempts: Total number of RAG attempts (including when no articles found)
            - total_requests: Total number of requests
        """
        return {
            'cache_hits': self.cache_hits,
            'cache_misses': self.cache_misses,
            'rag_uses': self.rag_uses,
            'rag_attempts': self.rag_attempts,
            'total_requests': self.cache_hits + self.cache_misses
        }
    
    def batch_analyze(
        self,
        texts: List[str],
        symbol: Optional[str] = None,
        max_workers: Optional[int] = None,
        worker_timeout: Optional[int] = None
    ) -> List[Dict[str, float]]:
        """
        Analyze sentiment for multiple texts in parallel (industry best practice).
        
        Uses ThreadPoolExecutor for concurrent processing, providing 5-10x
        performance improvement over sequential processing.
        
        Args:
            texts: List of texts to analyze
            symbol: Optional stock symbol for RAG context
            max_workers: Maximum number of parallel workers (default: from settings)
            
        Returns:
            List of sentiment score dictionaries in same order as input
        """
        if not texts:
            return []
        
        # Use configured max_workers if not provided
        if max_workers is None:
            configured_workers = self.settings.app.analysis_parallel_workers or self.settings.app.sentiment_max_workers
            max_workers = max(1, configured_workers)
        else:
            max_workers = max(1, max_workers)
        
        if worker_timeout is None:
            worker_timeout = max(30, self.settings.app.analysis_worker_timeout)
        
        # For small batches, sequential might be faster (avoid overhead)
        if len(texts) <= 2:
            return [self.analyze_sentiment(text, symbol) for text in texts]
        
        results = [None] * len(texts)
        import time
        batch_start = time.time()
        
        logger.info(f"   ⚡ Batch Analysis Configuration:")
        logger.info(f"      • Batch size: {len(texts)} articles")
        logger.info(f"      • Parallel workers: {max_workers}")
        logger.info(f"      • Worker timeout: {worker_timeout}s per task")
        
        completed_count = 0
        error_count = 0
        timeout_count = 0
        
        # Use ThreadPoolExecutor for parallel processing
        # ThreadPoolExecutor works well with I/O-bound operations like API calls
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            # Submit all tasks
            future_to_index = {
                executor.submit(self.analyze_sentiment, text, symbol): i
                for i, text in enumerate(texts)
            }
            
            # Collect results as they complete
            for future in as_completed(future_to_index):
                index = future_to_index[future]
                try:
                    results[index] = future.result(timeout=worker_timeout)
                    completed_count += 1
                    # Log progress every 5 completions for demo visibility
                    if completed_count % 5 == 0 or completed_count == len(texts):
                        logger.info(f"      • Progress: {completed_count}/{len(texts)} completed")
                except FuturesTimeoutError:
                    timeout_count += 1
                    logger.error(
                        f"      ⚠️ Timeout after {worker_timeout}s for text at index {index}"
                    )
                    results[index] = {'positive': 0.0, 'negative': 0.0, 'neutral': 1.0}
                except Exception as e:
                    error_count += 1
                    logger.error(f"      ❌ Error analyzing text at index {index}: {e}")
                    # Fallback to neutral sentiment on error
                    results[index] = {'positive': 0.0, 'negative': 0.0, 'neutral': 1.0}
        
        batch_time = time.time() - batch_start
        logger.info(f"   ✅ Batch Analysis Complete:")
        logger.info(f"      • Completed: {completed_count}/{len(texts)}")
        if timeout_count > 0:
            logger.info(f"      • Timeouts: {timeout_count}")
        if error_count > 0:
            logger.info(f"      • Errors: {error_count}")
        logger.info(f"      • Total time: {batch_time:.2f}s ({batch_time/len(texts):.2f}s per article avg)")
        
        return results

