/*
       Copyright 2020 IBM Corp All Rights Reserved
       Copyright 2023-2024 Kyndryl Corp, All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.account.client;

import com.ibm.hybrid.cloud.sample.stocktrader.account.json.WatsonInput;
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.WatsonOutput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;


@Path("/")
@RegisterRestClient(configKey = "watson-client-config")
/** mpRestClient "remote" interface for the stock quote microservice */
public interface WatsonClient {
	@POST
	@Path("/")
	@Consumes("application/json")
	@Produces("application/json")
    public WatsonOutput getTone(@HeaderParam("Authorization") String basicAuth, WatsonInput input);
}
