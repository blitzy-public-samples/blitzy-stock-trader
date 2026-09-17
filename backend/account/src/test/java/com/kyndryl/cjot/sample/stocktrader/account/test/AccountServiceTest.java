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
import com.kyndryl.cjot.sample.stocktrader.account.test.couchdb.CouchDBTestResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
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

import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;


/**
 * These test cases only interact with CouchDB. They do not put any messages on any MQ Topics
 */
@QuarkusTest
@WithTestResource(value = CouchDBTestResource.class, parallel = true)
@TestHTTPEndpoint(AccountService.class)
public class AccountServiceTest extends AbstractIntegrationTest {

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
    public void testGetAllAccountsEndpoint() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);

        List<Account> persistedAccounts =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().get("/")
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .body("$", hasSize(3))  // Check we got 3 results
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Sort the lists
        Collections.sort(accounts, Comparator.comparing(Account::getOwner));
        Collections.sort(persistedAccounts, Comparator.comparing(Account::getOwner));

        // Verify everything that we created was returned.
        Assertions.assertIterableEquals(accounts, persistedAccounts);
    }

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
    public void testGetOneAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                account);

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().get("/" + account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account, persistedAccount);

    }

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
    public void testCreateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().post("/" + account.getOwner())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getOwner(), persistedAccount.getOwner());
        Assertions.assertEquals(account.getLoyalty(), persistedAccount.getLoyalty());
        Assertions.assertEquals(account.getBalance(), persistedAccount.getBalance());
        Assertions.assertEquals(account.getCommissions(), persistedAccount.getCommissions());
        Assertions.assertEquals(account.getNextCommission(), persistedAccount.getNextCommission());
        Assertions.assertEquals(account.getFree(), persistedAccount.getFree());
        Assertions.assertEquals(account.getSentiment(), persistedAccount.getSentiment());
        Assertions.assertEquals(account.getOperation(), persistedAccount.getOperation());

    }

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
    public void testCreateDuplicateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account persistedAccount1 =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().post("/" + account.getOwner())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Make a repeat request to create the same account again
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().post("/" + account.getOwner())
                .then()
//                        .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204 <- Not created
    }

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
    public void testBasicUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 5000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/" + account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getId(), persistedAccount.getId());
        Assertions.assertEquals("Basic", persistedAccount.getLoyalty());
        Assertions.assertEquals(9.99, persistedAccount.getNextCommission());
        Assertions.assertEquals(9.99, persistedAccount.getCommissions());
        Assertions.assertEquals(40.01, persistedAccount.getBalance());
    }

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
    public void testBronzeUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 20_000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/" + account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getId(), persistedAccount.getId());
        Assertions.assertEquals("Bronze", persistedAccount.getLoyalty());
        Assertions.assertEquals(8.99, persistedAccount.getNextCommission());
        Assertions.assertEquals(8.99, persistedAccount.getCommissions());
        Assertions.assertEquals(41.01, persistedAccount.getBalance());
    }

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
    public void testSilverUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 60_000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/" + account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getId(), persistedAccount.getId());
        Assertions.assertEquals("Silver", persistedAccount.getLoyalty());
        Assertions.assertEquals(7.99, persistedAccount.getNextCommission());
        Assertions.assertEquals(7.99, persistedAccount.getCommissions());
        Assertions.assertEquals(42.01, persistedAccount.getBalance());
    }

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
    public void testGoldUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 110_000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/" + account.getId())
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Verify everything that we created was returned.
        Assertions.assertEquals(account.getId(), persistedAccount.getId());
        Assertions.assertEquals("Gold", persistedAccount.getLoyalty());
        Assertions.assertEquals(6.99, persistedAccount.getNextCommission());
        Assertions.assertEquals(6.99, persistedAccount.getCommissions());
        Assertions.assertEquals(43.01, persistedAccount.getBalance());
    }

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
    public void noJMS_testPlatinumUpdateAccountEndpoint() {

        Account account = new Account(faker.name().fullName());

        Account couchDbAccount = accountRepository.save(account);

        double total = 1_100_000;

        Account persistedAccount =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .queryParam("total", total)
                        .when().put("/" + account.getId())
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
    public void idInDB_testDeleteAccountEndpoint() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);

        Account accountToDelete = accounts.getFirst();
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().delete("/" + accountToDelete.getId())
                .then()
//                        .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_OK) // Check we got a 200
                .and()
                .extract().as(new TypeRef<>() {
                }); // return the values back

        List<Account> remainingAccounts =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().get("/")
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .body("$", hasSize(2))  // Check we got 2 results
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Sort the lists
        Collections.sort(accounts, Comparator.comparing(Account::getOwner));
        Collections.sort(remainingAccounts, Comparator.comparing(Account::getOwner));

        List<Account> differences = new ArrayList<>(accounts);
        differences.removeAll(remainingAccounts);

        // Verify everything that we deleted was returned.
        Assertions.assertEquals(1, differences.size());
        Assertions.assertSame(accountToDelete, differences.getFirst());
    }

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
    public void idNotInDB_testDeleteAccountEndpoint() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);

        Account accountToDelete = accounts.getFirst();
        given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().delete("/" + "abc123")
                .then()
//                        .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_NO_CONTENT); // Check we got a 204, not found

        List<Account> remainingAccounts =
                given()
//                        .log().all() // Log all parts of the request
                        .accept(ContentType.JSON)
                        .when().get()
                        .then()
//                        .log().all() // Log all parts of the response
                        .statusCode(HttpStatus.SC_OK) // Check we got a 200
                        .body("$", hasSize(3))  // Check we got 3 results
                        .and()
                        .extract().as(new TypeRef<>() {
                        }); // return the values back

        // Sort the lists
        Collections.sort(accounts, Comparator.comparing(Account::getOwner));
        Collections.sort(remainingAccounts, Comparator.comparing(Account::getOwner));

        Assertions.assertIterableEquals(accounts, remainingAccounts);
    }


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
    public void testGetAllByPage() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);
        var pageOfAccounts = given()
//                        .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("page", 1)
                .queryParam("pageSize", 5)
                .when().get()
                .then()
                .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_OK) // Check we got a 200
                .body("$", hasSize(5))  // Check we got 5 results
                .and()
                .extract().as(new TypeRef<>() {
                }); // return the values back
        //TODO check we got the right items
    }

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
    public void testGetByOwners() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        Iterable<Account> savedAccounts = accountRepository.saveAll(accounts);

        //accounts.forEach(accountRepository::save);
        savedAccounts.forEach(System.out::println);
        List<Account> pageOfAccounts = given()
//                .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("owners", Arrays.asList(accounts.get(2).getOwner(), accounts.get(6).getOwner()))
                .when().get()
                .then()
//                .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_OK) // Check we got a 200
                .body("$", hasSize(2))  // Check we got 5 results
                .and()
                .extract().as(new TypeRef<>() {
                }); // return the values back

        Assertions.assertTrue(pageOfAccounts.contains(accounts.get(2)));
        Assertions.assertTrue(pageOfAccounts.contains(accounts.get(6)));

    }

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
    public void testGetPage1OfOwners() {

        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        List<Account> savedAccounts = new ArrayList<>();
        accountRepository.saveAll(accounts).forEach(savedAccounts::add);
        savedAccounts.sort(Comparator.comparing(Account::getOwner));
        savedAccounts.forEach(System.out::println);

        //accounts.forEach(accountRepository::save);
        //savedAccounts.forEach(System.out::println);
        List<Account> pageOfAccounts = given()
//                .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .when().get()
                .then()
//                .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_OK) // Check we got a 200
                .body("$", hasSize(10))  // Check we got 10 results (default page size)
                .and()
                .extract().as(new TypeRef<>() {
                }); // return the values back

        Assertions.assertTrue(pageOfAccounts.get(2).equals(savedAccounts.get(2)));
        Assertions.assertTrue(pageOfAccounts.get(6).equals(savedAccounts.get(6)));
    }

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
    public void testGetPage2OfOwners() {
        List<Account> accounts = Arrays.asList(
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()),
                new Account(faker.name().fullName()));

        List<Account> savedAccounts = new ArrayList<>();
        accountRepository.saveAll(accounts).forEach(savedAccounts::add);
        savedAccounts.sort(Comparator.comparing(Account::getOwner));
        savedAccounts.forEach(System.out::println);

        //accounts.forEach(accountRepository::save);
        //savedAccounts.forEach(System.out::println);
        List<Account> pageOfAccounts = given()
//                .log().all() // Log all parts of the request
                .accept(ContentType.JSON)
                .queryParam("page", 2)
                .queryParam("pageSize", 10)
                .when().get()
                .then()
//                .log().all() // Log all parts of the response
                .statusCode(HttpStatus.SC_OK) // Check we got a 200
                .body("$", hasSize(2))  // Check we got 2 results
                .and()
                .extract().as(new TypeRef<>() {
                }); // return the values back

        Assertions.assertTrue(pageOfAccounts.get(0).equals(savedAccounts.get(10)));
        Assertions.assertTrue(pageOfAccounts.get(1).equals(savedAccounts.get(11)));
    }
}
