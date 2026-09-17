"""
Vector database integration interface for optimized vector search.

This module provides an abstraction layer for vector database operations,
supporting Azure AI Search for 10-100x faster vector search at scale.

The VectorDatabase abstract base class allows for extensibility if other
vector databases need to be integrated in the future.
"""

from typing import List, Dict, Optional, Any
from abc import ABC, abstractmethod
from datetime import datetime

from ..config.settings import Settings, get_settings
from ..utils.logger import get_logger

logger = get_logger(__name__)


class VectorDatabase(ABC):
    """
    Abstract base class for vector database operations.
    
    This interface allows switching between different vector database
    implementations (Redis+RediSearch, Pinecone, Weaviate, Qdrant) without
    changing application code.
    """
    
    @abstractmethod
    def store_vector(
        self,
        vector_id: str,
        vector: List[float],
        metadata: Dict[str, Any]
    ) -> bool:
        """
        Store a vector with metadata.
        
        Args:
            vector_id: Unique identifier for the vector
            vector: Embedding vector
            metadata: Associated metadata
            
        Returns:
            True if stored successfully
        """
        pass
    
    @abstractmethod
    def search_vectors(
        self,
        query_vector: List[float],
        top_k: int = 10,
        filter: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Search for similar vectors.
        
        Args:
            query_vector: Query embedding vector
            top_k: Number of results to return
            filter: Optional OData filter string (for Azure AI Search) or Dict (for others)
            
        Returns:
            List of results with similarity scores and metadata
        """
        pass
    
    @abstractmethod
    def is_available(self) -> bool:
        """Check if the vector database is available and ready to use."""
        pass
    
    @abstractmethod
    def delete_vector(self, vector_id: str) -> bool:
        """Delete a vector by ID."""
        pass


class AzureAISearchVectorDB(VectorDatabase):
    """
    Azure AI Search vector database implementation.
    
    This provides native vector search with indexing, filtering, and hybrid search
    capabilities. 10-100x faster than Redis SCAN-based search for large datasets.
    
    Features:
    - Native vector indexing (HNSW algorithm)
    - OData filter support (date ranges, sources, etc.)
    - Hybrid search (vector + keyword)
    - Built-in relevance scoring
    """
    
    def __init__(
        self,
        settings: Settings,
        redis_cache: Optional[Any] = None
    ):
        """
        Initialize Azure AI Search vector database.
        
        Args:
            settings: Application settings instance
            redis_cache: Optional Redis cache (for query embedding caching)
        """
        self.settings = settings
        self.redis_cache = redis_cache
        self._client = None
        self._index_client = None
        self._index_created = False
        
        if not settings.is_azure_ai_search_available():
            logger.warning("Azure AI Search not configured - vector DB disabled")
            return
        
        try:
            from azure.core.credentials import AzureKeyCredential
            from azure.search.documents import SearchClient
            from azure.search.documents.indexes import SearchIndexClient
            
            search_config = settings.azure_ai_search
            credential = AzureKeyCredential(search_config.api_key)
            
            self._client = SearchClient(
                endpoint=search_config.endpoint,
                index_name=search_config.index_name,
                credential=credential
            )
            
            self._index_client = SearchIndexClient(
                endpoint=search_config.endpoint,
                credential=credential
            )
            
            # Ensure index exists
            self._ensure_index_exists()
            
        except ImportError:
            logger.error(
                "azure-search-documents package not installed. "
                "Install it with: pip install azure-search-documents>=11.4.0"
            )
        except Exception as e:
            logger.error(f"Failed to initialize Azure AI Search: {e}")
    
    def _ensure_index_exists(self) -> bool:
        """Create index if it doesn't exist."""
        if not self._index_client:
            return False
        
        try:
            from azure.search.documents.indexes.models import (
                SearchIndex,
                SimpleField,
                SearchableField,
                SearchField,
                VectorSearch,
                HnswAlgorithmConfiguration,
                VectorSearchAlgorithmKind,
                VectorSearchAlgorithmMetric,
                VectorSearchProfile,
                SearchFieldDataType
            )
            
            search_config = self.settings.azure_ai_search
            index_name = search_config.index_name
            
            # Get vector search parameters from settings
            vector_m = self.settings.app.vector_search_m
            vector_ef_construction = self.settings.app.vector_search_ef_construction
            vector_ef_search = self.settings.app.vector_search_ef_search
            
            # Check if index exists
            try:
                existing_index = self._index_client.get_index(index_name)
                logger.info(f"Azure AI Search: Index '{index_name}' already exists")
                self._index_created = True
                return True
            except Exception:
                # Index doesn't exist, create it
                logger.info(f"Azure AI Search: Index '{index_name}' not found, creating new index...")
                pass
            
            # Define index schema
            fields = [
                SimpleField(name="id", type=SearchFieldDataType.String, key=True),
                SearchableField(name="content", type=SearchFieldDataType.String, retrievable=True),
                SearchField(
                    name="contentVector",
                    type=SearchFieldDataType.Collection(SearchFieldDataType.Single),
                    vector_search_dimensions=search_config.vector_dimension,
                    vector_search_profile_name="default-vector-profile"
                ),
                SimpleField(name="symbol", type=SearchFieldDataType.String, filterable=True, facetable=True, retrievable=True),
                SearchableField(name="title", type=SearchFieldDataType.String, retrievable=True),
                SearchableField(name="summary", type=SearchFieldDataType.String, retrievable=True),
                SimpleField(name="source", type=SearchFieldDataType.String, filterable=True, facetable=True, retrievable=True),
                SimpleField(name="url", type=SearchFieldDataType.String, retrievable=True),
                SimpleField(name="timestamp", type=SearchFieldDataType.DateTimeOffset, filterable=True, sortable=True, retrievable=True),
                SimpleField(name="article_id", type=SearchFieldDataType.String, retrievable=True)
            ]
            
            # Vector search configuration
            vector_search = VectorSearch(
                algorithms=[
                    HnswAlgorithmConfiguration(
                        name="default-algorithm",
                        kind=VectorSearchAlgorithmKind.HNSW,
                        parameters={
                            "m": vector_m,
                            "efConstruction": vector_ef_construction,
                            "efSearch": vector_ef_search,
                            "metric": VectorSearchAlgorithmMetric.COSINE
                        }
                    )
                ],
                profiles=[
                    VectorSearchProfile(
                        name="default-vector-profile",
                        algorithm_configuration_name="default-algorithm"
                    )
                ]
            )
            
            # Create index
            index = SearchIndex(
                name=index_name,
                fields=fields,
                vector_search=vector_search
            )
            
            self._index_client.create_index(index)
            logger.info(f"Azure AI Search: Successfully created index '{index_name}' with {len(fields)} fields (vector dimension: {search_config.vector_dimension})")
            self._index_created = True
            return True
            
        except Exception as e:
            logger.error(f"Failed to create Azure AI Search index: {e}")
            self._index_created = False
            return False
    
    def store_vector(
        self,
        vector_id: str,
        vector: List[float],
        metadata: Dict[str, Any]
    ) -> bool:
        """Store a vector with metadata in Azure AI Search."""
        if not self._client or not self._index_created:
            return False
        
        try:
            # Prepare document
            document = {
                "id": vector_id,
                "content": f"{metadata.get('title', '')} {metadata.get('summary', '')}",
                "contentVector": vector,
                "symbol": metadata.get("symbol", ""),
                "title": metadata.get("title", ""),
                "summary": metadata.get("summary", ""),
                "source": metadata.get("source", ""),
                "url": metadata.get("url", ""),
                "article_id": metadata.get("article_id", vector_id)
            }
            
            # Parse timestamp if provided
            timestamp = metadata.get("timestamp")
            if timestamp:
                if isinstance(timestamp, str):
                    try:
                        from dateutil import parser
                        timestamp = parser.parse(timestamp)
                    except Exception:
                        timestamp = None
                if timestamp:
                    document["timestamp"] = timestamp
            
            # Upload document
            self._client.upload_documents(documents=[document])
            return True
            
        except Exception as e:
            logger.error(f"Error storing vector in Azure AI Search: {e}")
            return False
    
    def batch_store_vectors(
        self,
        vectors: List[Dict[str, Any]]
    ) -> int:
        """
        Store multiple vectors in batch.
        
        Args:
            vectors: List of dicts with keys: vector_id, vector, metadata
            
        Returns:
            Number of successfully stored vectors
        """
        if not self._client or not self._index_created:
            return 0
        
        if not vectors:
            return 0
        
        try:
            documents = []
            for item in vectors:
                vector_id = item.get("vector_id", "")
                vector = item.get("vector", [])
                metadata = item.get("metadata", {})
                
                # Safely get metadata values, ensuring no None values
                title = metadata.get("title") or ""
                summary = metadata.get("summary") or ""
                symbol_val = metadata.get("symbol") or ""
                source = metadata.get("source") or ""
                url = metadata.get("url") or ""
                article_id_val = metadata.get("article_id") or vector_id
                
                document = {
                    "id": vector_id,
                    "content": f"{title} {summary}".strip() or "No content",
                    "contentVector": vector,
                    "symbol": symbol_val,
                    "title": title,
                    "summary": summary,
                    "source": source,
                    "url": url,
                    "article_id": article_id_val
                }
                
                # Handle timestamp safely
                timestamp = metadata.get("timestamp")
                if timestamp:
                    try:
                        if isinstance(timestamp, str):
                            from dateutil import parser
                            timestamp = parser.parse(timestamp)
                        # Ensure it's a datetime object
                        if hasattr(timestamp, 'isoformat'):
                            document["timestamp"] = timestamp
                        else:
                            logger.warning(f"Invalid timestamp format for document {vector_id[:8]}: {type(timestamp)}")
                    except Exception as e:
                        logger.warning(f"Error parsing timestamp for document {vector_id[:8]}: {e}")
                
                documents.append(document)
                
                # Log document being prepared
                logger.debug(f"Azure AI Search: Prepared document [{vector_id[:8]}] - '{title[:40]}...' (symbol: {symbol_val}, source: {source})")
            
            # Upload in batch
            search_config = self.settings.azure_ai_search
            index_name = search_config.index_name
            logger.info(f"Azure AI Search: Uploading {len(documents)} documents to index '{index_name}'")
            result = self._client.upload_documents(documents=documents)
            
            # Count successful uploads
            success_count = sum(1 for r in result if r.succeeded)
            failed_count = len(documents) - success_count
            
            if success_count > 0:
                search_config = self.settings.azure_ai_search
                index_name = search_config.index_name
                logger.info(f"Azure AI Search: ✅ Successfully stored {success_count}/{len(documents)} vectors in index '{index_name}'")
            if failed_count > 0:
                logger.warning(f"Azure AI Search: ⚠️ Failed to store {failed_count}/{len(documents)} vectors")
                # Log first few failures for debugging
                for i, r in enumerate(result):
                    if not r.succeeded and i < 5:  # Log first 5 failures
                        doc_id = r.key if hasattr(r, 'key') else f"document_{i}"
                        error_msg = r.error_message if hasattr(r, 'error_message') else str(r)
                        logger.warning(f"Azure AI Search: Upload failed for document {doc_id} - {error_msg}")
            
            return success_count
            
        except Exception as e:
            logger.error(f"Error batch storing vectors in Azure AI Search: {e}")
            return 0
    
    def search_vectors(
        self,
        query_vector: List[float],
        top_k: int = 10,
        filter: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Search for similar vectors using Azure AI Search.
        
        Args:
            query_vector: Query embedding vector
            top_k: Number of results to return
            filter: Optional OData filter string (e.g., "symbol eq 'AAPL' and timestamp ge 2024-12-10T00:00:00Z")
            
        Returns:
            List of results with similarity scores and metadata
        """
        if not self._client or not self._index_created:
            return []
        
        try:
            from azure.search.documents.models import VectorizedQuery
            
            logger.info(f"Azure AI Search: Performing vector search (top_k={top_k}, filter={'applied' if filter else 'none'})")
            
            # Create vectorized query
            vector_query = VectorizedQuery(
                vector=query_vector,
                k_nearest_neighbors=top_k,
                fields="contentVector"
            )
            
            # Build search options
            search_options = {
                "vector_queries": [vector_query],
                "top": top_k,
                "select": ["id", "symbol", "title", "summary", "source", "url", "timestamp", "article_id"]
            }
            
            if filter:
                search_options["filter"] = filter
                logger.info(f"Azure AI Search: Filter applied - {filter[:100]}...")
            
            # Perform search
            results = self._client.search(
                search_text="",  # Empty for pure vector search, or use for hybrid search
                **search_options
            )
            
            # Format results safely (handle None values)
            formatted_results = []
            for result in results:
                try:
                    # Safely extract all fields
                    article_id = result.get("article_id") or result.get("id") or ""
                    symbol = result.get("symbol") or ""
                    title = result.get("title") or ""
                    summary = result.get("summary") or ""
                    source = result.get("source") or ""
                    url = result.get("url") or ""
                    timestamp = result.get("timestamp") or ""
                    
                    # Safely get score
                    search_score = result.get("@search.score")
                    similarity = float(search_score) if search_score is not None else 0.0
                    
                    formatted_results.append({
                        "article_id": article_id,
                        "symbol": symbol,
                        "title": title,
                        "summary": summary,
                        "source": source,
                        "url": url,
                        "timestamp": timestamp,
                        "similarity": similarity
                    })
                except Exception as e:
                    logger.error(f"Error formatting vector search result: {e} - result: {result}")
                    continue
            
            logger.info(f"Azure AI Search: Vector search returned {len(formatted_results)} results")
            if formatted_results:
                top_score = formatted_results[0].get('similarity') or 0.0
                top_title = formatted_results[0].get('title') or 'N/A'
                top_title_display = top_title[:50] if top_title and len(top_title) > 50 else top_title
                logger.info(f"Azure AI Search: Top result score: {top_score:.3f} - '{top_title_display}...'")
            
            return formatted_results
            
        except Exception as e:
            logger.error(f"Error searching vectors in Azure AI Search: {e}")
            return []
    
    def hybrid_search(
        self,
        query_text: str,
        query_vector: List[float],
        top_k: int = 10,
        filter: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Perform hybrid search (vector + keyword) in Azure AI Search.
        
        Args:
            query_text: Keyword search query
            query_vector: Query embedding vector
            top_k: Number of results to return
            filter: Optional OData filter string
            
        Returns:
            List of results with similarity scores and metadata
        """
        if not self._client or not self._index_created:
            return []
        
        try:
            from azure.search.documents.models import VectorizedQuery
            
            query_preview = (query_text[:50] + '...') if query_text and len(query_text) > 50 else (query_text or '')
            filter_status = 'applied' if filter else 'none'
            logger.info(f"Azure AI Search: Performing hybrid search (vector + keyword, top_k={top_k}, query='{query_preview}', filter={filter_status})")
            
            # Create vectorized query
            vector_query = VectorizedQuery(
                vector=query_vector,
                k_nearest_neighbors=top_k * 2,  # Get more candidates for RRF
                fields="contentVector"
            )
            
            # Build search options
            # Azure Search SDK expects vector_queries as a list and search_text as separate parameter
            search_options = {
                "top": top_k,
                "select": ["id", "symbol", "title", "summary", "source", "url", "timestamp", "article_id"]
            }
            
            if filter:
                search_options["filter"] = filter
                logger.info(f"Azure AI Search: Filter applied - {filter[:100]}...")
            
            # Perform hybrid search (Azure AI Search handles RRF internally)
            # Pass search_text and vector_queries as separate keyword arguments
            results = self._client.search(
                search_text=query_text or "",
                vector_queries=[vector_query],
                **search_options
            )
            
            # Format results safely (handle None values)
            formatted_results = []
            for result in results:
                try:
                    # Safely extract all fields, ensuring no None values
                    article_id = result.get("article_id") or result.get("id") or ""
                    symbol = result.get("symbol") or ""
                    title = result.get("title") or ""
                    summary = result.get("summary") or ""
                    source = result.get("source") or ""
                    url = result.get("url") or ""
                    timestamp = result.get("timestamp") or ""
                    
                    # Safely get scores
                    search_score = result.get("@search.score")
                    similarity = float(search_score) if search_score is not None else 0.0
                    
                    reranker_score = result.get("@search.reranker_score")
                    rrf_score = float(reranker_score) if reranker_score is not None else similarity
                    
                    formatted_results.append({
                        "article_id": article_id,
                        "symbol": symbol,
                        "title": title,
                        "summary": summary,
                        "source": source,
                        "url": url,
                        "timestamp": timestamp,
                        "similarity": similarity,
                        "rrf_score": rrf_score
                    })
                except Exception as e:
                    logger.error(f"Error formatting search result: {e} - result: {result}")
                    continue
            
            logger.info(f"Azure AI Search: Hybrid search returned {len(formatted_results)} results")
            if formatted_results:
                top_score = formatted_results[0].get('rrf_score') or formatted_results[0].get('similarity') or 0.0
                top_title = formatted_results[0].get('title') or 'N/A'
                top_title_display = top_title[:50] if top_title and len(top_title) > 50 else top_title
                logger.info(f"Azure AI Search: Top result RRF score: {top_score:.3f} - '{top_title_display}...'")
                
                # Log details of all results for debugging
                logger.info(f"Azure AI Search: Result details:")
                for i, result in enumerate(formatted_results[:3], 1):  # Log top 3
                    score = result.get('rrf_score') or result.get('similarity') or 0.0
                    title = result.get('title') or 'No title'
                    symbol = result.get('symbol') or 'Unknown'
                    source = result.get('source') or 'Unknown'
                    logger.info(f"  [{i}] Score: {score:.3f} | Symbol: {symbol} | Source: {source} | Title: {title[:60]}")
            
            return formatted_results
            
        except Exception as e:
            logger.error(f"Error performing hybrid search in Azure AI Search: {e}")
            return []
    
    def document_exists(self, vector_id: str) -> bool:
        """
        Check if a document exists in Azure AI Search by ID.
        
        Args:
            vector_id: Document ID to check
            
        Returns:
            True if document exists, False otherwise
        """
        if not self._client or not self._index_created:
            return False
        
        try:
            # Try to get the document by ID
            result = self._client.get_document(key=vector_id)
            return result is not None
        except Exception:
            # Document doesn't exist if get_document raises an exception
            return False
    
    def batch_check_documents_exist(self, vector_ids: List[str]) -> Dict[str, bool]:
        """
        Check if multiple documents exist in Azure AI Search.
        
        Args:
            vector_ids: List of document IDs to check
            
        Returns:
            Dictionary mapping vector_id to existence status
        """
        if not self._client or not self._index_created:
            return {vid: False for vid in vector_ids}
        
        results = {}
        # Azure AI Search doesn't have batch get, so we check individually
        # But we can optimize by checking in parallel if needed
        for vector_id in vector_ids:
            results[vector_id] = self.document_exists(vector_id)
        
        return results
    
    def delete_vector(self, vector_id: str) -> bool:
        """Delete a vector by ID from Azure AI Search."""
        if not self._client or not self._index_created:
            return False
        
        try:
            self._client.delete_documents(documents=[{"id": vector_id}])
            return True
        except Exception as e:
            logger.error(f"Error deleting vector from Azure AI Search: {e}")
            return False
    
    def is_available(self) -> bool:
        """Check if Azure AI Search is available and ready to use."""
        return (
            self._client is not None
            and self._index_client is not None
            and self._index_created
        )

