package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The single authority on owner identity: one canonical form reaches the database and the wire. */
public final class OwnerNormalizer {

    // 32 is the width the stored column always had - CHAR(32) in the DCLGEN declaration
    // [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9] and in the DDL
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47] - and it is the width of every owner column in
    // cash-account-schema.sql. It is measured in Unicode code points because PostgreSQL counts character
    // varying in characters, so counting UTF-16 units instead would refuse a value of 32 supplementary code
    // points that VARCHAR(32) stores without complaint. That is load-bearing for canonicalize(...) rather than
    // for normalize(...): a stored owner is ASCII by the character rule below, while a legacy staging key is
    // whatever the export decoded to.
    public static final int MAX_LENGTH = 32;

    /**
     * The character set a stored owner may be spelled with, once canonical: upper-case ASCII letters, digits,
     * and the three separators real user identifiers carry - {@code .}, {@code _} and {@code -}.
     *
     * <p>Public and unanchored so the identical text can be anchored into the {@code CHECK} constraint on every
     * owner column of {@code src/main/resources/schema/cash-account-schema.sql}: one expression, written once,
     * with {@code OwnerNormalizerTest} asserting the schema still carries it. Two copies of an identity rule
     * drift, and a drifted copy either refuses an owner the database would accept or accepts one the database
     * then rejects with a constraint violation instead of a {@code 400}.
     */
    public static final String PERMITTED_OWNER_REGEX = "[A-Z0-9._-]{1," + MAX_LENGTH + "}";

    // Applied to the canonical form, never to the raw value: the fold is what makes a single pattern sufficient,
    // since a lower-case caller value is upper-cased before it is judged rather than refused for its casing.
    private static final Pattern PERMITTED_OWNER = Pattern.compile(PERMITTED_OWNER_REGEX);

    /**
     * The canonical form of the one path segment the institutional surface owns under {@code /cash-account}.
     *
     * <p>It is a routing fact rather than a rule about identity: {@link #normalize(String)} accepts this value
     * like any other, so a legacy export row carrying it still loads.
     */
    public static final String INSTITUTIONAL_PATH_SEGMENT = "INSTITUTIONAL";

    private OwnerNormalizer() {
    }

    /**
     * Returns the stored form of {@code raw}: stripped, upper-cased, and spelled with permitted characters only.
     *
     * <p>This is the identity rule for every owner a caller can name - retail and institutional alike - and for
     * every owner this service stores. A legacy export row is judged by the same rule but is never applied
     * through it; see {@link #canonicalize(String)}.
     *
     * @param raw the owner exactly as it arrived from a caller
     * @return the canonical owner, 1 to {@value #MAX_LENGTH} characters of {@value #PERMITTED_OWNER_REGEX}
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_OWNER} (HTTP 400) when {@code raw}
     *         is null, is blank, exceeds {@value #MAX_LENGTH} Unicode code points once stripped and upper-cased,
     *         or carries any character outside {@value #PERMITTED_OWNER_REGEX}
     */
    // The character rule is a deliberate narrowing of AAP 0.4.2 and 0.6.2, which fix INVALID_OWNER to
    // blank-or-over-32 and state no character class, adopted to close QA finding F03 (CWE-20, downstream CWE-79
    // risk): the unconstrained segment accepted and PERSISTED markup, shell and template metacharacters, SQL
    // fragments and arbitrary unicode as account identifiers - 12 such rows were created through this seam - and
    // every one of them was then reflected back in the owner field of a 200 response. Nothing was ever injected,
    // because JPA binds parameters and the ledger stores text, but an identifier is the one value this service
    // echoes to callers, writes into log records and joins reconciliation on, so it is spelled from a set chosen
    // rather than from whatever a URL can carry. The legacy CHAR(32) column carried no character restriction
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47], which is exactly why refusing here must not refuse a
    // legacy export row on the way in: canonicalize(...) below is that path, and the tooling records such a row
    // as a reviewable finding instead of failing a whole single-transaction bulk load (AAP 0.6.3).
    public static String normalize(String raw) {
        String canonical = canonicalize(raw);

        if (!permits(canonical)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_OWNER, raw);
        }

        return canonical;
    }

    /**
     * Returns whether {@code canonical} is spelled entirely from {@value #PERMITTED_OWNER_REGEX}.
     *
     * @param canonical a canonical owner, as {@link #canonicalize(String)} returns
     * @return {@code true} when a value of this spelling may be stored as an owner
     */
    // A predicate rather than a caught exception, because the migration tooling has to ASK the question: an
    // export row it must classify is not an error to unwind, and exception-as-control-flow inside a bulk load
    // would put the refusal on the same footing as an unreadable file. Callers:
    // migration/reconcile/ReconciliationService, which records the refusal as a finding.
    public static boolean permits(String canonical) {
        return canonical != null && PERMITTED_OWNER.matcher(canonical).matches();
    }

    /**
     * Returns the canonical form of {@code raw} - stripped, upper-cased and bounded - without judging how it is
     * spelled, which is the join key a legacy row is carried under.
     *
     * <p>Two consumers, both reading legacy data rather than serving a caller: the loader's uppercased
     * {@code legacy_history.owner_key}, whose raw {@code WS-VR-NAME} could hold any byte a COMMAREA carried, and
     * the reconciler, which needs the owner NAMED in the finding that refuses it.
     *
     * @param raw the owner exactly as it arrived from an export row, a replay stream or the target's own column
     * @return the canonical owner, 1 to {@value #MAX_LENGTH} Unicode code points, upper case
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_OWNER} (HTTP 400) when {@code raw}
     *         is null, blank, or exceeds {@value #MAX_LENGTH} Unicode code points once stripped and upper-cased
     */
    // Blank and over-length still throw here, and deliberately: neither is a value the VARCHAR(32) staging key
    // can hold at all, so an export carrying one is a file to fix rather than a row to lose (staging is
    // lossless). What this form does NOT do is decide identity - that is normalize's job - so a legacy owner
    // spelled with a byte the target refuses still reaches a finding under its own name.
    public static String canonicalize(String raw) {
        // Null carries no value worth echoing back, so this one rejection travels without an owner field
        // while the others use forOwner so ApiError can name what was refused.
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_OWNER);
        }

        // strip() rather than trim(): legacy CHAR columns and the fixed-layout history record are
        // blank-padded (EBCDIC 0x40), and an export or replay row may still carry that padding in a
        // Unicode form trim() does not recognize after code-page conversion.
        String stripped = raw.strip();

        if (stripped.isEmpty()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_OWNER, raw);
        }

        // Upper case is the legacy storage form, not an invention: the insert stored UPPER(:CUST-NAME-TEXT)
        // [CASH00.cbl:L155] and every lookup folded case [CASH00.cbl:L141, L178], so folding here preserves
        // every lookup that ever worked while making this value usable as the primary key and as the
        // reconciliation join key. Always RETURNING the folded form is the deliberate change: the legacy echo
        // was inconsistent, Q answering with the upper-case column [CASH00.cbl:L144] and A with the caller's
        // own casing [CASH00.cbl:L158], and the retail caller reads only balance and currency.
        //
        // Locale.ROOT, never the no-argument toUpperCase(): a Turkish default locale maps "i" to U+0130, which
        // would put a non-ASCII character into a primary key and make identity depend on the JVM's locale - the
        // same owner normalizing two ways in two pods.
        String canonical = stripped.toUpperCase(Locale.ROOT);

        // Rejecting rather than truncating is a deliberate change from the legacy program: the 15-character
        // limit was purely a COMMAREA artifact - WS-NAME was PIC X(15) [CASH00.cbl:L55] while the host variable
        // it fed was PIC X(32) [CASH00.cbl:L36] over a CHAR(32) column - so a longer name was silently cut at
        // the interface boundary and two owners sharing a 15-character prefix collapsed into one account with
        // nothing reporting it. Silent truncation is data loss; 400 INVALID_OWNER makes it visible.
        //
        // Measured on the canonical form, after the strip and after the fold, because folding is one-to-many:
        // "a" x31 followed by U+00DF folds to 33 characters. Bounding the pre-fold value would return an owner
        // this very method rejects, which every caller that re-normalizes an already-normalized owner then
        // trips over. One check suffices because folding never lowers the code-point count.
        if (canonical.codePointCount(0, canonical.length()) > MAX_LENGTH) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_OWNER, raw);
        }

        // Blank and over-length are this form's only rejections: strip() removes leading and trailing whitespace,
        // U+2028 and U+2029 among it, while an INTERIOR control or formatting code point survives into the
        // canonical form. That is correct for a legacy join key and is why normalize(...) applies the character
        // rule on top of this rather than inside it - a byte the legacy CHAR(32) column carried
        // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47] must still be able to reach a reconciliation
        // finding under its own name.
        //
        // The two boundaries that value crosses are therefore both encoded rather than trusted: a line separator
        // forges structure in a line-oriented format, so every log record naming an owner goes through
        // error/LogSafeText (error/ApiExceptionHandler is the only place in this module that logs one), and the
        // JSON payload escapes it as a matter of course. Both still matter after F03, because a REFUSED owner is
        // echoed back raw - that is the value the rejection has to name.
        return canonical;
    }

    /**
     * Returns whether {@code raw} canonicalizes to {@link #INSTITUTIONAL_PATH_SEGMENT}.
     *
     * @param raw the path segment exactly as it arrived from a caller
     * @return {@code true} when this value names the institutional surface's own path segment in any casing
     */
    // A predicate, deliberately not a rejection inside normalize. The two are different concerns: the
    // institutional surface is "a separate, additive path space" under /cash-account (AAP 0.6.2), and
    // /cash-account/institutional is a segment of it - but INSTITUTIONAL is an ordinarily spelled owner that the
    // character rule admits, so refusing the VALUE would change which accounts can exist and would refuse a
    // legacy export row the CHAR(32) column could hold. What is reserved is the retail ROUTE:
    // retail/RetailCashAccountController answers 404 UNSUPPORTED_PATH for it, while the loader, the reconciler and
    // the institutional endpoints carry the same owner unchanged.
    //
    // Canonicalized rather than compared literally, and that is the point of it living here: MVC matches a path
    // segment case-sensitively, so reserving only the lower-case spelling would leave /cash-account/INSTITUTIONAL
    // serving the very account /cash-account/institutional refuses - one identifier with two answers, which is
    // worse than the overlap it set out to close. The strip and the fold are normalize's own, applied through this
    // method so the module keeps ONE canonicalization.
    public static boolean isInstitutionalPathSegment(String raw) {
        return raw != null && INSTITUTIONAL_PATH_SEGMENT.equals(raw.strip().toUpperCase(Locale.ROOT));
    }
}
