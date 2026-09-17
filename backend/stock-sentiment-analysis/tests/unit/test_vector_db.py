"""
Unit tests for AzureAISearchVectorDB service.
"""

import pytest
from unittest.mock import Mock, MagicMock, patch
from datetime import datetime, timedelta

from src.stock_sentiment.services.vector_db import AzureAISearchVectorDB


@pytest.mark.unit
class TestAzureAISearchVectorDB:
    """Test suite for AzureAISearchVectorDB class."""
    
    def test_initialization_with_config(self, test_settings):
        """Test AzureAISearchVectorDB initialization with configuration."""
        # Mock Azure AI Search to be available
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        with patch('azure.search.documents.SearchClient') as mock_client:
            with patch('azure.search.documents.indexes.SearchIndexClient') as mock_index_client:
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                
                # Should initialize clients
                assert vector_db._client is not None or vector_db._index_client is not None
    
    def test_initialization_without_config(self, test_settings):
        """Test initialization when Azure AI Search is not configured."""
        test_settings.is_azure_ai_search_available = Mock(return_value=False)
        
        vector_db = AzureAISearchVectorDB(settings=test_settings)
        
        # Should handle gracefully
        assert vector_db is not None
    
    def test_is_available(self, test_settings):
        """Test is_available method."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        with patch('azure.search.documents.SearchClient'):
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = MagicMock()
                
                result = vector_db.is_available()
                assert isinstance(result, bool)
    
    def test_batch_store_vectors(self, test_settings):
        """Test batch storing vectors."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        vectors = [
            {
                "vector_id": "vec1",
                "vector": [0.1] * 1536,
                "metadata": {
                    "article_id": "art1",
                    "symbol": "AAPL",
                    "title": "Test Article",
                    "summary": "Test summary",
                    "source": "yfinance"
                }
            },
            {
                "vector_id": "vec2",
                "vector": [0.2] * 1536,
                "metadata": {
                    "article_id": "art2",
                    "symbol": "MSFT",
                    "title": "Another Article",
                    "summary": "Another summary",
                    "source": "alpha_vantage"
                }
            }
        ]
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            # Mock upload_documents to return list with .succeeded attribute
            mock_result1 = MagicMock()
            mock_result1.succeeded = True
            mock_result2 = MagicMock()
            mock_result2.succeeded = True
            mock_client.upload_documents.return_value = [mock_result1, mock_result2]
    
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = mock_client
                vector_db._index_created = True  # Set index as created
    
                count = vector_db.batch_store_vectors(vectors)
                assert count == 2
    
    def test_batch_check_documents_exist(self, test_settings):
        """Test checking if documents exist."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        vector_ids = ["vec1", "vec2", "vec3"]
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            
            # Mock get_document for each vector_id (batch_check uses document_exists which calls get_document)
            def mock_get_document(key):
                if key == "vec1" or key == "vec2":
                    return MagicMock()  # Document exists
                else:
                    raise Exception("Not found")  # Document doesn't exist
            
            mock_client.get_document.side_effect = mock_get_document
    
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = mock_client
                vector_db._index_created = True
    
                existing = vector_db.batch_check_documents_exist(vector_ids)
                assert "vec1" in existing
                assert existing["vec1"] is True
                assert "vec2" in existing
                assert existing["vec2"] is True
                assert "vec3" in existing
                assert existing["vec3"] is False
    
    def test_search_vectors(self, test_settings):
        """Test vector search."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        query_vector = [0.1] * 1536
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            
            # Mock search results
            mock_result = MagicMock()
            mock_result.get = Mock(side_effect=lambda key, default=None: {
                "id": "vec1",
                "article_id": "vec1",
                "title": "Test Article",
                "summary": "Test summary",
                "source": "yfinance",
                "@search.score": 0.85
            }.get(key, default))
    
            mock_client.search.return_value = [mock_result]
    
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = mock_client
                vector_db._index_created = True
    
                results = vector_db.search_vectors(query_vector, top_k=5)
                assert len(results) > 0
                assert results[0]["article_id"] == "vec1"
    
    def test_hybrid_search(self, test_settings):
        """Test hybrid search (vector + keyword)."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        query_vector = [0.1] * 1536
        query_text = "Apple earnings"
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            
            # Mock hybrid search results
            mock_result = MagicMock()
            # Make get() method work properly
            def mock_get(key, default=None):
                data = {
                    "id": "vec1",
                    "article_id": "vec1",
                    "title": "Apple Earnings Report",
                    "summary": "Apple reported strong earnings",
                    "source": "yfinance",
                    "@search.score": 0.90,
                    "@search.reranker_score": 0.95
                }
                return data.get(key, default)
            
            mock_result.get = mock_get
            # Also make it directly accessible as attributes
            mock_result.id = "vec1"
            mock_result.article_id = "vec1"
            mock_result.title = "Apple Earnings Report"
            mock_result.summary = "Apple reported strong earnings"
            mock_result.source = "yfinance"
            setattr(mock_result, "@search.score", 0.90)
            setattr(mock_result, "@search.reranker_score", 0.95)
    
            # Mock search to return iterable
            mock_client.search.return_value = iter([mock_result])
    
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = mock_client
                vector_db._index_created = True
    
                results = vector_db.hybrid_search(query_text, query_vector, top_k=5)
                assert len(results) > 0
                assert results[0]["article_id"] == "vec1"
    
    def test_filter_building(self, test_settings):
        """Test OData filter building."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        with patch('azure.search.documents.SearchClient'):
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                
                # Filter building is done in RAG service, not vector DB
                # Vector DB just accepts filter strings and passes them to Azure AI Search
                # Test that search methods accept filter parameter
                filter_str = "symbol eq 'AAPL' and source eq 'yfinance'"
                
                # Test that search_vectors accepts filter
                with patch.object(vector_db, '_client') as mock_client:
                    mock_client.search.return_value = []
                    vector_db._index_created = True
                    results = vector_db.search_vectors([0.1] * 1536, top_k=5, filter=filter_str)
                    assert isinstance(results, list)
                    # Verify filter was used (mock_client.search should have been called with filter)
                    mock_client.search.assert_called_once()
    
    def test_service_unavailable(self, test_settings):
        """Test handling when service is unavailable."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client_class.side_effect = Exception("Service unavailable")
            
            vector_db = AzureAISearchVectorDB(settings=test_settings)
            assert vector_db._client is None
    
    def test_invalid_vectors(self, test_settings):
        """Test handling of invalid vectors."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        invalid_vectors = [
            {
                "vector_id": "vec1",
                "vector": [],  # Empty vector
                "metadata": {}
            }
        ]
        
        with patch('azure.search.documents.SearchClient'):
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = None  # Simulate unavailable
                
                count = vector_db.batch_store_vectors(invalid_vectors)
                assert count == 0
    
    def test_connection_errors(self, test_settings):
        """Test handling of connection errors."""
        test_settings.is_azure_ai_search_available = Mock(return_value=True)
        
        with patch('azure.search.documents.SearchClient') as mock_client_class:
            mock_client = MagicMock()
            mock_client_class.return_value = mock_client
            mock_client.upload_documents.side_effect = Exception("Connection error")
            
            with patch('azure.search.documents.indexes.SearchIndexClient'):
                vector_db = AzureAISearchVectorDB(settings=test_settings)
                vector_db._client = mock_client
                
                vectors = [{"vector_id": "vec1", "vector": [0.1]*1536, "metadata": {}}]
                count = vector_db.batch_store_vectors(vectors)
                # Should handle error gracefully
                assert count == 0

