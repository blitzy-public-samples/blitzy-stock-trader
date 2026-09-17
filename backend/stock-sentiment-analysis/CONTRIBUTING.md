# Contributing to Stock Sentiment Analysis

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing to the project.

## Code of Conduct

- Be respectful and inclusive
- Welcome newcomers and help them learn
- Focus on constructive feedback
- Follow the project's coding standards

## Development Setup

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/IBMStockTrader/stock-sentiment-analysis.git
   cd stock-sentiment-analysis
   ```
3. Create a virtual environment:
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```
4. Install development dependencies:
   ```bash
   make install-dev
   ```

## Coding Standards

### Python Style

- Follow PEP 8 style guide
- Use type hints for all function parameters and return values
- Maximum line length: 100 characters
- Use `black` for code formatting
- Use `flake8` for linting

### Code Formatting

Before committing, run:
```bash
make format
```

### Type Hints

Always include type hints:
```python
def analyze_sentiment(text: str, symbol: Optional[str] = None) -> Dict[str, float]:
    """Analyze sentiment of text."""
    ...
```

### Docstrings

Use Google-style docstrings:
```python
def function_name(param1: str, param2: int) -> bool:
    """
    Brief description of the function.
    
    Args:
        param1: Description of param1
        param2: Description of param2
        
    Returns:
        Description of return value
        
    Raises:
        ValueError: When something goes wrong
    """
    ...
```

## Commit Messages

Use clear, descriptive commit messages:
- Use present tense ("Add feature" not "Added feature")
- First line should be 50 characters or less
- Include more details in the body if needed

Example:
```
Add Redis connection retry logic

Implement exponential backoff for Redis connection failures
to improve reliability in network-constrained environments.
```

## Pull Request Process

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes and commit:
   ```bash
   git add .
   git commit -m "Add your feature"
   ```

3. Push to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

4. Open a Pull Request on GitHub

5. Ensure all tests pass:
   ```bash
   make test
   ```

6. Address any review feedback

## Testing

- Write tests for new features
- Ensure all existing tests pass
- Aim for >80% code coverage

Run tests:
```bash
make test
```

## Documentation

- Update README.md if adding new features
- Add docstrings to all new functions/classes
- Update CHANGELOG.md for user-facing changes

## Questions?

Open an issue for questions or discussions about contributions.

