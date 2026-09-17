/*
       Copyright 2025 Kyndryl, All Rights Reserved

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

package com.kyndryl.cjot.stocktrader.websocket;

import com.kyndryl.cjot.stocktrader.assistant.PortfolioAssistant;
import com.kyndryl.cjot.stocktrader.helpers.jwt.JwtContextHolder;
import io.micrometer.core.annotation.Timed;
import io.quarkus.security.Authenticated;
import io.quarkus.websockets.next.*;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.auth.LoginConfig;
import org.eclipse.microprofile.jwt.JsonWebToken;

@WebSocket(path = "/ws/stream")
@Authenticated
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
public class PortfolioAssistantWebSocket {

    @Inject
    WebSocketConnection connection;

    @Inject
    JWTParser jwtParser;

    @Inject
    JwtContextHolder jwtHolder;

    @Inject
    PortfolioAssistant assistant;

    @Inject
    AdvisorAssistant advisorAssistant;

    private JsonWebToken jwt;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @OnOpen
    public void onOpen() {
        // We need to extract the JWT from the connection's handshake request
        // and set it in the JwtContextHolder for later use when calling downstream StockTrader services.
        var authHeader = connection.handshakeRequest().header("Authorization");
        String token = authHeader.substring("Bearer ".length());
        try {
            jwt = jwtParser.parse(token);
            if (jwt != null) {
                System.out.println("JWT Principal: " + jwt.getName());
            } else {
                System.out.println("No JWT Principal found.");
            }
            jwtHolder.setToken(token);
        } catch (ParseException e) {
            System.err.println("Invalid JWT");
            connection.close(new CloseReason(400, "Invalid JWT"));
        }
    }

    @OnTextMessage
    @RolesAllowed({"StockTrader", "StockViewer"})
    @Timed(description = "Time needed chatting to the agent.")
    public String onTextMessage(String question) {
        var result = assistant.advice(question);
        return result;
    }
}
