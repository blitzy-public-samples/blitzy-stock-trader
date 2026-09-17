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

package com.kyndryl.cjot.stocktrader.rest;

import com.kyndryl.cjot.stocktrader.assistant.PortfolioAssistant;
import io.micrometer.core.annotation.Timed;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.auth.LoginConfig;

@Path("/portfolioAdvisor")
@ApplicationScoped
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
public class PortfolioAssistantResource {

    @Inject
    PortfolioAssistant makeMeRichService;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Timed(description = "Welcome")
    @PermitAll
    public String welcome() {
        return "Welcome to Portfolio Assistant.";
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed({"StockTrader", "StockViewer"})
    @Timed(description = "Time needed chatting to the agent.")
    public String advice(String question) {
        return makeMeRichService.advice(question);
    }

    @POST
    @Path("/streaming")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"StockTrader", "StockViewer"})
    @Timed(description = "Time needed chatting to the agent.")
    public Multi<String> adviceStreaming(String question) {
        return makeMeRichService.adviceStreaming(question);
    }
}
