# Stock Sentiment Analysis Dashboard

[![Python 3.8+](https://img.shields.io/badge/python-3.8+-blue.svg)](https://www.python.org/downloads/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Code style: black](https://img.shields.io/badge/code%20style-black-000000.svg)](https://github.com/psf/black)

A production-grade stock sentiment analysis application that leverages Azure OpenAI, Azure AI Search, Redis caching, and RAG (Retrieval Augmented Generation) with hybrid search to provide real-time sentiment insights for stock market analysis.

## 🚀 Features

- **AI-Powered Sentiment Analysis**: Uses Azure OpenAI GPT-4 for accurate financial sentiment analysis
- **RAG with Hybrid Search**: Retrieval Augmented Generation with semantic + keyword search using Reciprocal Rank Fusion (RRF)
- **Azure AI Search**: High-performance vector database for 10-100× faster search than traditional methods
- **Redis Caching**: Multi-tier caching reduces API calls by 50-90% and improves performance
- **Multi-Source Data Collection**: Aggregates news from Yahoo Finance, Alpha Vantage, Finnhub, and Reddit
- **Modular Architecture**: Clean separation of concerns with presentation, service, and infrastructure layers
- **Interactive Dashboard**: Beautiful Streamlit-based web interface with multiple analysis views
- **Comprehensive Analytics**: Price charts, sentiment trends, news analysis, and technical indicators
- **Demo-Ready**: Operation summaries, detailed logging, and configurable cache controls

## 📸 Screenshots

### Stock Search on Dashboard
![Stock Search on Dashboard](src/public/stocksearchondashboard.png)

### Stock Result on Dashboard
![Stock Result on Dashboard](src/public/stockresultondashboard.png)

### Stock Search on Buy/Sell
![Stock Search on Buy/Sell](src/public/stockserachonbuysell.png)

### Stock Result on Buy/Sell
![Stock Result on Buy/Sell](src/public/stockresultonbuysell.png)

## 📋 Table of Contents

- [Prerequisites](#prerequisites)
- [Installation](#installation)
- [Configuration](#configuration)
- [Azure Setup](#azure-setup)
- [Running the Application](#running-the-application)
- [Project Structure](#project-structure)
- [Development](#development)
- [Contributing](#contributing)
- [License](#license)

## Prerequisites

- Python 3.8 or higher
- Azure account with:
  - Azure OpenAI service (with GPT-4 and text-embedding-ada-002 deployments)
  - Azure Cache for Redis (optional but recommended)
  - Azure AI Search (optional but recommended for RAG)
- Azure CLI installed and configured (optional, for automated setup)
- Git

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/IBMStockTrader/stock-sentiment-analysis.git
cd stock-sentiment-analysis
```

### 2. Create Virtual Environment

```bash
# Using Makefile (recommended)
make venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Or manually
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
```

### 3. Install Dependencies

```bash
# Using Makefile (recommended)
make install          # Production dependencies
make install-dev      # Development dependencies

# Or manually
pip install -r requirements.txt
```

## Configuration

### 1. Create Environment File

Copy the example environment file:

```bash
cp .env.example .env
```

### 2. Configure Environment Variables

Edit `.env` and fill in your actual values. The `.env.example` file contains detailed comments explaining each variable.

**Required Configuration:**
- `AZURE_OPENAI_ENDPOINT` - Your Azure OpenAI endpoint URL
- `AZURE_OPENAI_API_KEY` - Your Azure OpenAI API key
- `AZURE_OPENAI_DEPLOYMENT_NAME` - Your GPT-4 deployment name (default: gpt-4)
- `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` - Embedding model deployment (required for RAG)

**Optional but Recommended:**
- `REDIS_HOST` - Redis host for caching (recommended for performance)
- `REDIS_PASSWORD` - Redis password
- `AZURE_AI_SEARCH_ENDPOINT` - Azure AI Search endpoint (recommended for RAG)
- `AZURE_AI_SEARCH_API_KEY` - Azure AI Search API key

**Optional Data Sources:**
- **Reddit** (Free, requires app registration at https://www.reddit.com/prefs/apps)
- **Alpha Vantage** (Free tier: 500 calls/day, get key at https://www.alphavantage.co/support/#api-key)
- **Finnhub** (Free tier: 60 calls/minute, get key at https://finnhub.io/register)

**Note**: yfinance is always enabled (no API key needed). All other settings have sensible defaults and can be adjusted through the application UI.

See `.env.example` for detailed configuration options.

## Azure Setup

We provide automated scripts to set up Azure infrastructure. See [scripts/README.md](scripts/README.md) for detailed documentation.

### Quick Setup

#### 1. Login to Azure

```bash
az login
az account set --subscription "your-subscription-id"
```

#### 2. Create Resource Group

```bash
az group create --name stock-sentiment-rg --location eastus
```

#### 3. Setup Azure OpenAI (with RAG)

```bash
# Using the setup script
./scripts/setup-azure-openai.sh stock-sentiment-rg --location eastus

# Or using make
make setup-azure RG=stock-sentiment-rg
```

This script will:
- Create Azure OpenAI service
- Deploy GPT-4 model for chat completions
- Deploy text-embedding-ada-002 for RAG
- Output configuration for your `.env` file

#### 4. Setup Azure Redis (Optional but Recommended)

```bash
# Using the setup script
./scripts/setup-azure-redis.sh stock-sentiment-rg --location eastus

# Or using make
make setup-redis RG=stock-sentiment-rg
```

#### 5. Setup Azure AI Search (Optional but Recommended for RAG)

```bash
# Manual setup recommended - see Azure Portal
# Create Azure AI Search service and configure index
# See docs/ARCHITECTURE.md for index schema details
```

#### 6. Setup Everything at Once

```bash
make setup-all RG=stock-sentiment-rg
```

### Manual Setup

If you prefer to set up resources manually:

1. **Azure OpenAI**:
   - Create Azure OpenAI resource
   - Deploy GPT-4 model
   - Deploy text-embedding-ada-002 model (for RAG)
   - Get API key and endpoint

2. **Azure Redis** (Optional):
   - Create Azure Cache for Redis
   - Get connection details (host, port, password)

3. **Azure AI Search** (Optional but Recommended):
   - Create Azure AI Search service
   - Create index with vector search capability
   - See `docs/ARCHITECTURE.md` for index schema
   - Get API key and endpoint

## Running the Application

The application consists of two components:
1. **FastAPI Backend Server** - REST API for sentiment analysis
2. **Streamlit Dashboard** - Web interface for visualization

### Start the API Server

The API server must be running before starting the dashboard:

```bash
# Using uvicorn directly
cd src/stock_sentiment
uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload

# Or using make (if available)
make run-api
```

The API server will be available at:
- **API**: `http://localhost:8000`
- **API Docs**: `http://localhost:8000/docs`
- **ReDoc**: `http://localhost:8000/redoc`

**Note**: The dashboard requires the API server to be running. If the API is not available, the dashboard will show an error.

### Start the Dashboard

```bash
# Using streamlit directly
streamlit run src/stock_sentiment/app.py

# Or using make
make run
```

The dashboard will be available at `http://localhost:8501`

**Note**: The application is now located in `src/stock_sentiment/app.py` as part of the refactored structure.

### Using the Dashboard

1. **Enter Stock Symbol**: Type a stock ticker (e.g., AAPL, MSFT, GOOGL)
2. **Configure Data Sources**: Enable/disable data sources in the sidebar (yfinance, Alpha Vantage, Finnhub, Reddit)
3. **Configure Cache**: Adjust sentiment cache TTL or disable it to force RAG usage
4. **Load Data**: Click "Load Data" to fetch stock information and news from enabled sources
5. **View Operation Summary**: Check the sidebar for Redis and RAG usage statistics
6. **Explore Tabs**:
   - **Overview**: Summary of stock data and overall sentiment
   - **Price Analysis**: Historical price charts and trends
   - **News & Sentiment**: News articles with sentiment analysis from multiple sources
   - **Technical Analysis**: Technical indicators and metrics
   - **AI Insights**: AI-generated insights using RAG with hybrid search
   - **Comparison**: Compare multiple stocks side-by-side

## Project Structure

```
stock-sentiment-analysis/
├── src/
│   └── stock_sentiment/          # Main application package
│       ├── __init__.py
│       ├── app.py                # Streamlit dashboard (thin orchestrator)
│       ├── config/               # Configuration management
│       │   ├── __init__.py
│       │   └── settings.py       # Settings and environment validation
│       ├── presentation/         # Presentation layer
│       │   ├── styles.py         # Custom CSS styling
│       │   ├── initialization.py # App setup and service initialization
│       │   ├── data_loader.py    # Data loading orchestration
│       │   ├── components/       # Reusable UI components
│       │   │   ├── sidebar.py   # Sidebar with controls and summary
│       │   │   └── empty_state.py
│       │   └── tabs/             # Tab modules
│       │       ├── overview_tab.py
│       │       ├── price_analysis_tab.py
│       │       ├── news_sentiment_tab.py
│       │       ├── technical_analysis_tab.py
│       │       ├── ai_insights_tab.py
│       │       └── comparison_tab.py
│       ├── services/             # Business logic services
│       │   ├── __init__.py
│       │   ├── cache.py          # Redis cache service
│       │   ├── collector.py      # Multi-source data collector
│       │   ├── rag.py            # RAG service with hybrid search
│       │   ├── sentiment.py      # Sentiment analyzer
│       │   └── vector_db.py     # Azure AI Search integration
│       ├── models/               # Data models
│       │   ├── __init__.py
│       │   ├── sentiment.py      # Sentiment data models
│       │   └── stock.py          # Stock data models
│       └── utils/                # Utility functions
│           ├── __init__.py
│           ├── logger.py         # Logging configuration
│           ├── retry.py          # Retry logic
│           ├── circuit_breaker.py
│           └── preprocessing.py
├── scripts/                      # Deployment and setup scripts
│   ├── setup-azure-openai.sh
│   ├── setup-azure-redis.sh
│   ├── setup-azure-ai-search.sh
│   └── add-embedding-model.sh
├── docs/                         # Documentation
│   ├── index.md                  # Complete documentation
│   ├── ARCHITECTURE.md           # Architecture documentation
│   └── diagrams/                 # Architecture diagrams
├── tests/                        # Test files
├── .env.example                  # Example environment file
├── .gitignore                    # Git ignore rules
├── Makefile                      # Common commands
├── pyproject.toml                # Package configuration
├── requirements.txt              # Python dependencies
└── README.md                     # This file
```

## Development

### Makefile Commands

The project includes a comprehensive Makefile with industry-standard commands:

```bash
# Virtual Environment
make venv              # Create virtual environment
make venv-activate     # Show activation command

# Installation
make install           # Install production dependencies
make install-dev       # Install development dependencies

# Running
make run               # Run the Streamlit application

# Testing & Quality
make test              # Run tests with coverage
make lint              # Run linters (flake8, mypy)
make format            # Format code with black
make format-check      # Check formatting without changes

# Cleanup
make clean             # Clean cache and build files
make clean-all         # Clean everything including venv

# Azure Setup
make setup-azure RG=your-resource-group    # Setup Azure OpenAI
make setup-redis RG=your-resource-group   # Setup Azure Redis
make setup-all RG=your-resource-group     # Setup both

# Help
make help              # Show all available commands
```

### Setup Development Environment

```bash
make venv
source venv/bin/activate
make install-dev
```

### Run Tests

```bash
make test
```

### Code Formatting

```bash
make format        # Format code
make format-check  # Check formatting
```

### Linting

```bash
make lint
```

### Clean Build Files

```bash
make clean         # Clean cache and build files
make clean-all     # Clean everything including venv
```

## Architecture

### Components

1. **Sentiment Analyzer**: Uses Azure OpenAI GPT-4 to analyze sentiment with RAG context
2. **Data Collector**: Fetches stock data and news from multiple sources (yfinance, Alpha Vantage, Finnhub, Reddit)
3. **RAG Service**: Manages embeddings, hybrid search (semantic + keyword), and retrieves relevant context
4. **Vector Database**: Azure AI Search for high-performance vector search (10-100× faster)
5. **Redis Cache**: Multi-tier caching reduces API calls by 50-90% and improves performance
6. **Streamlit Dashboard**: Interactive web interface with modular architecture

### Data Flow

```
User Input (Stock Symbol)
    ↓
Data Collector → Fetch from Multiple Sources (yfinance, Alpha Vantage, Finnhub, Reddit)
    ↓
RAG Service → Store Articles in Azure AI Search (with embeddings)
    ↓
Sentiment Analyzer → Retrieve Context via Hybrid Search (RRF)
    ↓
Sentiment Analyzer → Analyze with RAG Context using GPT-4
    ↓
Redis Cache → Store Results
    ↓
Streamlit Dashboard → Display Results with Operation Summary
```

### Key Technologies

- **Hybrid Search**: Combines semantic (vector) and keyword search using Reciprocal Rank Fusion (RRF)
- **Azure AI Search**: HNSW algorithm for fast approximate nearest neighbor search
- **Temporal Decay**: Boosts recent articles in search results
- **Batch Processing**: Efficient embedding generation (100 articles per API call)
- **Parallel Processing**: Concurrent sentiment analysis for throughput

For detailed architecture documentation, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/index.md](docs/index.md).

## Configuration Options

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `AZURE_OPENAI_ENDPOINT` | Azure OpenAI service endpoint | - | Yes |
| `AZURE_OPENAI_API_KEY` | Azure OpenAI API key | - | Yes |
| `AZURE_OPENAI_DEPLOYMENT_NAME` | Chat model deployment name | `gpt-4` | No |
| `AZURE_OPENAI_EMBEDDING_DEPLOYMENT` | Embedding model deployment | - | Yes (for RAG) |
| `REDIS_HOST` | Redis host address | - | No (recommended) |
| `REDIS_PASSWORD` | Redis password | - | No (if Redis enabled) |
| `REDIS_PORT` | Redis port | `6380` | No |
| `REDIS_SSL` | Enable SSL | `true` | No |
| `AZURE_AI_SEARCH_ENDPOINT` | Azure AI Search endpoint | - | No (recommended for RAG) |
| `AZURE_AI_SEARCH_API_KEY` | Azure AI Search API key | - | No (if Azure AI Search enabled) |
| `AZURE_AI_SEARCH_INDEX_NAME` | Index name | `stock-articles` | No |

All other settings have sensible defaults and can be adjusted through the application UI. See `.env.example` for a complete list.

## Troubleshooting

### Redis Connection Issues

- Verify Redis credentials in `.env`
- Check if Redis is accessible from your network
- Ensure SSL settings match your Redis configuration

### Azure OpenAI Errors

- Verify API key and endpoint are correct
- Check if models are deployed in your Azure OpenAI resource
- Ensure you have sufficient quota

### RAG Not Working

- Verify embedding model is deployed: `AZURE_OPENAI_EMBEDDING_DEPLOYMENT`
- Check deployment name matches your Azure resource
- Verify Azure AI Search is configured (optional but recommended)
- Check operation summary in sidebar for RAG usage statistics
- Run `./scripts/add-embedding-model.sh` to add embedding model
- Disable sentiment cache in sidebar to force RAG usage for testing

### Import Errors

- Ensure virtual environment is activated
- Run `pip install -r requirements.txt`
- Check Python version (3.8+)

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style

- Follow PEP 8 style guide
- Use type hints
- Add docstrings to all functions and classes
- Run `make format` before committing

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Documentation

- **[Complete Documentation](docs/index.md)**: Comprehensive guide with examples, algorithms, and mathematical formulas
- **[Architecture Documentation](docs/ARCHITECTURE.md)**: Detailed architecture, components, and data flows
- **[API Documentation](docs/API.md)**: REST API reference and examples
- **[Diagrams](docs/diagrams/)**: High-quality architecture diagrams

## Acknowledgments

- [Streamlit](https://streamlit.io/) for the dashboard framework
- [Azure OpenAI](https://azure.microsoft.com/en-us/products/cognitive-services/openai-service/) for AI capabilities
- [Azure AI Search](https://azure.microsoft.com/en-us/products/ai-services/ai-search/) for vector search
- [yfinance](https://github.com/ranaroussi/yfinance) for stock data
- [Plotly](https://plotly.com/) for interactive visualizations

## Support

For issues, questions, or contributions, please open an issue on GitHub.

---

**Note**: This application uses free APIs where possible. For production use, consider implementing rate limiting, error handling, and monitoring.