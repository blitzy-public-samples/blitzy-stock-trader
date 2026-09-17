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

import com.kyndryl.cjot.stocktrader.clients.portfolio.Portfolio;
import com.kyndryl.cjot.stocktrader.clients.portfolio.PortfolioClient;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.java.Log;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
@Log
public class PortfolioTools {

    @Inject
    @RestClient
    PortfolioClient portfolioClient;

    @Tool(name = "retrieve_portfolio",
            value = "Return a SPECIFIC PERSON'S investment portfolio. Use ONLY when the user asks about someone's portfolio.")
    public Portfolio getPortfolio(@P("The name of the portfolio owner, e.g. 'Frank', 'Tim', or 'Karri' . This is a person's name.") String owner) {
        log.info("Retrieving portfolio for owner: " + owner);
        var portfolio = portfolioClient.getPortfolio(owner, false); // Needs to be false to get the stocks
        log.info("Portfolio: " + portfolio);
        return portfolio;
    }
}
