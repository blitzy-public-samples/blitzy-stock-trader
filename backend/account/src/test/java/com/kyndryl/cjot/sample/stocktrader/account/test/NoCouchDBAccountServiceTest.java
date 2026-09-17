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
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.ConfigMetadata;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.quarkus.test.security.oidc.UserInfo;
import io.restassured.http.ContentType;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * These test cases do not interact with CouchDB or AMQP. They do not put any messages on any MQ Topics
 * This tests exception cases
 */
@QuarkusTest
@TestHTTPEndpoint(AccountService.class)
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
public class NoCouchDBAccountServiceTest extends AbstractIntegrationTest {

    @Test
    public void testGetAllAccountsEndpoint() {
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().get("/")
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void testGetOneAccountEndpoint() {
        Account account = new Account(faker.name().fullName());
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().get("/" + account.getId())
                .then()
//                .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void testCreateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().post("/" + account.getOwner())
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void testBasicUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        double total = 5000;

        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("total", total)
                .when().put("/" + account.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void testBronzeUpdateAccountEndpoint() {
        Account account = new Account(faker.name().fullName());

        double total = 20_000;

        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("total", total)
                .when().put("/" + account.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content

    }

    @Test
    public void testSilverUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        double total = 60_000;
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("total", total)
                .when().put("/" + account.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void testGoldUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        double total = 110_000;
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("total", total)
                .when().put("/" + account.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .assertThat()
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

    @Test
    public void noJMS_testPlatinumUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        double total = 1_100_000;
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("total", total)
                .when().put("/" + account.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }


    @Test
    public void testDeleteAccountEndpoint() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Account accountToDelete = accounts.getFirst();
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().delete("/" + accountToDelete.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 - No content
    }

}
