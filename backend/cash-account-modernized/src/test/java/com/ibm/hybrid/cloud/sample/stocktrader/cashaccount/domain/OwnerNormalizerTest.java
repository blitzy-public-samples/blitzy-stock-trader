package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Locale;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.LogSafeText;
import org.junit.jupiter.api.Test;

/** Unit tests for owner identity normalization: trim, upper case, and the 1-32 character bound. */
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
        // UTF-16 units, so counting units would refuse an owner that VARCHAR(32) stores without complaint
        // (verified on postgres 12.22). It folds to itself, so the canonical form is the input.
        String oneCharacterTwoUnits = "\uD835\uDC00";
        String fullWidthInCodePoints = oneCharacterTwoUnits.repeat(OwnerNormalizer.MAX_LENGTH);
        assertThat(fullWidthInCodePoints).hasSize(OwnerNormalizer.MAX_LENGTH * 2);
        String normalized = OwnerNormalizer.normalize(fullWidthInCodePoints);
        assertThat(normalized).isEqualTo(fullWidthInCodePoints);
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(OwnerNormalizer.MAX_LENGTH);

        assertThat(rejectionCodeFor(oneCharacterTwoUnits.repeat(OwnerNormalizer.MAX_LENGTH + 1)))
                .isEqualTo(CashAccountErrorCode.INVALID_OWNER);
    }

    // The two halves of one contract, asserted together because each is only safe given the other. An owner may
    // carry an interior control or line-separator code point - the legacy CHAR(32) column restricted none and AAP
    // 0.4.2 and 0.6.2 fix INVALID_OWNER to blank-or-over-32, so narrowing identity here would change which accounts
    // exist and would refuse export rows the migration loader must carry. That makes the value dangerous in exactly
    // one place: a line-oriented log record, where a newline ends the record and whatever follows reads as a
    // separate line this service wrote (CWE-117). error/LogSafeText is where it is neutralized, and every record
    // naming an owner goes through it.
    @Test
    void controlCharactersSurviveNormalizationAndAreEncodedAtTheLoggingBoundary() {
        // Normalization is unchanged: case-folded, stripped at the edges, interior code points intact.
        assertThat(OwnerNormalizer.normalize("  jo\nhn  ")).isEqualTo("JO\nHN");

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

    private static CashAccountErrorCode rejectionCodeFor(String raw) {
        CashAccountException thrown =
                catchThrowableOfType(() -> OwnerNormalizer.normalize(raw), CashAccountException.class);
        assertThat(thrown).as("normalize(\"%s\") must be rejected", raw).isNotNull();
        return thrown.errorCode();
    }
}
