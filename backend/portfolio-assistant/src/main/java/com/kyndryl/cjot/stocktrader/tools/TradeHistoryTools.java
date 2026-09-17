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

import com.kyndryl.cjot.stocktrader.clients.tradehistory.TradeHistoryClient;
import com.kyndryl.cjot.stocktrader.clients.tradehistory.Transaction;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

@ApplicationScoped
@Log
public class TradeHistoryTools {

    @Inject
    @RestClient
    TradeHistoryClient tradeHistoryClient;

    @Tool(name = "get_portfolio_returns",
            value = "Return the portfolio return on investment as a currency amount (e.g., '5.5' for $5.50) for a given owner and current portfolio value. " +
                    "Use ONLY for questions about portfolio performance.")
    public String getReturns(String ownerName, Double portfolioValue) {
        log.info("Retrieving returns for owner: " + ownerName + " with portfolio value: " + portfolioValue);
        String returns = tradeHistoryClient.getReturns(ownerName, portfolioValue);
        log.info("Retrieved returns: " + returns);
        return returns;
    }

    @Tool(name = "get_portfolio_notional",
            value = "Return the notional value of the portfolio for a given owner. " +
                    "Use ONLY for questions about portfolio notional value.")
    public Float getNotional(String ownerName) {
        log.info("Retrieving notional for owner: " + ownerName);
        Float notional = tradeHistoryClient.getNotional(ownerName);
        log.info("Retrieved notional: " + notional);
        return notional;
    }

    @Tool(name = "get_trade_history_for_owner_and_symbol",
            value = "Get trade history of specified owner for the specified stock symbol. ")
    public List<Transaction> getTradesForOwnerAndSymbol(String ownerName, String symbol) {
        log.info("Retrieving Trades for owner: " + ownerName + " and symbol: " + symbol);
        var returns = tradeHistoryClient.getTradesByOwnerAndSymbol(ownerName, symbol);
        log.info("Retrieved trades: " + returns);
        return returns.transactions();
    }

    @Tool(name = "get_historical_trades",
            value = "Return the trade history for a given owner. " +
                    "Use ONLY for questions about trades.")
    public List<Transaction> getHistoricalTrades(String ownerName) {
        log.info("Retrieving trades for owner: " + ownerName);
        var trades = tradeHistoryClient.getTradesByOwner(ownerName);
        log.info("Retrieved trades: " + trades);
        return trades.transactions();
    }
}
