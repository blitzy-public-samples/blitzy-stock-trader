"""
Cache management API routes.
"""

from fastapi import APIRouter, HTTPException
from typing import Optional
from pydantic import BaseModel, Field

from ..models.response import ErrorResponse
from ..dependencies import get_all_services
from ...utils.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/cache", tags=["cache"])


class CacheStatsResponse(BaseModel):
    """Response model for cache statistics."""
    cache_hits: int = Field(..., description="Number of cache hits")
    cache_misses: int = Field(..., description="Number of cache misses")
    cache_sets: int = Field(..., description="Number of cache sets")


class ClearCacheRequest(BaseModel):
    """Request model for clearing cache."""
    confirm: bool = Field(True, description="Confirmation flag for safety")


class CacheResponse(BaseModel):
    """Response model for cache operations."""
    success: bool = Field(..., description="Operation success status")
    message: str = Field(..., description="Operation message")
    keys_deleted: Optional[int] = Field(None, description="Number of keys deleted (for clear operation)")


@router.get(
    "/stats",
    response_model=CacheStatsResponse,
    responses={
        200: {"description": "Successful response"},
        503: {"model": ErrorResponse, "description": "Service unavailable"}
    },
    summary="Get cache statistics",
    description="""
    Get cache statistics (hits, misses, sets).
    
    Returns the current cache statistics counters.
    """
)
async def get_cache_stats():
    """
    Get cache statistics.
    
    Returns:
        Cache statistics (hits, misses, sets)
    
    Raises:
        HTTPException: If cache is unavailable
    """
    try:
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if not redis_cache or not redis_cache.client:
            raise HTTPException(
                status_code=503,
                detail="Redis cache not available"
            )
        
        stats = redis_cache.get_cache_stats()
        logger.info(f"Cache statistics retrieved: {stats}")
        
        return CacheStatsResponse(**stats)
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting cache stats: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get cache statistics: {str(e)}"
        )


@router.post(
    "/stats/reset",
    response_model=CacheResponse,
    responses={
        200: {"description": "Successful response"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Reset cache statistics",
    description="""
    Reset cache statistics (hits, misses, sets counters).
    
    This operation resets the cache statistics counters but does not clear cached data.
    """
)
async def reset_cache_stats():
    """
    Reset cache statistics.
    
    Returns:
        Success response
    
    Raises:
        HTTPException: If reset fails
    """
    try:
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if not redis_cache or not redis_cache.client:
            raise HTTPException(
                status_code=503,
                detail="Redis cache not available"
            )
        
        redis_cache.reset_cache_stats()
        logger.info("Cache statistics reset")
        
        return CacheResponse(
            success=True,
            message="Cache statistics reset"
        )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error resetting cache stats: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to reset cache statistics: {str(e)}"
        )


@router.post(
    "/clear",
    response_model=CacheResponse,
    responses={
        200: {"description": "Successful response"},
        400: {"model": ErrorResponse, "description": "Bad request (confirmation required)"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Clear all cache data",
    description="""
    Clear all cached data from Redis.
    
    **Warning:** This operation deletes ALL cached data including:
    - Stock price data
    - News articles
    - Sentiment analysis results
    - Article embeddings
    
    Requires confirmation in request body.
    """
)
async def clear_cache(request: ClearCacheRequest):
    """
    Clear all cache data.
    
    Args:
        request: Clear cache request with confirmation
    
    Returns:
        Success response with number of keys deleted
    
    Raises:
        HTTPException: If clear fails or confirmation missing
    """
    try:
        if not request.confirm:
            raise HTTPException(
                status_code=400,
                detail="Confirmation required to clear cache. Set 'confirm' to true."
            )
        
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if not redis_cache or not redis_cache.client:
            raise HTTPException(
                status_code=503,
                detail="Redis cache not available"
            )
        
        # Get count before clearing (approximate)
        try:
            # Try to get key count (may not be available in all Redis configurations)
            info = redis_cache.client.info('keyspace')
            keys_deleted = sum(int(db_info.get('keys', 0)) for db_info in info.values()) if info else None
        except Exception:
            keys_deleted = None
        
        success = redis_cache.clear_all_cache()
        
        if success:
            logger.info("All cache data cleared")
            return CacheResponse(
                success=True,
                message="Cache cleared successfully",
                keys_deleted=keys_deleted
            )
        else:
            raise HTTPException(
                status_code=500,
                detail="Failed to clear cache. Check logs for details."
            )
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error clearing cache: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to clear cache: {str(e)}"
        )

