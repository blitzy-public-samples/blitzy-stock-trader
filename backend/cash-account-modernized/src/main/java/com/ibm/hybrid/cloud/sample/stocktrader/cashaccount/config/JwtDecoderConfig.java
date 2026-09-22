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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

// This service introduces no identity mechanism of its own; it verifies the token the estate already issues. Broker
// forwards the caller's Authorization header to us verbatim - its client declares
// org.eclipse.microprofile.rest.client.propagateHeaders=Authorization,Proxy-Authorization
// [backend/broker/src/main/resources/META-INF/microprofile-config.properties:L1] - so the credential arriving here is
// the same RS256 JWT that the trader minted and that broker itself validates: signed with the shared jwtSigner alias
// and checked against the same issuer and audience [backend/broker/src/main/liberty/config/includes/basic.xml:L39-L40;
// backend/broker/src/main/liberty/config/server.xml:L40-L42]. Re-authenticating the caller, or accepting a different
// credential shape, would make cutover a change to every caller instead of a change to deployment values.
//
// The four modes are not an invention either: the siblings select their security configuration by including
// includes/${AUTH_TYPE}.xml [backend/broker/src/main/liberty/config/server.xml:L44], and the include files that exist
// are basic, ldap, oidc and none - which is exactly why those four values are supported and a fifth fails start-up.
// basic and ldap share one branch here because they differ only in the user registry Liberty consults when ISSUING a
// token; the token this service verifies is identical in both, so the key material is identical too.
/** Verifies the estate's existing RS256 JWT and turns its {@code groups} claim into Spring Security authorities. */
@Configuration
public class JwtDecoderConfig {

    // MicroProfile JWT sources roles from "groups" and identifies the caller by "upn"; the estate's tokens are minted
    // by Liberty's jwtBuilder and consumed by mpJwt, so these two claim names are given, not chosen here.
    private static final String GROUPS_CLAIM = "groups";

    private static final String UPN_CLAIM = "upn";

    private static final String ROLE_PREFIX = "ROLE_";

    private static final String STOCK_TRADER_AUTHORITY = ROLE_PREFIX + "StockTrader";

    private static final String AUTH_TYPE_PROPERTY = "cashaccount.security.auth-type";

    private static final String JWKS_URL_PROPERTY = "cashaccount.security.jwt.jwks-url";

    private static final String PUBLIC_KEY_LOCATION_PROPERTY = "cashaccount.security.jwt.public-key-location";

    private static final String MODE_BASIC = "basic";

    private static final String MODE_LDAP = "ldap";

    private static final String MODE_OIDC = "oidc";

    private static final String CERTIFICATE_PEM_HEADER = "-----BEGIN CERTIFICATE-----";

    private static final String PUBLIC_KEY_PEM_HEADER = "-----BEGIN PUBLIC KEY-----";

    private static final String PUBLIC_KEY_PEM_FOOTER = "-----END PUBLIC KEY-----";

    // Conditional rather than a bean that returns null, because auth-type=none means this service authenticates
    // nothing at all and config/SecurityConfig therefore resolves the decoder through an optional dependency and
    // configures no resource server in that mode. A null-returning @Bean would leave Spring with a bean definition it
    // cannot satisfy; an absent definition is what SecurityConfig's optional lookup expects, so the two files must
    // keep agreeing on this mechanism. @ConditionalOnProperty offers no not-equals, hence the expression; the
    // :basic default mirrors application.yml and broker's own variable default so an unset AUTH_TYPE still
    // authenticates. The converter bean below stays unconditional because SecurityConfig needs it in every mode.
    @Bean
    @ConditionalOnExpression("!'none'.equalsIgnoreCase('${" + AUTH_TYPE_PROPERTY + ":basic}'.trim())")
    public JwtDecoder jwtDecoder(CashAccountProperties properties, ResourceLoader resourceLoader) {
        CashAccountProperties.Security security = properties.getSecurity();
        CashAccountProperties.Jwt jwt = security.getJwt();
        String authType = security.getAuthType();
        String mode = (authType == null) ? "" : authType.toLowerCase(Locale.ROOT);

        // Key material is read here, once, while the context starts: a per-request read would put file or network
        // I/O on the path of every authenticated call and would let a mid-flight edit change who can be trusted.
        NimbusJwtDecoder decoder = switch (mode) {
            case MODE_BASIC, MODE_LDAP -> NimbusJwtDecoder
                    .withPublicKey(signerPublicKey(resourceLoader, jwt.getPublicKeyLocation()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            case MODE_OIDC -> NimbusJwtDecoder
                    .withJwkSetUri(requiredJwksUrl(jwt.getJwksUrl()))
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            default -> throw new IllegalStateException("Unsupported " + AUTH_TYPE_PROPERTY + " (AUTH_TYPE) '"
                    + authType + "'. Supported values are 'basic', 'ldap', 'oidc' and 'none'; in 'none' mode this "
                    + "bean is omitted rather than built, so reaching this branch with 'none' means the condition "
                    + "guarding it was bypassed. Start-up fails rather than fall through, because a mode this "
                    + "service cannot verify must never resolve to accepting unverified tokens.");
        };

        decoder.setJwtValidator(tokenValidator(jwt.getIssuer(), jwt.getAudience()));
        decoder.setClaimSetConverter(callerIdentityClaimSetConverter());
        return decoder;
    }

    // Maps the groups claim to ROLE_-prefixed authorities, which is what makes hasRole("StockTrader") and
    // hasAnyRole("StockViewer", "StockTrader") in config/SecurityConfig decide the same way broker's web.xml split
    // decides today [backend/broker/src/main/webapp/WEB-INF/web.xml:L11-L18].
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(CashAccountProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(UPN_CLAIM);
        // The flag is read on every conversion rather than captured once, so the value stays the bound one: a
        // deployment - and security/RoleEnforcementIT's strict-mode context - selects it through configuration, and
        // nothing about it may become a compile-time or class-load-time constant.
        converter.setJwtGrantedAuthoritiesConverter(
                token -> authorities(token, properties.getSecurity().isAllAuthenticatedHoldStocktrader()));
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt token, boolean allAuthenticatedHoldStockTrader) {
        // A set, because the parity grant below and an explicit StockTrader group would otherwise both be added.
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        // Tolerant by intent: a token is a third party's output. A JSON array is the shape the estate mints, a bare
        // string is legal JSON for a single group, and a caller with no groups at all is authenticated with no roles
        // - which the rules then reject with 403 rather than a 500 raised while reading the claim.
        Object groups = token.getClaim(GROUPS_CLAIM);
        if (groups instanceof Collection<?> values) {
            for (Object value : values) {
                addRole(authorities, value);
            }
        } else if (groups != null) {
            addRole(authorities, groups);
        }

        // Parity, not laxity. The siblings bind the StockTrader role to the ALL_AUTHENTICATED_USERS special subject
        // <security-role id="StockTrader"><special-subject type="ALL_AUTHENTICATED_USERS" id="IBMid"/></security-role>
        // [backend/broker/src/main/liberty/config/server.xml:L56-L60], so any authenticated caller can write through
        // broker today. Granting the same here by default means cutover changes no caller's effective permissions and
        // the GET-versus-write split stays latent rather than absent; setting the property false is the supported
        // strict mode in which only the token's groups decide.
        if (allAuthenticatedHoldStockTrader) {
            authorities.add(new SimpleGrantedAuthority(STOCK_TRADER_AUTHORITY));
        }
        return authorities;
    }

    private static void addRole(Set<GrantedAuthority> authorities, Object group) {
        if (group == null) {
            return;
        }
        String name = group.toString().trim();
        if (!name.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + name));
        }
    }

    private static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        // The audience check is written out because Spring installs none: JwtValidators.createDefault() covers exp
        // and nbf only, and an issuer match alone would accept a token this estate minted for a different service -
        // every StockTrader token shares one signer and one issuer, so the audience is the only thing distinguishing
        // them. Omitting it would make any such token a valid credential here.
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtIssuerValidator(issuer),
                new RequiredAudienceValidator(audience));
    }

    // MicroProfile JWT identifies the caller by upn and falls back to sub when upn is absent, but
    // JwtAuthenticationConverter derives the principal name from exactly one claim and its convert method is final,
    // so the fallback cannot live in the converter. Defaulting upn from sub as the claim set is converted - the
    // decoder's own documented extension point, wrapping the same MappedJwtClaimSetConverter defaults it would use
    // anyway - gives "upn when present, else sub" for every authentication without reimplementing the converter.
    private static Converter<Map<String, Object>, Map<String, Object>> callerIdentityClaimSetConverter() {
        Converter<Map<String, Object>, Map<String, Object>> defaults =
                MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());
        return claims -> {
            Map<String, Object> converted = new LinkedHashMap<>(defaults.convert(claims));
            Object upn = converted.get(UPN_CLAIM);
            Object subject = converted.get(JwtClaimNames.SUB);
            if ((upn == null || upn.toString().isBlank()) && subject != null) {
                converted.put(UPN_CLAIM, subject.toString());
            }
            return converted;
        };
    }

    private static String requiredJwksUrl(String jwksUrl) {
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException(JWKS_URL_PROPERTY + " (OIDC_JWKS_URL) must be set when "
                    + AUTH_TYPE_PROPERTY + " is 'oidc', because the JWKS endpoint is the only verification key "
                    + "source in that mode, mirroring mpJwt jwksUri "
                    + "[backend/broker/src/main/liberty/config/includes/oidc.xml:L16-L21]. The chart marks the "
                    + "variable optional, so its absence is a real configuration state and start-up must fail "
                    + "rather than run without a way to verify signatures.");
        }
        return jwksUrl;
    }

    // Only the public half of the signing key is ever read, and only from this property. The shared trust store the
    // siblings mount carries the jwtSigner entry complete with its private key and is copied into every sibling
    // image - a defect this module does not repeat, so nothing here reads a key store, its credentials, or any
    // signing key material. Keeping the location a property is also what lets tests point it at an ephemeral,
    // per-JVM key that is never checked in.
    private static RSAPublicKey signerPublicKey(ResourceLoader resourceLoader, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " must name the signer's public "
                    + "certificate or public key; it was blank, and there is no default this service may invent.");
        }

        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location
                    + "' does not resolve to an existing resource. Accepted forms are 'classpath:<path>', "
                    + "'file:<absolute path>' and a plain classpath-relative path.");
        }

        String pem;
        try (InputStream stream = resource.getInputStream()) {
            pem = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException exception) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location
                    + "' could not be read.", exception);
        }

        // Two shapes, each handled explicitly rather than guessed at: the certificate this module ships is exported
        // from the estate's trust store with keytool -exportcert -rfc and is therefore an X.509 certificate PEM,
        // while an ephemeral test key is written as a SubjectPublicKeyInfo PEM. Anything else fails here.
        PublicKey publicKey;
        if (pem.contains(CERTIFICATE_PEM_HEADER)) {
            publicKey = certificatePublicKey(pem, location);
        } else if (pem.contains(PUBLIC_KEY_PEM_HEADER)) {
            publicKey = subjectPublicKeyInfo(pem, location);
        } else {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location + "' is neither an X.509 "
                    + "certificate PEM (" + CERTIFICATE_PEM_HEADER + ") nor a public key PEM ("
                    + PUBLIC_KEY_PEM_HEADER + ").");
        }

        if (!(publicKey instanceof RSAPublicKey rsaPublicKey)) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location + "' carries a "
                    + publicKey.getAlgorithm() + " key, but the estate signs its tokens RS256, which requires an "
                    + "RSA key.");
        }
        return rsaPublicKey;
    }

    private static PublicKey certificatePublicKey(String pem, String location) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Certificate certificate =
                    factory.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            return certificate.getPublicKey();
        } catch (CertificateException exception) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location
                    + "' is not a readable X.509 certificate.", exception);
        }
    }

    private static PublicKey subjectPublicKeyInfo(String pem, String location) {
        String body = pem.replace(PUBLIC_KEY_PEM_HEADER, "")
                .replace(PUBLIC_KEY_PEM_FOOTER, "")
                .replaceAll("\\s", "");
        try {
            byte[] encoded = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException(PUBLIC_KEY_LOCATION_PROPERTY + " '" + location
                    + "' is not a readable RSA public key PEM.", exception);
        }
    }

    /** Rejects a token whose {@code aud} claim omits the audience this service was configured for. */
    private static final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String audience;

        private RequiredAudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audiences = token.getAudience();
            if (audiences != null && audiences.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            // Names the failure class and the expected audience - both configuration, not credentials. Neither the
            // token nor any claim of it is reproduced here or logged anywhere in this file.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                    "The required audience '" + audience + "' is absent from the token's aud claim.", null));
        }
    }
}
