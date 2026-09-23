package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.LogSafeText;
import org.junit.jupiter.api.Test;

/** Unit tests for owner identity: trim, upper case, the 1-32 character bound and the permitted character set. */
class OwnerNormalizerTest {

    @Test
    void normalizesToOneUpperCaseFormRegardlessOfCallerCasing() {
        // Upper case is the legacy storage form rather than an invention of this service: the insert wrote
        // UPPER(:CUST-NAME-TEXT) [backend/cash-account-cobol/COBOL/CASH00.cbl:L155] into a CHAR(32) column
        // [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9, L17], so every owner the legacy table ever held was
        // already upper case.
        assertThat(OwnerNormalizer.normalize("  john  ")).isEqualTo("JOHN");
        assertThat(OwnerNormalizer.normalize("\tkarri\n")).isEqualTo("KARRI");

        // Already-canonical input must come back untouched: the loader and the reconciler re-normalize owners that
        // were normalized on the way in, so a non-idempotent fold would make the reconciliation join key depend on
        // how many times a row had been read.
        assertThat(OwnerNormalizer.normalize("JOHN")).isEqualTo("JOHN");

        // Preserved (AAP 0.4.6): legacy owner identity was case-insensitive on every path, reads and deletes
        // matching LOWER(Owner) [CASH00.cbl:L141] and updates UPPER(Owner) [CASH00.cbl:L178], so collapsing the
        // casings to one stored form keeps every lookup that worked before working, against a single primary key.
        // Always answering with that stored form is the deliberate change: the legacy echo was inconsistent, Q
        // returning the column [CASH00.cbl:L144] and A the caller's own casing [CASH00.cbl:L158], and broker maps
        // only balance and currency out of the response.
        assertThat(OwnerNormalizer.normalize("john"))
                .isEqualTo(OwnerNormalizer.normalize("John"))
                .isEqualTo(OwnerNormalizer.normalize("JOHN"))
                .isEqualTo("JOHN");

        // Locale.ROOT, not the no-argument toUpperCase(): under a Turkish default locale the latter maps "i" to
        // U+0130 (dotted capital I), putting a non-ASCII character into the primary key and making identity depend
        // on the JVM's locale. The ASCII outcome is asserted rather than the locale switched, which would corrupt
        // every test sharing this JVM.
        assertThat(OwnerNormalizer.normalize("iris")).isEqualTo("IRIS");

        // Only the edges are stripped; interior characters survive as given, so owners carrying separators are not
        // silently rewritten into a different account key.
        assertThat(OwnerNormalizer.normalize("  john.doe-1  ")).isEqualTo("JOHN.DOE-1");
    }

    @Test
    void acceptsTheFullThirtyTwoCharacterWidthAndRejectsLonger() {
        assertThat(OwnerNormalizer.MAX_LENGTH).isEqualTo(32);

        assertThat(OwnerNormalizer.normalize("j")).isEqualTo("J");

        // Deliberate change (AAP 0.4.6): the legacy 15-character ceiling was a COMMAREA artifact - WS-NAME was
        // PIC X(15) [CASH00.cbl:L55] while the column behind it was CHAR(32) [DCLCASH.cpy:L9, L17] - so a longer
        // owner was silently cut at the interface boundary and two owners sharing a 15-character prefix collapsed
        // into one account with nothing reporting it. A twenty-character owner surviving whole is that gone.
        assertThat(OwnerNormalizer.normalize("institutional-desk-7"))
                .isEqualTo("INSTITUTIONAL-DESK-7")
                .hasSize(20);

        String fullWidth = "institutional-custody-account-01";
        assertThat(OwnerNormalizer.normalize(fullWidth))
                .isEqualTo("INSTITUTIONAL-CUSTODY-ACCOUNT-01")
                .hasSize(OwnerNormalizer.MAX_LENGTH);

        // The bound is measured after stripping, so a full-width owner still carrying the blank padding of a
        // CHAR(32) export column is accepted rather than refused for being 38 characters of raw text.
        assertThat(OwnerNormalizer.normalize("   " + fullWidth + "   ")).hasSize(OwnerNormalizer.MAX_LENGTH);

        assertThat(rejectionCodeFor("A".repeat(OwnerNormalizer.MAX_LENGTH + 1)))
                .isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // The bound is applied to the canonical form, after the case fold, because folding is one-to-many: 31
        // "a" followed by U+00DF (sharp s) is exactly 32 characters as sent and 33 once folded, since sharp s
        // upper-cases to "SS". Bounding the pre-fold value would return a 33-character owner the VARCHAR(32)
        // column cannot hold and that normalize itself rejects - a non-idempotent fold every consumer that
        // re-normalizes an already-normalized owner then trips over, rejecting late and echoing the folded value.
        String expandsWhenFolded = "a".repeat(OwnerNormalizer.MAX_LENGTH - 1) + "\u00DF";
        assertThat(expandsWhenFolded).hasSize(OwnerNormalizer.MAX_LENGTH);
        assertThat(expandsWhenFolded.toUpperCase(Locale.ROOT)).hasSize(OwnerNormalizer.MAX_LENGTH + 1);
        assertThat(rejectionCodeFor(expandsWhenFolded)).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // Counted in code points, not UTF-16 units, because code points are the unit the destination column
        // bounds: PostgreSQL measures character varying in characters. U+1D400 is one character costing two
        // UTF-16 units, so counting units would refuse a value that VARCHAR(32) stores without complaint
        // (verified on postgres 12.22). It folds to itself, so the canonical form is the input. Asserted on
        // canonicalize, because that is the form the bound now governs alone: as an OWNER the value is refused
        // for its spelling, and as a legacy_history join key it is stored.
        String oneCharacterTwoUnits = "\uD835\uDC00";
        String fullWidthInCodePoints = oneCharacterTwoUnits.repeat(OwnerNormalizer.MAX_LENGTH);
        assertThat(fullWidthInCodePoints).hasSize(OwnerNormalizer.MAX_LENGTH * 2);
        String canonical = OwnerNormalizer.canonicalize(fullWidthInCodePoints);
        assertThat(canonical).isEqualTo(fullWidthInCodePoints);
        assertThat(canonical.codePointCount(0, canonical.length())).isEqualTo(OwnerNormalizer.MAX_LENGTH);

        assertThat(canonicalizationCodeFor(oneCharacterTwoUnits.repeat(OwnerNormalizer.MAX_LENGTH + 1)))
                .isEqualTo(CashAccountErrorCode.INVALID_OWNER);
    }

    // The two halves of one contract, asserted together because each is only safe given the other. An owner
    // carrying an interior control or line-separator code point is refused outright (QA finding F03), but the
    // value a refusal ECHOES is the raw one - that is what lets the ApiError payload and the log record name what
    // was rejected - so the hazard moves rather than disappearing: in a line-oriented log record a newline ends
    // the record and whatever follows reads as a separate line this service wrote (CWE-117). error/LogSafeText is
    // where it is neutralized, and every record naming an owner goes through it.
    @Test
    void controlCharactersAreRefusedAndTheEchoedValueIsEncodedAtTheLoggingBoundary() {
        // An interior control code point is not an identifier, whatever the edges look like after stripping.
        assertThat(rejectionCodeFor("  jo\nhn  ")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("JO\u0000HN")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("JOHN\u2028X")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // Still the canonical join key of a legacy row, because staging is lossless: the character rule is
        // normalize's, and canonicalize is the form a decoded VSAM name is carried under (CASH00.cbl:L114).
        assertThat(OwnerNormalizer.canonicalize("  jo\nhn  ")).isEqualTo("JO\nHN");

        // The value that reaches a log record is the RAW one, because a rejection echoes back what it refused so
        // the ApiError payload can name it - which is why the encoder, not the normalizer, is the control.
        String forged = "JOHN\r\n2026-09-22 ERROR Rejecting request for owner ADMIN: ACCOUNT_DELETED";
        assertThat(rejectionCodeFor(forged)).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // Encoded, so no code point that could end or rewrite a record survives - and unambiguously, since the
        // escape character is itself escaped.
        assertThat(LogSafeText.of("JO\nHN")).isEqualTo("JO\\u000AHN");
        assertThat(LogSafeText.of(forged))
                .startsWith("JOHN\\u000D\\u000A")
                .doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(LogSafeText.of("JOHN\u2028X")).isEqualTo("JOHN\\u2028X");
        assertThat(LogSafeText.of("JOHN\\u000A")).isEqualTo("JOHN\\\\u000A");

        // Bounded, because a rejected owner has passed no length check at all: an unbounded log field is its own
        // denial of service against whoever has to read and store it.
        assertThat(LogSafeText.of("A".repeat(500)))
                .hasSize(LogSafeText.MAX_CODE_POINTS + 3)
                .endsWith("...");

        // A legitimate owner is untouched, and a condition that names no owner keeps logging exactly as before:
        // the encoder changes what a record contains, never which records exist.
        assertThat(LogSafeText.of("JOHN.DOE-1")).isEqualTo("JOHN.DOE-1");
        assertThat(LogSafeText.of(null)).isNull();

        // The message form encodes identically and differs only in how much it keeps, because a framework message
        // built around a percent-decoded request path carries the same hazard while needing to stay readable.
        assertThat(LogSafeText.ofMessage("No static resource /cash-account/x\nFORGED.")).doesNotContain("\n");
        assertThat(LogSafeText.ofMessage("A".repeat(500)))
                .hasSize(LogSafeText.MAX_MESSAGE_CODE_POINTS + 3)
                .endsWith("...");
        assertThat(LogSafeText.MAX_MESSAGE_CODE_POINTS).isGreaterThan(LogSafeText.MAX_CODE_POINTS);
        assertThat(LogSafeText.ofMessage(null)).isNull();
    }

    // The reservation of the institutional path segment, and the line it does not cross. The bare prefix
    // /cash-account/institutional reaches the retail /{owner} mappings, where it used to create and serve a real
    // account named INSTITUTIONAL; retail/RetailCashAccountController now refuses that ROUTE through this
    // predicate. Identity is untouched on purpose: AAP 0.4.2 and 0.6.2 fix INVALID_OWNER to blank-or-over-32, so
    // refusing the value as an owner would change which accounts can exist and would fail a whole
    // single-transaction bulk load (AAP 0.6.3) over one legacy export row.
    @Test
    void recognizesTheReservedInstitutionalPathSegmentWithoutNarrowingOwnerIdentity() {
        assertThat(OwnerNormalizer.INSTITUTIONAL_PATH_SEGMENT).isEqualTo("INSTITUTIONAL");

        // Every casing and the padding of a CHAR(32) export column, because the retail path segment is matched
        // case-sensitively by Spring MVC while the owner behind it is case-folded: reserving one spelling only
        // would leave /cash-account/INSTITUTIONAL serving the account /cash-account/institutional refuses.
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("institutional")).isTrue();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("INSTITUTIONAL")).isTrue();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("Institutional")).isTrue();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("  institutional  ")).isTrue();

        // Nothing else is reserved - not a neighbouring owner, not a longer name that merely starts with it.
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("institutional-desk-7")).isFalse();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("JOHN")).isFalse();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment("")).isFalse();
        assertThat(OwnerNormalizer.isInstitutionalPathSegment(null)).isFalse();

        // And the value is still a perfectly ordinary owner, which is what keeps the loader and the
        // institutional endpoints able to carry it.
        assertThat(OwnerNormalizer.normalize("institutional"))
                .isEqualTo(OwnerNormalizer.INSTITUTIONAL_PATH_SEGMENT);
    }

    @Test
    void rejectsNullAndBlankOwners() {
        assertThat(rejectionCodeFor(null)).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("   ")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // AAP 0.6.2 pins 400 for this condition and CashAccountErrorCode is the single place it is decided, so
        // the rejection is tied here to the response a caller actually receives.
        assertThat(CashAccountErrorCode.INVALID_OWNER.status().value()).isEqualTo(400);
    }

    // The character rule, one case per class of value the runtime security pass actually created through the
    // retail seam (QA finding F03): markup, an SQL fragment, a comment marker, a command substitution, a
    // backquoted command, a template expression, an arithmetic sign and non-ASCII text. Every one of them was
    // accepted as an owner, persisted, and echoed back in the owner field of a 200 response.
    @Test
    void refusesOwnersSpelledWithAnythingOutsideThePermittedSet() {
        assertThat(rejectionCodeFor("SEC1<img src=x onerror=alert(1)>"))
                .isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("<b>x")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("SEC1' OR '1'='1")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("SEC1\" OR \"\"=\"")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("$(whoami)")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("`id`")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("{{7*7}}")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("SEC1+1")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("über")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("日本")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        // A space is refused with them: the legacy column could hold one, so this is the deliberate line - an
        // identifier is not a display name, and two owners differing only in spacing are a support incident.
        assertThat(rejectionCodeFor("MARY JANE")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // What the set DOES admit, and why it is these three separators: real user identifiers carry them, this
        // module's own documented example owners are spelled with them, and a legacy account named this way must
        // still be loadable. Two values from that same runtime pass are therefore accepted BY DESIGN - SEC1_ and
        // SEC1-- - because both are spelled entirely from the set the F03 suggested fix names,
        // ^[A-Z0-9._-]{1,32}$. Neither is markup, a shell metacharacter or a template expression; SEC1-- reads as
        // a SQL comment only where an identifier is concatenated into SQL text, which nothing here does (every
        // statement binds parameters). Refusing a doubled or trailing separator would be a rule of this test's
        // invention, and it would refuse legacy owners the export may legitimately carry.
        assertThat(OwnerNormalizer.normalize("john.doe-1")).isEqualTo("JOHN.DOE-1");
        assertThat(OwnerNormalizer.normalize("sec1_")).isEqualTo("SEC1_");
        assertThat(OwnerNormalizer.normalize("sec1--")).isEqualTo("SEC1--");
        assertThat(OwnerNormalizer.normalize("RAUNAK")).isEqualTo("RAUNAK");
        assertThat(OwnerNormalizer.normalize("desk-07.eu_1")).isEqualTo("DESK-07.EU_1");

        // The predicate the migration tooling asks the question with, so a refused export row becomes a finding
        // instead of an aborted single-transaction load (AAP 0.6.3). It judges the canonical form, which is why
        // a lower-case value answers false: migration/reconcile/ReconciliationService canonicalizes first.
        assertThat(OwnerNormalizer.permits("JOHN.DOE-1")).isTrue();
        assertThat(OwnerNormalizer.permits("MARY JANE")).isFalse();
        assertThat(OwnerNormalizer.permits("john")).isFalse();
        assertThat(OwnerNormalizer.permits("")).isFalse();
        assertThat(OwnerNormalizer.permits(null)).isFalse();
        assertThat(OwnerNormalizer.permits("A".repeat(OwnerNormalizer.MAX_LENGTH))).isTrue();
        assertThat(OwnerNormalizer.permits("A".repeat(OwnerNormalizer.MAX_LENGTH + 1))).isFalse();

        // And the legacy path is genuinely still open, which is the other half of the fix: the same value that
        // cannot be an owner is carried as a staging join key rather than failing the file it arrived in.
        assertThat(OwnerNormalizer.canonicalize("mary jane")).isEqualTo("MARY JANE");
        assertThat(OwnerNormalizer.canonicalize("o'brien")).isEqualTo("O'BRIEN");
    }

    // One rule, written once. The database is the backstop for every path that does not go through this class -
    // a hand-run UPDATE, a future endpoint, the loader - so the two expressions have to be the same expression,
    // and a test is the only thing that can say so: nothing at build time compares a Java pattern with SQL text.
    @Test
    void theSchemaConstraintCarriesTheSamePermittedSetAsThisClass() {
        assertThat(OwnerNormalizer.PERMITTED_OWNER_REGEX).isEqualTo("[A-Z0-9._-]{1,32}");

        String schema = schemaText();
        String anchored = "'^" + OwnerNormalizer.PERMITTED_OWNER_REGEX + "$'";

        // Every owner column that stores an identity, named individually: a CHECK silently dropped from one of
        // them would leave that table able to hold what the other two refuse.
        for (String table : new String[] {"cash_account", "cash_reservation", "ledger_entry"}) {
            assertThat(schema)
                    .as("%s must carry the owner CHECK with the normalizer's own expression", table)
                    .contains("CONSTRAINT ck_" + table + "_owner_identifier CHECK (owner ~ " + anchored + ")");
        }

        // The upgrade half: CREATE TABLE IF NOT EXISTS cannot add a constraint to a database that already holds
        // these tables, so the guarded ALTER is what carries the rule to one - and it must carry the same text.
        assertThat(schema)
                .as("the guarded ALTER path must add the same expression to an existing database")
                .contains("ADD CONSTRAINT ck_cash_account_owner_identifier\n"
                        + "            CHECK (owner ~ " + anchored + ") NOT VALID");
    }

    private static String schemaText() {
        // Read from the classpath, so the file this asserts against is the one that is packaged and applied.
        try (InputStream schema =
                     OwnerNormalizerTest.class.getResourceAsStream("/schema/cash-account-schema.sql")) {
            assertThat(schema).as("schema/cash-account-schema.sql must be on the classpath").isNotNull();
            return new String(schema.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("schema/cash-account-schema.sql could not be read", unreadable);
        }
    }

    private static CashAccountErrorCode rejectionCodeFor(String raw) {
        CashAccountException thrown =
                catchThrowableOfType(() -> OwnerNormalizer.normalize(raw), CashAccountException.class);
        assertThat(thrown).as("normalize(\"%s\") must be rejected", raw).isNotNull();
        return thrown.errorCode();
    }

    // The same assertion for the lenient form, kept separate so a case can state which of the two refused it.
    private static CashAccountErrorCode canonicalizationCodeFor(String raw) {
        CashAccountException thrown =
                catchThrowableOfType(() -> OwnerNormalizer.canonicalize(raw), CashAccountException.class);
        assertThat(thrown).as("canonicalize(\"%s\") must be rejected", raw).isNotNull();
        return thrown.errorCode();
    }
}
