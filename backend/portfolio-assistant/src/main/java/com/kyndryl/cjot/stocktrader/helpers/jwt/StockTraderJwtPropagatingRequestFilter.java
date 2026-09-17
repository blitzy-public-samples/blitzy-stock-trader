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

package com.kyndryl.cjot.stocktrader.helpers.jwt;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import lombok.extern.java.Log;

import java.util.logging.Level;


@Priority(Priorities.AUTHENTICATION) // Important for auth filters
@ApplicationScoped
@Log
public class StockTraderJwtPropagatingRequestFilter implements ClientRequestFilter {
    @Inject
    JwtContextHolder jwtContextHolder;

    @Inject
    JWTParser jwtParser;

    @Override
    public void filter(ClientRequestContext requestContext) {
        log.finest("Here is the original outgoing request headers: " + requestContext.getHeaders().get("Authorization"));
        log.finest(requestContext.getUri().toString());
        String jwt = jwtContextHolder.getToken();

        if (log.getLevel() == Level.FINEST) {
            try {
                log.finest("StockTraderJwtPropagatingRequestFilter UserName is: " + (jwt != null ? jwtParser.parse(jwt).getName() : "null"));
            } catch (ParseException e) {
                log.severe("Invalid JWT in request filter: " + e.getMessage());
            }
        }

        if (jwt != null && !jwt.isBlank()) {
            requestContext.getHeaders().add("Authorization", "Bearer " + jwt);
        }
        log.finest("Here is the new outgoing request headers: " + requestContext.getHeaders().get("Authorization"));
    }
}
