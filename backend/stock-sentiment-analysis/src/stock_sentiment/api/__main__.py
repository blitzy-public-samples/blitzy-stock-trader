"""
Entry point for running the API server.

Usage:
    python -m stock_sentiment.api
    python -m stock_sentiment.api --host 0.0.0.0 --port 8000
    python -m stock_sentiment.api --reload  # Development mode
"""

import uvicorn
import sys
from pathlib import Path

# Add project root to path
project_root = Path(__file__).parent.parent.parent.parent
sys.path.insert(0, str(project_root))

from ..config.settings import get_settings


def main():
    """Run the API server."""
    settings = get_settings()
    
    # Get command line arguments
    host = settings.app.api_host
    port = settings.app.api_port
    reload = settings.app.api_reload
    
    # Override with command line args if provided
    if "--host" in sys.argv:
        idx = sys.argv.index("--host")
        if idx + 1 < len(sys.argv):
            host = sys.argv[idx + 1]
    
    if "--port" in sys.argv:
        idx = sys.argv.index("--port")
        if idx + 1 < len(sys.argv):
            port = int(sys.argv[idx + 1])
    
    if "--reload" in sys.argv:
        reload = True
    
    print(f"Starting Stock Sentiment Analysis API on {host}:{port}")
    print(f"API Documentation: http://{host}:{port}/docs")
    print(f"Health Check: http://{host}:{port}/health")
    
    uvicorn.run(
        "stock_sentiment.api.main:app",
        host=host,
        port=port,
        reload=reload,
        log_level=settings.app.log_level.lower()
    )


if __name__ == "__main__":
    main()

