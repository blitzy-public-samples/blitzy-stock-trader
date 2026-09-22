package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Jackson configuration that keeps monetary values exact on the way in and plainly rendered on the way out. */
@Configuration
public class JacksonConfig {

    // A customizer rather than an ObjectMapper bean of our own: a hand-built mapper would drop the modules Spring
    // Boot registers, JavaTimeModule among them, and error/ApiErrorAuthenticationEntryPoint and
    // error/ApiErrorAccessDeniedHandler inject that same mapper to write an ApiError carrying a java.time timestamp.
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalMoneyCustomizer() {
        // Fixed-point money must survive JSON both ways: inbound, an untyped float literal would bind to Double and
        // a balance that has been through a binary double no longer reconciles against COBOL packed decimal at the
        // penny (WS-CALC is PIC 9(7)V99 with no ROUNDED [backend/cash-account-cobol/COBOL/CASH00.cbl:L17, L222]);
        // outbound, the retail contract is the response text itself, so scientific notation would break it.
        // application.yml enables the same two features as spring.jackson.*; the two must never be made to disagree.
        return builder -> builder.featuresToEnable(
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
