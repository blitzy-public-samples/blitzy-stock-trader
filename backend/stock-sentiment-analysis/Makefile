.PHONY: help install install-dev venv venv-activate run test lint format clean setup-azure setup-redis setup-all

# Variables
PYTHON := python3
VENV := venv
VENV_BIN := $(VENV)/bin
VENV_PYTHON := $(VENV_BIN)/python
VENV_PIP := $(VENV_BIN)/pip
STREAMLIT := $(VENV_BIN)/streamlit
PYTEST := $(VENV_BIN)/pytest
BLACK := $(VENV_BIN)/black
FLAKE8 := $(VENV_BIN)/flake8
MYPY := $(VENV_BIN)/mypy

# Detect if running in venv
ifeq ($(VIRTUAL_ENV),)
	VENV_ACTIVE := false
else
	VENV_ACTIVE := true
endif

help: ## Show this help message
	@echo "Stock Sentiment Analysis - Makefile Commands"
	@echo ""
	@echo "Virtual Environment:"
	@echo "  make venv          - Create virtual environment"
	@echo "  make venv-activate - Show activation command"
	@echo ""
	@echo "Running:"
	@echo "  make run           - Run the Streamlit dashboard"
	@echo "  make run-api       - Run the FastAPI server"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

venv: ## Create virtual environment
	@if [ -d "$(VENV)" ]; then \
		echo "Virtual environment already exists at $(VENV)"; \
	else \
		echo "Creating virtual environment..."; \
		$(PYTHON) -m venv $(VENV); \
		echo "Virtual environment created. Activate it with: source $(VENV_BIN)/activate"; \
	fi

venv-activate: ## Show virtual environment activation command
	@echo "To activate the virtual environment, run:"
	@echo "  source $(VENV_BIN)/activate"
	@echo ""
	@echo "Or on Windows:"
	@echo "  $(VENV_BIN)\\activate"

install: venv ## Install production dependencies
	@echo "Installing production dependencies..."
	$(VENV_PIP) install --upgrade pip
	$(VENV_PIP) install -r requirements.txt
	@echo "Production dependencies installed."

install-dev: venv ## Install development dependencies
	@echo "Installing development dependencies..."
	$(VENV_PIP) install --upgrade pip
	$(VENV_PIP) install -r requirements.txt
	$(VENV_PIP) install -e ".[dev]"
	@echo "Development dependencies installed."

run: venv ## Run the Streamlit application
	@if [ ! -f "$(STREAMLIT)" ]; then \
		echo "Streamlit not found. Installing dependencies..."; \
		$(MAKE) install; \
	fi
	@if [ ! -f "$(STREAMLIT)" ]; then \
		echo "Error: Streamlit installation failed. Please run 'make install' manually."; \
		exit 1; \
	fi
	@if [ "$(VENV_ACTIVE)" = "false" ]; then \
		echo "Note: Using venv Python (venv not activated)."; \
	fi
	@PYTHONPATH=src $(STREAMLIT) run src/stock_sentiment/app.py

run-api: venv ## Run the FastAPI server
	@if [ ! -f "$(VENV_BIN)/uvicorn" ]; then \
		echo "uvicorn not found. Installing dependencies..."; \
		$(MAKE) install; \
	fi
	@if [ "$(VENV_ACTIVE)" = "false" ]; then \
		echo "Note: Using venv Python (venv not activated)."; \
	fi
	@cd src/stock_sentiment && PYTHONPATH=../.. $(VENV_BIN)/uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload

test: venv ## Run tests
	@if [ ! -f "$(PYTEST)" ]; then \
		echo "pytest not found. Installing dev dependencies..."; \
		$(MAKE) install-dev; \
	fi
	@if [ ! -d "tests" ]; then \
		echo "No tests directory found. Skipping tests."; \
		exit 0; \
	fi
	$(PYTEST) tests/ -v --cov=src/stock_sentiment --cov-report=html --cov-report=term

lint: venv ## Run linters
	@if [ ! -f "$(FLAKE8)" ] || [ ! -f "$(MYPY)" ]; then \
		echo "Linters not found. Installing dev dependencies..."; \
		$(MAKE) install-dev; \
	fi
	@echo "Running flake8..."
	@$(FLAKE8) src/ tests/ || true
	@echo "Running mypy..."
	@$(MYPY) src/stock_sentiment || true

format: venv ## Format code with black
	@if [ ! -f "$(BLACK)" ]; then \
		echo "black not found. Installing dev dependencies..."; \
		$(MAKE) install-dev; \
	fi
	@echo "Formatting code with black..."
	$(BLACK) src/ tests/
	@echo "Code formatted."

format-check: venv ## Check code formatting without making changes
	@if [ ! -f "$(BLACK)" ]; then \
		echo "black not found. Installing dev dependencies..."; \
		$(MAKE) install-dev; \
	fi
	@echo "Checking code formatting..."
	$(BLACK) --check src/ tests/

clean: ## Clean cache and build files
	@echo "Cleaning cache and build files..."
	@find . -type d -name __pycache__ -not -path "./$(VENV)/*" -exec rm -rf {} + 2>/dev/null || true
	@find . -type f -name "*.pyc" -not -path "./$(VENV)/*" -delete 2>/dev/null || true
	@find . -type d -name "*.egg-info" -not -path "./$(VENV)/*" -exec rm -rf {} + 2>/dev/null || true
	@rm -rf build/ dist/ .pytest_cache/ .coverage htmlcov/ .mypy_cache/ .ruff_cache/
	@echo "Clean complete."

clean-all: clean ## Clean everything including virtual environment
	@echo "Removing virtual environment..."
	@rm -rf $(VENV)
	@echo "All cleaned."

setup-azure: ## Setup Azure OpenAI (requires RESOURCE_GROUP_NAME)
	@if [ -z "$(RG)" ]; then \
		echo "Error: RESOURCE_GROUP_NAME is required"; \
		echo "Usage: make setup-azure RG=your-resource-group-name"; \
		exit 1; \
	fi
	@if [ ! -f "./scripts/setup-azure-openai.sh" ]; then \
		echo "Error: setup-azure-openai.sh not found"; \
		exit 1; \
	fi
	@chmod +x ./scripts/setup-azure-openai.sh
	./scripts/setup-azure-openai.sh $(RG)

setup-redis: ## Setup Azure Redis (requires RESOURCE_GROUP_NAME)
	@if [ -z "$(RG)" ]; then \
		echo "Error: RESOURCE_GROUP_NAME is required"; \
		echo "Usage: make setup-redis RG=your-resource-group-name"; \
		exit 1; \
	fi
	@if [ ! -f "./scripts/setup-azure-redis.sh" ]; then \
		echo "Error: setup-azure-redis.sh not found"; \
		exit 1; \
	fi
	@chmod +x ./scripts/setup-azure-redis.sh
	./scripts/setup-azure-redis.sh $(RG)

setup-all: ## Setup both Azure OpenAI and Redis
	@if [ -z "$(RG)" ]; then \
		echo "Error: RESOURCE_GROUP_NAME is required"; \
		echo "Usage: make setup-all RG=your-resource-group-name"; \
		exit 1; \
	fi
	@$(MAKE) setup-azure RG=$(RG)
	@$(MAKE) setup-redis RG=$(RG)

check-env: ## Check if .env file exists
	@if [ ! -f ".env" ]; then \
		echo "Warning: .env file not found. Copy .env.example to .env and configure it."; \
		exit 1; \
	else \
		echo ".env file found."; \
	fi

.DEFAULT_GOAL := help
