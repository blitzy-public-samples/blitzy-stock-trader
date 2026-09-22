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
import java.util.function.Consumer;

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
        List<DelimitedRow> rows = new ArrayList<>();
        streamRows(file, requiredColumns, rows::add);
        return List.copyOf(rows);
    }

    /**
     * Hands every data line of a delimited export to {@code sink}, one row at a time.
     *
     * @return the number of data rows read, the header line excluded
     */
    // THE BOUNDED FORM, AND THE ONE EVERY OTHER READ HERE IS BUILT ON. A bulk export is a whole DB2 UNLOAD
    // of a production table (AAP 0.12.1), so no read may hold the file's characters, its delimited rows and
    // its typed records at once: one record is parsed, handed to the sink and released before the next is
    // read, which makes this reader's footprint one record rather than one file. The List-returning methods
    // collect over this, so a caller that genuinely wants a list holds exactly one representation of it.
    public long streamRows(Path file, List<String> requiredColumns, Consumer<DelimitedRow> sink) {
        Objects.requireNonNull(file, "A delimited export path is required");
        Objects.requireNonNull(requiredColumns,
                "A required-column list is needed; pass an empty list to read a file without a column check");
        Objects.requireNonNull(sink, "A row consumer is required to stream a delimited export");
        try (RecordCursor cursor = new RecordCursor(file)) {
            Header header = readHeader(cursor);
            requireColumns(header, requiredColumns);
            return streamBody(cursor, header, sink);
        }
    }

    /** Reads an exported STOCKTRD.CASHACCOUNTY, or a target-state file in that same column shape. */
    public List<LegacyCashAccountRecord> readCashAccounts(Path file) {
        List<LegacyCashAccountRecord> accounts = new ArrayList<>();
        streamCashAccounts(file, accounts::add);
        return List.copyOf(accounts);
    }

    /**
     * Hands every row of an exported STOCKTRD.CASHACCOUNTY to {@code sink}, one record at a time.
     *
     * @return the number of account rows read
     */
    public long streamCashAccounts(Path file, Consumer<LegacyCashAccountRecord> sink) {
        Objects.requireNonNull(sink, "An account-record consumer is required to stream an account export");
        return streamRows(file, LegacyExportFormat.CASH_ACCOUNT_COLUMNS, row -> sink.accept(cashAccount(row)));
    }

    /** Reads an exported STOCKTRD.FRANKFURT1 rate table, staged for reconciliation and nothing else. */
    public List<LegacyRateRecord> readRates(Path file) {
        List<LegacyRateRecord> rates = new ArrayList<>();
        streamRates(file, rates::add);
        return List.copyOf(rates);
    }

    /**
     * Hands every row of an exported STOCKTRD.FRANKFURT1 rate table to {@code sink}, one record at a time.
     *
     * @return the number of rate rows read
     */
    // Its own cursor rather than a call to streamRows, because the required-column set of this one file is
    // not known until its header has been read: the base-currency column carries either legacy spelling.
    public long streamRates(Path file, Consumer<LegacyRateRecord> sink) {
        Objects.requireNonNull(file, "A rate-table export path is required");
        Objects.requireNonNull(sink, "A rate-record consumer is required to stream a rate-table export");
        try (RecordCursor cursor = new RecordCursor(file)) {
            Header header = readHeader(cursor);
            // Either legacy spelling of the base-currency column is accepted because the two legacy
            // artifacts disagree and this repository cannot say which describes the deployed catalog: the
            // copybook and the program's SELECT name CURRNBASE (DCLFRANK.cpy:L10) while the shipped DDL
            // declares cyrrnbase (DB2DDL.jcl:L56), so as written the program would not precompile against
            // that DDL (AAP 0.11.2). The resolved spelling replaces the declared one in the required set, so
            // the column shape still has a single home.
            String baseColumn = resolveBaseColumn(header);
            List<String> required = new ArrayList<>(LegacyExportFormat.RATE_COLUMNS.size());
            for (String column : LegacyExportFormat.RATE_COLUMNS) {
                required.add(LegacyExportFormat.RATE_BASE_COLUMN.equals(column) ? baseColumn : column);
            }
            requireColumns(header, required);
            return streamBody(cursor, header, row -> sink.accept(rate(row, baseColumn)));
        }
    }

    /** Reads a delimited conversion of the legacy VSAM history, yielding the records its binary decoder yields. */
    public List<VsamHistoryRecord> readHistory(Path file) {
        List<VsamHistoryRecord> history = new ArrayList<>();
        streamHistory(file, history::add);
        return List.copyOf(history);
    }

    /**
     * Hands every row of a delimited history conversion to {@code sink}, one record at a time.
     *
     * @return the number of history rows read
     */
    public long streamHistory(Path file, Consumer<VsamHistoryRecord> sink) {
        Objects.requireNonNull(sink, "A history-record consumer is required to stream a history export");
        return streamRows(file, LegacyExportFormat.HISTORY_COLUMNS, row -> sink.accept(history(row)));
    }

    private static LegacyCashAccountRecord cashAccount(DelimitedRow row) {
        return new LegacyCashAccountRecord(
                row.requireText(LegacyExportFormat.CASH_ACCOUNT_OWNER_COLUMN),
                row.decimal(LegacyExportFormat.CASH_ACCOUNT_BALANCE_COLUMN),
                row.text(LegacyExportFormat.CASH_ACCOUNT_CURRENCY_COLUMN));
    }

    private static LegacyRateRecord rate(DelimitedRow row, String baseColumn) {
        return new LegacyRateRecord(
                row.requireText(LegacyExportFormat.RATE_KEY_COLUMN),
                row.text(baseColumn),
                row.decimal(LegacyExportFormat.RATE_AMOUNT_COLUMN),
                row.decimal(LegacyExportFormat.RATE_RATES_COLUMN),
                row.date(LegacyExportFormat.RATE_LOAD_DATE_COLUMN, LegacyExportFormat.LOADDT_FORMAT));
    }

    // The name keeps the caller's own casing and is never folded: CASH00.cbl:L111 moves WS-NAME into the
    // record with no case change, so "John"+stamp and "JOHN"+stamp are two separate valid 29-byte KSDS keys
    // and legacy_history keys on the raw name. Folding here would collapse two real records into one. The
    // date and time stay raw text because resolving them to an instant needs the CICS region's zone, which
    // arrives as tool.legacy-timezone and is applied by load/LegacyLoader.
    private static VsamHistoryRecord history(DelimitedRow row) {
        return new VsamHistoryRecord(
                row.requireText(LegacyExportFormat.HISTORY_NAME_COLUMN),
                row.requireText(LegacyExportFormat.HISTORY_DATE_COLUMN),
                row.requireText(LegacyExportFormat.HISTORY_TIME_COLUMN),
                row.requireText(LegacyExportFormat.HISTORY_REQUEST_CODE_COLUMN),
                row.decimal(LegacyExportFormat.HISTORY_BALANCE_COLUMN),
                row.text(LegacyExportFormat.HISTORY_CURRENCY_COLUMN),
                row.text(LegacyExportFormat.HISTORY_RETCODE_COLUMN));
    }

    // The header is the first record of the file and is consumed from the same cursor the body is read
    // from, so a file is opened and walked exactly once however it is read.
    private static Header readHeader(RecordCursor cursor) {
        RawRecord headerRecord = cursor.next();
        if (headerRecord == null) {
            throw new IllegalArgumentException("The delimited export " + cursor.file()
                    + " holds no lines; its first line must be a header naming the columns");
        }
        List<String> names = new ArrayList<>(headerRecord.fields().size());
        for (RawField cell : headerRecord.fields()) {
            names.add(cell.text());
        }
        Map<String, Integer> index;
        try {
            index = LegacyExportFormat.indexHeader(names);
        } catch (IllegalArgumentException unusable) {
            throw new IllegalArgumentException(describeHeader(cursor.file(), headerRecord.lineNumber())
                    + " cannot be indexed: " + unusable.getMessage(), unusable);
        }
        return new Header(cursor.file(), headerRecord.lineNumber(), List.copyOf(names), index);
    }

    // Fields are keyed by the header's column names and never by ordinal position: EBCDIC and ASCII
    // collate differently (AAP 0.12.2), so neither column order nor row order survives the conversion as
    // meaning and a re-ordered export must still load into the columns it names. Rows reach the sink in the
    // file's physical order because that is the order they are read in, never because a consumer may rely on
    // it - reconciliation joins on the normalized owner key.
    private static long streamBody(RecordCursor cursor, Header header, Consumer<DelimitedRow> sink) {
        long rows = 0;
        for (RawRecord record = cursor.next(); record != null; record = cursor.next()) {
            if (record.fields().size() != header.names().size()) {
                throw new IllegalArgumentException("The row on " + describeLine(header.file(), record.lineNumber())
                        + " carries " + record.fields().size() + " fields but the header names "
                        + header.names().size() + " columns " + header.index().keySet()
                        + "; a name-keyed read needs one field per column");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> column : header.index().entrySet()) {
                values.put(column.getKey(), value(record.fields().get(column.getValue())));
            }
            sink.accept(new DelimitedRow(header.file(), record.lineNumber(), values));
            rows++;
        }
        return rows;
    }

    private static String value(RawField field) {
        return !field.quoted() && LegacyExportFormat.isNull(field.text()) ? null : field.text();
    }

    private static void requireColumns(Header header, List<String> requiredColumns) {
        for (String column : requiredColumns) {
            try {
                LegacyExportFormat.columnIndex(header.index(), column);
            } catch (IllegalArgumentException missing) {
                throw new IllegalArgumentException(
                        describeHeader(header.file(), header.lineNumber()) + ": " + missing.getMessage(), missing);
            }
        }
    }

    private static String resolveBaseColumn(Header header) {
        try {
            return LegacyExportFormat.resolveRateBaseColumn(header.names());
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalArgumentException(
                    describeHeader(header.file(), header.lineNumber()) + ": " + unresolved.getMessage(), unresolved);
        }
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

    private record Header(Path file, int lineNumber, List<String> names, Map<String, Integer> index) {
    }

    /** One reader over one delimited export, yielding its raw records one at a time. */
    // WHY A CURSOR RATHER THAN A LIST OF RECORDS. A record is parsed, returned and forgotten, so the whole
    // file is never resident and a caller that needs it all pays for exactly one representation of it: the
    // runbook's bulk step supplies complete DB2 unloads (AAP 0.12.1), where a list of every raw record plus
    // every delimited row plus every typed record is three copies of a file of unbounded size.
    //
    // The scan stays character by character over one PushbackReader, and the reader is opened once per
    // cursor, because a quoted field may legally carry the delimiter, an escaped quote and a newline - none
    // of which survives a line-at-a-time read or a String.split - and because the physical line counter has
    // to advance across those embedded newlines so that an error names the line an operator can open.
    private static final class RecordCursor implements AutoCloseable {

        private final Path file;

        private final PushbackReader reader;

        // Cross-record state: the physical line the scan has reached and the line the next record starts on.
        private int line = 1;

        private int recordLine = 1;

        private boolean atStartOfFile = true;

        private RecordCursor(Path file) {
            this.file = file;
            try {
                this.reader = new PushbackReader(new BufferedReader(
                        new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)), 1);
            } catch (NoSuchFileException absent) {
                throw new IllegalArgumentException("The delimited export " + file + " does not exist", absent);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("The delimited export " + file + " could not be read", unreadable);
            }
        }

        private Path file() {
            return file;
        }

        /** The next record of the file, or {@code null} once every record has been returned. */
        private RawRecord next() {
            try {
                return readRecord();
            } catch (IOException unreadable) {
                throw new UncheckedIOException("The delimited export " + file + " could not be read", unreadable);
            }
        }

        private RawRecord readRecord() throws IOException {
            List<RawField> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quotedField = false;
            boolean inQuotes = false;
            boolean recordOpen = false;
            // The field is closed and the scan is between a closing quote and the delimiter or record end
            // that must follow it. Without this state the character after a closing quote falls through to
            // the append below, and "ABC"xyz loads as ABCxyz - a value no producer wrote, accepted silently.
            boolean fieldClosedByQuote = false;
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
                            fieldClosedByQuote = true;
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
                // Only a delimiter, a record end or end of file may follow a closing quote (RFC 4180). A
                // doubled quote never reaches here - the branch above consumes it as an escaped quote and
                // stays inside the field - so anything else is a producer this reader cannot parse rather
                // than a value it may guess at, and guessing is what would corrupt an imported balance.
                if (fieldClosedByQuote && character != DELIMITER && character != CR && character != LF) {
                    throw new IllegalArgumentException("The character '" + character + "' follows the closing"
                            + " quote of a field on " + describeLine(file, line) + "; RFC 4180 admits only a"
                            + " delimiter, a record end or end of file after a closing quote, so a field"
                            + " carrying a quote must repeat it inside the quoted value");
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
                    fieldClosedByQuote = false;
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
                        RawRecord record = new RawRecord(recordLine, List.copyOf(fields));
                        line++;
                        recordLine = line;
                        return record;
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
            // The last record of a file that ends without a newline. recordOpen is false on the next call,
            // because the reader stays at end of file, which is what ends the iteration.
            if (recordOpen) {
                fields.add(new RawField(field.toString(), quotedField));
                return new RawRecord(recordLine, List.copyOf(fields));
            }
            return null;
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException unclosable) {
                throw new UncheckedIOException("The delimited export " + file + " could not be closed", unclosable);
            }
        }
    }
}
