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

package com.ibm.hybrid.cloud.sample.stocktrader.broker.client.tradehistory;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@ApplicationPath("/")
@Path("/")
@ApplicationScoped
@RegisterRestClient
@RegisterClientHeaders //To enable JWT propagation
public interface TradeHistoryClient {

    @GET
    @Path("/returns/{owner}")
    @Produces(MediaType.TEXT_PLAIN)
    @WithSpan(kind = SpanKind.CLIENT, value = "TradeHistoryClient.getReturns")
    Double getReturns(@PathParam("owner") String ownerName, @QueryParam("currentValue") Double portfolioValue);

    @GET
    @Path("/notional/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan(kind = SpanKind.CLIENT, value = "TradeHistoryClient.getNotional")
    Double getNotional(@PathParam("owner") String ownerName);

    @Path("/shares/{owner}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan
    Shares getPortfolioShares(@PathParam("owner") String ownerName);

    @Path("/shares/{owner}/{symbol}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan
    Share getCurrentShares(@PathParam("owner") String ownerName, @PathParam("symbol") String symbol);

    @Path("/trades/{owner}/{symbol}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan
    Transactions getTradesByOwnerAndSymbol(@PathParam("owner") String ownerName, @PathParam("symbol") String symbol);

    @Path("/trades/{owner}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan
    Transactions getTradesByOwner(@PathParam("owner") String ownerName);

    @Path("/latestBuy")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan
    Transaction latestBuy();

}