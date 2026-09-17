"""
System status API routes.
"""

from fastapi import APIRouter, HTTPException
from typing import Dict, Any, Optional

from ..models.response import ErrorResponse
from ..dependencies import get_all_services
from ...utils.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/system", tags=["system"])


@router.get(
    "/status",
    responses={
        200: {"description": "Successful response"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Get system health and status",
    description="""
    Get system health and status information for Redis, RAG, and Azure services.
    
    Returns detailed status for:
    - Redis cache connection and availability
    - RAG service status and configuration
    - Azure OpenAI service status
    """
)
async def get_system_status():
    """
    Get system health and status.
    
    Returns:
        Dictionary with system status information
    
    Raises:
        HTTPException: If status check fails
    """
    try:
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        status_info: Dict[str, Any] = {
            "redis": {
                "available": redis_cache is not None,
                "connected": False,
                "host": None,
                "port": None
            },
            "rag": {
                "available": rag_service is not None,
                "embeddings_enabled": False,
                "embedding_deployment": None,
                "vector_db_available": False
            },
            "azure_openai": {
                "available": analyzer is not None,
                "deployment_name": None
            }
        }
        
        # Check Redis status
        if redis_cache and redis_cache.client:
            try:
                redis_cache.client.ping()
                status_info["redis"]["connected"] = True
                status_info["redis"]["host"] = settings.redis.host
                status_info["redis"]["port"] = settings.redis.port
            except Exception as e:
                logger.warning(f"Redis ping failed: {e}")
                status_info["redis"]["connected"] = False
        
        # Check RAG status
        if rag_service:
            status_info["rag"]["embeddings_enabled"] = rag_service.embeddings_enabled
            status_info["rag"]["embedding_deployment"] = rag_service.embedding_deployment
            if rag_service.vector_db:
                status_info["rag"]["vector_db_available"] = rag_service.vector_db.is_available()
        
        # Check Azure OpenAI status
        if analyzer:
            status_info["azure_openai"]["deployment_name"] = analyzer.deployment_name
        
        logger.info("System status checked successfully")
        
        return status_info
        
    except Exception as e:
        logger.error(f"Error checking system status: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to check system status: {str(e)}"
        )

