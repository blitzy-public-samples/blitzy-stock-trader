"""
Retrieval Augmented Generation (RAG) service for enhanced sentiment analysis.

This module provides RAG functionality using Azure OpenAI embeddings to retrieve
relevant context from stored articles for improved sentiment analysis accuracy.
"""

import json
import hashlib
from typing import List, Dict, Optional
from openai import AzureOpenAI
import numpy as np

from ..config.settings import Settings, get_settings
from ..utils.logger import get_logger
from ..utils.retry import retry_with_exponential_backoff
from ..utils.preprocessing import preprocess_text, is_financial_text
from .cache import RedisCache
from .vector_db import AzureAISearchVectorDB

logger = get_logger(__name__)


class RAGService:
    """
    Retrieval Augmented Generation service for enhanced sentiment analysis.
    
    This service uses Azure OpenAI embeddings to:
    - Store article embeddings in Redis
    - Retrieve relevant articles using cosine similarity
    - Provide context for sentiment analysis
    
    Attributes:
        client: Azure OpenAI client for embeddings
        cache: Redis cache instance
        settings: Application settings
        embeddings_enabled: Whether embeddings are working
        
    Example:
        >>> settings = get_settings()
        >>> cache = RedisCache(settings=settings)
        >>> rag = RAGService(settings=settings, redis_cache=cache)
        >>> rag.store_article(article_dict, "AAPL")
        >>> context = rag.retrieve_relevant_context("Apple earnings", "AAPL")
    """
    
    def __init__(
        self,
        settings: Optional[Settings] = None,
        redis_cache: Optional[RedisCache] = None,
        vector_db: Optional[AzureAISearchVectorDB] = None
    ):
        """
        Initialize RAG service with Azure OpenAI and Redis cache.
        
        Args:
            settings: Application settings (uses global if not provided)
            redis_cache: Redis cache instance for storing embeddings
            vector_db: Optional Azure AI Search vector database (replaces Redis SCAN for vector search)
            
        Raises:
            ValueError: If Azure OpenAI configuration is invalid
        """
        self.settings = settings or get_settings()
        self.cache = redis_cache
        self.embeddings_enabled = False
        self.embedding_deployment = None
        
        # Initialize Azure AI Search vector database if available
        if vector_db is None and self.settings.is_azure_ai_search_available():
            try:
                self.vector_db = AzureAISearchVectorDB(
                    settings=self.settings,
                    redis_cache=redis_cache
                )
            except Exception as e:
                logger.warning(f"Failed to initialize Azure AI Search vector DB: {e}")
                self.vector_db = None
        else:
            self.vector_db = vector_db
        
        # Initialize Azure OpenAI for embeddings
        azure_config = self.settings.azure_openai
        self.client = AzureOpenAI(
            azure_endpoint=azure_config.endpoint,
            api_key=azure_config.api_key,
            api_version=azure_config.api_version
        )
        
        # Get embedding deployment name
        self.embedding_deployment = azure_config.embedding_deployment
        
        # Test if embeddings work
        self._test_embeddings()
    
    def _test_embeddings(self) -> None:
        """
        Test if embedding model is available and working.
        
        Sets embeddings_enabled flag based on test result.
        """
        if not self.embedding_deployment:
            logger.warning("No embedding deployment configured - RAG disabled")
            self.embeddings_enabled = False
            return
        
        try:
            test_embedding = self.get_embedding("test")
            if test_embedding:
                self.embeddings_enabled = True
                logger.info("RAG embeddings enabled and working")
            else:
                logger.warning("RAG embeddings disabled - embedding model not available")
                self.embeddings_enabled = False
        except Exception as e:
            logger.warning(f"RAG embeddings disabled - {e}")
            self.embeddings_enabled = False
    
    def get_embeddings_batch(
        self, 
        texts: List[str], 
        batch_size: Optional[int] = None,
        use_cache: bool = True
    ) -> List[Optional[List[float]]]:
        """
        Generate embeddings for multiple texts in batches (industry best practice).
        
        Azure OpenAI supports up to 2048 inputs per API call, significantly
        reducing API calls and costs compared to individual requests.
        
        Args:
            texts: List of texts to generate embeddings for
            batch_size: Number of texts per batch (max 2048 for Azure OpenAI)
            use_cache: Whether to check cache for individual texts
            
        Returns:
            List of embedding vectors (None for failed texts)
            
        Example:
            >>> texts = ["Article 1 text", "Article 2 text", ...]
            >>> embeddings = rag.get_embeddings_batch(texts, batch_size=100)
        """
        if not texts:
            return []
        
        # Use configured batch_size if not provided
        if batch_size is None:
            batch_size = self.settings.app.rag_batch_size
        
        if not self.embedding_deployment:
            logger.warning("No embedding deployment configured")
            return [None] * len(texts)
        
        # Filter out empty texts and track indices
        valid_texts = []
        valid_indices = []
        results = [None] * len(texts)
        
        for i, text in enumerate(texts):
            if text and len(text.strip()) > 0:
                valid_texts.append(text)
                valid_indices.append(i)
        
        if not valid_texts:
            return results
        
        # Check cache for valid texts if enabled
        cached_embeddings = {}
        texts_to_fetch = []
        fetch_indices = []
        
        if use_cache and self.cache and self.cache.client:
            for idx, text in zip(valid_indices, valid_texts):
                cache_key = f"query_embedding:{hashlib.md5(text.encode()).hexdigest()}"
                cached = self.cache.client.get(cache_key)
                if cached:
                    try:
                        cached_embeddings[idx] = json.loads(cached)
                        results[idx] = cached_embeddings[idx]
                    except Exception as e:
                        logger.warning(f"Error loading cached embedding: {e}")
                        texts_to_fetch.append(text)
                        fetch_indices.append(idx)
                else:
                    texts_to_fetch.append(text)
                    fetch_indices.append(idx)
        else:
            texts_to_fetch = valid_texts
            fetch_indices = valid_indices
        
        # Generate embeddings in batches
        if texts_to_fetch:
            
            for i in range(0, len(texts_to_fetch), batch_size):
                batch = texts_to_fetch[i:i + batch_size]
                batch_indices = fetch_indices[i:i + batch_size]
                
                @retry_with_exponential_backoff(
                    max_attempts=self.settings.app.retry_max_attempts,
                    initial_delay=self.settings.app.retry_initial_delay,
                    max_delay=self.settings.app.retry_max_delay,
                    exponential_base=self.settings.app.retry_exponential_base
                )
                def _get_batch_embeddings_internal():
                    return self.client.embeddings.create(
                        model=self.embedding_deployment,
                        input=batch
                    )
                
                try:
                    response = _get_batch_embeddings_internal()
                    
                    # Map results back to original indices
                    for j, embedding_data in enumerate(response.data):
                        original_idx = batch_indices[j]
                        embedding = embedding_data.embedding
                        results[original_idx] = embedding
                        
                        # Cache the embedding
                        if use_cache and self.cache and self.cache.client:
                            text = batch[j]
                            cache_key = f"query_embedding:{hashlib.md5(text.encode()).hexdigest()}"
                            self.cache.client.setex(
                                cache_key,
                                86400,  # 24 hours
                                json.dumps(embedding)
                            )
                    
                    
                except Exception as e:
                    logger.error(f"Error generating batch embeddings after retries: {e}")
                    # Mark batch as failed
                    for idx in batch_indices:
                        results[idx] = None
        
        cached_count = len(cached_embeddings)
        generated_count = sum(1 for r in results if r is not None) - cached_count
        
        return results
    
    def get_embedding(self, text: str, use_cache: bool = True) -> Optional[List[float]]:
        """
        Get embedding vector for text using Azure OpenAI with optional caching.
        
        Args:
            text: Text to generate embedding for
            use_cache: Whether to use Redis cache for embeddings (default: True)
            
        Returns:
            Embedding vector as list of floats, or None if failed
        """
        if not text or len(text.strip()) == 0:
            logger.warning("Empty text provided for embedding")
            return None
        
        if not self.embedding_deployment:
            logger.warning("No embedding deployment configured")
            return None
        
        # Check cache first if enabled
        if use_cache and self.cache and self.cache.client:
            cache_key = f"query_embedding:{hashlib.md5(text.encode()).hexdigest()}"
            cached_embedding = self.cache.client.get(cache_key)
            if cached_embedding:
                try:
                    embedding = json.loads(cached_embedding)
                    return embedding
                except Exception as e:
                    logger.warning(f"Error loading cached embedding: {e}")
        
        @retry_with_exponential_backoff(
            max_attempts=self.settings.app.retry_max_attempts,
            initial_delay=self.settings.app.retry_initial_delay,
            max_delay=self.settings.app.retry_max_delay,
            exponential_base=self.settings.app.retry_exponential_base
        )
        def _get_embedding_internal():
            return self.client.embeddings.create(
                model=self.embedding_deployment,
                input=text
            )
        
        try:
            response = _get_embedding_internal()
            embedding = response.data[0].embedding
            
            # Cache the embedding if enabled
            if use_cache and self.cache and self.cache.client:
                cache_key = f"query_embedding:{hashlib.md5(text.encode()).hexdigest()}"
                # Cache query embeddings for 24 hours
                self.cache.client.setex(
                    cache_key,
                    86400,
                    json.dumps(embedding)
                )
            
            return embedding
        except Exception as e:
            logger.error(f"Error getting embedding after retries: {e}")
            return None
    
    def store_articles_batch(
        self, 
        articles: List[Dict], 
        symbol: str,
        batch_size: Optional[int] = None
    ) -> int:
        """
        Store multiple articles with batch embedding generation (industry best practice).
        
        This method is significantly faster than storing articles one-by-one,
        reducing API calls from N to N/batch_size.
        
        Args:
            articles: List of article dictionaries
            symbol: Stock ticker symbol
            batch_size: Number of articles to process per batch
            
        Returns:
            Number of articles successfully stored
        """
        if not self.embeddings_enabled:
            logger.warning(f"Cannot store articles: embeddings not enabled (embedding_deployment={self.embedding_deployment})")
            return 0
        
        if not self.cache or not self.cache.client:
            logger.warning("Cannot store articles: Redis cache not available - articles will not be stored in RAG")
            return 0
        
        if not articles:
            logger.warning(f"Cannot store articles: empty articles list provided for {symbol}")
            return 0
        
        # Use configured batch_size if not provided
        if batch_size is None:
            batch_size = self.settings.app.rag_batch_size
        
        stored_count = 0
        
        # Prepare article texts for batch embedding
        article_texts = []
        article_metadata = []
        
        duplicates_count = 0
        empty_text_count = 0
        
        for article in articles:
            # Create article ID
            article_id = hashlib.md5(
                f"{symbol}:{article.get('title', '')}:{article.get('url', '')}".encode()
            ).hexdigest()
            
            # Check for duplicates to avoid storing same article multiple times
            duplicate_key = f"article_hash:{symbol}:{article_id}"
            if self.cache.client.exists(duplicate_key):
                duplicates_count += 1
                continue
            
            # Prepare article text (preprocess for better quality)
            raw_text = f"{article.get('title', '')} {article.get('summary', '')}"
            if not raw_text.strip():
                empty_text_count += 1
                continue
            
            # Preprocess text before embedding (improves embedding quality)
            article_text = preprocess_text(raw_text, expand_abbreviations=True)
            
            # Optional: Filter low-quality articles
            if not is_financial_text(article_text):
                pass  # Store anyway, but could filter here in future
            
            article_texts.append(article_text)
            article_metadata.append({
                'article_id': article_id,
                'article': article,
                'symbol': symbol,
                'duplicate_key': duplicate_key
            })
        
        if not article_texts:
            if duplicates_count != len(articles):
                logger.warning(
                    f"   ⚠️ No articles to store for {symbol}: "
                    f"{duplicates_count} duplicates, {empty_text_count} empty, {len(article_texts)} valid"
                )
            return 0
        
        # Log preparation summary
        logger.info(f"   📊 Article Preparation Summary:")
        logger.info(f"      • Total articles: {len(articles)}")
        logger.info(f"      • Duplicates skipped: {duplicates_count}")
        logger.info(f"      • Empty texts skipped: {empty_text_count}")
        logger.info(f"      • Valid articles to store: {len(article_texts)}")
        
        # Check Azure AI Search for existing documents BEFORE generating embeddings
        # This saves API calls and processing time
        articles_to_process = article_texts
        metadata_to_process = article_metadata
        existing_in_vector_db = 0
        
        if self.vector_db and self.vector_db.is_available():
            logger.info(f"   🔍 Checking Azure AI Search for existing articles...")
            vector_ids_to_check = [f"{symbol}_{meta['article_id']}" for meta in article_metadata]
            existing_docs = self.vector_db.batch_check_documents_exist(vector_ids_to_check)
            
            # Filter out articles that already exist in Azure AI Search
            filtered_texts = []
            filtered_metadata = []
            for i, (text, metadata) in enumerate(zip(article_texts, article_metadata)):
                vector_id = f"{symbol}_{metadata['article_id']}"
                if existing_docs.get(vector_id, False):
                    existing_in_vector_db += 1
                    # Still mark in Redis for duplicate checking
                    if self.cache and self.cache.client:
                        try:
                            ttl = self.settings.app.cache_ttl_rag_articles
                            self.cache.client.setex(metadata['duplicate_key'], ttl, "1")
                        except Exception:
                            pass
                else:
                    filtered_texts.append(text)
                    filtered_metadata.append(metadata)
            
            articles_to_process = filtered_texts
            metadata_to_process = filtered_metadata
            
            if existing_in_vector_db > 0:
                logger.info(f"   ✅ Found {existing_in_vector_db} articles already in Azure AI Search (skipping)")
            if len(articles_to_process) > 0:
                logger.info(f"   📝 {len(articles_to_process)} new articles need processing")
        
        # Generate embeddings only for articles that don't exist in vector DB
        if not articles_to_process:
            logger.info(f"   ℹ️ All articles already exist in vector database - no embeddings needed")
            return existing_in_vector_db
        
        logger.info(f"   🔄 Generating embeddings for {len(articles_to_process)} articles (batch_size={batch_size})...")
        embeddings = self.get_embeddings_batch(articles_to_process, batch_size=batch_size, use_cache=True)
        
        # Count successful embeddings
        valid_embeddings = sum(1 for e in embeddings if e is not None)
        failed_embeddings = len(articles_to_process) - valid_embeddings
        logger.info(f"   ✅ Embedding Generation Complete:")
        logger.info(f"      • Successful: {valid_embeddings}/{len(articles_to_process)}")
        if failed_embeddings > 0:
            logger.warning(f"      • Failed: {failed_embeddings}")
        
        # Use Azure AI Search if available, otherwise fallback to Redis
        if self.vector_db and self.vector_db.is_available():
            logger.info(f"   📦 Preparing {len(articles_to_process)} new articles for storage in Azure AI Search...")
            
            # Store in Azure AI Search
            vectors_to_store = []
            for metadata, embedding in zip(metadata_to_process, embeddings):
                if embedding is None:
                    logger.warning(f"      ⚠️ Skipping article (no embedding): {metadata['article_id'][:8]}")
                    continue
                
                article = metadata['article']
                article_id = metadata['article_id']
                
                # Prepare metadata with proper timestamp handling
                timestamp = article.get('timestamp', '')
                if timestamp:
                    # Convert datetime to ISO format string if needed
                    if hasattr(timestamp, 'isoformat'):
                        timestamp_str = timestamp.isoformat()
                    else:
                        timestamp_str = str(timestamp)
                else:
                    timestamp_str = ''
                
                # Prepare metadata
                article_meta = {
                    'article_id': article_id,
                    'symbol': symbol,
                    'title': article.get('title', '') or '',
                    'summary': article.get('summary', '') or '',
                    'source': article.get('source', '') or 'Unknown',
                    'url': article.get('url', '') or '',
                    'timestamp': timestamp_str
                }
                
                # Log article being stored (debug level to avoid too much noise)
                title_preview = article_meta['title'][:50] if article_meta['title'] else 'No title'
                logger.debug(
                    f"      • Preparing article [{article_id[:8]}] - "
                    f"'{title_preview}...' (source: {article_meta['source']})"
                )
                
                vectors_to_store.append({
                    'vector_id': vector_id,
                    'vector': embedding,
                    'metadata': article_meta
                })
            
            # Batch store in Azure AI Search
            logger.info(f"   💾 Storing {len(vectors_to_store)} vectors in Azure AI Search...")
            stored_count = self.vector_db.batch_store_vectors(vectors_to_store)
            
            if stored_count == len(vectors_to_store):
                logger.info(f"   ✅ Successfully stored all {stored_count} articles in Azure AI Search")
            elif stored_count > 0:
                logger.warning(f"   ⚠️ Partially stored: {stored_count}/{len(vectors_to_store)} articles")
            else:
                logger.error(f"   ❌ Failed to store any articles in Azure AI Search")
            
            # Also mark newly stored articles in Redis for duplicate checking
            # (Existing articles were already marked during the check phase)
            if self.cache and self.cache.client:
                ttl = self.settings.app.cache_ttl_rag_articles
                # Mark newly stored articles
                for metadata in metadata_to_process:
                    try:
                        self.cache.client.setex(metadata['duplicate_key'], ttl, "1")
                    except Exception:
                        pass  # Non-critical
            
            # Return total count: existing + newly stored
            total_stored = existing_in_vector_db + stored_count
            logger.info(f"   📊 Total articles in RAG: {total_stored} ({existing_in_vector_db} existing + {stored_count} newly stored)")
            return total_stored
        else:
            # Fallback to Redis storage (original implementation)
            logger.info(f"   📦 Using Redis fallback for {len(article_texts)} articles (Azure AI Search not available)")
            ttl = self.settings.app.cache_ttl_rag_articles
            
            for metadata, embedding in zip(article_metadata, embeddings):
                if embedding is None:
                    logger.warning(f"Failed to generate embedding for article: {metadata['article_id'][:8]}")
                    continue
                
                try:
                    article = metadata['article']
                    article_id = metadata['article_id']
                    
                    # Prepare metadata
                    article_meta = {
                        'article_id': article_id,
                        'symbol': symbol,
                        'title': article.get('title', ''),
                        'summary': article.get('summary', ''),
                        'source': article.get('source', ''),
                        'url': article.get('url', ''),
                        'timestamp': str(article.get('timestamp', ''))
                    }
                    
                    # Store embedding and metadata
                    embedding_key = f"embedding:{symbol}:{article_id}"
                    metadata_key = f"article:{symbol}:{article_id}"
                    
                    # Use pipeline for atomic operations
                    pipe = self.cache.client.pipeline()
                    pipe.setex(embedding_key, ttl, json.dumps(embedding))
                    pipe.setex(metadata_key, ttl, json.dumps(article_meta, default=str))
                    pipe.setex(metadata['duplicate_key'], ttl, "1")  # Mark as stored
                    pipe.execute()
                    
                    stored_count += 1
                    
                except Exception as e:
                    logger.error(f"Error storing article in batch: {e}")
                    continue
            
            logger.info(f"   ✅ Stored {stored_count}/{len(article_texts)} articles in Redis")
        
        if stored_count == 0:
            logger.error(
                f"Failed to store articles in RAG for {symbol}: "
                f"{len(article_texts)} valid articles processed, {stored_count} stored. "
                f"Check embedding generation and Redis connectivity."
            )
        return stored_count
    
    def store_article(self, article: Dict, symbol: str) -> bool:
        """
        Store a single article with embedding for RAG retrieval.
        
        For multiple articles, use store_articles_batch() for better performance.
        
        Args:
            article: Article dictionary with title, summary, url, etc.
            symbol: Stock ticker symbol
            
        Returns:
            True if stored successfully, False otherwise
        """
        stored_count = self.store_articles_batch([article], symbol, batch_size=1)
        return stored_count > 0
    
    def _keyword_search(
        self,
        query: str,
        symbol: str,
        top_k: int
    ) -> List[Dict]:
        """
        Perform keyword-based search on article titles and summaries.
        
        This complements semantic search by catching exact keyword matches
        that might be missed by embeddings (e.g., "earnings", "FDA approval").
        
        Args:
            query: Query text with keywords to search for
            symbol: Stock ticker symbol
            top_k: Number of top articles to return
            
        Returns:
            List of article metadata dictionaries with keyword match scores
        """
        if not self.cache or not self.cache.client:
            return []
        
        try:
            # Extract keywords from query (simple approach)
            query_lower = query.lower()
            # Split into words and filter common stop words
            stop_words = {'the', 'a', 'an', 'and', 'or', 'but', 'in', 'on', 'at', 'to', 'for', 'of', 'with', 'by'}
            keywords = [w for w in query_lower.split() if len(w) > 2 and w not in stop_words]
            
            if not keywords:
                return []
            
            # Get all articles for this symbol
            pattern = f"article:{symbol}:*"
            keys = []
            cursor = 0
            
            while True:
                cursor, partial_keys = self.cache.client.scan(
                    cursor=cursor,
                    match=pattern,
                    count=100
                )
                keys.extend(partial_keys)
                if cursor == 0:
                    break
            
            if not keys:
                return []
            
            # Score articles based on keyword matches
            keyword_scores = []
            for key in keys:
                metadata_data = self.cache.client.get(key)
                if metadata_data:
                    try:
                        metadata = json.loads(metadata_data)
                        title = metadata.get('title', '').lower()
                        summary = metadata.get('summary', '').lower()
                        text = f"{title} {summary}"
                        
                        # Calculate keyword match score
                        # Count keyword occurrences and normalize
                        matches = sum(1 for keyword in keywords if keyword in text)
                        if matches > 0:
                            # Score based on number of matches and position (title matches weighted higher)
                            title_matches = sum(1 for keyword in keywords if keyword in title)
                            score = (title_matches * 2 + matches) / (len(keywords) * 3)  # Normalize to 0-1
                            keyword_scores.append((score, metadata.get('article_id', '')))
                    except Exception as e:
                        continue
            
            # Sort by score and get top_k
            keyword_scores.sort(reverse=True, key=lambda x: x[0])
            
            # Retrieve full metadata for top results
            results = []
            for score, article_id in keyword_scores[:top_k]:
                metadata_key = f"article:{symbol}:{article_id}"
                metadata_data = self.cache.client.get(metadata_key)
                if metadata_data:
                    try:
                        metadata = json.loads(metadata_data)
                        metadata['keyword_score'] = score
                        metadata['similarity'] = score  # Use similarity field for consistency
                        results.append(metadata)
                    except Exception as e:
                        continue
            
            return results
            
        except Exception as e:
            logger.error(f"Error in keyword search: {e}")
            return []
    
    def _reciprocal_rank_fusion(
        self,
        semantic_results: List[Dict],
        keyword_results: List[Dict],
        k: int = 60
    ) -> List[Dict]:
        """
        Combine semantic and keyword search results using Reciprocal Rank Fusion (RRF).
        
        RRF is a proven method for combining multiple ranking systems without
        requiring score normalization. It's widely used in information retrieval.
        
        Args:
            semantic_results: Results from semantic search with similarity scores
            keyword_results: Results from keyword search with keyword scores
            k: RRF constant (default: 60, standard in literature)
            
        Returns:
            Combined and re-ranked results
        """
        # Create article_id -> RRF score mapping
        rrf_scores = {}
        
        # Add semantic search results
        for rank, article in enumerate(semantic_results, 1):
            article_id = article.get('article_id', '')
            if article_id:
                rrf_scores[article_id] = rrf_scores.get(article_id, 0) + (1 / (k + rank))
                # Store original similarity for reference
                if 'similarity' not in article:
                    article['similarity'] = article.get('keyword_score', 0)
        
        # Add keyword search results
        for rank, article in enumerate(keyword_results, 1):
            article_id = article.get('article_id', '')
            if article_id:
                rrf_scores[article_id] = rrf_scores.get(article_id, 0) + (1 / (k + rank))
                # Store keyword score for reference
                if 'keyword_score' not in article:
                    article['keyword_score'] = article.get('similarity', 0)
        
        # Combine all articles and sort by RRF score
        all_articles = {}
        for article in semantic_results + keyword_results:
            article_id = article.get('article_id', '')
            if article_id and article_id not in all_articles:
                all_articles[article_id] = article
        
        # Sort by RRF score
        combined = [
            {**article, 'rrf_score': rrf_scores.get(article.get('article_id', ''), 0)}
            for article in all_articles.values()
        ]
        combined.sort(reverse=True, key=lambda x: x.get('rrf_score', 0))
        
        return combined
    
    def _expand_query(self, query: str, symbol: str) -> str:
        """
        Expand query with synonyms and related terms for better retrieval.
        
        Query expansion improves retrieval by adding:
        - Stock symbol synonyms (e.g., "Apple" for "AAPL")
        - Financial domain terms
        - Related concepts
        
        Args:
            query: Original query text
            symbol: Stock ticker symbol
            
        Returns:
            Expanded query with additional terms
        """
        expanded_terms = [query]
        
        # Add symbol itself
        expanded_terms.append(symbol)
        
        # Common financial context terms (helps with short queries)
        financial_terms = [
            "earnings", "revenue", "stock price", "market", "financial",
            "quarterly", "annual", "report", "forecast", "analyst"
        ]
        
        # Add financial terms if query is short (likely to benefit from expansion)
        if len(query.split()) <= 3:
            expanded_terms.extend(financial_terms[:3])  # Add top 3 terms
        
        # Join and return (deduplicate while preserving order)
        seen = set()
        unique_terms = []
        for term in expanded_terms:
            term_lower = term.lower()
            if term_lower not in seen:
                seen.add(term_lower)
                unique_terms.append(term)
        
        expanded_query = " ".join(unique_terms)
        return expanded_query
    
    def _build_filter_string(
        self,
        symbol: str,
        date_range: Optional[tuple] = None,
        sources: Optional[List[str]] = None,
        exclude_sources: Optional[List[str]] = None,
        days_back: Optional[int] = None
    ) -> Optional[str]:
        """
        Build OData filter string for Azure AI Search.
        
        Args:
            symbol: Stock ticker symbol
            date_range: Optional tuple of (start_date, end_date) as datetime objects
            sources: Optional list of source names to include
            exclude_sources: Optional list of source names to exclude
            days_back: Optional number of days to look back (convenience parameter)
            
        Returns:
            OData filter string or None if no filters needed
        """
        if not self.vector_db or not self.vector_db.is_available():
            return None
        
        filters = []
        filter_details = []
        
        # Always filter by symbol (safely escape)
        escaped_symbol = str(symbol).replace("'", "''")
        filters.append(f"symbol eq '{escaped_symbol}'")
        filter_details.append(f"symbol={symbol}")
        
        # Date range filter
        if days_back:
            from datetime import datetime, timedelta
            end_date = datetime.now()
            start_date = end_date - timedelta(days=days_back)
            try:
                filters.append(f"timestamp ge {start_date.isoformat()}Z")
                filters.append(f"timestamp le {end_date.isoformat()}Z")
                filter_details.append(f"date_range=last_{days_back}_days")
                logger.info(f"RAG Filter: Applying date filter - last {days_back} days")
            except Exception as e:
                logger.error(f"RAG Filter: Error building date filter: {e}")
        elif date_range:
            start_date, end_date = date_range
            if start_date:
                try:
                    if hasattr(start_date, 'isoformat'):
                        filters.append(f"timestamp ge {start_date.isoformat()}Z")
                        filter_details.append(f"start_date={start_date.strftime('%Y-%m-%d')}")
                    else:
                        logger.warning(f"RAG Filter: Invalid start_date type: {type(start_date)}")
                except Exception as e:
                    logger.error(f"RAG Filter: Error formatting start_date: {e}")
            if end_date:
                try:
                    if hasattr(end_date, 'isoformat'):
                        filters.append(f"timestamp le {end_date.isoformat()}Z")
                        filter_details.append(f"end_date={end_date.strftime('%Y-%m-%d')}")
                    else:
                        logger.warning(f"RAG Filter: Invalid end_date type: {type(end_date)}")
                except Exception as e:
                    logger.error(f"RAG Filter: Error formatting end_date: {e}")
            if start_date or end_date:
                start_str = start_date.strftime('%Y-%m-%d') if start_date and hasattr(start_date, 'strftime') else 'any'
                end_str = end_date.strftime('%Y-%m-%d') if end_date and hasattr(end_date, 'strftime') else 'any'
                logger.info(f"RAG Filter: Applying custom date range - {start_str} to {end_str}")
        
        # Source filters (safely escape source names)
        if sources:
            source_filters = []
            for s in sources:
                if s:  # Only add non-empty sources
                    escaped_source = str(s).replace("'", "''")
                    source_filters.append(f"source eq '{escaped_source}'")
            if source_filters:
                filters.append(f"({' or '.join(source_filters)})")
                filter_details.append(f"sources={', '.join(sources)}")
                logger.info(f"RAG Filter: Including sources - {', '.join(sources)}")
        
        if exclude_sources:
            exclude_filters = []
            for s in exclude_sources:
                if s:  # Only add non-empty sources
                    escaped_source = str(s).replace("'", "''")
                    exclude_filters.append(f"source ne '{escaped_source}'")
            if exclude_filters:
                filters.extend(exclude_filters)
                filter_details.append(f"exclude_sources={', '.join(exclude_sources)}")
                logger.info(f"RAG Filter: Excluding sources - {', '.join(exclude_sources)}")
        
        filter_string = " and ".join(filters) if filters else None
        if filter_string:
            logger.info(f"RAG Filter: Built OData filter for {symbol} - {', '.join(filter_details)}")
            logger.debug(f"RAG Filter: Full OData filter string: {filter_string}")
        
        return filter_string
    
    def retrieve_relevant_context(
        self,
        query: str,
        symbol: str,
        top_k: Optional[int] = None,
        use_hybrid: bool = True,
        apply_temporal_decay: bool = True,
        expand_query: bool = True,
        date_range: Optional[tuple] = None,
        sources: Optional[List[str]] = None,
        exclude_sources: Optional[List[str]] = None,
        days_back: Optional[int] = None
    ) -> List[Dict]:
        """
        Retrieve relevant articles for RAG context using hybrid search.
        
        Hybrid search combines:
        - Semantic search (cosine similarity on embeddings) - catches conceptual matches
        - Keyword search (exact keyword matching) - catches specific terms
        
        This approach provides 20-30% better retrieval accuracy than semantic-only.
        
        Args:
            query: Query text to find similar articles for
            symbol: Stock ticker symbol
            top_k: Number of top articles to return (default: from settings)
            use_hybrid: Whether to use hybrid search (default: True)
            apply_temporal_decay: Whether to boost recent articles (default: True)
            expand_query: Whether to expand query with related terms (default: True)
            date_range: Optional tuple of (start_date, end_date) as datetime objects
            sources: Optional list of source names to include
            exclude_sources: Optional list of source names to exclude
            days_back: Optional number of days to look back (convenience parameter)
            
        Returns:
            List of article metadata dictionaries with similarity scores
        """
        
        if not self.embeddings_enabled and not use_hybrid:
            logger.warning("RAG retrieval skipped: embeddings not enabled and hybrid search disabled")
            return []
        
        # Check if Azure AI Search is available, otherwise check Redis
        if not self.vector_db or not self.vector_db.is_available():
            if not self.cache or not self.cache.client:
                logger.warning("RAG retrieval skipped: No vector database or Redis cache available")
                return []
        
        if top_k is None:
            top_k = self.settings.app.rag_top_k
        
        # Expand query for better retrieval (especially for short queries)
        if expand_query:
            expanded_query = self._expand_query(query, symbol)
        else:
            expanded_query = query
        
        # Build filter string for Azure AI Search
        filter_string = None
        if self.vector_db and self.vector_db.is_available():
            filter_string = self._build_filter_string(
                symbol=symbol,
                date_range=date_range,
                sources=sources,
                exclude_sources=exclude_sources,
                days_back=days_back
            )
        
        try:
            # Use Azure AI Search if available
            if self.vector_db and self.vector_db.is_available():
                logger.info(f"RAG Retrieval: Using Azure AI Search for retrieval (symbol={symbol}, top_k={top_k}, hybrid={use_hybrid})")
                logger.info(f"RAG Retrieval: Query: '{query[:100] if query else 'empty'}...'")
                logger.info(f"RAG Retrieval: Expanded query: '{expanded_query[:100] if expanded_query else 'empty'}...'")
                
                # Get query embedding
                logger.info(f"RAG Retrieval: Generating embedding for query...")
                query_embedding = self.get_embedding(expanded_query)
                if not query_embedding:
                    logger.warning("RAG Retrieval: ❌ Failed - Could not get query embedding")
                    return []
                logger.info(f"RAG Retrieval: ✅ Query embedding generated (dimension: {len(query_embedding) if query_embedding else 0})")
                
                # Use hybrid search if enabled, otherwise pure vector search
                if use_hybrid:
                    query_preview = query[:50] if query and len(query) > 50 else query
                    logger.info(f"RAG Retrieval: Performing hybrid search (vector + keyword) for query: '{query_preview}...' (symbol={symbol})")
                    if filter_string:
                        logger.info(f"RAG Retrieval: Filter applied - {filter_string[:100]}...")
                    results = self.vector_db.hybrid_search(
                        query_text=query,
                        query_vector=query_embedding,
                        top_k=top_k * 2,  # Get more for filtering
                        filter=filter_string
                    )
                    logger.info(f"RAG Retrieval: Hybrid search returned {len(results)} raw results for {symbol}")
                    
                    # Log details of retrieved articles
                    if results:
                        logger.info(f"RAG Retrieval: ✅ Retrieved {len(results)} articles for {symbol}:")
                        for i, result in enumerate(results[:5], 1):  # Log top 5
                            title = result.get('title') or 'No title'
                            score = result.get('rrf_score') or result.get('similarity') or 0.0
                            source = result.get('source') or 'Unknown'
                            article_id = result.get('article_id') or 'Unknown'
                            symbol_found = result.get('symbol') or 'Unknown'
                            logger.info(f"  [{i}] Score: {score:.3f} | Symbol: {symbol_found} | Source: {source} | ID: {article_id[:8]} | Title: {title[:60]}")
                    else:
                        logger.warning(f"RAG Retrieval: ⚠️ No articles found for {symbol} with query '{query_preview}...'")
                        logger.warning(f"RAG Retrieval: Possible reasons:")
                        logger.warning(f"  1. Articles may not be indexed yet (Azure AI Search indexing delay)")
                        logger.warning(f"  2. Filter may be too restrictive")
                        logger.warning(f"  3. Query may not match stored articles")
                        if filter_string:
                            logger.warning(f"RAG Retrieval: Current filter: {filter_string}")
                        logger.warning(f"RAG Retrieval: Try searching without filters or wait a few seconds for indexing")
                else:
                    logger.info(f"RAG: Performing vector search for query: '{query[:50]}...'")
                    results = self.vector_db.search_vectors(
                        query_vector=query_embedding,
                        top_k=top_k * 2,
                        filter=filter_string
                    )
                    logger.info(f"RAG: Vector search returned {len(results)} results")
                
                # Apply similarity threshold
                similarity_threshold = self.settings.app.rag_similarity_threshold
                filtered_results = []
                for r in results:
                    score = r.get('rrf_score') or r.get('similarity') or 0.0
                    if score >= similarity_threshold:
                        filtered_results.append(r)
                    else:
                        title = r.get('title') or 'No title'
                        logger.debug(f"RAG Filter: Excluded article (score {score:.3f} < threshold {similarity_threshold}): '{title[:50]}...'")
                
                if len(results) > len(filtered_results):
                    logger.info(f"RAG Filter: Similarity threshold ({similarity_threshold}) filtered {len(results) - len(filtered_results)} results, {len(filtered_results)} remaining")
                
                # Apply temporal decay if enabled (for Redis fallback compatibility)
                if apply_temporal_decay and filtered_results:
                    logger.info(f"RAG Filter: Applying temporal decay to {len(filtered_results)} results")
                    filtered_results = self._apply_temporal_decay(filtered_results)
                
                # Return top_k
                final_results = filtered_results[:top_k]
                logger.info(f"RAG Retrieval: ✅ Returning {len(final_results)} articles for context (requested: {top_k}, symbol={symbol})")
                if final_results:
                    top_title = final_results[0].get('title') or 'N/A'
                    top_score = final_results[0].get('rrf_score') or final_results[0].get('similarity') or 0.0
                    top_source = final_results[0].get('source') or 'Unknown'
                    logger.info(f"RAG Retrieval: Top article - '{top_title[:60]}...' (score: {top_score:.3f}, source: {top_source})")
                else:
                    logger.warning(f"RAG Retrieval: ⚠️ No articles returned after filtering (symbol={symbol}, threshold={similarity_threshold})")
                
                return final_results
            
            # Fallback to Redis SCAN-based search (original implementation)
            logger.info(f"RAG: Using Redis SCAN fallback for retrieval (symbol={symbol}, top_k={top_k}, Azure AI Search not available)")
            
            # Hybrid search: combine semantic and keyword search
            if use_hybrid and self.embeddings_enabled:
                # Perform both semantic and keyword search
                # Use expanded query for semantic search (better embeddings)
                # Use original query for keyword search (more precise)
                semantic_results = self._semantic_search(expanded_query, symbol, top_k * 2)
                keyword_results = self._keyword_search(query, symbol, top_k * 2)
                
                # Combine using Reciprocal Rank Fusion (RRF)
                if semantic_results or keyword_results:
                    combined_results = self._reciprocal_rank_fusion(
                        semantic_results,
                        keyword_results,
                        k=60  # Standard RRF constant
                    )
                    
                    # Apply similarity threshold and get top_k
                    similarity_threshold = self.settings.app.rag_similarity_threshold
                    filtered_results = [
                        r for r in combined_results 
                        if r.get('rrf_score', r.get('similarity', 0)) >= similarity_threshold
                    ]
                    
                    # Log if threshold is filtering out all results
                    if combined_results and not filtered_results:
                        max_score = max(r.get('rrf_score', r.get('similarity', 0)) for r in combined_results)
                        
                        # Auto-adjust threshold if it's too high for RRF scores
                        # RRF scores are typically 0.01-0.15, so if threshold > max_score, auto-lower it
                        if similarity_threshold > max_score and max_score > 0:
                            # Use a threshold slightly below the max score to allow some results through
                            adjusted_threshold = max(0.01, max_score * self.settings.app.rag_similarity_auto_adjust_multiplier)
                            logger.warning(
                                f"Similarity threshold {similarity_threshold} too high (max score: {max_score:.3f}). "
                                f"Auto-adjusting to {adjusted_threshold:.3f}"
                            )
                            # Re-filter with adjusted threshold
                            filtered_results = [
                                r for r in combined_results 
                                if r.get('rrf_score', r.get('similarity', 0)) >= adjusted_threshold
                            ]
                        else:
                            logger.warning(
                                f"Similarity threshold {similarity_threshold} filtered all {len(combined_results)} results "
                                f"(max score: {max_score:.3f}). Consider lowering threshold in .env file."
                            )
                    
                    results = filtered_results[:top_k]
                    
                    # Apply temporal decay (boost recent articles) - improves relevance for time-sensitive financial news
                    if apply_temporal_decay and results:
                        results = self._apply_temporal_decay(results)
                    
                    # Ensure we only return top_k
                    results = results[:top_k]
                    
                    if not results:
                        logger.warning(
                            f"RAG found 0 articles for {symbol} after filtering "
                            f"(threshold: {similarity_threshold}, combined: {len(combined_results)})"
                        )
                    
                    return results
                else:
                    # Fallback to semantic-only if hybrid fails
                    return self._semantic_search(expanded_query, symbol, top_k)
            
            elif self.embeddings_enabled:
                # Semantic-only search (use expanded query)
                return self._semantic_search(expanded_query, symbol, top_k)
            else:
                # Keyword-only search (fallback when embeddings disabled)
                keyword_results = self._keyword_search(query, symbol, top_k)
                if keyword_results:
                    # Apply similarity threshold for keyword-only results
                    similarity_threshold = self.settings.app.rag_similarity_threshold
                    filtered = [
                        r for r in keyword_results 
                        if r.get('similarity', r.get('keyword_score', 0)) >= similarity_threshold
                    ]
                    if filtered:
                        return filtered
                return []
                
        except Exception as e:
            logger.error(f"Error retrieving context: {e}")
            return []
    
    def _semantic_search(
        self,
        query: str,
        symbol: str,
        top_k: int
    ) -> List[Dict]:
        """
        Perform semantic search using cosine similarity on embeddings.
        
        This is the original semantic search method, now extracted as a helper.
        
        Args:
            query: Query text to find similar articles for
            symbol: Stock ticker symbol
            top_k: Number of top articles to return
            
        Returns:
            List of article metadata dictionaries with similarity scores
        """
        if not self.embeddings_enabled:
            logger.warning("Semantic search skipped: embeddings not enabled")
            return []
        
        try:
            # Get embedding for query
            query_embedding = self.get_embedding(query)
            if not query_embedding:
                logger.warning("Semantic search failed: Could not get query embedding")
                return []
            
            # Get all article embeddings for this symbol using SCAN (production-safe)
            pattern = f"embedding:{symbol}:*"
            keys = []
            cursor = 0
            
            while True:
                cursor, partial_keys = self.cache.client.scan(
                    cursor=cursor,
                    match=pattern,
                    count=100
                )
                keys.extend(partial_keys)
                if cursor == 0:
                    break
            
            if not keys:
                logger.warning(
                    f"Semantic search found 0 stored embeddings for {symbol} (pattern: {pattern}). "
                    f"This means no articles have been stored in RAG for this symbol yet, "
                    f"or they may have expired (TTL: 7 days). Try loading data for this symbol again."
                )
                return []
            
            # Calculate similarities
            similarities = []
            for key in keys:
                article_id = key.split(":")[-1]
                embedding_data = self.cache.client.get(key)
                if embedding_data:
                    try:
                        embedding = json.loads(embedding_data)
                        similarity = self._cosine_similarity(query_embedding, embedding)
                        similarities.append((similarity, article_id))
                    except Exception as e:
                        logger.warning(f"Error processing embedding: {e}")
                        continue
            
            # Sort by similarity
            similarities.sort(reverse=True, key=lambda x: x[0])
            
            # Get top_k results
            results = []
            for similarity, article_id in similarities[:top_k]:
                metadata_key = f"article:{symbol}:{article_id}"
                metadata_data = self.cache.client.get(metadata_key)
                if metadata_data:
                    try:
                        metadata = json.loads(metadata_data)
                        metadata['similarity'] = similarity
                        results.append(metadata)
                    except Exception as e:
                        logger.warning(f"Error loading metadata: {e}")
                        continue
            
            return results
            
        except Exception as e:
            logger.error(f"Error in semantic search: {e}")
            return []
    
    def _cosine_similarity(self, vec1: List[float], vec2: List[float]) -> float:
        """
        Calculate cosine similarity between two vectors.
        
        Args:
            vec1: First vector
            vec2: Second vector
            
        Returns:
            Cosine similarity score (0.0 to 1.0)
        """
        try:
            # Use numpy if available (faster)
            vec1_array = np.array(vec1)
            vec2_array = np.array(vec2)
            dot_product = np.dot(vec1_array, vec2_array)
            norm1 = np.linalg.norm(vec1_array)
            norm2 = np.linalg.norm(vec2_array)
            
            if norm1 == 0 or norm2 == 0:
                return 0.0
            
            return float(dot_product / (norm1 * norm2))
        except Exception:
            # Fallback without numpy
            try:
                dot_product = sum(a * b for a, b in zip(vec1, vec2))
                norm1 = sum(a * a for a in vec1) ** 0.5
                norm2 = sum(b * b for b in vec2) ** 0.5
                
                if norm1 == 0 or norm2 == 0:
                    return 0.0
                
                return dot_product / (norm1 * norm2)
            except Exception:
                logger.warning("Error calculating cosine similarity")
                return 0.0
    
    def _apply_temporal_decay(self, results: List[Dict]) -> List[Dict]:
        """
        Apply temporal decay to boost recent articles.
        
        Recent articles are more relevant for financial sentiment analysis,
        so we boost their scores based on recency.
        
        Args:
            results: List of article metadata dictionaries
            
        Returns:
            Re-ranked results with temporal boost applied
        """
        from datetime import datetime
        
        now = datetime.now()
        
        for result in results:
            # Get article timestamp
            timestamp_str = result.get('timestamp', '')
            if not timestamp_str:
                continue
            
            try:
                # Parse timestamp (handle different formats)
                if isinstance(timestamp_str, str):
                    # Try ISO format first
                    try:
                        article_time = datetime.fromisoformat(timestamp_str.replace('Z', '+00:00'))
                    except ValueError:
                        # Try other formats
                        try:
                            from dateutil import parser
                            article_time = parser.parse(timestamp_str)
                        except Exception:
                            continue
                else:
                    continue
                
                # Calculate age in days
                age_days = (now - article_time.replace(tzinfo=None)).days
                
                # Apply decay: 1.0 for today, 0.5 for 7 days ago, 0.1 for 30+ days
                # Formula: decay = 1.0 / (1 + age_days / decay_days)
                decay_days = self.settings.app.rag_temporal_decay_days
                decay = max(0.1, 1.0 / (1 + age_days / decay_days))
                
                # Boost score by 20% for recency (weighted by decay)
                current_score = result.get('rrf_score', result.get('similarity', 0))
                boosted_score = current_score * (1 + decay * 0.2)
                
                # Update score (use rrf_score if available, otherwise similarity)
                if 'rrf_score' in result:
                    result['rrf_score'] = boosted_score
                else:
                    result['similarity'] = boosted_score
                
                result['temporal_boost'] = decay
                
            except Exception as e:
                continue
        
        # Re-sort by boosted score
        results.sort(
            reverse=True,
            key=lambda x: x.get('rrf_score', x.get('similarity', 0))
        )
        
        return results

