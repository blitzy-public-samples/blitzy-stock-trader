"""
Comparison insights API routes.
"""

from fastapi import APIRouter, HTTPException
from typing import Dict, Any
from pydantic import BaseModel, Field

from ..models.response import ErrorResponse
from ..dependencies import get_all_services
from ...utils.logger import get_logger

logger = get_logger(__name__)

router = APIRouter(prefix="/comparison", tags=["comparison"])


class ComparisonInsightsRequest(BaseModel):
    """Request model for comparison insights."""
    comparison_data: Dict[str, Dict[str, Any]] = Field(..., description="Comparison data for each symbol")
    comparison_sentiments: Dict[str, Dict[str, float]] = Field(..., description="Sentiment scores for each symbol")


class ComparisonInsightsResponse(BaseModel):
    """Response model for comparison insights."""
    insights: str = Field(..., description="AI-generated comparison insights")


@router.post(
    "/insights",
    response_model=ComparisonInsightsResponse,
    responses={
        200: {"description": "Successful response"},
        400: {"model": ErrorResponse, "description": "Bad request"},
        500: {"model": ErrorResponse, "description": "Internal server error"}
    },
    summary="Generate AI-powered comparison insights",
    description="""
    Generate AI-powered insights comparing multiple stocks.
    
    Takes comparison data and sentiment scores for multiple symbols and generates
    comprehensive AI analysis comparing their sentiment, performance, and market perception.
    """
)
async def generate_comparison_insights(
    request: ComparisonInsightsRequest
):
    """
    Generate AI-powered comparison insights for multiple stocks.
    
    Args:
        request: Comparison data and sentiment scores
    
    Returns:
        AI-generated comparison insights text
    
    Raises:
        HTTPException: If generation fails
    """
    try:
        comparison_data = request.comparison_data
        comparison_sentiments = request.comparison_sentiments
        
        # Validate input
        symbols = list(comparison_sentiments.keys())
        if len(symbols) < 2:
            raise HTTPException(
                status_code=400,
                detail="At least 2 symbols required for comparison"
            )
        
        # Check that all symbols in sentiments have data
        for sym in symbols:
            if sym not in comparison_data:
                raise HTTPException(
                    status_code=400,
                    detail=f"Missing comparison data for symbol {sym}"
                )
        
        logger.info(f"Generating comparison insights for {len(symbols)} symbols: {symbols}")
        
        # Get analyzer from dependencies
        settings, redis_cache, rag_service, collector, analyzer = get_all_services()
        
        if analyzer is None or not hasattr(analyzer, 'client') or analyzer.client is None:
            raise HTTPException(
                status_code=503,
                detail="Sentiment analyzer not available for generating insights"
            )
        
        # Build comparison summary
        comparison_text = f"Comparing {len(symbols)} stocks:\n\n"
        
        for sym in symbols:
            sent = comparison_sentiments[sym]
            price_data = comparison_data[sym].get('price_data', {})
            price = price_data.get('price', 0)
            net_sentiment = sent['positive'] - sent['negative']
            
            comparison_text += f"{sym}:\n"
            comparison_text += f"  - Price: ${price:.2f}\n"
            comparison_text += f"  - Positive Sentiment: {sent['positive']:.1%}\n"
            comparison_text += f"  - Negative Sentiment: {sent['negative']:.1%}\n"
            comparison_text += f"  - Net Sentiment: {net_sentiment:+.2%}\n\n"
        
        # Generate AI insights
        prompt = f"""You are an expert financial analyst. Analyze the following stock comparison and provide:
1. Which stock has the most positive sentiment and why
2. Which stock has the most negative sentiment and why
3. Key differences in market perception
4. Investment implications
5. Risk assessment

{comparison_text}

Provide a concise, professional analysis (3-5 paragraphs)."""
        
        try:
            response = analyzer.client.chat.completions.create(
                model=analyzer.deployment_name,
                messages=[
                    {
                        "role": "system",
                        "content": "You are an expert financial analyst specializing in stock market sentiment analysis and comparative investment analysis."
                    },
                    {
                        "role": "user",
                        "content": prompt
                    }
                ],
                temperature=0.7,
                max_tokens=500
            )
            
            insights = response.choices[0].message.content
            logger.info(f"Successfully generated comparison insights for {len(symbols)} symbols")
            
            return ComparisonInsightsResponse(insights=insights)
            
        except Exception as e:
            logger.error(f"Error generating AI insights: {e}", exc_info=True)
            # Fallback to basic insights
            insights = _generate_fallback_insights(comparison_sentiments, comparison_data)
            return ComparisonInsightsResponse(insights=insights)
            
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error in comparison insights generation: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate comparison insights: {str(e)}"
        )


def _generate_fallback_insights(
    comparison_sentiments: Dict[str, Dict[str, float]],
    comparison_data: Dict[str, Dict[str, Any]]
) -> str:
    """
    Generate fallback insights without AI.
    
    Args:
        comparison_sentiments: Dictionary mapping symbols to sentiment scores
        comparison_data: Dictionary mapping symbols to their data
    
    Returns:
        Fallback insights text
    """
    symbols = list(comparison_sentiments.keys())
    
    # Find best and worst sentiment
    best_sentiment = max(symbols, key=lambda s: comparison_sentiments[s]['positive'] - comparison_sentiments[s]['negative'])
    worst_sentiment = min(symbols, key=lambda s: comparison_sentiments[s]['positive'] - comparison_sentiments[s]['negative'])
    
    best_net = comparison_sentiments[best_sentiment]['positive'] - comparison_sentiments[best_sentiment]['negative']
    worst_net = comparison_sentiments[worst_sentiment]['positive'] - comparison_sentiments[worst_sentiment]['negative']
    
    insights = f"## Comparison Analysis\n\n"
    insights += f"**Most Positive Sentiment:** {best_sentiment} (Net: {best_net:+.2%})\n"
    insights += f"- Positive: {comparison_sentiments[best_sentiment]['positive']:.1%}\n"
    insights += f"- Negative: {comparison_sentiments[best_sentiment]['negative']:.1%}\n\n"
    
    insights += f"**Most Negative Sentiment:** {worst_sentiment} (Net: {worst_net:+.2%})\n"
    insights += f"- Positive: {comparison_sentiments[worst_sentiment]['positive']:.1%}\n"
    insights += f"- Negative: {comparison_sentiments[worst_sentiment]['negative']:.1%}\n\n"
    
    insights += f"**Key Differences:**\n"
    for sym in symbols:
        net = comparison_sentiments[sym]['positive'] - comparison_sentiments[sym]['negative']
        price = comparison_data[sym].get('price_data', {}).get('price', 0)
        insights += f"- {sym}: ${price:.2f}, Net Sentiment: {net:+.2%}\n"
    
    return insights

