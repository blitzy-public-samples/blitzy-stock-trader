package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error;

/** Renders an untrusted value as one bounded, single-line, structure-free log field. */
public final class LogSafeText {

    /*
     * Bounded because the value is: a rejected owner has not passed any length check, and a log field that can be
     * megabytes long is its own denial of service against the operator reading it and the aggregator storing it.
     * Forty code points shows an owner that overran the 32-code-point bound whole - which is what makes the record
     * diagnostic - while refusing to carry anything larger.
     */
    public static final int MAX_CODE_POINTS = 40;

    /*
     * A diagnostic message needs room that an identifier does not: "No static resource /cash-account/..." has to
     * stay readable, and cutting it at forty code points would leave the record useless while defending nothing -
     * the injection risk is the code points a message CARRIES, which are encoded whatever the bound, not its
     * length. Two hundred bounds it against a message built around an unbounded request path.
     */
    public static final int MAX_MESSAGE_CODE_POINTS = 200;

    private static final String TRUNCATION_MARK = "...";

    private static final String BMP_ESCAPE_FORMAT = "\\u%04X";

    private static final String SUPPLEMENTARY_ESCAPE_FORMAT = "\\U%08X";

    private LogSafeText() {
    }

    /**
     * Returns {@code raw} with every code point that could forge log structure replaced by its escape, bounded to
     * {@value #MAX_CODE_POINTS} code points.
     *
     * @param raw the untrusted value, exactly as it arrived from a caller, an export row or a replay stream
     * @return the encoded value, or {@code null} when {@code raw} is {@code null}
     */
    // A log line is a parsed format, not free text - one record per line, shipped to an aggregator and matched by
    // alert rules - so an untrusted value interpolated unencoded forges structure: an owner of
    // "JOHN\n2026-09-22 ERROR Rejecting request for owner ADMIN: ACCOUNT_DELETED" splits one record into two
    // (CWE-117), a bare CR rewrites the visible line and U+202E reverses what a human reads. The owner
    // error/ApiExceptionHandler logs is the RAW caller value - domain/OwnerNormalizer echoes back what it refused -
    // so the value most likely to be logged is the least likely to be normalized. Encoded at the sink rather than
    // validated at the source because the accepted owner set is fixed by AAP 0.4.2 and 0.6.2 and a new rejection
    // would refuse legacy export rows the single-transaction bulk load (AAP 0.6.3) must carry.
    public static String of(String raw) {
        return bounded(raw, MAX_CODE_POINTS);
    }

    /**
     * Returns a diagnostic message encoded the same way, bounded to {@value #MAX_MESSAGE_CODE_POINTS} code points.
     *
     * <p>For the framework messages this service logs rather than returns: a no-handler message is built around
     * the request path, and a method-not-supported message around the request's verb, so both carry text the
     * caller chose - percent-decoded, which is how a newline reaches a path that could not contain one on the
     * wire.
     *
     * @param raw the message, as the exception rendered it
     * @return the encoded message, or {@code null} when {@code raw} is {@code null}
     */
    public static String ofMessage(String raw) {
        return bounded(raw, MAX_MESSAGE_CODE_POINTS);
    }

    private static String bounded(String raw, int maxCodePoints) {
        // Null passes through rather than becoming a placeholder: a condition that names no owner - every
        // CashAccountException.of(code) rejection - must keep logging as it did, so this encoder changes what a
        // record CONTAINS and never which records exist.
        if (raw == null) {
            return null;
        }

        StringBuilder safe = new StringBuilder(Math.min(raw.length(), maxCodePoints) + TRUNCATION_MARK.length());
        int written = 0;
        for (int index = 0; index < raw.length();) {
            int codePoint = raw.codePointAt(index);
            index += Character.charCount(codePoint);

            if (written == maxCodePoints) {
                safe.append(TRUNCATION_MARK);
                break;
            }
            written++;
            appendEncoded(safe, codePoint);
        }
        return safe.toString();
    }

    /*
     * The backslash is escaped first, and it is not decoration: without it an owner containing the seven literal
     * characters \u000A and an owner containing a real newline both render as "\u000A", so a forged record could be
     * denied by claiming the log had merely escaped it. Escaping the escape makes the encoding unambiguous, and it
     * costs nothing on the owners that actually occur, none of which contain a backslash.
     */
    private static void appendEncoded(StringBuilder target, int codePoint) {
        if (codePoint == '\\') {
            target.append("\\\\");
            return;
        }
        if (forgesStructure(codePoint)) {
            target.append(codePoint <= Character.MAX_VALUE
                    ? String.format(BMP_ESCAPE_FORMAT, codePoint)
                    : String.format(SUPPLEMENTARY_ESCAPE_FORMAT, codePoint));
            return;
        }
        target.appendCodePoint(codePoint);
    }

    /*
     * Judged by Unicode general category rather than by a list of characters, because the dangerous set is larger
     * than the two everyone remembers. Cc covers LF, CR, NUL, ESC and DEL - the record separators and the terminal
     * escape sequences. Zl and Zp are U+2028 and U+2029, which many log viewers and JSON-lines consumers break
     * lines on. Cf covers the zero-width and bidirectional formatting characters, which forge nothing structurally
     * but make the rendered value disagree with the stored one. Cs is an unpaired surrogate, which is not a
     * character at all and would be written into a UTF-8 log file as a replacement byte sequence.
     *
     * Everything else is passed through unchanged: an owner is a customer identifier, and mangling the accented or
     * non-Latin letters of a legitimate one would defeat the diagnostic purpose of logging it.
     */
    private static boolean forgesStructure(int codePoint) {
        int category = Character.getType(codePoint);
        return category == Character.CONTROL
                || category == Character.FORMAT
                || category == Character.LINE_SEPARATOR
                || category == Character.PARAGRAPH_SEPARATOR
                || category == Character.SURROGATE;
    }
}
