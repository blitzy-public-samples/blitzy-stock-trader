package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import java.util.Locale;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The single authority on owner identity: one canonical form reaches the database and the wire. */
public final class OwnerNormalizer {

    // 32 is the width the stored column always had - CHAR(32) in the DCLGEN declaration
    // [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9] and in the DDL
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47] - and it is the width of every owner column in
    // cash-account-schema.sql. It is measured in Unicode code points because PostgreSQL counts character
    // varying in characters, so counting UTF-16 units instead would refuse an owner of 32 supplementary
    // code points that VARCHAR(32) stores without complaint.
    public static final int MAX_LENGTH = 32;

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
     * Returns the canonical stored form of {@code raw}: stripped and upper-cased.
     *
     * @param raw the owner exactly as it arrived from a caller, an export row or a replay stream
     * @return the canonical owner, 1 to {@value #MAX_LENGTH} Unicode code points, upper case
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_OWNER} (HTTP 400) when
     *         {@code raw} is null, blank, or whose canonical form exceeds {@value #MAX_LENGTH} Unicode
     *         code points once stripped and upper-cased
     */
    public static String normalize(String raw) {
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

        // Blank and over-length are the ONLY rejections, and the absence of a character-class check is a recorded
        // decision rather than an oversight. Length and casing are the whole of owner identity here: the legacy
        // CHAR(32) column carried no character restriction, AAP 0.4.2 and 0.6.2 fix INVALID_OWNER to exactly these
        // two conditions, and a narrower set would refuse export rows that migration/load/LegacyLoader must be able
        // to carry - failing a whole single-transaction bulk load (AAP 0.6.3) over a byte the ledger stores
        // harmlessly. strip() removes leading and trailing whitespace, U+2028 and U+2029 among it, but an INTERIOR
        // control or formatting code point survives into the canonical form by design.
        //
        // What that costs is contained where it can do damage, which is not here: a value carrying a line
        // separator forges structure when it is written into a line-oriented format, so every log record that
        // names an owner encodes it through error/LogSafeText (the only place in this module that logs one is
        // error/ApiExceptionHandler), and the JSON payload escapes it as a matter of course. Do not "fix" this
        // method by adding a pattern - the encoding at the boundary is the fix, and narrowing identity here would
        // change which accounts exist.
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
    // /cash-account/institutional is a segment of it - but AAP 0.4.2 and 0.6.2 fix owner identity to exactly
    // blank-or-over-32, so refusing this value as an OWNER would change which accounts can exist and would fail a
    // whole single-transaction bulk load (AAP 0.6.3) over one legacy row. What is reserved is the retail ROUTE:
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
