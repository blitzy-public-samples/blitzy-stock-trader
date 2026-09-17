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
package com.kyndryl.cjot.sample.stocktrader.account.test;

import com.ibm.hybrid.cloud.sample.stocktrader.account.AccountService;
import com.ibm.hybrid.cloud.sample.stocktrader.account.json.Account;
import com.kyndryl.cjot.sample.stocktrader.account.test.amqp.AMQPTestResource;
import com.kyndryl.cjot.sample.stocktrader.account.test.couchdb.CouchDBTestResource;
import com.kyndryl.cjot.sample.stocktrader.account.test.jms.JmsTestProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ConfigMetadata;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.quarkus.test.security.oidc.UserInfo;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * These test cases interact with CouchDB and AMQP. They do put messages on a messaging provider
 */
@QuarkusTest
@QuarkusTestResource(value = CouchDBTestResource.class, restrictToAnnotatedClass = true, parallel = true)
@QuarkusTestResource(value = AMQPTestResource.class, restrictToAnnotatedClass = true, parallel = true)
@TestHTTPEndpoint(AccountService.class)
@TestProfile(JmsTestProfile.class)
public class JmsServiceTest extends AbstractIntegrationTest {
    @Test
    // Set up the JWT/Security items
    @TestSecurity(user = "stock", roles = "StockTrader")
    @OidcSecurity(claims = {
            @Claim(key = "email", value = "user@gmail.com")
    }, userinfo = {
            @UserInfo(key = "sub", value = "subject")
    }, config = {
            @ConfigMetadata(key = "issuer", value = "http://stock-trader.ibm.com"),
            @ConfigMetadata(key = "audience", value = "stock-trader")
    })
    public void jms_testPlatinumUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 1_100_000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/"+account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getId(), persistedAccount.getId());
        Assertions.assertEquals("Platinum", persistedAccount.getLoyalty());
        Assertions.assertEquals(5.99, persistedAccount.getNextCommission());
        Assertions.assertEquals(5.99, persistedAccount.getCommissions());
        Assertions.assertEquals(44.01, persistedAccount.getBalance());
    }
}
