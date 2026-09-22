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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The accepted shapes of a legacy cash-account export: file names, delimited headers, fixed-record offsets and value conventions.
 *
 * @see "backend/cash-account-modernized/docs/legacy-characterization.md"
 */
// Every number and column name below is a declaration of legacy structure, verified against the
// READ-ONLY sources cited beside it, and this class is the single place any of them is written down:
// export/VsamHistoryRecordDecoder, export/DelimitedExportReader, load/LegacyLoader,
// reconcile/ReconciliationService, shadow/ShadowComparator and MigrationToolRunner all read them from
// here, so a literal repeated in one of those is drift waiting to happen.
//
// The class declares shape and nothing else: no reader, no connection, no data set name, no host and no
// credential appears here or may be added, because the tooling is proven against fixtures only and the
// live mainframe is a handoff, not a dependency (AAP 0.3.2). Values that the operator must be able to
// correct without a rebuild - the legacy code page and the binary record length - are bound from
// tool.* properties by MigrationToolRunner; this class contributes only their defaults and the set of
// values it will accept.
public final class LegacyExportFormat {

    private LegacyExportFormat() {
    }

    // ------------------------------------------------------------------------------------------
    // File names
    //
    // Bare names, never paths: callers resolve them against tool.input, which is a fixture directory
    // under test and an operator-supplied export directory in a runbook rehearsal. The seven names are
    // also the fixture file names under src/test/resources/fixtures/legacy-export/{matched,
    // seeded-mismatch}/ and fixtures/shadow/{matched,seeded-mismatch}/, so a rename here breaks those
    // tests on file-not-found rather than on behaviour.

    public static final String CASH_ACCOUNT_FILE = "cashaccounty.csv";

    public static final String RATE_TABLE_FILE = "frankfurt1.csv";

    public static final String HISTORY_TEXT_FILE = "history.csv";

    // Named for the code page it is expected to carry (IBM037 is IBM's CP037), which is a reminder in
    // the file name and never an override: the effective charset is DEFAULT_LEGACY_CHARSET or whatever
    // tool.legacy-charset supplies.
    public static final String HISTORY_BINARY_FILE = "history.cp037.bin";

    public static final String TARGET_STATE_FILE = "target-state.csv";

    public static final String SHADOW_TRANSACTIONS_FILE = "transactions.csv";

    public static final String SHADOW_LEGACY_RESPONSES_FILE = "legacy-responses.csv";

    // ------------------------------------------------------------------------------------------
    // Delimited column names
    //
    // A column name is part of one file's shape, so each file declares its own even where the text
    // repeats across files: the shapes are independent contracts, and a future export that renames one
    // file's column must not silently rename another's. Readers bind by these names through
    // indexHeader; nothing in the tooling may bind by ordinal position (AAP 0.12.2 - EBCDIC and ASCII
    // collate differently, so neither row order nor column order survives the conversion as meaning).

    // STOCKTRD.CASHACCOUNTY, in the declared column order (DCLCASH.cpy:L9-L11, DB2DDL.jcl:L47-L49).
    public static final String CASH_ACCOUNT_OWNER_COLUMN = "owner";

    public static final String CASH_ACCOUNT_BALANCE_COLUMN = "balance";

    public static final String CASH_ACCOUNT_CURRENCY_COLUMN = "currencyc";

    public static final List<String> CASH_ACCOUNT_COLUMNS =
            List.of(CASH_ACCOUNT_OWNER_COLUMN, CASH_ACCOUNT_BALANCE_COLUMN, CASH_ACCOUNT_CURRENCY_COLUMN);

    // An alias rather than a second list: target-state.csv is a divergent *migrated* state in the same
    // column shape as the legacy export (AAP 0.10.3), and reconciliation compares the two file by file,
    // so the day the account shape changes both must change together or the comparison is meaningless.
    public static final List<String> TARGET_STATE_COLUMNS = CASH_ACCOUNT_COLUMNS;

    // STOCKTRD.FRANKFURT1, in the declared column order (DCLFRANK.cpy:L9-L13, DB2DDL.jcl:L55-L59).
    public static final String RATE_KEY_COLUMN = "currnkey";

    // Both spellings are accepted because the two legacy artifacts disagree and this repository cannot
    // say which describes the deployed catalog: the DDL declares "cyrrnbase" (DB2DDL.jcl:L56) while the
    // copybook declares CURRNBASE (DCLFRANK.cpy:L10) and the program's SELECT names CURRNBASE
    // (CASH00.cbl:L215, L249) - as written the program would not precompile against that DDL. Guessing
    // is prohibited, so the reader tolerates either header (AAP 0.11.2, characterization 9.7). Nothing
    // is lost by the tolerance: the column is staged and never read by any arithmetic.
    public static final String RATE_BASE_COLUMN = "currnbase";

    public static final String RATE_BASE_COLUMN_ALIAS = "cyrrnbase";

    // Staged, never computed with. The rate SELECT fetches AMOUNT (CASH00.cbl:L215, L249) and no
    // COMPUTE or MOVE in the program references it; the multiplicand of the legacy arithmetic is the
    // caller's COMMAREA amount (AAP 0.4.1). It is declared here because the export carries it.
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

    // A captured shadow-mode window: the replayed requests and the legacy replies they are compared
    // with, joined on seq plus the normalized owner.
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

    // The rollback replay file of the cutover procedure (AAP 0.12.1): one line per owner touched after
    // the ledger watermark, carrying that owner's absolute end state for replay through the legacy
    // A/U/X codes. Its header lives here for the same reason every other shape does - RollbackReplayFileTest
    // and the derivation it checks must read one declaration, not two that can diverge. Absolute state
    // is what makes the file replayable at all: C and D would depend on exchange rates, and A/U/X do not.
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

    /** Resolves the rate table's base-currency column from a header, accepting either legacy spelling. */
    public static String resolveRateBaseColumn(Collection<String> headerNames) {
        if (headerNames == null) {
            throw new IllegalArgumentException("A " + RATE_TABLE_FILE + " header is required; none was read");
        }
        // The copybook spelling wins when both are present: it is the one the program's SELECT names
        // (CASH00.cbl:L215), so an export that carries both columns is far likelier to have been
        // produced from the real catalog under that name than under the DDL's.
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

    /** Maps each column name of a delimited header to its zero-based position, so readers bind by name. */
    // The one binding mechanism in the tooling, and the reason there is only one: a reader that fell
    // back to ordinal positions would still appear to work on a re-ordered export and would silently
    // load balances into the currency column. Names are matched case- and padding-insensitively because
    // a mainframe UNLOAD may upper-case its header and may pad it like any other CHAR value.
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

    /** Returns the position of a required column in an indexed header, or fails naming what is missing. */
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

    // ------------------------------------------------------------------------------------------
    // Fixed-record (binary history) layout

    /** One fixed-position field of the legacy history record. */
    public record FixedField(String name, int offset, int length) {

        public FixedField {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A fixed field must be named");
            }
            // A negative offset or length is a transcription error in this class, not bad input, and it
            // would corrupt every decoded row rather than fail: the guard turns it into a load-time
            // failure the very first test hits.
            if (offset < 0) {
                throw new IllegalArgumentException("Field '" + name + "' has a negative offset: " + offset);
            }
            if (length <= 0) {
                throw new IllegalArgumentException("Field '" + name + "' has a non-positive length: " + length);
            }
        }
    }

    // WS-VSAM-RECORD, field by field, from CASH00.cbl:L38-L45: X(15), X(08), X(06), X(1), 9(7)V99
    // (nine digit positions, no sign nibble), X(8), X(10). The offsets are the accumulated widths -
    // 0, 15, 23, 29, 30, 39, 47 - and the last field ends at 57, which is the LENGTH OF
    // WS-VSAM-RECORD the program writes (CASH00.cbl:L128).
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

    // WS-VSAM-KEY is NAME + DATE + TIME at offset 0 (CASH00.cbl:L47-L50), which is the cluster's
    // KEYS(29 0) (DEFKSDS.jcl:L14). The key includes the caller's own casing of the name, so two
    // records differing only in case are two valid keys and neither may be folded away on import.
    public static final int HISTORY_KEY_LENGTH = 29;

    // WS-VR-BALANCE is unsigned zoned decimal 9(7)V99 (CASH00.cbl:L43): nine digit positions and an
    // implied point, with no sign nibble and no separator in the data. After code-page conversion the
    // field is nine digit characters, and the only correct read is those digits as text into a
    // BigDecimal with the point shifted left by MONEY_SCALE - never through a binary floating-point
    // type, which reintroduces representation error into values COBOL held exactly and scatters
    // penny-level differences across a reconciliation run (AAP 0.7.1, 0.12.2).
    public static final int HISTORY_BALANCE_DIGITS = 9;

    public static final int MONEY_SCALE = 2;

    // The default only. The region's real CCSID is recorded nowhere in the legacy module - CCSID EBCDIC
    // (DB2DDL.jcl:L22) names an encoding family, not a code page - so the effective charset arrives
    // from tool.legacy-charset (AAP 0.11.2). A wrong code page has to be correctable by configuration
    // inside a migration window; a constant would make it correctable only by a rebuild.
    public static final String DEFAULT_LEGACY_CHARSET = "IBM037";

    /** Whether a declared binary history record length is one this module will decode. */
    // Both lengths are accepted because the two legacy artifacts disagree and the artifact that
    // reconciles them is not in this repository: CASH00 writes 57 bytes (CASH00.cbl:L38-L45, L128) into
    // a cluster defined RECSZ(100 100) (DEFKSDS.jcl:L11). A fixed-format CICS FILE definition would have
    // raised LENGERR, which the program does not suppress (only NOTOPEN and DUPREC are ignored,
    // CASH00.cbl:L123-L124); a variable-format one would hold 57-byte records. Which is on disk is
    // settled by the FCT/CSD FILE attributes, an open item (AAP 0.11.2, characterization 9.5).
    public static boolean isAcceptedHistoryRecordLength(int declaredLength) {
        return declaredLength == HISTORY_RECORD_LENGTH || declaredLength == HISTORY_PADDED_RECORD_LENGTH;
    }

    /** Validates a declared binary history record length, returning it unchanged when accepted. */
    public static int requireAcceptedHistoryRecordLength(int declaredLength) {
        if (!isAcceptedHistoryRecordLength(declaredLength)) {
            throw new IllegalArgumentException("tool.history-record-length must be " + HISTORY_RECORD_LENGTH
                    + " (what CASH00 writes) or " + HISTORY_PADDED_RECORD_LENGTH
                    + " (what the cluster declares), but was " + declaredLength
                    + "; the record length of a binary history export is declared, never inferred from the file");
        }
        return declaredLength;
    }

    /** Whether a byte occupies the unused tail of a padded history record rather than record content. */
    // Bytes 57-99 of a 100-byte record carry nothing the program wrote. EBCDIC blank (0x40) is what a
    // blank-padded fixed-length record holds and 0x00 is what an unwritten tail holds, and both are
    // accepted because the export step that produced the file is outside this repository (AAP 0.12.1).
    // A predicate rather than a byte[] constant: an exposed array would be mutable by every caller.
    public static boolean isPaddingByte(byte b) {
        return b == (byte) 0x40 || b == (byte) 0x00;
    }

    /**
     * The first index in {@code [from, toExclusive)} that is not padding, or {@code -1} when every byte is.
     *
     * @throws IllegalArgumentException when the range does not lie inside {@code data}
     */
    // The byte-range form of isPaddingByte, here rather than in the decoder so the accepted shape of a padded
    // record keeps one home: what a tail may hold is part of the export's format, exactly as the record length
    // and the field offsets above are. It returns the offending index rather than a boolean because the caller
    // has to tell the operator WHERE the frame broke - a tail that is neither EBCDIC blank nor zero means the
    // file was not framed at the declared length, and the one number that locates that is the offset.
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

    // ------------------------------------------------------------------------------------------
    // Shared value conventions

    // The delimited decimal shape: plain text, exactly two decimal places, no thousands separator and
    // no exponent. Anchored by matches() at every use, so a value carrying an exponent - which a
    // binary floating-point type's own text form emits and a BigDecimal would silently accept - is
    // rejected at the boundary instead of being loaded at an unintended scale.
    public static final Pattern DECIMAL_TEXT = Pattern.compile("-?\\d+\\.\\d{2}");

    // uuuu rather than yyyy throughout, because STRICT resolution rejects yyyy without an era: strict
    // parsing is the point, so that an impossible legacy stamp such as 20250231 fails the load rather
    // than being shifted to a neighbouring day and reconciled against the wrong window.
    public static final DateTimeFormatter LOADDT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter HISTORY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    public static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);

    // The six request codes of EVALUATE WS-REQ (CASH00.cbl:L89-L102), case-sensitive as the EVALUATE
    // is: a lowercase 'a' matched no branch there and must not match one here either.
    public static final Set<String> REQUEST_CODES = Set.of("A", "Q", "U", "X", "C", "D");

    // Q is excluded from transaction counts because a read changed no state: the ledger records state
    // changes only, so there is nothing on the target side to count a legacy Q against, and AAP 0.12.1
    // fixes the count filter as successful A/U/X/C/D. Legacy Q rows are still staged in legacy_history
    // for reference - excluded from the count, not discarded.
    public static final Set<String> COUNTED_REQUEST_CODES = Set.of("A", "U", "X", "C", "D");

    /** Whether a delimited field carries a decimal in the accepted export shape. */
    public static boolean isDecimalText(String rawField) {
        String trimmed = trimPadding(rawField);
        return trimmed != null && DECIMAL_TEXT.matcher(trimmed).matches();
    }

    /** Whether a raw export field represents a legacy NULL. */
    // A DB2 delimited unload writes NULL as an empty unquoted field, and the literal text NULL is a
    // value rather than a null - so this test is emptiness after padding is trimmed and the tooling
    // never special-cases the string "NULL" anywhere. The documented consequence is that an all-blank
    // CHAR value is indistinguishable from NULL once its padding is gone (AAP 0.12.1, 0.12.2): legacy
    // CHAR columns are blank-padded, nothing in the export distinguishes "all blanks" from "absent",
    // and both classify as NULL_IN_LEGACY, which is a variance an operator reviews rather than a value
    // this class may invent.
    public static boolean isNull(String rawField) {
        String trimmed = trimPadding(rawField);
        return trimmed == null || trimmed.isEmpty();
    }

    /** Right-trims the padding a legacy CHAR or PIC X field carries, leaving leading content untouched. */
    // Right only, and every character at or below U+0020: EBCDIC blank 0x40 decodes to a space, an
    // unwritten binary tail decodes to NUL, and a text export transferred through a CRLF-converting
    // channel leaves a stray CR - all three are padding, none is data. Leading characters are never
    // touched because an owner or currency value is meaningful exactly as stored, and String.strip()
    // would erase a leading blank that the legacy key actually contained (CASH00.cbl:L111 preserves the
    // caller's name verbatim, and that name is part of the 29-byte key).
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

    /** Whether a legacy return code represents success. */
    // The test is "parses to zero" rather than equality with a literal, because the field's text is
    // not predictable: WS-RETCODE X(10) starts as spaces (CASH00.cbl:L78) and is then filled by a
    // numeric-to-alphanumeric MOVE of SQLCODE (CASH00.cbl:L104, and L117 for the history copy), which
    // renders the absolute digits and drops the sign - so -803 and +803 both arrive as "000000803",
    // padding and digit count vary with the producer, and only zero is unambiguous. Every non-zero
    // value has an unknowable sign and is therefore never treated as success.
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
            // width, and no binary floating-point type is permitted anywhere in this module (AAP 0.7.1).
            return new BigDecimal(trimmed).signum() == 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    // Header cells are identifiers rather than values, so both ends are trimmed here and the name is
    // folded to lower case - unlike trimPadding, which must leave a value's leading content alone.
    private static String normalize(String headerName) {
        if (headerName == null) {
            return "";
        }
        return headerName.trim().toLowerCase(Locale.ROOT);
    }
}
