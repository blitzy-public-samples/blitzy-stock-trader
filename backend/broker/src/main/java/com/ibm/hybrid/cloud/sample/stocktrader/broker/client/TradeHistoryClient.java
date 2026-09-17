/*
       Copyright 2020-2021 IBM Corp All Rights Reserved
       Copyright 2022-2024 Kyndryl, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.broker.client;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@ApplicationPath("/")
@Path("/")
@ApplicationScoped
@RegisterRestClient
@RegisterClientHeaders //To enable JWT propagation
// JWT is propagated.  See src/main/resources/META-INF/microprofile-config.properties
/** mpRestClient "remote" interface for the trade history microservice */
@Deprecated
public interface TradeHistoryClient {
    @GET
    @Path("/returns/{owner}")
    @Produces(MediaType.TEXT_PLAIN)
    @WithSpan(kind = SpanKind.CLIENT, value="TradeHistoryClient.getReturns")
    public String getReturns(@PathParam("owner") String ownerName, @QueryParam("currentValue") Double portfolioValue);
}
