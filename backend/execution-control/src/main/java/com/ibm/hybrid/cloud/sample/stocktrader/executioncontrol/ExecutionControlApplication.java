/*
       Copyright 2020-2021 IBM Corp, All Rights Reserved
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

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol;

//CDI 4.0
import jakarta.enterprise.context.ApplicationScoped;

//mpJWT 2.1
import org.eclipse.microprofile.auth.LoginConfig;

//mpOpenAPI 4.1
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.info.License;

//Jakarta REST 3.1
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

//MP-JWT is declared here rather than in web.xml's login-config, the choice the estate's own
//descriptors record; web.xml is left to carry only the per-method role constraints.
//The OpenAPI info block is declared because the generated one is not identifying: mpOpenAPI titles
//an unannotated application "Generated API" at version 1.0, which names neither this service nor
//the fact that everything it reports is simulated - the one thing a consumer of this contract has
//to know before acting on a fill or an exception.
/** JAX-RS application root of the simulated institutional execution and post-trade control service. */
@ApplicationPath("/")
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
@OpenAPIDefinition(info = @Info(title = "StockTrader Execution Control",
		version = "1.0-SNAPSHOT",
		description = "Simulated institutional order handling and post-trade settlement-exception control. "
				+ "Every order, execution, settlement exception and audit event this API returns is simulated "
				+ "on synthetic reference data: no order reaches a venue, no instruction reaches a custodian, "
				+ "and nothing settles. Responses carry simulated = true, reference data carries "
				+ "synthetic = true, and every refusal carries an ErrorResponse body.",
		license = @License(name = "Apache 2.0", url = "http://www.apache.org/licenses/LICENSE-2.0")))
@ApplicationScoped
public class ExecutionControlApplication extends Application {
}
