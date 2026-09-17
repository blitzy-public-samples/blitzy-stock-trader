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

package com.kyndryl.cjot.stocktrader.clients.stockquote;

import com.kyndryl.cjot.stocktrader.helpers.jwt.StockTraderJwtPropagatingRequestFilter;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@ApplicationPath("/")
@Path("/")
@ApplicationScoped
@RegisterRestClient(configKey = "stock-api")
@RegisterProvider(StockTraderJwtPropagatingRequestFilter.class) //To enable JWT propagation
public interface StockQuoteClient {

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan(kind = SpanKind.CLIENT, value = "StockQuoteClient.getAllCachedQuotes")
    public List<Quote> getAllCachedQuotes();

    @GET
    @Path("/{symbol}")
    @Produces(MediaType.APPLICATION_JSON)
    @WithSpan(kind = SpanKind.CLIENT, value = "StockQuoteClient.getStockQuote")
    public Quote getStockQuote(@PathParam("symbol") String symbol);
}
