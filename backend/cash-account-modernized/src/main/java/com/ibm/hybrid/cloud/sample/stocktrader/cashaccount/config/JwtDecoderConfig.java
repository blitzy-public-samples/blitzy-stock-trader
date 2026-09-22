package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotatedTypeMetadata;
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

    // Conditional rather than a bean returning null: config/SecurityConfig resolves the decoder through an optional
    // dependency and configures no resource server in none mode, so an absent definition is what its lookup
    // expects. The Condition below decides that as text, which nothing about SecurityConfig's ObjectProvider lookup
    // has to know; the converter bean further down stays unconditional because every authenticating mode needs it.
    @Bean
    @Conditional(AuthenticatingModeCondition.class)
    public JwtDecoder jwtDecoder(CashAccountProperties properties, ResourceLoader resourceLoader) {
        CashAccountProperties.Security security = properties.getSecurity();
        CashAccountProperties.Jwt jwt = security.getJwt();
        String authType = security.getAuthType();
        // The same normalisation the condition applies, from the same helper: one definition of "the mode" is what
        // keeps the bean's presence and the branch it takes from ever disagreeing about a value.
        String mode = normalizedMode(authType);

        // Key material is read here, once, while the context starts: a per-request read would put file or network
        // I/O on the path of every authenticated call and would let a mid-flight edit change who can be trusted.
        // basic and ldap share one branch because they differ only in the registry Liberty consults when ISSUING a
        // token [backend/broker/src/main/liberty/config/includes/basic.xml:L39-L40], leaving the key material equal.
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

    /**
     * Whether an authenticating mode is configured, and therefore whether the decoder bean above exists.
     *
     * <p>Public and taking the {@link Environment} because the read itself is the security property being
     * asserted: {@code AUTH_TYPE} arrives from a ConfigMap
     * [infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L73-L77], and
     * {@code Environment.getProperty} resolves {@code ${...}} placeholders and nothing else, so the value is
     * compared as text and is never parsed or evaluated as an expression. The predecessor of this method was
     * {@code @ConditionalOnExpression}, whose argument is an expression TEMPLATE: the property was interpolated
     * into it and the result was then evaluated, so a value closing the quote the template opened ran arbitrary
     * SpEL while conditions were still being read - ahead of every validation in this file and in
     * config/SecurityConfig. Neither inert Boot condition expresses "any mode except none" on its own
     * ({@code @ConditionalOnProperty} has no not-equals), which is why the predicate is written out here.
     *
     * <p>Deliberately does not reject an unsupported mode. A condition that threw would move the fail-closed
     * message into condition evaluation and away from the two gates that already own it - this file's
     * {@code default} branch and {@link SecurityConfig#authenticating(String)} - both of which stop start-up for
     * anything outside the four supported modes (AAP 0.6.5).
     *
     * @param environment the context's environment, read for {@code cashaccount.security.auth-type}
     * @return {@code false} only when the configured mode is {@code none}, ignoring case and surrounding space
     */
    public static boolean decoderRequired(Environment environment) {
        // MODE_BASIC as the fallback mirrors application.yml's own ${AUTH_TYPE:basic} and broker's variable default
        // [backend/broker/src/main/liberty/config/server.xml:L40], so an unset AUTH_TYPE still authenticates.
        String configured = environment.getProperty(AUTH_TYPE_PROPERTY, MODE_BASIC);
        return !SecurityConfig.MODE_NONE.equals(normalizedMode(configured));
    }

    // Trimmed and lower-cased exactly as the removed expression's .trim() and equalsIgnoreCase did, so the swap of
    // mechanism cannot change which values omit the bean; Locale.ROOT keeps that independent of the pod's locale.
    private static String normalizedMode(String authType) {
        return (authType == null) ? "" : authType.trim().toLowerCase(Locale.ROOT);
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

        // Parity, not laxity: the siblings bind StockTrader to the ALL_AUTHENTICATED_USERS special subject
        // [backend/broker/src/main/liberty/config/server.xml:L56-L60], so any authenticated caller can write
        // through broker today and granting the same by default means cutover changes no caller's effective
        // permissions. False is the supported strict mode in which only the token's groups decide.
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

    // MicroProfile JWT identifies the caller by upn and falls back to sub, but JwtAuthenticationConverter derives
    // the principal from exactly one claim and its convert method is final, so the fallback cannot live there.
    // Defaulting upn from sub while the claim set is converted is the decoder's own extension point, and wrapping
    // MappedJwtClaimSetConverter keeps the defaults it would apply anyway.
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

        // In oidc mode this endpoint is the whole trust anchor: whatever it serves decides which signatures this
        // service accepts, so whoever can answer for it can mint a StockTrader token of their own. Over cleartext
        // http that is anyone on the path between the pod and the identity provider, and nothing downstream would
        // notice - the tokens verify. Requiring https here, while the context starts, is therefore the only place
        // the guarantee can be made; Nimbus will not make it, and a pod that has already begun serving traffic
        // cannot be told to stop trusting keys it fetched. A missing host, a user-info section or a fragment are
        // refused with it: the first would leave the address ambiguous, the second puts a credential in a URL this
        // service can neither protect nor rotate, and the third is never part of a key-set address - each marks a
        // value that was not meant for this property rather than one to interpret generously.
        String candidate = jwksUrl.trim();
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException exception) {
            throw jwksRejection(syntaxFailure(exception));
        }

        // Absoluteness first: a relative or opaque value has no scheme or no hierarchical authority to inspect,
        // so every later check would read null and report the wrong reason.
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw jwksRejection("it is not an absolute hierarchical URI of the form https://host/path");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw jwksRejection("its scheme is '" + uri.getScheme() + "', not https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw jwksRejection("it names no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw jwksRejection("it carries a user-info section");
        }
        if (uri.getRawFragment() != null) {
            throw jwksRejection("it carries a fragment");
        }
        return candidate;
    }

    // The parser's own exception is deliberately neither attached as a cause nor quoted: URISyntaxException's
    // message embeds the entire input it choked on, and a start-up stack trace prints every cause, so a
    // malformed URL carrying a password would put that password in pod logs through the exception chain even
    // though the message below redacts it. Its reason and index are fixed diagnostics that never contain the
    // input, and they are all an operator needs to find the character at fault in their own configuration.
    private static String syntaxFailure(URISyntaxException exception) {
        String reason = (exception.getReason() == null) ? "malformed" : exception.getReason();
        return (exception.getIndex() < 0)
                ? "it is not a valid URI (" + reason + ")"
                : "it is not a valid URI (" + reason + " at index " + exception.getIndex() + ")";
    }

    // The rejected value is named by property, never reproduced: it may carry the very user-info this method
    // refuses, and a start-up failure is written to pod logs. The scheme is the one part quoted, because it is
    // what an operator has to change and cannot be a credential. No cause is accepted at all, so nothing holding
    // the candidate URI can be attached to the failure by a later edit.
    private static IllegalStateException jwksRejection(String reason) {
        return new IllegalStateException(JWKS_URL_PROPERTY + " (OIDC_JWKS_URL) must be an absolute https URI that "
                + "names a host and carries neither user-info nor a fragment, because in 'oidc' mode the JWKS "
                + "endpoint is the sole source of the keys this service verifies tokens with "
                + "[backend/broker/src/main/liberty/config/includes/oidc.xml:L16-L21]. The configured value is "
                + "rejected because " + reason + "; it is not echoed here, since a rejected URL can contain "
                + "credentials. Start-up fails rather than fetch verification keys over a channel an on-path "
                + "attacker can answer for - substituted keys forge tokens this service would accept, including "
                + "tokens claiming the StockTrader role.");
    }

    // Only the public half of the signing key is ever read, and only from this property: the shared trust store the
    // siblings mount carries the jwtSigner private key too, so nothing here reads a key store or its credentials.
    // Keeping the location a property is also what lets tests point it at an ephemeral, per-JVM key.
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

    /** Registers the decoder bean for every authenticating mode, omitting it only for {@code auth-type=none}. */
    static final class AuthenticatingModeCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            // Nothing is read from the annotation metadata: the decision is a property comparison, and taking no
            // input from the annotation is what keeps this condition usable - and testable - as a plain predicate.
            return decoderRequired(context.getEnvironment());
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
