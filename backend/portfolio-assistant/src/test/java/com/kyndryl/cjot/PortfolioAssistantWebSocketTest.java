package com.kyndryl.cjot;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Allows non-static @BeforeAll/@AfterAll
public class PortfolioAssistantWebSocketTest extends AbstractIntegrationTest {

    @Inject
    BasicWebSocketConnector connector;

    @TestHTTPResource
    java.net.URI httpBase; // e.g., http://localhost:8081

    private static WebSocketClientConnection connection;

    /**
     * Test the WebSocket connection by sending a message and expecting a response.
     * This test assumes that the server is set up to echo messages back.
     */
    @Test
    @TestSecurity(user="stock", roles={"StockTrader"})
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "subject"),
            @Claim(key = "email", value = "user@gmail.com"),
            @Claim(key = "iss", value = "http://stock-trader.ibm.com"),
            @Claim(key = "aud", value = "stock-trader")
    })
    void testWebSocketConnection() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        // convert http(s) -> ws(s)
        URI wsBase = toWebSocketBase(httpBase);

        WebSocketClientConnection connection = connector
                .baseUri(wsBase)
                .path("/ws/stream")
                .executionModel(BasicWebSocketConnector.ExecutionModel.NON_BLOCKING)
                .onOpen(connector ->latch.countDown())
                .connectAndAwait();

        boolean connected = latch.await(2, TimeUnit.SECONDS);
        connection.close();

        assert connected;
    }


    @AfterAll
    static void closeConnection() {
        if (connection != null) {
            connection.close();
        }
    }

}
