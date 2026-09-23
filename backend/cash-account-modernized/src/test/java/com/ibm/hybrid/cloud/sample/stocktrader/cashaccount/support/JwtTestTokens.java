package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Mints the RS256 bearer tokens the module's secured tests carry, signed by an ephemeral per-JVM key pair. */
public final class JwtTestTokens {

    /** Token issuer; {@code JWT_ISSUER} default in backend/broker/src/main/liberty/config/server.xml:L42. */
    public static final String ISSUER = "http://stock-trader.ibm.com";

    /** Token audience; {@code JWT_AUDIENCE} default in backend/broker/src/main/liberty/config/server.xml:L41. */
    public static final String AUDIENCE = "stock-trader";

    /** Write role; group in backend/broker/src/main/liberty/config/includes/none.xml:L39. */
    public static final String GROUP_STOCK_TRADER = "StockTrader";

    /** Read-only role; group in backend/broker/src/main/liberty/config/includes/none.xml:L44. */
    public static final String GROUP_STOCK_VIEWER = "StockViewer";

    /** StockTrader member {@code stock} (none.xml:L40). */
    public static final String USER_STOCK_TRADER = "stock";

    /** StockTrader member {@code debug} (none.xml:L41). */
    public static final String USER_DEBUG = "debug";

    /** StockTrader member {@code john.alcorn@kyndryl.com} (none.xml:L42). */
    public static final String USER_KYNDRYL = "john.alcorn@kyndryl.com";

    /** StockViewer member {@code read} (none.xml:L45). */
    public static final String USER_STOCK_VIEWER = "read";

    /** Registry user {@code other}, a member of no group (none.xml:L37). */
    public static final String USER_UNPRIVILEGED = "other";

    // Claim names given by MicroProfile JWT and read as such by config/JwtDecoderConfig, never chosen here.
    private static final String UPN_CLAIM = "upn";

    private static final String GROUPS_CLAIM = "groups";

    // The deployed expiry="12h" [backend/broker/src/main/liberty/config/includes/basic.xml:L40;
    // frontend/trader/src/main/liberty/config/includes/basic.xml:L24], rather than a shorter window that could
    // expire mid-suite.
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    // Far outside the 60-second clock skew Spring's default timestamp validator allows, so the rejection of an
    // expired mint is unambiguous rather than marginal.
    private static final Duration EXPIRED_AGE = Duration.ofHours(1);

    // Deliberately not production's CN=Stock Trader, so an ephemeral artefact can never be read as the real signer.
    private static final String SIGNER_COMMON_NAME = "Stock Trader Test Signer";

    private static final Duration CERTIFICATE_LIFETIME = Duration.ofDays(1);

    // Backdates the certificate so a host whose clock trails the one that generated the key still sees it as valid.
    private static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofMinutes(5);

    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int TAG_INTEGER = 0x02;

    private static final int TAG_BIT_STRING = 0x03;

    private static final int TAG_UTF8_STRING = 0x0C;

    private static final int TAG_UTC_TIME = 0x17;

    private static final int TAG_SEQUENCE = 0x30;

    private static final int TAG_SET = 0x31;

    private static final int TAG_CONTEXT_CONSTRUCTED = 0xA0;

    /** {@code AlgorithmIdentifier} OID 1.2.840.113549.1.1.11, sha256WithRSAEncryption. */
    private static final byte[] OID_SHA256_WITH_RSA = {
        0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B
    };

    /** Attribute type OID 2.5.4.3, commonName. */
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};

    /** The absent-parameters encoding sha256WithRSAEncryption requires. */
    private static final byte[] DER_NULL = {0x05, 0x00};

    // Generated inside the test JVM, its private half never leaving memory, rather than read from the estate's
    // shared Liberty trust store whose jwtSigner entry carries a private key: no key material is checked in. One
    // pair for the whole JVM because several *IT classes bind cashaccount.security.jwt.public-key-location in
    // separate Spring contexts, and a per-context key would leave tokens minted in one untrusted in the next.
    private static final KeyPair KEY_PAIR = generateKeyPair();

    // Published as an X.509 certificate, the shape config/JwtDecoderConfig loads from
    // src/main/resources/security/jwtsigner.pem, so the tests exercise the deployed certificate-parsing path
    // rather than a second branch only tests take.
    private static final Path CERTIFICATE_PEM = writeCertificatePem(KEY_PAIR);

    private JwtTestTokens() {
    }

    /**
     * Spring-resolvable location of the ephemeral signer certificate, for
     * {@code registry.add("cashaccount.security.jwt.public-key-location", JwtTestTokens::publicKeyLocation)}.
     *
     * @return a {@code file:} URL naming the certificate this JVM published
     */
    public static String publicKeyLocation() {
        // toUri() rather than "file:" + path: it escapes spaces and normalises separators, which a concatenated
        // Windows path would not, and Spring's ResourceLoader accepts the resulting file: URL unchanged.
        return CERTIFICATE_PEM.toUri().toString();
    }

    public static String tokenFor(String upn, String... groups) {
        Instant issuedAt = Instant.now();
        return mint(upn, groups, issuedAt, issuedAt.plus(TOKEN_LIFETIME));
    }

    public static String stockTraderToken() {
        return tokenFor(USER_STOCK_TRADER, GROUP_STOCK_TRADER);
    }

    public static String stockViewerToken() {
        return tokenFor(USER_STOCK_VIEWER, GROUP_STOCK_VIEWER);
    }

    // Signed and structurally valid, with only exp in the past, so the expiry validator is the one check it fails.
    public static String expiredTokenFor(String upn, String... groups) {
        Instant expiresAt = Instant.now().minus(EXPIRED_AGE);
        return mint(upn, groups, expiresAt.minus(TOKEN_LIFETIME), expiresAt);
    }

    public static String bearer(String token) {
        // The shape frontend/trader/.../Utilities.java:L122 sends and broker forwards unchanged.
        return "Bearer " + token;
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    private static String mint(String upn, String[] groups, Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                // sub carries the same value as upn so the decoder's "upn when present, else sub" fallback resolves
                // to one identity whichever branch a future claim-set change takes.
                .subject(upn)
                .claim(UPN_CLAIM, upn)
                // A JSON array, never a joined string: the granted-authorities converter reads a list claim and
                // would turn "StockTrader,StockViewer" into the single bogus authority ROLE_StockTrader,StockViewer.
                .claim(GROUPS_CLAIM, groupClaim(groups))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        } catch (JOSEException exception) {
            // Unchecked throughout this class: a signing failure is unrecoverable test infrastructure, so every
            // caller is spared a try/catch it could do nothing with.
            throw new IllegalStateException("Could not sign a test token for '" + upn + "'.", exception);
        }
        return jwt.serialize();
    }

    private static List<String> groupClaim(String[] groups) {
        return (groups == null) ? List.of() : List.of(groups);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            // RSA-2048 is the estate's signer strength, and RS256 requires at least that.
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not generate the ephemeral RSA-2048 test signing key.", exception);
        }
    }

    private static Path writeCertificatePem(KeyPair keyPair) {
        byte[] der = selfSignedCertificate(keyPair);
        verifyParseable(der, (RSAPublicKey) keyPair.getPublic());

        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
        try {
            // No parent argument, so the file lands in the system temp directory and can never be mistaken for a
            // checked-in resource or dirty the repository working tree.
            Path directory = Files.createTempDirectory("cashaccount-jwt-");
            Path file = directory.resolve("jwtsigner-test.pem");
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
            restrictToOwner(file);
            // Registration order is load-bearing and must not be "tidied": File.deleteOnExit deletes in REVERSE
            // registration order, and a directory deletion fails while the directory still holds a file. The
            // directory is therefore registered first so that on exit the PEM is removed and then the emptied
            // directory, leaving no residue on a long-lived CI worker (AAP 0.7.5).
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not publish the ephemeral signer certificate.", exception);
        }
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException exception) {
            // Hardening only - the file holds public material, and Windows has no POSIX view - so an unsupported
            // file system must not fail the suite.
        }
    }

    // Parsed back through the very API config/JwtDecoderConfig uses, so a malformed-DER mistake fails legibly at
    // class load instead of surfacing while an unrelated *IT builds its Spring context; verifying the signature
    // with the certificate's own key also proves the signature bytes and the algorithm identifier agree.
    private static void verifyParseable(byte[] der, RSAPublicKey expected) {
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            certificate.verify(expected);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "The hand-assembled ephemeral signer certificate is not a valid X.509 certificate.", exception);
        }
        if (!Arrays.equals(expected.getEncoded(), certificate.getPublicKey().getEncoded())) {
            throw new IllegalStateException("The ephemeral signer certificate carries a different public key than "
                    + "the one its tokens are signed with; every verification would fail.");
        }
    }

    // The DER is assembled field by field because there is no other route: BouncyCastle is not a dependency and
    // sun.security.x509 is not exported on Java 21, so a certificate builder would mean either a new dependency or
    // an --add-exports argument, both of them pom.xml changes this file does not own.
    private static byte[] selfSignedCertificate(KeyPair keyPair) {
        Instant now = Instant.now();
        byte[] signatureAlgorithm = sequence(OID_SHA256_WITH_RSA, DER_NULL);
        // Issuer and subject are one Name: a self-signed certificate is all the decoder needs, since it trusts the
        // file it was pointed at rather than a chain.
        byte[] name = distinguishedName(SIGNER_COMMON_NAME);

        byte[] tbsCertificate = sequence(
                explicit(0, integer(BigInteger.valueOf(2))),
                integer(serialNumber()),
                signatureAlgorithm,
                name,
                sequence(utcTime(now.minus(CLOCK_SKEW_ALLOWANCE)), utcTime(now.plus(CERTIFICATE_LIFETIME))),
                name,
                // Already exactly the SubjectPublicKeyInfo DER this field wants, so it is spliced in whole.
                keyPair.getPublic().getEncoded());

        return sequence(tbsCertificate, signatureAlgorithm, bitString(sign(tbsCertificate, keyPair)));
    }

    private static byte[] sign(byte[] tbsCertificate, KeyPair keyPair) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(tbsCertificate);
            return signature.sign();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign the ephemeral signer certificate.", exception);
        }
    }

    private static BigInteger serialNumber() {
        // Random and forced positive: a negative or zero serial is malformed, and randomness keeps two JVMs from
        // publishing different certificates under one identity.
        return new BigInteger(64, RANDOM).add(BigInteger.ONE);
    }

    private static byte[] distinguishedName(String commonName) {
        return sequence(set(sequence(OID_COMMON_NAME, utf8String(commonName))));
    }

    private static byte[] utf8String(String value) {
        return tlv(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] utcTime(Instant instant) {
        return tlv(TAG_UTC_TIME, UTC_TIME.format(instant).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] integer(BigInteger value) {
        byte[] content = value.toByteArray();
        if ((content[0] & 0x80) != 0) {
            byte[] padded = new byte[content.length + 1];
            System.arraycopy(content, 0, padded, 1, content.length);
            content = padded;
        }
        return tlv(TAG_INTEGER, content);
    }

    private static byte[] bitString(byte[] bits) {
        byte[] content = new byte[bits.length + 1];
        content[0] = 0; // unused trailing bits
        System.arraycopy(bits, 0, content, 1, bits.length);
        return tlv(TAG_BIT_STRING, content);
    }

    private static byte[] sequence(byte[]... elements) {
        return tlv(TAG_SEQUENCE, concat(elements));
    }

    private static byte[] set(byte[]... elements) {
        return tlv(TAG_SET, concat(elements));
    }

    private static byte[] explicit(int number, byte[]... elements) {
        return tlv(TAG_CONTEXT_CONSTRUCTED | number, concat(elements));
    }

    private static byte[] tlv(int tag, byte[] content) {
        byte[] length = length(content.length);
        byte[] encoded = new byte[1 + length.length + content.length];
        encoded[0] = (byte) tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(content, 0, encoded, 1 + length.length, content.length);
        return encoded;
    }

    private static byte[] length(int value) {
        if (value < 0x80) {
            return new byte[] {(byte) value};
        }
        byte[] magnitude = BigInteger.valueOf(value).toByteArray();
        int offset = (magnitude[0] == 0) ? 1 : 0;
        int count = magnitude.length - offset;
        byte[] encoded = new byte[1 + count];
        encoded[0] = (byte) (0x80 | count);
        System.arraycopy(magnitude, offset, encoded, 1, count);
        return encoded;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            buffer.writeBytes(part);
        }
        return buffer.toByteArray();
    }
}
