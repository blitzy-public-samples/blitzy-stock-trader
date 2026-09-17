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

package com.kyndryl.cjot.stocktrader.websocket;

import com.kyndryl.cjot.stocktrader.tools.PortfolioTools;
import com.kyndryl.cjot.stocktrader.tools.StockTools;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.SessionScoped;

@RegisterAiService(tools = {PortfolioTools.class, StockTools.class})
@SystemMessage("""
        You are a portfolio advisor. Your job:
        1) If a portfolio owner is mentioned, FIRST call retrieve_portfolio(owner).
        2) Analyze diversification by sector/industry/region, concentration (top 5 weights), and style tilt (growth/value).
        3) Propose 3–6 additions that improve diversification or advance the user's stated theme/risk constraints.
        4) For each proposed ticker, call get_stock_price(ticker) once to include the current price.
        5) Return only structured JSON matching AdviceResult. If constraints are missing (budget, risk, themes), include followUps.
        
        Hard rules:
        - Never call retrieve_portfolio unless a PERSON's name is provided.
        - Never call get_stock_price for tickers already held unless you’re recommending *adding* to them.
        - At most 6 total tool calls per answer (1 portfolio + up to 5 quotes).
        - Do not include chain-of-thought; provide only final recommendations and short rationales.
        """)
@SessionScoped
@Deprecated
// Not used. Future work.
public interface AdvisorAssistant {
    @UserMessage("{userInput}")
    Multi<String> recommend(String userInput);
}
