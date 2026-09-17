/*
       Copyright 2020-2021 IBM Corp All Rights Reserved
       Copyright 2022-2025 Kyndryl, All Rights Reserved

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

import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Account;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Feedback;
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
/** mpRestClient "remote" interface for the Account microservice */
public interface AccountClient {
	@GET
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.getAccounts")
    public List<Account> getAccounts(@QueryParam("page") @DefaultValue("1") int pageNumber, @QueryParam("pageSize") @DefaultValue("10") int pageSize, @QueryParam("owners") List<String> owners);

	@GET
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.getAccount")
	public Account getAccount(@PathParam("id") String id, @QueryParam("total") double total);

	@POST
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.createAccount")
	public Account createAccount(@PathParam("id") String id);

	@PUT
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.updateAccount")
	public Account updateAccount(@PathParam("id") String id, @QueryParam("total") double total);

	@DELETE
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.deleteAccount")
	public Account deleteAccount(@PathParam("id") String id);

	@POST
	@Path("/{id}/feedback")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@WithSpan(kind = SpanKind.CLIENT, value="AccountClient.submitFeedback")
	public Feedback submitFeedback(@PathParam("id") String id, WatsonInput input);
}
