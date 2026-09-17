/*
       Copyright 2024 Kyndryl Corp, All Rights Reserved

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

package com.kyndryl.cjot;

import com.kyndryl.cjot.stocktrader.rest.PortfolioAssistantResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.quarkus.test.security.jwt.Claim;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
@TestHTTPEndpoint(PortfolioAssistantResource.class)
public class PortfolioAssistantRESTServiceTest extends AbstractIntegrationTest {

    @Test
    @TestSecurity(user="stock", roles={"StockTrader"})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "subject"),
            @Claim(key = "email", value = "user@gmail.com"),
            @Claim(key = "iss", value = "http://stock-trader.ibm.com"),
            @Claim(key = "aud", value = "stock-trader")
    })
    void testGetEndpoint() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(is("Welcome to Portfolio Assistant."));
    }
}
