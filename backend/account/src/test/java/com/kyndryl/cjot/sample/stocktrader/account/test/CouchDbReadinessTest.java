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

import com.kyndryl.cjot.sample.stocktrader.account.test.couchdb.CouchDBTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;


/***
 * Test the healthcheck code is testing CouchDB and that the healthcheck passes
 */
@QuarkusTest
@WithTestResource(value = CouchDBTestResource.class, parallel = true)
public class CouchDbReadinessTest {

    @Test
    public void testCouchDBReadyEndpointUp() throws Exception {
        given().
                accept(ContentType.JSON)
                .when()
                .get("/q/health/ready")
                .then()
//                .log().all()
                .statusCode(HttpStatus.SC_OK);
    }
}
