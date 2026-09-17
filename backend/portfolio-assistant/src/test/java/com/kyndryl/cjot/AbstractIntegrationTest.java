package com.kyndryl.cjot;


import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

public class AbstractIntegrationTest {

//    // Creates fakes name/data etc.
//    static final Faker faker = new Faker();
//
//    @Inject
//    AccountRepository accountRepository;

    @AfterEach
    /**
     * Clean up the database after each test execution
     */
    void cleanUpDatabase() throws InterruptedException {
        //Clean up
//        System.out.println("Cleaning out CouchDB");
//        try {
//            accountRepository.findAll().forEach(account -> {
//                accountRepository.delete(account);
//            });
//        } catch (Exception ce) {
//            if (ce.toString().contains("CouchDBHttpClientException")) {
//                System.out.println("No connection to DB. This might be expected depending on the test.");
//            } else {
//                System.out.println("Error Cleaning DB");
//                ce.printStackTrace();
//            }
//        }
//        TimeUnit.SECONDS.sleep(2);
//        try {
//            accountRepository.findAll().forEach(account -> {
//                accountRepository.delete(account);
//            });
//        } catch (Exception ce) {
//            if (ce.toString().contains("CouchDBHttpClientException")) {
//                System.out.println("No connection to DB. This might be expected depending on the test.");
//            } else {
//                System.out.println("Error Cleaning DB");
//                ce.printStackTrace();
//            }
//        }
//        TimeUnit.SECONDS.sleep(2);
    }


    /**
     * Convert http(s)://host:port/... -> ws(s)://host:port/ (with a non-null "/" path).
     * Keeps userInfo/host/port intact; path/query/fragment are omitted since we set .path(...) separately.
     */
    public static URI toWebSocketBase(URI base) throws URISyntaxException {
        String scheme = "ws";
        if ("https".equalsIgnoreCase(base.getScheme())) {
            scheme = "wss";
        }
        return URI.create(new URI(
                scheme,
                base.getUserInfo(),
                base.getHost(),
                base.getPort(),
                "/",      // ensure non-null path to avoid mergePath NPEs
                null,
                null
        ).toString());
    }
}
