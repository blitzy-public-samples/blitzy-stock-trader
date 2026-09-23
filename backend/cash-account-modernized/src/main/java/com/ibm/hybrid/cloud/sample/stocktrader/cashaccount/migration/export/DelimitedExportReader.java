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
public final class DelimitedExportReader {

    private static final char DELIMITER = ',';

    private static final char QUOTE = '"';

    // A UTF-8 byte-order mark decodes to this one character and, left in place, joins the first column's
    // name - surfacing as a missing required column on a file that looks correct in every editor.
    private static final char BOM = '\uFEFF';

    private static final char CR = '\r';

    private static final char LF = '\n';

    /**
     * One data line of a delimited export, its fields keyed by the column names its header declared.
     *
     * @param source     the file the line was read from, named in every failure message
     * @param lineNumber the one-based physical line number, so a rejection points at the row
     * @param values     the line's fields keyed by header name, never by ordinal position
     */
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

        /**
         * The column's text with legacy CHAR padding right-trimmed.
         *
         * @param column the column name this row's header declared
         * @return the trimmed text, or {@code null} where the export held a legacy NULL
         */
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

        /**
         * The column's decimal, at the scale the export carries.
         *
         * @param column the column name this row's header declared
         * @return the decimal, or {@code null} where the export held a legacy NULL
         */
        public BigDecimal decimal(String column) {
            String raw = rawField(column);
            if (raw == null) {
                return null;
            }
            String trimmed = LegacyExportFormat.trimPadding(raw);
            // Built from the digit text, never through a binary approximation type (AAP 0.7.1), and never
            // rescaled. Checked first, so an exponent or a separator is refused rather than loaded.
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

        /**
         * The column's date, parsed with the format its legacy export declares.
         *
         * @param column the column name this row's header declared
         * @param format the column's own date format - loaddt and the history stamps differ
         * @return the date, or {@code null} where the export held a legacy NULL
         */
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

        /**
         * The column's whole number.
         *
         * @param column the column name this row's header declared
         * @return the value, or {@code null} where the export held a legacy NULL
         */
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

        // A DB2 delimited unload writes NULL as an empty UNQUOTED field, so a quoted empty field is the
        // empty string and the literal text NULL stays a value. Nulls are handed on rather than defaulted -
        // balance, currencyc, currnbase, amount and rates are nullable in the legacy catalog
        // (DB2DDL.jcl:L48-L49, L56-L58) and a zero would erase the variance validateSource() records. A
        // column absent from the header is its own failure, never "the export held no value".
        private String rawField(String column) {
            String key = columnKey(column);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Column '" + column + "' was not named by the header of " + source
                        + "; columns read: " + values.keySet());
            }
            return values.get(key);
        }
    }

    /**
     * Reads every data line of a delimited export, checking first that its header names the required columns.
     *
     * @param file            the delimited export to read
     * @param requiredColumns the columns the header must name; empty reads a file without a column check
     * @return every data row, the header line excluded
     */
    // Every list-returning read here is capped: a list is a whole window resident at once, so the caller that
    // asks for one is asking for a captured shadow window or a committed fixture rather than a bulk export
    // (LegacyExportFormat.MAX_WINDOW_RECORDS; AAP 0.12.1 puts an unload on the streaming forms). The cap is
    // applied as the rows are collected, so an oversized file is refused at the limit, not after the list is built.
    public List<DelimitedRow> readRows(Path file, List<String> requiredColumns) {
        return readRows(LegacyExportFormat.ExportFile.named(file), requiredColumns);
    }

    /** Reads every data line of an export opened inside its approved directory. */
    public List<DelimitedRow> readRows(LegacyExportFormat.ExportFile source, List<String> requiredColumns) {
        List<DelimitedRow> rows = new ArrayList<>();
        streamRows(source, requiredColumns, row -> {
            LegacyExportFormat.requireWindowWithinLimit(rows.size(), source.path());
            rows.add(row);
        });
        return List.copyOf(rows);
    }

    /**
     * Hands every data line of a delimited export to {@code sink}, one row at a time.
     *
     * @param file            the delimited export to read
     * @param requiredColumns the columns the header must name; empty reads a file without a column check
     * @param sink            the consumer each row is handed to before the next is read
     * @return the number of data rows read, the header line excluded
     */
    // The bounded form every other read here is built on: a bulk export is a whole DB2 UNLOAD (AAP
    // 0.12.1), so a row is parsed, handed on and released, and the List-returning methods collect over it.
    public long streamRows(Path file, List<String> requiredColumns, Consumer<DelimitedRow> sink) {
        Objects.requireNonNull(file, "A delimited export path is required");
        return streamRows(LegacyExportFormat.ExportFile.named(file), requiredColumns, sink);
    }

    /**
     * Hands every data line of an export opened inside its approved directory to {@code sink}.
     *
     * @return the number of data rows read, the header line excluded
     */
    public long streamRows(LegacyExportFormat.ExportFile source,
                           List<String> requiredColumns,
                           Consumer<DelimitedRow> sink) {
        Objects.requireNonNull(source, "A delimited export source is required");
        Objects.requireNonNull(requiredColumns,
                "A required-column list is needed; pass an empty list to read a file without a column check");
        Objects.requireNonNull(sink, "A row consumer is required to stream a delimited export");
        try (RecordCursor cursor = new RecordCursor(source)) {
            Header header = readHeader(cursor);
            requireColumns(header, requiredColumns);
            return streamBody(cursor, header, sink);
        }
    }

    /**
     * Reads an exported STOCKTRD.CASHACCOUNTY, or a target-state file in that same column shape.
     *
     * @param file the account export to read
     * @return every exported account row
     */
    public List<LegacyCashAccountRecord> readCashAccounts(Path file) {
        List<LegacyCashAccountRecord> accounts = new ArrayList<>();
        streamCashAccounts(LegacyExportFormat.ExportFile.named(file), account -> {
            LegacyExportFormat.requireWindowWithinLimit(accounts.size(), file);
            accounts.add(account);
        });
        return List.copyOf(accounts);
    }

    /**
     * Hands every row of an exported STOCKTRD.CASHACCOUNTY to {@code sink}, one record at a time.
     *
     * @param file the account export to read
     * @param sink the consumer each record is handed to before the next is read
     * @return the number of account rows read
     */
    public long streamCashAccounts(Path file, Consumer<LegacyCashAccountRecord> sink) {
        return streamCashAccounts(LegacyExportFormat.ExportFile.named(file), sink);
    }

    /**
     * Hands every account row of an export opened inside its approved directory to {@code sink}.
     *
     * @return the number of account rows read
     */
    public long streamCashAccounts(LegacyExportFormat.ExportFile source,
                                   Consumer<LegacyCashAccountRecord> sink) {
        Objects.requireNonNull(sink, "An account-record consumer is required to stream an account export");
        return streamRows(source, LegacyExportFormat.CASH_ACCOUNT_COLUMNS, row -> sink.accept(cashAccount(row)));
    }

    /**
     * Reads an exported STOCKTRD.FRANKFURT1 rate table, staged for reconciliation and nothing else.
     *
     * @param file the rate-table export to read
     * @return every exported rate row
     */
    public List<LegacyRateRecord> readRates(Path file) {
        List<LegacyRateRecord> rates = new ArrayList<>();
        streamRates(file, rate -> {
            LegacyExportFormat.requireWindowWithinLimit(rates.size(), file);
            rates.add(rate);
        });
        return List.copyOf(rates);
    }

    /**
     * Hands every row of an exported STOCKTRD.FRANKFURT1 rate table to {@code sink}, one record at a time.
     *
     * @param file the rate-table export to read
     * @param sink the consumer each record is handed to before the next is read
     * @return the number of rate rows read
     */
    // Its own cursor rather than a call to streamRows, because the required-column set of this one file is
    // not known until its header has been read: the base-currency column carries either legacy spelling.
    public long streamRates(Path file, Consumer<LegacyRateRecord> sink) {
        Objects.requireNonNull(file, "A rate-table export path is required");
        return streamRates(LegacyExportFormat.ExportFile.named(file), sink);
    }

    /**
     * Hands every rate row of an export opened inside its approved directory to {@code sink}.
     *
     * @return the number of rate rows read
     */
    public long streamRates(LegacyExportFormat.ExportFile source, Consumer<LegacyRateRecord> sink) {
        Objects.requireNonNull(source, "A rate-table export source is required");
        Objects.requireNonNull(sink, "A rate-record consumer is required to stream a rate-table export");
        try (RecordCursor cursor = new RecordCursor(source)) {
            Header header = readHeader(cursor);
            // Either legacy spelling is accepted: the copybook names CURRNBASE (DCLFRANK.cpy:L10) while the
            // shipped DDL declares cyrrnbase (DB2DDL.jcl:L56), an open item under AAP 0.11.2. The resolved
            // spelling replaces the declared one in the required set, so the shape keeps a single home.
            String baseColumn = resolveBaseColumn(header);
            List<String> required = new ArrayList<>(LegacyExportFormat.RATE_COLUMNS.size());
            for (String column : LegacyExportFormat.RATE_COLUMNS) {
                required.add(LegacyExportFormat.RATE_BASE_COLUMN.equals(column) ? baseColumn : column);
            }
            requireColumns(header, required);
            return streamBody(cursor, header, row -> sink.accept(rate(row, baseColumn)));
        }
    }

    /**
     * Reads a delimited conversion of the legacy VSAM history, yielding the records its binary decoder does.
     *
     * @param file the delimited history conversion to read
     * @return every exported history row
     */
    public List<VsamHistoryRecord> readHistory(Path file) {
        List<VsamHistoryRecord> history = new ArrayList<>();
        streamHistory(file, record -> {
            LegacyExportFormat.requireWindowWithinLimit(history.size(), file);
            history.add(record);
        });
        return List.copyOf(history);
    }

    /**
     * Hands every row of a delimited history conversion to {@code sink}, one record at a time.
     *
     * @param file the delimited history conversion to read
     * @param sink the consumer each record is handed to before the next is read
     * @return the number of history rows read
     */
    public long streamHistory(Path file, Consumer<VsamHistoryRecord> sink) {
        return streamHistory(LegacyExportFormat.ExportFile.named(file), sink);
    }

    /**
     * Hands every history row of an export opened inside its approved directory to {@code sink}.
     *
     * @return the number of history rows read
     */
    public long streamHistory(LegacyExportFormat.ExportFile source, Consumer<VsamHistoryRecord> sink) {
        Objects.requireNonNull(sink, "A history-record consumer is required to stream a history export");
        return streamRows(source, LegacyExportFormat.HISTORY_COLUMNS, row -> sink.accept(history(row)));
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

    // The name is never folded: CASH00.cbl:L111 moves WS-NAME in unchanged, so two records differing only
    // in case are two valid 29-byte KSDS keys and folding would collapse them into one. Date and time stay
    // raw text because resolving them needs tool.legacy-timezone, applied by load/LegacyLoader.
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

    // Consumed from the cursor the body is read from, so a file is opened and walked exactly once.
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

    // Keyed by the header's column names, never by ordinal position: EBCDIC and ASCII collate differently
    // (AAP 0.12.2), so a re-ordered export must still load into the columns it names. Rows reach the sink
    // in physical order, but no consumer may rely on it - reconciliation joins on the normalized owner key.
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

    // One normalization authority: a lookup key comes from the same call that produced the stored key, so
    // the trim-and-fold rule cannot drift between the two ends of a name-keyed read.
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
    private static final class RecordCursor implements AutoCloseable {

        private final Path file;

        // Hand-written over a character stream: no CSV library is in this module's dependency inventory
        // (AAP 0.9.1) and adding one is not authorized, and a quoted field may legally carry the delimiter,
        // an escaped quote and a newline - none of which survives a line read or a String.split.
        private final PushbackReader reader;

        // The physical line the scan has reached and the line the next record starts on; it advances across
        // a quoted field's embedded newlines, so an error names a line an operator can open.
        private int line = 1;

        private int recordLine = 1;

        private boolean atStartOfFile = true;

        // The open is the containment check, not merely guarded by one: LegacyExportFormat.ExportFile.open()
        // refuses a symbolic link and a non-ordinary file and then opens the file relative to the approved
        // directory itself, following no link - so neither the directory nor the child can be substituted
        // between the validation and the open, which is what a pathname re-resolved at open time allows
        // (AAP 0.3.2). A file the caller named outright carries no approved directory and is opened with the
        // same link refusal. An absent file still raises NoSuchFileException, so the message below is
        // unchanged, and only a link substituted in the moment of opening reaches the second refusal.
        private RecordCursor(LegacyExportFormat.ExportFile source) {
            this.file = source.path();
            try {
                this.reader = new PushbackReader(new BufferedReader(
                        new InputStreamReader(source.open(), StandardCharsets.UTF_8)), 1);
            } catch (NoSuchFileException absent) {
                throw new IllegalArgumentException("The delimited export " + file + " does not exist", absent);
            } catch (IOException unreadable) {
                // A NOFOLLOW open of a symbolic link fails as a plain IOException (ELOOP), which no exception
                // type separates from an ordinary I/O failure - so the path is re-examined rather than the
                // message parsed, and a link found here is one that replaced the file after the check above.
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("The delimited export " + file + " could not be opened"
                            + " without following a symbolic link, so a symbolic link replaced the file between"
                            + " the check and the open; no tool input file is read through a link (AAP 0.3.2)",
                            unreadable);
                }
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
            // THE RECORD'S SIZE BUDGET, AND WHY IT IS SPENT AS THE RECORD IS SCANNED. Every one of these
            // counters is a local, so it is reset by the very act of returning a record and no per-record
            // budget can leak into the next one. Checking them while scanning is the whole point: a field, a
            // record or a quoted run of lines that is measured only once it is complete has already cost the
            // memory the limits exist to bound (LegacyExportFormat, input bounds).
            int closedFieldCharacters = 0;
            // The record's first physical line is already being consumed, so the budget starts at one.
            int recordLines = 1;
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
                            requireRoomForOneMoreCharacter(field.length(), closedFieldCharacters);
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
                        // Counted against the record's line budget only here, where the newline is INSIDE an
                        // open quoted field and therefore part of this record. The record end below and the
                        // skipped empty line beside it advance the same physical counter but belong to no
                        // open record, so a file with a trailing newline block must never be charged for them.
                        LegacyExportFormat.requireRecordLinesWithinLimit(recordLines, file, recordLine);
                        recordLines++;
                        line++;
                    }
                    requireRoomForOneMoreCharacter(field.length(), closedFieldCharacters);
                    field.append(character);
                    continue;
                }
                // Only a delimiter, a record end or end of file may follow a closing quote (RFC 4180); a
                // doubled quote never reaches here, the branch above keeps it inside the field. Anything
                // else is a producer this reader cannot parse, and guessing would corrupt a loaded balance.
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
                    LegacyExportFormat.requireFieldCountWithinLimit(fields.size(), file, line);
                    fields.add(new RawField(field.toString(), quotedField));
                    // The closed field's characters stay charged to the record: the per-field limit alone
                    // would admit a record of many fields each just inside it.
                    closedFieldCharacters += field.length();
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
                    // A line with no characters is not a record: a transfer routinely leaves a trailing
                    // newline, and treating it as a row would fail the field-count check. A line beginning
                    // with '#' IS data - the export contract defines no comment syntax (AAP 0.12.1).
                    if (recordOpen) {
                        LegacyExportFormat.requireFieldCountWithinLimit(fields.size(), file, line);
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
                requireRoomForOneMoreCharacter(field.length(), closedFieldCharacters);
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
                LegacyExportFormat.requireFieldCountWithinLimit(fields.size(), file, line);
                fields.add(new RawField(field.toString(), quotedField));
                return new RawRecord(recordLine, List.copyOf(fields));
            }
            return null;
        }

        // Both character budgets are spent at the same three points - the escaped quote, the character
        // inside a quoted field and the ordinary character - so the field's own width and the record's total
        // are bounded by one call rather than by six that could drift apart.
        private void requireRoomForOneMoreCharacter(int fieldCharacters, int closedFieldCharacters) {
            LegacyExportFormat.requireFieldWithinLimit(fieldCharacters, file, line);
            LegacyExportFormat.requireRecordWithinLimit(closedFieldCharacters + fieldCharacters, file, line);
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
