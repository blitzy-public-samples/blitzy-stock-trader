/*
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.kyndryl.cjot.stocktrader.tools;

import com.kyndryl.cjot.stocktrader.clients.stockquote.Quote;
import com.kyndryl.cjot.stocktrader.clients.stockquote.StockQuoteClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
@Log
public class StockTools {

    @Inject
    @RestClient
    StockQuoteClient stockQuoteClient;

    @Tool(name = "get_stock_price",
            value = "Return the live/most-recent market price for a PUBLIC TICKER (e.g., 'TSLA'). Use ONLY for public stock price/quote questions. " +
                    "Use to fetch the current price for tickers you’re recommending.Do NOT use for personal portfolios.")
    public Quote getStockPrice(@P("The public stock ticker symbol to look up") String stockSymbol) {
        log.info("Retrieving stock quote for symbol: " + stockSymbol);
        var quote = stockQuoteClient.getStockQuote(stockSymbol);
        log.info("Retrieved quote: " + quote);
        return quote;
    }
}
