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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Money in this service is fixed-point because it was fixed-point in the program being replaced: WS-CALC is
// PIC 9(7)V99 and its COMPUTE carries no ROUNDED [backend/cash-account-cobol/COBOL/CASH00.cbl:L17, L222], so a
// migrated balance is exact to the cent rather than approximate. JSON is the one layer that can quietly undo that
// in both directions, which is why both directions are pinned here. Inbound, an untyped floating-point literal
// binds to Double unless told otherwise, and a balance that has been through a binary double can no longer
// reconcile against COBOL packed decimal at the penny. Outbound, a BigDecimal is free to render in scientific
// notation, whereas the retail contract is the response text itself: broker's client declares balance as a double
// [backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/json/CashAccount.java:L23], so the
// typed value a caller sees proves nothing about our precision and contract/RetailContractIT instead captures the
// raw body and asserts the literal "balance":1234.56. Plain rendering is therefore a contract, not a preference.
//
// These two settings are also declared as spring.jackson.* in application.yml, and that duplication is deliberate:
// the YAML keeps that file the whole self-describing configuration, while this class is the programmatic
// enforcement the monetary-precision requirement names, so the guarantee outlives an edit to either one. They
// enable the same two features and must never be made to disagree - do not "clean up" one of them.
/** Jackson configuration that keeps monetary values exact on the way in and plainly rendered on the way out. */
@Configuration
public class JacksonConfig {

    // Customizing the auto-configured builder rather than declaring an ObjectMapper bean of our own. A hand-built
    // mapper would drop every module Spring Boot registers, JavaTimeModule among them, and the two renderers that
    // Spring Security invokes before any controller runs - error/ApiErrorAuthenticationEntryPoint and
    // error/ApiErrorAccessDeniedHandler - inject that same ObjectMapper bean to write an ApiError whose java.time
    // timestamp would then fail to serialize, turning the 401 and 403 bodies asserted by security/RoleEnforcementIT
    // into serialization errors. A customizer adds these features and changes nothing else.
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalMoneyCustomizer() {
        return builder -> builder.featuresToEnable(
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
