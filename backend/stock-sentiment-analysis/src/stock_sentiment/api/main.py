"""
FastAPI main application for Stock Sentiment Analysis API.
"""

from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from contextlib import asynccontextmanager
import time
from typing import Dict

from .routes.sentiment import router as sentiment_router
from .routes.price import router as price_router
from .routes.comparison import router as comparison_router
from .routes.system import router as system_router
from .routes.cache import router as cache_router
from .models.response import HealthResponse, ErrorResponse
from .dependencies import get_all_services
from ..utils.logger import get_logger, setup_logger
from ..config.settings import get_settings

# Initialize logger
setup_logger("stock_sentiment", level="INFO")
logger = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan context manager for startup and shutdown events."""
    # Startup
    logger.info("Starting Stock Sentiment Analysis API...")
    
    try:
        # Initialize services
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        logger.info("API startup complete")
        logger.info(f"Redis cache: {'available' if redis_cache else 'not configured'}")
        logger.info(f"RAG service: {'available' if rag_service else 'not configured'}")
        logger.info(f"Sentiment analyzer: {'available' if analyzer else 'unavailable'}")
        
    except Exception as e:
        logger.error(f"Error during startup: {e}", exc_info=True)
    
    yield
    
    # Shutdown
    logger.info("Shutting down Stock Sentiment Analysis API...")


# Create FastAPI app
app = FastAPI(
    title="Stock Sentiment Analysis API",
    description="""
    REST API for AI-powered stock sentiment analysis.
    
    This API provides sentiment analysis for stock symbols by:
    - Collecting news from multiple sources (yfinance, Alpha Vantage, Finnhub, Reddit)
    - Using Azure OpenAI GPT-4 for sentiment analysis
    - Leveraging RAG (Retrieval Augmented Generation) for context-aware analysis
    - Aggregating sentiment scores across multiple articles
    
    ## Features
    
    - **Multi-source data collection**: Aggregates news from multiple sources
    - **AI-powered analysis**: Uses Azure OpenAI GPT-4 for accurate sentiment analysis
    - **RAG enhancement**: Context-aware analysis using Retrieval Augmented Generation
    - **Caching**: Redis caching for improved performance
    - **Hybrid search**: Semantic + keyword search for better context retrieval
    """,
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    lifespan=lifespan
)

# CORS configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, specify allowed origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Request logging middleware
@app.middleware("http")
async def log_requests(request: Request, call_next):
    """Log all API requests with timing."""
    start_time = time.time()
    
    # Skip logging for health checks and docs (too noisy)
    if request.url.path in ["/health", "/docs", "/redoc", "/openapi.json", "/favicon.ico"]:
        response = await call_next(request)
        return response
    
    # Log request
    logger.info(f"🌐 API Request: {request.method} {request.url.path}")
    if request.query_params:
        params_str = "&".join([f"{k}={v}" for k, v in request.query_params.items()])
        logger.info(f"   📋 Query params: {params_str}")
    
    # Process request
    response = await call_next(request)
    
    # Log response
    process_time = time.time() - start_time
    status_emoji = "✅" if 200 <= response.status_code < 300 else "⚠️" if 300 <= response.status_code < 400 else "❌"
    logger.info(
        f"{status_emoji} API Response: {request.method} {request.url.path} - "
        f"Status: {response.status_code} - Time: {process_time:.3f}s"
    )
    
    return response


# Error handling middleware
@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    """Handle HTTP exceptions."""
    logger.warning(f"HTTP Exception: {exc.status_code} - {exc.detail}")
    return JSONResponse(
        status_code=exc.status_code,
        content=ErrorResponse(
            error=exc.detail,
            detail=f"HTTP {exc.status_code} error"
        ).model_dump()
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Handle validation errors."""
    logger.warning(f"Validation error: {exc.errors()}")
    return JSONResponse(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        content=ErrorResponse(
            error="Validation error",
            detail=str(exc.errors())
        ).model_dump()
    )


@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    """Handle general exceptions."""
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=ErrorResponse(
            error="Internal server error",
            detail=str(exc) if str(exc) else "An unexpected error occurred"
        ).model_dump()
    )


# Include routers
app.include_router(sentiment_router)
app.include_router(price_router)
app.include_router(comparison_router)
app.include_router(system_router)
app.include_router(cache_router)


# Root endpoint
@app.get("/", tags=["info"])
async def root():
    """
    Root endpoint with API information.
    """
    return {
        "name": "Stock Sentiment Analysis API",
        "version": "2.0.0",
        "description": "REST API for AI-powered stock sentiment analysis",
        "docs": "/docs",
        "health": "/health",
        "endpoints": {
            "sentiment": "/sentiment/{symbol}",
            "health": "/health"
        }
    }


# Health check endpoint
@app.get(
    "/health",
    response_model=HealthResponse,
    tags=["health"],
    summary="Health check endpoint",
    description="Check the health status of the API and its dependencies"
)
async def health_check():
    """
    Health check endpoint.
    
    Checks the availability of:
    - Redis cache
    - RAG service
    - Azure OpenAI
    - Sentiment analyzer
    
    Returns:
        HealthResponse with status of all services
    """
    try:
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        services: Dict[str, str] = {}
        overall_status = "healthy"
        
        # Check Redis
        if redis_cache and redis_cache.client:
            try:
                redis_cache.client.ping()
                services["redis"] = "available"
            except Exception as e:
                services["redis"] = f"unavailable: {str(e)}"
                overall_status = "degraded"
        else:
            services["redis"] = "not_configured"
            overall_status = "degraded"
        
        # Check RAG service
        if rag_service:
            try:
                # Simple check - verify vector DB is available
                if hasattr(rag_service, 'vector_db') and rag_service.vector_db:
                    services["rag"] = "available"
                else:
                    services["rag"] = "not_configured"
                    overall_status = "degraded"
            except Exception as e:
                services["rag"] = f"unavailable: {str(e)}"
                overall_status = "degraded"
        else:
            services["rag"] = "not_configured"
            overall_status = "degraded"
        
        # Check Azure OpenAI
        if analyzer:
            try:
                # Check if client is initialized
                if hasattr(analyzer, 'client') and analyzer.client:
                    services["azure_openai"] = "available"
                else:
                    services["azure_openai"] = "not_configured"
                    overall_status = "degraded"
            except Exception as e:
                services["azure_openai"] = f"unavailable: {str(e)}"
                overall_status = "degraded"
        else:
            services["azure_openai"] = "unavailable"
            overall_status = "degraded"
        
        # Check sentiment analyzer
        if analyzer:
            services["sentiment_analyzer"] = "available"
        else:
            services["sentiment_analyzer"] = "unavailable"
            overall_status = "degraded"
        
        return HealthResponse(
            status=overall_status,
            services=services
        )
        
    except Exception as e:
        logger.error(f"Health check error: {e}", exc_info=True)
        return HealthResponse(
            status="unhealthy",
            services={"error": str(e)}
        )


# Startup and shutdown events are now handled by the lifespan context manager above

