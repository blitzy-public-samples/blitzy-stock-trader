import azure.functions as func
import yfinance as yf
import json
from datetime import datetime
import logging

def main(req: func.HttpRequest) -> func.HttpResponse:
    """
    Azure Function to get stock quote using Yahoo Finance API
    Supports both URL formats:
    - /api/stock_quote?symbol=AAPL (query parameter)
    - /api/stock_quote/AAPL (path parameter)
    """
    logging.info('Stock quote function triggered')
    
    try:
        # Get stock symbol from query parameter first
        symbol = req.params.get('symbol')
        
        # If not found in query params, try to extract from URL path
        if not symbol:
            # Extract symbol from URL path like /api/stock_quote/AAPL
            url_parts = req.url.split('/')
            if len(url_parts) > 0:
                # Get the last part of the URL path
                potential_symbol = url_parts[-1]
                # Remove any query parameters if present
                potential_symbol = potential_symbol.split('?')[0]
                # Check if it looks like a stock symbol (letters only, 1-5 chars)
                if potential_symbol and potential_symbol.isalpha() and len(potential_symbol) <= 5:
                    symbol = potential_symbol
        
        if not symbol:
            return func.HttpResponse(
                json.dumps({"error": "Stock symbol parameter is required. Use ?symbol=AAPL or /stock_quote/AAPL"}),
                status_code=400,
                mimetype="application/json"
            )
        
        # Convert to uppercase for consistency
        symbol = symbol.upper()
        
        # Fetch stock data using yfinance
        ticker = yf.Ticker(symbol)
        info = ticker.info
        
        # Get current price
        current_price = info.get('currentPrice') or info.get('regularMarketPrice')
        
        if current_price is None:
            return func.HttpResponse(
                json.dumps({"error": f"Could not retrieve price for symbol {symbol}"}),
                status_code=404,
                mimetype="application/json"
            )
        
        # Get current date and time
        current_date = datetime.now().strftime("%Y-%m-%d")
        current_time = int(datetime.now().timestamp())
        
        # Format response to match the expected structure
        response_data = {
            "date": current_date,
            "price": round(current_price, 2),
            "symbol": symbol,
            "time": current_time
        }
        
        logging.info(f'Successfully retrieved quote for {symbol}: ${current_price}')
        
        return func.HttpResponse(
            json.dumps(response_data),
            status_code=200,
            mimetype="application/json"
        )
        
    except Exception as e:
        logging.error(f'Error retrieving stock quote: {str(e)}')
        return func.HttpResponse(
            json.dumps({"error": "Internal server error occurred"}),
            status_code=500,
            mimetype="application/json"
        )
