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

import com.github.javafaker.Faker;
import com.ibm.hybrid.cloud.sample.stocktrader.account.db.AccountRepository;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.TimeUnit;

public class AbstractIntegrationTest {

    // Creates fakes name/data etc.
    static final Faker faker = new Faker();

    @Inject
    AccountRepository accountRepository;

    @AfterEach
    /**
     * Clean up the database after each test execution
     */
    void cleanUpDatabase() throws InterruptedException {
        //Clean up
        System.out.println("Cleaning out CouchDB");
        try {
            accountRepository.findAll().forEach(account -> {
                accountRepository.delete(account);
            });
        } catch (Exception ce) {
            if (ce.toString().contains("CouchDBHttpClientException")) {
                System.out.println("No connection to DB. This might be expected depending on the test.");
            } else {
                System.out.println("Error Cleaning DB");
                ce.printStackTrace();
            }
        }
        TimeUnit.SECONDS.sleep(2);
        try {
            accountRepository.findAll().forEach(account -> {
                accountRepository.delete(account);
            });
        } catch (Exception ce) {
            if (ce.toString().contains("CouchDBHttpClientException")) {
                System.out.println("No connection to DB. This might be expected depending on the test.");
            } else {
                System.out.println("Error Cleaning DB");
                ce.printStackTrace();
            }
        }
        TimeUnit.SECONDS.sleep(2);
    }

}
