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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the delimited half of a legacy export - a DB2 UNLOAD/DSNTIAUL text file - into this package's record types. */
// Hand-written instead of delegated to a CSV library because no CSV library appears in this module's
// dependency inventory (AAP 0.9.1), and adding one is a dependency change this plan does not authorize.
// The parser runs over the character stream rather than over pre-read lines because a quoted field may
// legally carry the delimiter, an escaped quote and a newline, none of which survives a line-at-a-time
// read or a String.split.
//
// Every file name, column name, decimal shape and date format is read from LegacyExportFormat and none
// is written down again here, so the export's shape keeps exactly one home.
public final class DelimitedExportReader {

    private static final char DELIMITER = ',';

    private static final char QUOTE = '"';

    // A UTF-8 byte-order mark decodes to this one character and, left in place, becomes part of the
    // first column's name - which then surfaces as a missing required column on a file that looks
    // correct in every editor.
    private static final char BOM = '\uFEFF';

    private static final char CR = '\r';

    private static final char LF = '\n';

    /** One data line of a delimited export, its fields keyed by the column names its header declared. */
    public record DelimitedRow(Path source, int lineNumber, Map<String, String> values) {

        public DelimitedRow {
            Objects.requireNonNull(source, "A delimited row must name the file it was read from");
            Objects.requireNonNull(values, "A delimited row must carry its fields, even when empty");
            if (lineNumber < 1) {
                throw new IllegalArgumentException(
                        "A delimited row's line number is 1-based so an operator can open it, but was " + lineNumber);
            }
            // A LinkedHashMap wrapper rather than Map.copyOf: a legacy NULL is carried as a null value
            // (see rawField) and Map.copyOf rejects null values outright.
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /** The column's text, right-trimmed of legacy CHAR padding, or {@code null} where the export held a NULL. */
        public String text(String column) {
            String raw = rawField(column);
            return raw == null ? null : LegacyExportFormat.trimPadding(raw);
        }

        public String requireText(String column) {
            String value = text(column);
            if (value == null) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " is an empty field, but that column is NOT NULL in the legacy catalog and a value is"
                        + " required there");
            }
            return value;
        }

        /** The column's decimal, built straight from its text, or {@code null} where the export held a NULL. */
        public BigDecimal decimal(String column) {
            String raw = rawField(column);
            if (raw == null) {
                return null;
            }
            String trimmed = LegacyExportFormat.trimPadding(raw);
            // Constructed from the digit text, never routed through a binary approximation type: those
            // reintroduce representation error into values COBOL held exactly and scatter penny-level
            // differences across a reconciliation run (AAP 0.7.1). The text is checked first so a value
            // carrying an exponent or a thousands separator is rejected here rather than loaded at an
            // unintended scale, and it is never rescaled - the export already carries the legacy scale.
            if (!LegacyExportFormat.isDecimalText(trimmed)) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " carries '" + trimmed + "', which is not the plain fixed-point decimal text a legacy"
                        + " delimited export declares");
            }
            return new BigDecimal(trimmed);
        }

        public BigDecimal requireDecimal(String column) {
            BigDecimal value = decimal(column);
            if (value == null) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " is an empty field, but a decimal value is required there");
            }
            return value;
        }

        /** The column's date parsed with the caller's declared format, or {@code null} where the export held a NULL. */
        public LocalDate date(String column, DateTimeFormatter format) {
            Objects.requireNonNull(format, "A date column needs the format its legacy export declares");
            String raw = rawField(column);
            if (raw == null) {
                return null;
            }
            String trimmed = LegacyExportFormat.trimPadding(raw);
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " carries '" + trimmed + "', which does not parse as the date format the legacy export"
                        + " declares", invalid);
            }
        }

        /** The column's whole number, or {@code null} where the export held a NULL. */
        public Long longValue(String column) {
            String raw = rawField(column);
            if (raw == null) {
                return null;
            }
            String trimmed = LegacyExportFormat.trimPadding(raw);
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " carries '" + trimmed + "', which is not a whole number", invalid);
            }
        }

        public long requireLong(String column) {
            Long value = longValue(column);
            if (value == null) {
                throw new IllegalArgumentException("Column '" + column + "' on " + describeLine(source, lineNumber)
                        + " is an empty field, but a whole number is required there");
            }
            return value;
        }

        // The NULL convention of a DB2 delimited unload, and the one rule the seeded fixtures test: a
        // legacy NULL is an EMPTY UNQUOTED field, so a quoted empty field is the empty string and the
        // literal text NULL is an ordinary value that is never special-cased (an account genuinely named
        // NULL must survive an import). Nulls are handed on untouched rather than defaulted, because
        // balance and currencyc (DB2DDL.jcl:L48-L49) and currnbase, amount and rates
        // (DB2DDL.jcl:L56-L58) are all nullable in the legacy catalog and deciding what a null means is
        // ReconciliationService.validateSource()'s job - it records NULL_IN_LEGACY, INVALID_IN_LEGACY or
        // NULL_RATE and declines to load the row. Inventing a zero here would erase exactly the variance
        // an operator has to review.
        //
        // A column absent from the header is a different failure from a null value and is raised as one:
        // a mistyped column name must never read back as "the export held no value".
        private String rawField(String column) {
            String key = columnKey(column);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Column '" + column + "' was not named by the header of " + source
                        + "; columns read: " + values.keySet());
            }
            return values.get(key);
        }
    }

    /** Reads every data line of a delimited export, checking first that its header names the required columns. */
    public List<DelimitedRow> readRows(Path file, List<String> requiredColumns) {
        Objects.requireNonNull(file, "A delimited export path is required");
        Objects.requireNonNull(requiredColumns,
                "A required-column list is needed; pass an empty list to read a file without a column check");
        ParsedFile parsed = parse(file);
        requireColumns(parsed, requiredColumns);
        return parsed.rows();
    }

    /** Reads an exported STOCKTRD.CASHACCOUNTY, or a target-state file in that same column shape. */
    public List<LegacyCashAccountRecord> readCashAccounts(Path file) {
        List<DelimitedRow> rows = readRows(file, LegacyExportFormat.CASH_ACCOUNT_COLUMNS);
        List<LegacyCashAccountRecord> accounts = new ArrayList<>(rows.size());
        for (DelimitedRow row : rows) {
            accounts.add(new LegacyCashAccountRecord(
                    row.requireText(LegacyExportFormat.CASH_ACCOUNT_OWNER_COLUMN),
                    row.decimal(LegacyExportFormat.CASH_ACCOUNT_BALANCE_COLUMN),
                    row.text(LegacyExportFormat.CASH_ACCOUNT_CURRENCY_COLUMN)));
        }
        return List.copyOf(accounts);
    }

    /** Reads an exported STOCKTRD.FRANKFURT1 rate table, staged for reconciliation and nothing else. */
    public List<LegacyRateRecord> readRates(Path file) {
        Objects.requireNonNull(file, "A rate-table export path is required");
        ParsedFile parsed = parse(file);
        // Either legacy spelling of the base-currency column is accepted because the two legacy
        // artifacts disagree and this repository cannot say which describes the deployed catalog: the
        // copybook and the program's SELECT name CURRNBASE (DCLFRANK.cpy:L10) while the shipped DDL
        // declares cyrrnbase (DB2DDL.jcl:L56), so as written the program would not precompile against
        // that DDL (AAP 0.11.2). The resolved spelling replaces the declared one in the required set, so
        // the column shape still has a single home.
        String baseColumn = resolveBaseColumn(parsed);
        List<String> required = new ArrayList<>(LegacyExportFormat.RATE_COLUMNS.size());
        for (String column : LegacyExportFormat.RATE_COLUMNS) {
            required.add(LegacyExportFormat.RATE_BASE_COLUMN.equals(column) ? baseColumn : column);
        }
        requireColumns(parsed, required);

        List<LegacyRateRecord> rates = new ArrayList<>(parsed.rows().size());
        for (DelimitedRow row : parsed.rows()) {
            rates.add(new LegacyRateRecord(
                    row.requireText(LegacyExportFormat.RATE_KEY_COLUMN),
                    row.text(baseColumn),
                    row.decimal(LegacyExportFormat.RATE_AMOUNT_COLUMN),
                    row.decimal(LegacyExportFormat.RATE_RATES_COLUMN),
                    row.date(LegacyExportFormat.RATE_LOAD_DATE_COLUMN, LegacyExportFormat.LOADDT_FORMAT)));
        }
        return List.copyOf(rates);
    }

    /** Reads a delimited conversion of the legacy VSAM history, yielding the records its binary decoder yields. */
    public List<VsamHistoryRecord> readHistory(Path file) {
        List<DelimitedRow> rows = readRows(file, LegacyExportFormat.HISTORY_COLUMNS);
        List<VsamHistoryRecord> history = new ArrayList<>(rows.size());
        for (DelimitedRow row : rows) {
            // The name keeps the caller's own casing and is never folded: CASH00.cbl:L111 moves WS-NAME
            // into the record with no case change, so "John"+stamp and "JOHN"+stamp are two separate
            // valid 29-byte KSDS keys and legacy_history keys on the raw name. Folding here would
            // collapse two real records into one. The date and time stay raw text because resolving them
            // to an instant needs the CICS region's zone, which arrives as tool.legacy-timezone and is
            // applied by load/LegacyLoader.
            history.add(new VsamHistoryRecord(
                    row.requireText(LegacyExportFormat.HISTORY_NAME_COLUMN),
                    row.requireText(LegacyExportFormat.HISTORY_DATE_COLUMN),
                    row.requireText(LegacyExportFormat.HISTORY_TIME_COLUMN),
                    row.requireText(LegacyExportFormat.HISTORY_REQUEST_CODE_COLUMN),
                    row.decimal(LegacyExportFormat.HISTORY_BALANCE_COLUMN),
                    row.text(LegacyExportFormat.HISTORY_CURRENCY_COLUMN),
                    row.text(LegacyExportFormat.HISTORY_RETCODE_COLUMN)));
        }
        return List.copyOf(history);
    }

    // Fields are keyed by the header's column names and never by ordinal position: EBCDIC and ASCII
    // collate differently (AAP 0.12.2), so neither column order nor row order survives the conversion as
    // meaning and a re-ordered export must still load into the columns it names. The file's physical row
    // order is preserved in the returned list because it is the file's order, never because a consumer
    // may rely on it - reconciliation joins on the normalized owner key.
    private static ParsedFile parse(Path file) {
        List<RawRecord> records = readRawRecords(file);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The delimited export " + file
                    + " holds no lines; its first line must be a header naming the columns");
        }
        RawRecord headerRecord = records.get(0);
        List<String> header = new ArrayList<>(headerRecord.fields().size());
        for (RawField cell : headerRecord.fields()) {
            header.add(cell.text());
        }
        Map<String, Integer> headerIndex;
        try {
            headerIndex = LegacyExportFormat.indexHeader(header);
        } catch (IllegalArgumentException unusable) {
            throw new IllegalArgumentException(
                    describeHeader(file, headerRecord.lineNumber()) + " cannot be indexed: " + unusable.getMessage(),
                    unusable);
        }

        List<DelimitedRow> rows = new ArrayList<>(records.size() - 1);
        for (int position = 1; position < records.size(); position++) {
            RawRecord record = records.get(position);
            if (record.fields().size() != header.size()) {
                throw new IllegalArgumentException("The row on " + describeLine(file, record.lineNumber())
                        + " carries " + record.fields().size() + " fields but the header names " + header.size()
                        + " columns " + headerIndex.keySet() + "; a name-keyed read needs one field per column");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> column : headerIndex.entrySet()) {
                values.put(column.getKey(), value(record.fields().get(column.getValue())));
            }
            rows.add(new DelimitedRow(file, record.lineNumber(), values));
        }
        return new ParsedFile(file, headerRecord.lineNumber(), header, headerIndex, List.copyOf(rows));
    }

    private static String value(RawField field) {
        return !field.quoted() && LegacyExportFormat.isNull(field.text()) ? null : field.text();
    }

    private static void requireColumns(ParsedFile parsed, List<String> requiredColumns) {
        for (String column : requiredColumns) {
            try {
                LegacyExportFormat.columnIndex(parsed.headerIndex(), column);
            } catch (IllegalArgumentException missing) {
                throw new IllegalArgumentException(
                        describeHeader(parsed.file(), parsed.headerLine()) + ": " + missing.getMessage(), missing);
            }
        }
    }

    private static String resolveBaseColumn(ParsedFile parsed) {
        try {
            return LegacyExportFormat.resolveRateBaseColumn(parsed.header());
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalArgumentException(
                    describeHeader(parsed.file(), parsed.headerLine()) + ": " + unresolved.getMessage(), unresolved);
        }
    }

    private static List<RawRecord> readRawRecords(Path file) {
        List<RawRecord> records = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)), 1)) {
            List<RawField> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quotedField = false;
            boolean inQuotes = false;
            boolean recordOpen = false;
            boolean atStartOfFile = true;
            int line = 1;
            int recordLine = 1;
            int read;
            while ((read = reader.read()) >= 0) {
                char character = (char) read;
                if (atStartOfFile) {
                    atStartOfFile = false;
                    if (character == BOM) {
                        continue;
                    }
                }
                if (inQuotes) {
                    if (character == QUOTE) {
                        int next = reader.read();
                        if (next == QUOTE) {
                            field.append(QUOTE);
                        } else {
                            inQuotes = false;
                            if (next >= 0) {
                                reader.unread(next);
                            }
                        }
                        continue;
                    }
                    if (character == LF) {
                        line++;
                    }
                    field.append(character);
                    continue;
                }
                if (character == QUOTE) {
                    if (quotedField || field.length() > 0) {
                        throw new IllegalArgumentException("A quote character appears inside an unquoted field on "
                                + describeLine(file, line) + "; RFC 4180 requires a field carrying a quote, a comma"
                                + " or a newline to be quoted, with its embedded quotes repeated");
                    }
                    quotedField = true;
                    inQuotes = true;
                    recordOpen = true;
                    continue;
                }
                if (character == DELIMITER) {
                    fields.add(new RawField(field.toString(), quotedField));
                    field.setLength(0);
                    quotedField = false;
                    recordOpen = true;
                    continue;
                }
                if (character == CR || character == LF) {
                    if (character == CR) {
                        int next = reader.read();
                        if (next >= 0 && next != LF) {
                            reader.unread(next);
                        }
                    }
                    // A physical line holding no characters at all is not a record: a file transfer
                    // routinely leaves a trailing newline, and treating that as a row would fail the
                    // field-count check on a file that is in fact well formed. A line beginning with '#'
                    // is data, though - the export contract reserves the first line for the header and
                    // defines no comment syntax (AAP 0.12.1).
                    if (recordOpen) {
                        fields.add(new RawField(field.toString(), quotedField));
                        records.add(new RawRecord(recordLine, List.copyOf(fields)));
                        fields = new ArrayList<>();
                        field.setLength(0);
                        quotedField = false;
                        recordOpen = false;
                    }
                    line++;
                    recordLine = line;
                    continue;
                }
                field.append(character);
                recordOpen = true;
            }
            if (inQuotes) {
                throw new IllegalArgumentException("A quoted field opened on " + describeLine(file, recordLine)
                        + " is never closed; RFC 4180 requires a closing quote character");
            }
            if (recordOpen) {
                fields.add(new RawField(field.toString(), quotedField));
                records.add(new RawRecord(recordLine, List.copyOf(fields)));
            }
        } catch (NoSuchFileException absent) {
            throw new IllegalArgumentException("The delimited export " + file + " does not exist", absent);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The delimited export " + file + " could not be read", unreadable);
        }
        return records;
    }

    // One normalization authority: a lookup key is produced by the same call that produced the key the
    // value was stored under, so the trim-and-fold rule can never drift between the two ends of a
    // name-keyed read.
    private static String columnKey(String column) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("A column name is required to read a delimited field");
        }
        return LegacyExportFormat.indexHeader(List.of(column)).keySet().iterator().next();
    }

    private static String describeLine(Path file, int lineNumber) {
        return "line " + lineNumber + " of " + file;
    }

    private static String describeHeader(Path file, int lineNumber) {
        return "The header on line " + lineNumber + " of " + file;
    }

    private record RawField(String text, boolean quoted) {
    }

    private record RawRecord(int lineNumber, List<RawField> fields) {
    }

    private record ParsedFile(
            Path file,
            int headerLine,
            List<String> header,
            Map<String, Integer> headerIndex,
            List<DelimitedRow> rows) {
    }
}
