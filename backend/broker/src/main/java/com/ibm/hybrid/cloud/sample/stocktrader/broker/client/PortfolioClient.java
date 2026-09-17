/*
       Copyright 2017-2021 IBM Corp All Rights Reserved
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

import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Feedback;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Portfolio;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.WatsonInput;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;


@ApplicationPath("/")
@Path("/")
@ApplicationScoped
@RegisterRestClient
@RegisterClientHeaders //To enable JWT propagation
// JWT is propagated.  See src/main/resources/META-INF/microprofile-config.properties
/** mpRestClient "remote" interface for the Portfolio microservice */
public interface PortfolioClient {
//	@GET
//	@Path("/")
//	@Produces(MediaType.APPLICATION_JSON)
//	public List<Portfolio> getPortfolios();

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="PortfolioClient.getPortfolios")
	public List<Portfolio> getPortfolios(@QueryParam("page") @DefaultValue("1") int pageNumber, @QueryParam("pageSize") @DefaultValue("10") int pageSize);

	@POST
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="PortfolioClient.createPortfolio")
	public Portfolio createPortfolio(@PathParam("owner") String owner, @QueryParam("accountID") String accountID);

	@GET
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="PortfolioClient.getPortfolio")
	public Portfolio getPortfolio(@PathParam("owner") String owner, @QueryParam("immutable") boolean immutable);

	@PUT
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="PortfolioClient.updatePortfolio")
	public Portfolio updatePortfolio(@PathParam("owner") String owner, @QueryParam("symbol") String symbol, @QueryParam("shares") int shares, @QueryParam("commission") double commission);

	@DELETE
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="PortfolioClient.deletePortfolio")
	public Portfolio deletePortfolio( @PathParam("owner") String owner);
}
