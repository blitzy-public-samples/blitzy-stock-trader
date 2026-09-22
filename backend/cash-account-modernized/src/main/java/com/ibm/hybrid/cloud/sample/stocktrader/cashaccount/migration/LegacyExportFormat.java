package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The accepted shapes, size bounds and input-path containment rules of a legacy cash-account export.
 *
 * @see "backend/cash-account-modernized/docs/legacy-characterization.md"
 */
public final class LegacyExportFormat {

    private LegacyExportFormat() {
    }

    // Bare names, never paths: callers resolve them against tool.input. They are also the fixture file
    // names under src/test/resources/fixtures/, so a rename here fails those tests on file-not-found
    // rather than on behaviour.
    public static final String CASH_ACCOUNT_FILE = "cashaccounty.csv";

    public static final String RATE_TABLE_FILE = "frankfurt1.csv";

    public static final String HISTORY_TEXT_FILE = "history.csv";

    // The code page in the name is a reminder, never an override: the effective charset is
    // DEFAULT_LEGACY_CHARSET or whatever tool.legacy-charset supplies.
    public static final String HISTORY_BINARY_FILE = "history.cp037.bin";

    public static final String TARGET_STATE_FILE = "target-state.csv";

    public static final String SHADOW_TRANSACTIONS_FILE = "transactions.csv";

    public static final String SHADOW_LEGACY_RESPONSES_FILE = "legacy-responses.csv";

    // STOCKTRD.CASHACCOUNTY, in the declared column order (DCLCASH.cpy:L9-L11, DB2DDL.jcl:L47-L49).
    // Each file declares its own column names even where the text repeats, because the shapes are
    // independent contracts; readers bind by these names through indexHeader, never by ordinal position.
    public static final String CASH_ACCOUNT_OWNER_COLUMN = "owner";

    public static final String CASH_ACCOUNT_BALANCE_COLUMN = "balance";

    public static final String CASH_ACCOUNT_CURRENCY_COLUMN = "currencyc";

    public static final List<String> CASH_ACCOUNT_COLUMNS =
            List.of(CASH_ACCOUNT_OWNER_COLUMN, CASH_ACCOUNT_BALANCE_COLUMN, CASH_ACCOUNT_CURRENCY_COLUMN);

    // An alias, not a second list: target-state.csv carries a divergent migrated state in this same
    // shape (AAP 0.10.3), and the two are compared file by file, so they must change together.
    public static final List<String> TARGET_STATE_COLUMNS = CASH_ACCOUNT_COLUMNS;

    // STOCKTRD.FRANKFURT1, in the declared column order (DCLFRANK.cpy:L9-L13, DB2DDL.jcl:L55-L59).
    public static final String RATE_KEY_COLUMN = "currnkey";

    // Both spellings are accepted because the legacy artifacts disagree: the DDL declares "cyrrnbase"
    // (DB2DDL.jcl:L56) while the copybook and the program's SELECT name CURRNBASE (DCLFRANK.cpy:L10;
    // CASH00.cbl:L215, L249), so as written the program would not precompile against that DDL. Which
    // matches the deployed catalog is AAP 0.11.2's open item, and guessing is prohibited.
    public static final String RATE_BASE_COLUMN = "currnbase";

    public static final String RATE_BASE_COLUMN_ALIAS = "cyrrnbase";

    // Staged, never computed with: the rate SELECT fetches AMOUNT (CASH00.cbl:L215, L249) and no COMPUTE
    // or MOVE reads it - the legacy multiplicand is the caller's COMMAREA amount (AAP 0.4.1).
    public static final String RATE_AMOUNT_COLUMN = "amount";

    public static final String RATE_RATES_COLUMN = "rates";

    public static final String RATE_LOAD_DATE_COLUMN = "loaddt";

    public static final List<String> RATE_COLUMNS = List.of(
            RATE_KEY_COLUMN, RATE_BASE_COLUMN, RATE_AMOUNT_COLUMN, RATE_RATES_COLUMN, RATE_LOAD_DATE_COLUMN);

    // The delimited conversion of the VSAM history, one column per field of the 57-byte record below.
    public static final String HISTORY_NAME_COLUMN = "name";

    public static final String HISTORY_DATE_COLUMN = "event_date";

    public static final String HISTORY_TIME_COLUMN = "event_time";

    public static final String HISTORY_REQUEST_CODE_COLUMN = "request_code";

    public static final String HISTORY_BALANCE_COLUMN = "balance";

    public static final String HISTORY_CURRENCY_COLUMN = "currency";

    public static final String HISTORY_RETCODE_COLUMN = "retcode";

    public static final List<String> HISTORY_COLUMNS = List.of(
            HISTORY_NAME_COLUMN,
            HISTORY_DATE_COLUMN,
            HISTORY_TIME_COLUMN,
            HISTORY_REQUEST_CODE_COLUMN,
            HISTORY_BALANCE_COLUMN,
            HISTORY_CURRENCY_COLUMN,
            HISTORY_RETCODE_COLUMN);

    // A captured shadow-mode window, joined on seq plus the normalized owner.
    public static final String SHADOW_TRANSACTION_SEQ_COLUMN = "seq";

    public static final String SHADOW_TRANSACTION_OWNER_COLUMN = "owner";

    public static final String SHADOW_TRANSACTION_REQUEST_CODE_COLUMN = "req";

    public static final String SHADOW_TRANSACTION_AMOUNT_COLUMN = "amount";

    public static final String SHADOW_TRANSACTION_CURRENCY_COLUMN = "currency";

    public static final List<String> SHADOW_TRANSACTION_COLUMNS = List.of(
            SHADOW_TRANSACTION_SEQ_COLUMN,
            SHADOW_TRANSACTION_OWNER_COLUMN,
            SHADOW_TRANSACTION_REQUEST_CODE_COLUMN,
            SHADOW_TRANSACTION_AMOUNT_COLUMN,
            SHADOW_TRANSACTION_CURRENCY_COLUMN);

    public static final String SHADOW_RESPONSE_SEQ_COLUMN = "seq";

    public static final String SHADOW_RESPONSE_OWNER_COLUMN = "owner";

    public static final String SHADOW_RESPONSE_RETCODE_COLUMN = "retcode";

    public static final String SHADOW_RESPONSE_BALANCE_COLUMN = "balance";

    public static final List<String> SHADOW_LEGACY_RESPONSE_COLUMNS = List.of(
            SHADOW_RESPONSE_SEQ_COLUMN,
            SHADOW_RESPONSE_OWNER_COLUMN,
            SHADOW_RESPONSE_RETCODE_COLUMN,
            SHADOW_RESPONSE_BALANCE_COLUMN);

    // The cutover procedure's rollback replay file (AAP 0.12.1): one line per owner touched after the
    // ledger watermark, carrying that owner's absolute end state for replay through the legacy A/U/X
    // codes. Absolute state is what makes it replayable - C and D would depend on exchange rates.
    public static final String ROLLBACK_SEQ_COLUMN = "seq";

    public static final String ROLLBACK_OWNER_COLUMN = "owner";

    public static final String ROLLBACK_OP_COLUMN = "op";

    public static final String ROLLBACK_BALANCE_COLUMN = "balance";

    public static final String ROLLBACK_CURRENCY_COLUMN = "currency";

    public static final String ROLLBACK_FIRST_ENTRY_ID_COLUMN = "first_entry_id";

    public static final String ROLLBACK_LAST_ENTRY_ID_COLUMN = "last_entry_id";

    public static final String ROLLBACK_INCARNATION_ID_COLUMN = "incarnation_id";

    public static final List<String> ROLLBACK_REPLAY_COLUMNS = List.of(
            ROLLBACK_SEQ_COLUMN,
            ROLLBACK_OWNER_COLUMN,
            ROLLBACK_OP_COLUMN,
            ROLLBACK_BALANCE_COLUMN,
            ROLLBACK_CURRENCY_COLUMN,
            ROLLBACK_FIRST_ENTRY_ID_COLUMN,
            ROLLBACK_LAST_ENTRY_ID_COLUMN,
            ROLLBACK_INCARNATION_ID_COLUMN);

    /**
     * Resolves the rate table's base-currency column from a header, accepting either legacy spelling.
     *
     * @param headerNames the column names the rate-table export's header line declared
     * @return whichever accepted spelling the header carries
     * @throws IllegalArgumentException when the header names neither spelling
     */
    public static String resolveRateBaseColumn(Collection<String> headerNames) {
        if (headerNames == null) {
            throw new IllegalArgumentException("A " + RATE_TABLE_FILE + " header is required; none was read");
        }
        // The copybook spelling wins when both are present: it is the one the program's SELECT names
        // (CASH00.cbl:L215), so an export carrying both was likelier produced from the real catalog.
        boolean alias = false;
        for (String headerName : headerNames) {
            String normalized = normalize(headerName);
            if (RATE_BASE_COLUMN.equals(normalized)) {
                return RATE_BASE_COLUMN;
            }
            if (RATE_BASE_COLUMN_ALIAS.equals(normalized)) {
                alias = true;
            }
        }
        if (alias) {
            return RATE_BASE_COLUMN_ALIAS;
        }
        throw new IllegalArgumentException("The " + RATE_TABLE_FILE + " header names neither accepted spelling of"
                + " the base-currency column ('" + RATE_BASE_COLUMN + "' per DCLFRANK.cpy:L10, or '"
                + RATE_BASE_COLUMN_ALIAS + "' per DB2DDL.jcl:L56); header read: " + headerNames);
    }

    /**
     * Maps each column name of a delimited header to its zero-based position, so readers bind by name.
     *
     * @param actualHeader the header line's cells, in the order the file declared them
     * @return each normalized column name against its position
     */
    // The tooling's one binding mechanism: a reader falling back to ordinal positions would still appear
    // to work on a re-ordered export while loading balances into the currency column. Names match case-
    // and padding-insensitively because a mainframe UNLOAD may upper-case and pad its header.
    public static Map<String, Integer> indexHeader(List<String> actualHeader) {
        if (actualHeader == null || actualHeader.isEmpty()) {
            throw new IllegalArgumentException("A delimited export must carry a header line; none was read");
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int position = 0; position < actualHeader.size(); position++) {
            String normalized = normalize(actualHeader.get(position));
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Column " + position + " of the header line is blank; header read: " + actualHeader);
            }
            Integer previous = index.put(normalized, position);
            if (previous != null) {
                throw new IllegalArgumentException("Column '" + normalized + "' appears twice in the header line,"
                        + " at positions " + previous + " and " + position + "; a name-keyed read cannot choose"
                        + " between them");
            }
        }
        return Map.copyOf(index);
    }

    /**
     * The position of a required column in an indexed header, or a failure naming what is missing.
     *
     * @param headerIndex a header already indexed by {@link #indexHeader(List)}
     * @param column      the required column's declared name
     * @return that column's zero-based position
     * @throws IllegalArgumentException when the header does not name the column
     */
    public static int columnIndex(Map<String, Integer> headerIndex, String column) {
        if (headerIndex == null) {
            throw new IllegalArgumentException("An indexed header is required; call indexHeader first");
        }
        Integer position = headerIndex.get(normalize(column));
        if (position == null) {
            throw new IllegalArgumentException("The export header does not name the required column '" + column
                    + "'; columns read: " + headerIndex.keySet());
        }
        return position;
    }

    /**
     * One fixed-position field of the legacy history record.
     *
     * @param name   the field's name, used in decode failure messages
     * @param offset the field's zero-based byte offset in the record
     * @param length the field's byte length
     */
    public record FixedField(String name, int offset, int length) {

        public FixedField {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A fixed field must be named");
            }
            // A negative offset or length is a transcription error here, not bad input: unguarded it would
            // corrupt every decoded row rather than fail, so it becomes a load-time failure instead.
            if (offset < 0) {
                throw new IllegalArgumentException("Field '" + name + "' has a negative offset: " + offset);
            }
            if (length <= 0) {
                throw new IllegalArgumentException("Field '" + name + "' has a non-positive length: " + length);
            }
        }
    }

    // WS-VSAM-RECORD field by field (CASH00.cbl:L38-L45): X(15), X(08), X(06), X(1), 9(7)V99 - nine digit
    // positions, no sign nibble - X(8), X(10). The offsets are the accumulated widths, so the last field
    // ends at 57, the LENGTH OF WS-VSAM-RECORD the program writes (CASH00.cbl:L128).
    public static final FixedField HISTORY_NAME = new FixedField(HISTORY_NAME_COLUMN, 0, 15);

    public static final FixedField HISTORY_DATE = new FixedField(HISTORY_DATE_COLUMN, 15, 8);

    public static final FixedField HISTORY_TIME = new FixedField(HISTORY_TIME_COLUMN, 23, 6);

    public static final FixedField HISTORY_REQUEST_CODE = new FixedField(HISTORY_REQUEST_CODE_COLUMN, 29, 1);

    public static final FixedField HISTORY_BALANCE = new FixedField(HISTORY_BALANCE_COLUMN, 30, 9);

    public static final FixedField HISTORY_CURRENCY = new FixedField(HISTORY_CURRENCY_COLUMN, 39, 8);

    public static final FixedField HISTORY_RETCODE = new FixedField(HISTORY_RETCODE_COLUMN, 47, 10);

    public static final List<FixedField> HISTORY_FIELDS = List.of(
            HISTORY_NAME,
            HISTORY_DATE,
            HISTORY_TIME,
            HISTORY_REQUEST_CODE,
            HISTORY_BALANCE,
            HISTORY_CURRENCY,
            HISTORY_RETCODE);

    public static final int HISTORY_RECORD_LENGTH = 57;

    public static final int HISTORY_PADDED_RECORD_LENGTH = 100;

    // WS-VSAM-KEY is NAME + DATE + TIME at offset 0 (CASH00.cbl:L47-L50), matching the cluster's
    // KEYS(29 0) (DEFKSDS.jcl:L14). It carries the caller's own casing, so two records differing only in
    // case are two valid keys and neither may be folded away on import.
    public static final int HISTORY_KEY_LENGTH = 29;

    // WS-VR-BALANCE is unsigned zoned decimal 9(7)V99 (CASH00.cbl:L43): nine digit positions, an implied
    // point, no sign nibble and no separator, so after code-page conversion the only correct read is those
    // nine digit characters as text into a BigDecimal with the point shifted left by MONEY_SCALE - never
    // through a binary floating-point type (AAP 0.7.1, 0.12.2).
    public static final int HISTORY_BALANCE_DIGITS = 9;

    public static final int MONEY_SCALE = 2;

    // The default only: CCSID EBCDIC (DB2DDL.jcl:L22) names an encoding family, not a code page, and the
    // region's real CCSID is AAP 0.11.2's open item, so the effective charset arrives from
    // tool.legacy-charset. A wrong code page must be correctable inside a migration window, not by a
    // rebuild.
    public static final String DEFAULT_LEGACY_CHARSET = "IBM037";

    // Both lengths are accepted because the legacy artifacts disagree - CASH00 writes 57 bytes
    // (CASH00.cbl:L38-L45, L128) into a cluster defined RECSZ(100 100) (DEFKSDS.jcl:L11) - and which is
    // on disk is settled by the FCT/CSD FILE attributes, an open item (AAP 0.11.2, characterization 9.5).
    public static boolean isAcceptedHistoryRecordLength(int declaredLength) {
        return declaredLength == HISTORY_RECORD_LENGTH || declaredLength == HISTORY_PADDED_RECORD_LENGTH;
    }

    /**
     * Validates a declared binary history record length, returning it unchanged when accepted.
     *
     * @param declaredLength the length tool.history-record-length declared
     * @return that same length
     * @throws IllegalArgumentException when it is neither accepted length
     */
    public static int requireAcceptedHistoryRecordLength(int declaredLength) {
        if (!isAcceptedHistoryRecordLength(declaredLength)) {
            throw new IllegalArgumentException("tool.history-record-length must be " + HISTORY_RECORD_LENGTH
                    + " (what CASH00 writes) or " + HISTORY_PADDED_RECORD_LENGTH
                    + " (what the cluster declares), but was " + declaredLength
                    + "; the record length of a binary history export is declared, never inferred from the file");
        }
        return declaredLength;
    }

    // Bytes 57-99 of a 100-byte record carry nothing the program wrote: EBCDIC blank (0x40) is what a
    // blank-padded record holds and 0x00 what an unwritten tail holds, and both are accepted because the
    // export step that produced the file is outside this repository (AAP 0.12.1). A predicate rather than
    // a byte[] constant, which every caller could mutate.
    public static boolean isPaddingByte(byte b) {
        return b == (byte) 0x40 || b == (byte) 0x00;
    }

    /**
     * The first index in {@code [from, toExclusive)} that is not padding, or {@code -1} when every byte is.
     *
     * @param data        the record buffer to inspect
     * @param from        the first index of the padded tail
     * @param toExclusive one past its last index
     * @return the offending index, or {@code -1} when the whole range is padding
     * @throws IllegalArgumentException when the range does not lie inside {@code data}
     */
    // Here rather than in the decoder, so what a tail may hold stays with the record length and the field
    // offsets that define the same format. It yields the offending index rather than a boolean because a
    // non-padding tail means the file was not framed at the declared length, and the offset is the one
    // number that tells an operator where the framing broke.
    public static int paddingViolationOffset(byte[] data, int from, int toExclusive) {
        if (data == null) {
            throw new IllegalArgumentException("A record buffer is required to inspect a padded tail");
        }
        if (from < 0 || toExclusive > data.length || from > toExclusive) {
            throw new IllegalArgumentException("The padded tail [" + from + ", " + toExclusive
                    + ") does not lie inside a buffer of " + data.length + " bytes");
        }
        for (int index = from; index < toExclusive; index++) {
            if (!isPaddingByte(data[index])) {
                return index;
            }
        }
        return -1;
    }

    // The delimited decimal shape: plain text, two decimal places, no separator and no exponent.
    // Anchored by matches() at every use, so an exponent - which a floating-point type's own text form
    // emits and BigDecimal would accept - is refused at the boundary rather than loaded at a wrong scale.
    public static final Pattern DECIMAL_TEXT = Pattern.compile("-?\\d+\\.\\d{2}");

    // uuuu rather than yyyy throughout, because STRICT resolution rejects yyyy without an era, and STRICT
    // is the point: an impossible legacy stamp such as 20250231 must fail the load rather than be shifted
    // to a neighbouring day and reconciled against the wrong window.
    public static final DateTimeFormatter LOADDT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter HISTORY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);

    // The six request codes of EVALUATE WS-REQ (CASH00.cbl:L89-L102), case-sensitive as the EVALUATE
    // is: a lowercase 'a' matched no branch there and must not match one here either.
    public static final Set<String> REQUEST_CODES = Set.of("A", "Q", "U", "X", "C", "D");

    // Q is excluded because a read changed no state, so the ledger holds nothing on the target side to
    // count it against; AAP 0.12.1 fixes the filter as successful A/U/X/C/D. Q rows are still staged in
    // legacy_history - excluded from the count, not discarded.
    public static final Set<String> COUNTED_REQUEST_CODES = Set.of("A", "U", "X", "C", "D");

    public static boolean isDecimalText(String rawField) {
        String trimmed = trimPadding(rawField);
        return trimmed != null && DECIMAL_TEXT.matcher(trimmed).matches();
    }

    // A DB2 delimited unload writes NULL as an empty unquoted field and the literal text NULL is a value,
    // so the test is emptiness after padding and nothing in the tooling special-cases "NULL". The
    // documented consequence is that an all-blank CHAR value is indistinguishable from NULL once trimmed
    // (AAP 0.12.1, 0.12.2); both classify as NULL_IN_LEGACY, a variance an operator reviews.
    public static boolean isNull(String rawField) {
        String trimmed = trimPadding(rawField);
        return trimmed == null || trimmed.isEmpty();
    }

    // Right only, and every character at or below U+0020: EBCDIC blank 0x40 decodes to a space, an
    // unwritten binary tail to NUL, and a CRLF-converting transfer leaves a stray CR - all padding, none
    // data. Leading characters are never touched, because String.strip() would erase a leading blank the
    // legacy key actually contained (CASH00.cbl:L111 keeps the caller's name verbatim inside the key).
    public static String trimPadding(String rawField) {
        if (rawField == null) {
            return null;
        }
        int end = rawField.length();
        while (end > 0 && rawField.charAt(end - 1) <= ' ') {
            end--;
        }
        return rawField.substring(0, end);
    }

    // "Parses to zero" rather than equality with a literal, because the text is not predictable:
    // WS-RETCODE X(10) starts as spaces (CASH00.cbl:L78) and is filled by a numeric-to-alphanumeric MOVE
    // of SQLCODE (CASH00.cbl:L104, and L117 for the history copy) that drops the sign, so -803 and +803
    // both arrive as "000000803". Only zero is unambiguous; every other value has an unknowable sign.
    public static boolean isSuccessRetcode(String retcode) {
        if (retcode == null) {
            return false;
        }
        String trimmed = retcode.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            // BigDecimal, not a primitive parse: the digits are money-path-adjacent text of unbounded
            // width, and no binary floating-point type is permitted in this module (AAP 0.7.1).
            return new BigDecimal(trimmed).signum() == 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    // Input bounds. The tooling's input is a file produced outside this repository - a DB2 UNLOAD, an IDCAMS
    // REPRO or a captured shadow window - so a truncated transfer, a wrong delimiter or an unclosed quote
    // arrives as one field, record or window of arbitrary size, and a parser that accepts it exhausts the tool
    // JVM instead of naming the line to open. Each limit below is derived from a shape this class already
    // declares, so none is a number an operator has to tune: a well-formed export is nowhere near any of them.

    // The widest field any declared shape carries is the 36-character incarnation_id UUID of the rollback
    // replay header (ROLLBACK_INCARNATION_ID_COLUMN); owner is CHAR(32) (DCLCASH.cpy:L9), a balance renders
    // as 11 characters at most (9(7)V99 with a sign and a point) and retcode is X(10) (CASH00.cbl:L45). 256
    // leaves room for CHAR padding and for RFC 4180 quote doubling while still bounding a single field.
    public static final int MAX_FIELD_CHARACTERS = 256;

    // The widest declared shape names eight columns (ROLLBACK_REPLAY_COLUMNS), so 64 tolerates an export
    // that grew several columns this module does not read and still refuses a line whose delimiter was
    // mis-declared - the case that turns one record into thousands of empty fields.
    public static final int MAX_FIELDS_PER_RECORD = 64;

    // Derived, so the record cap introduces no independent number to justify: the widest record this class
    // will accept is every permitted field at its permitted width, plus one delimiter each. The sum is capped
    // as well as the two parts because the parts bound it only while BOTH of them hold - widen either one and
    // this is the limit that still stands, which is exactly what a derived bound is for.
    public static final int MAX_RECORD_CHARACTERS = MAX_FIELDS_PER_RECORD * (MAX_FIELD_CHARACTERS + 1);

    // No declared field carries a newline, so a well-formed record occupies exactly one physical line. The
    // budget exists only because the reader honours RFC 4180 embedded newlines: an unclosed quote otherwise
    // swallows the rest of the file into one record, and 16 lines is far more than any producer of these
    // shapes needs while still catching that at the sixteenth line rather than at the millionth.
    public static final int MAX_LINES_PER_RECORD = 16;

    // The list-returning reads serve a CAPTURED SHADOW WINDOW - reviewed row by row at runbook Step 2 - and
    // the small committed fixtures, never a bulk export: a whole DB2 UNLOAD or a REPRO of a production KSDS
    // is read through the streaming forms, which stay one record resident (AAP 0.12.1). 100 000 records is
    // therefore a ceiling on a reviewable window rather than on a migration, and a file that exceeds it is
    // either the wrong kind of input or a window that has to be split before anyone can sign it off.
    public static final int MAX_WINDOW_RECORDS = 100_000;

    /** Refuses an open delimited field that has already reached {@link #MAX_FIELD_CHARACTERS}. */
    // Checked as the field is scanned rather than after it is built, because a field that is allowed to grow
    // first has already cost the memory the limit exists to bound.
    public static void requireFieldWithinLimit(int fieldCharacters, Path file, int lineNumber) {
        if (fieldCharacters >= MAX_FIELD_CHARACTERS) {
            throw new IllegalArgumentException("A field on " + describeLine(file, lineNumber) + " is wider than"
                    + " the " + MAX_FIELD_CHARACTERS + " characters an export field may carry (the widest shape"
                    + " declared for this export is a 36-character UUID). An unclosed quote or a delimiter the"
                    + " file was not written with is the usual cause: correct the producer, or split the value");
        }
    }

    /** Refuses an open delimited record whose fields together have reached {@link #MAX_RECORD_CHARACTERS}. */
    public static void requireRecordWithinLimit(int recordCharacters, Path file, int lineNumber) {
        if (recordCharacters >= MAX_RECORD_CHARACTERS) {
            throw new IllegalArgumentException("The record being read at " + describeLine(file, lineNumber)
                    + " carries more than the " + MAX_RECORD_CHARACTERS + " characters an export record may"
                    + " carry across its fields. Check the delimiter and the record separator the export was"
                    + " written with, then re-transfer it");
        }
    }

    /** Refuses an open delimited record that has already reached {@link #MAX_FIELDS_PER_RECORD} fields. */
    public static void requireFieldCountWithinLimit(int fieldCount, Path file, int lineNumber) {
        if (fieldCount >= MAX_FIELDS_PER_RECORD) {
            throw new IllegalArgumentException("The row on " + describeLine(file, lineNumber) + " names more"
                    + " than the " + MAX_FIELDS_PER_RECORD + " fields an export record may name; the widest"
                    + " shape this module reads names " + ROLLBACK_REPLAY_COLUMNS.size() + " columns. Check"
                    + " that the export was written with ',' as its field delimiter");
        }
    }

    /** Refuses a record whose quoted field has already spanned {@link #MAX_LINES_PER_RECORD} physical lines. */
    public static void requireRecordLinesWithinLimit(int recordLines, Path file, int recordStartLine) {
        if (recordLines >= MAX_LINES_PER_RECORD) {
            throw new IllegalArgumentException("The record beginning on " + describeLine(file, recordStartLine)
                    + " spans more than the " + MAX_LINES_PER_RECORD + " physical lines an export record may"
                    + " span. No column of this export carries a newline, so a quote that is never closed is"
                    + " the usual cause: close it, or repeat it inside the quoted value as RFC 4180 requires");
        }
    }

    /** Refuses a list-returning read that has already collected {@link #MAX_WINDOW_RECORDS} records. */
    // Checked as the rows are collected, so the list can never exceed the limit rather than being measured
    // once it already does.
    public static void requireWindowWithinLimit(long recordsCollected, Path file) {
        if (recordsCollected >= MAX_WINDOW_RECORDS) {
            throw new IllegalArgumentException("The file '" + file + "' holds more than the "
                    + MAX_WINDOW_RECORDS + " records a read that returns a list will hold in memory. A bulk"
                    + " export is read through the streaming form of this read, which hands on one record at a"
                    + " time; a captured window has to be split into windows of at most " + MAX_WINDOW_RECORDS
                    + " records, which is also what makes one reviewable");
        }
    }

    // Input path containment. The tooling reaches exactly two things: ordinary files beneath the directory
    // named by tool.input, and the configured PostgreSQL datasource (AAP 0.3.2). A child name resolved with
    // Path.resolve and tested with a link-following Files.isRegularFile satisfies neither half: a symbolic link
    // named cashaccounty.csv is any file this process may read, staged as legacy data and echoed in failure
    // messages. The rules below are the whole of what an accepted input path is, and every reader and
    // resolver takes them from here so one refusal vocabulary reaches the operator.

    // A child name can only ever be one of the seven declared names, which also bars traversal through the
    // name itself - no '..', no absolute path and no nested directory can be requested.
    public static final Set<String> ACCEPTED_INPUT_FILE_NAMES = Set.of(
            CASH_ACCOUNT_FILE,
            RATE_TABLE_FILE,
            HISTORY_TEXT_FILE,
            HISTORY_BINARY_FILE,
            TARGET_STATE_FILE,
            SHADOW_TRANSACTIONS_FILE,
            SHADOW_LEGACY_RESPONSES_FILE);

    /**
     * An approved input directory: its trusted real path together with the identity of the directory object
     * that was actually validated.
     *
     * @param trustedRoot        the real path of the directory the operator named
     * @param directoryIdentity  the validated directory's own file key, which every later open re-checks
     */
    // An identity and not just a path, because a path is a name that is re-resolved on every use, so a directory
    // component renamed and replaced by a symbolic link after validation sends a later open somewhere else
    // entirely - and NOFOLLOW_LINKS cannot see it, because it refuses only a link in the FINAL component.
    // The file key is the directory OBJECT (its device and inode on a POSIX file system), so carrying it from
    // the validation to the open is what lets openExportFile prove it is reading inside the very directory
    // that was approved rather than inside whatever that name now leads to.
    public record ApprovedDirectory(Path trustedRoot, Object directoryIdentity) {

        public ApprovedDirectory {
            Objects.requireNonNull(trustedRoot, "An approved input directory must carry its trusted real path");
            Objects.requireNonNull(directoryIdentity,
                    "An approved input directory must carry the identity of the directory that was validated");
        }
    }

    /**
     * The trusted real path of an approved input directory: an existing, readable directory.
     *
     * @throws IllegalArgumentException when the path is absent, is not a directory, or is not readable
     */
    public static Path requireInputDirectory(Path requested) {
        return approveInputDirectory(requested).trustedRoot();
    }

    /**
     * Validates an input directory and captures the identity a later open will be measured against.
     *
     * @throws IllegalArgumentException when the path is absent, is not a directory, is not readable, or sits
     *         on a file system that cannot supply directory-relative operations
     */
    // toRealPath() DELIBERATELY FOLLOWS A LINK HERE. The operator named this directory on the command line,
    // so if it is their own symbolic link, the directory it points at is what they asked the tool to read -
    // and that directory's real path and identity are then what every child is measured against. What may
    // not escape is a CHILD, and what may not change underneath the run is the directory itself.
    public static ApprovedDirectory approveInputDirectory(Path requested) {
        if (requested == null) {
            throw new IllegalArgumentException("tool.input is required: it names the directory holding the"
                    + " export or the captured window to be processed");
        }
        Path trustedRoot;
        try {
            trustedRoot = requested.toRealPath();
        } catch (IOException unreachable) {
            throw new IllegalArgumentException("tool.input must name an existing directory, but '" + requested
                    + "' does not resolve to one: " + unreachable.getMessage(), unreachable);
        }
        if (!Files.isDirectory(trustedRoot)) {
            throw new IllegalArgumentException("tool.input must name an existing directory, but '" + trustedRoot
                    + "' is not one");
        }
        if (!Files.isReadable(trustedRoot)) {
            throw new IllegalArgumentException("tool.input directory '" + trustedRoot + "' is not readable by"
                    + " this process");
        }
        // The identity is read THROUGH the open directory rather than from its path, so it describes the
        // directory this validation actually inspected.
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(trustedRoot)) {
            SecureDirectoryStream<Path> secure = requireDirectoryRelativeOperations(directory, trustedRoot);
            Object identity = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
            if (identity == null) {
                // Fail closed rather than fall back to pathnames: without an identity there is nothing a
                // later open could re-check, so the containment this class promises could not be kept.
                throw new IllegalArgumentException("tool.input directory '" + trustedRoot + "' reports no file"
                        + " identity, so a read of it could not be shown to stay inside the directory that was"
                        + " approved; run the tooling against a directory on a file system that reports one");
            }
            return new ApprovedDirectory(trustedRoot, identity);
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("tool.input directory '" + trustedRoot + "' could not be opened"
                    + " for reading: " + unreadable.getMessage(), unreadable);
        }
    }

    /**
     * Resolves one declared export file beneath an approved input directory, contained and link-free.
     *
     * <p>A file that is not there is returned as the contained path it would occupy rather than refused: the
     * loader and the reconciliation deliberately resolve files that may be absent, so that the reader which
     * opens one names both the missing file and the column shape it wanted.</p>
     *
     * @throws IllegalArgumentException when the name is not a declared export file name, when the directory
     *         is not an approved input directory, or when the child is a symbolic link, is not an ordinary
     *         file, or lies outside the directory
     */
    public static Path resolveInputFile(Path inputDirectory, String declaredFileName) {
        requireDeclaredFileName(declaredFileName);
        Path trustedRoot = requireInputDirectory(inputDirectory);
        Path child = trustedRoot.resolve(declaredFileName);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            return child;
        }
        requireNotASymbolicLink(child);
        requireOrdinaryFile(child);

        Path realChild;
        try {
            realChild = child.toRealPath();
        } catch (IOException unreachable) {
            throw new IllegalArgumentException("The tool input file '" + child + "' cannot be resolved to a"
                    + " real path, so it cannot be shown to lie beneath '" + trustedRoot + "': "
                    + unreachable.getMessage(), unreachable);
        }
        // The last containment check, and the one a link-free child can still fail: a bind mount or a
        // reparse point under the trusted root resolves elsewhere without ever being a symbolic link.
        if (!trustedRoot.equals(realChild.getParent())) {
            throw new IllegalArgumentException("The tool input file '" + child + "' resolves to '" + realChild
                    + "', which is not a child of the approved input directory '" + trustedRoot + "'; a tool"
                    + " input file never escapes the directory tool.input named");
        }
        return realChild;
    }

    /**
     * The open-time guard every export reader applies: an ordinary, readable, link-free file.
     *
     * @return {@code file} unchanged, so a reader can guard and open in one expression
     * @throws IllegalArgumentException when the file is a symbolic link, is not an ordinary file, or is not
     *         readable by this process
     */
    // An absent file is deliberately NOT refused here: the reader that is about to open it names the file
    // and the column shape or record length it wanted, which is the message an operator can act on, and
    // pre-empting it with a generic one would lose that. Presence is therefore the opener's failure, and
    // this guard exists for the substitutions a path cannot be trusted about - which is also why every
    // reader opens with LinkOption.NOFOLLOW_LINKS afterwards rather than trusting this check to hold.
    public static Path requireReadableExportFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("An export file path is required; none was supplied");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return file;
        }
        requireNotASymbolicLink(file);
        requireOrdinaryFile(file);
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("The tool input file '" + file + "' is not readable by this"
                    + " process");
        }
        return file;
    }

    /**
     * Whether a declared export file is present in an approved directory as an ordinary file.
     *
     * <p>Judged through the approved directory itself rather than by pathname, so a symbolic link cannot
     * answer "this shape is present" and a substituted directory cannot answer at all.</p>
     */
    public static boolean isExportFilePresent(ApprovedDirectory approvedDirectory, String declaredFileName) {
        requireDeclaredFileName(declaredFileName);
        Objects.requireNonNull(approvedDirectory, "An approved input directory is required");
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(approvedDirectory.trustedRoot())) {
            SecureDirectoryStream<Path> secure =
                    requireSameDirectory(directory, approvedDirectory);
            return readChildAttributes(secure, declaredFileName,
                    approvedDirectory.trustedRoot().resolve(declaredFileName)) != null;
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("The approved input directory '"
                    + approvedDirectory.trustedRoot() + "' could not be read to see whether it holds '"
                    + declaredFileName + "': " + unreadable.getMessage(), unreadable);
        }
    }

    /**
     * One export file, opened the only two ways this tooling opens a file at all.
     *
     * <p>{@link #inApprovedDirectory} is the form every {@code tool.input} read uses: the file is opened
     * relative to the approved directory itself, so neither the directory nor the child can be substituted
     * between validation and open. {@link #named} is the form for a file a caller names outright - a
     * target-state export, a fixture - where there is no approved directory for containment to mean
     * anything, and only link-refusal applies.</p>
     *
     * @param path              the file's path, which is what failures name
     * @param approvedDirectory the directory the open is anchored to, or {@code null} for a named file
     */
    public record ExportFile(Path path, ApprovedDirectory approvedDirectory) {

        public ExportFile {
            Objects.requireNonNull(path, "An export file must carry the path its failures name");
        }

        /** The declared export file {@code declaredFileName} inside {@code approvedDirectory}. */
        public static ExportFile inApprovedDirectory(ApprovedDirectory approvedDirectory,
                                                     String declaredFileName) {
            Objects.requireNonNull(approvedDirectory, "An approved input directory is required");
            requireDeclaredFileName(declaredFileName);
            return new ExportFile(approvedDirectory.trustedRoot().resolve(declaredFileName), approvedDirectory);
        }

        /** A file a caller names outright, with no approved directory to anchor the open to. */
        public static ExportFile named(Path file) {
            return new ExportFile(file, null);
        }

        /**
         * The stream a reader consumes, opened link-free and - where there is one - inside the approved
         * directory.
         *
         * @throws NoSuchFileException when the file is not there, so a reader can name it and the shape it
         *         wanted
         */
        public InputStream open() throws IOException {
            return Channels.newInputStream(openChannel());
        }

        /**
         * The channel a fixed-record reader consumes, whose {@code size()} describes the object that was
         * opened rather than a pathname looked up beside it.
         *
         * @throws IllegalArgumentException when the file is a symbolic link, is not an ordinary file, or the
         *         approved directory has been substituted since it was validated
         * @throws NoSuchFileException when the file is not there
         */
        public SeekableByteChannel openChannel() throws IOException {
            return approvedDirectory == null
                    ? openNamedFile(path)
                    : openWithinApprovedDirectory(approvedDirectory, fileName(), path);
        }

        /** The file's name, which for an anchored open is the declared name the open uses. */
        public String fileName() {
            return path.getFileName().toString();
        }
    }

    // The whole point of the anchored open: everything here is done relative to the open directory rather
    // than to its name: the directory is opened once, its identity is checked against the one the validation
    // captured, the child's attributes are read through that same open directory, and the child is finally
    // opened through it with no link followed. A directory component renamed and replaced by a symbolic link
    // after validation therefore fails the identity check instead of redirecting the read, which pathname
    // resolution plus NOFOLLOW_LINKS cannot do - NOFOLLOW_LINKS refuses only a link in the final component
    // (AAP 0.3.2).
    //
    // The residual, stated rather than implied: between the child's attribute read and its open, both of
    // which go through the same directory handle, the name could be replaced by another object in the SAME
    // approved directory. A link is still refused by NOFOLLOW_LINKS on the open, so that substitution cannot
    // leave the directory - it needs write access inside the approved directory, and the worst it achieves
    // is a read of another object the operator placed there.
    private static SeekableByteChannel openWithinApprovedDirectory(ApprovedDirectory approvedDirectory,
                                                                   String declaredFileName,
                                                                   Path childPath) throws IOException {
        try (DirectoryStream<Path> directory =
                     Files.newDirectoryStream(approvedDirectory.trustedRoot())) {
            SecureDirectoryStream<Path> secure = requireSameDirectory(directory, approvedDirectory);
            if (readChildAttributes(secure, declaredFileName, childPath) == null) {
                throw new NoSuchFileException(childPath.toString());
            }
            // A channel opened from a directory stream is an independent handle, so closing the stream here
            // does not affect the read that follows.
            return secure.newByteChannel(Path.of(declaredFileName),
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private static SeekableByteChannel openNamedFile(Path file) throws IOException {
        requireReadableExportFile(file);
        return Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    /** The child's attributes read through the open directory, or {@code null} when the child is absent. */
    private static BasicFileAttributes readChildAttributes(SecureDirectoryStream<Path> directory,
                                                           String declaredFileName,
                                                           Path childPath) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = directory
                    .getFileAttributeView(Path.of(declaredFileName), BasicFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
        } catch (NoSuchFileException absent) {
            return null;
        }
        if (attributes.isSymbolicLink()) {
            throw symbolicLinkRefused(childPath);
        }
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("The tool input file '" + childPath + "' is not an ordinary"
                    + " file; tool.input holds the declared export files and nothing else (AAP 0.3.2)");
        }
        return attributes;
    }

    // Both checks in one place because they are one question: is this the directory that was approved, opened
    // in a way that lets its children be reached without resolving their names again?
    private static SecureDirectoryStream<Path> requireSameDirectory(DirectoryStream<Path> directory,
                                                                    ApprovedDirectory approvedDirectory)
            throws IOException {
        SecureDirectoryStream<Path> secure =
                requireDirectoryRelativeOperations(directory, approvedDirectory.trustedRoot());
        Object identity = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
        if (!approvedDirectory.directoryIdentity().equals(identity)) {
            throw new IllegalArgumentException("The directory '" + approvedDirectory.trustedRoot()
                    + "' is no longer the directory that was approved for this run: the name now leads to a"
                    + " different directory, so the read is refused rather than followed (AAP 0.3.2). Re-run"
                    + " the command against a directory that is not being changed underneath it");
        }
        return secure;
    }

    private static SecureDirectoryStream<Path> requireDirectoryRelativeOperations(DirectoryStream<Path> directory,
                                                                                  Path trustedRoot) {
        if (directory instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        // Fail closed where the guarantee cannot be had: without directory-relative operations every open is
        // a fresh pathname resolution, which is exactly what this class refuses to rely on.
        throw new IllegalArgumentException("The file system holding '" + trustedRoot + "' does not support"
                + " directory-relative reads, so a tool input file could not be shown to stay inside the"
                + " approved directory; run the tooling against a local file system that does");
    }

    private static void requireDeclaredFileName(String declaredFileName) {
        if (!ACCEPTED_INPUT_FILE_NAMES.contains(declaredFileName)) {
            throw new IllegalArgumentException("'" + declaredFileName + "' is not one of the export file names"
                    + " this module reads " + new TreeSet<>(ACCEPTED_INPUT_FILE_NAMES) + "; a tool input file"
                    + " is resolved by declared name only, never by a name a caller composes");
        }
    }

    // One wording for a symlinked input, at the resolution layer and at the open layer alike, so that an
    // operator reading a failure and a test asserting one match on the same two things: the words "symbolic
    // link" and the path that carries it. The link's target is deliberately not echoed - it is precisely the
    // path this refusal declines to read, and naming it would put it in the tool's log instead.
    private static IllegalArgumentException symbolicLinkRefused(Path file) {
        return new IllegalArgumentException("The tool input file '" + file + "' is a symbolic link, and a"
                + " symbolic link is never read: only an ordinary file beneath the directory tool.input"
                + " names is an input of this tooling (AAP 0.3.2), because a link can name any file this"
                + " process may open and its content would then be staged as legacy data. Replace the link"
                + " with the export file itself");
    }

    private static void requireNotASymbolicLink(Path file) {
        if (Files.isSymbolicLink(file)) {
            throw symbolicLinkRefused(file);
        }
    }

    private static void requireOrdinaryFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("The tool input file '" + file + "' is not an ordinary file;"
                    + " tool.input holds the declared export files and nothing else (AAP 0.3.2)");
        }
    }

    // The one rendering of a file position in this class's messages, matching the readers' own so that a
    // limit failure and a parse failure name a line an operator can open in the same words.
    private static String describeLine(Path file, int lineNumber) {
        return "line " + lineNumber + " of " + file;
    }

    // Header cells are identifiers, not values, so both ends are trimmed and the name is folded to lower
    // case - unlike trimPadding, which must leave a value's leading content alone.
    private static String normalize(String headerName) {
        if (headerName == null) {
            return "";
        }
        return headerName.trim().toLowerCase(Locale.ROOT);
    }
}
