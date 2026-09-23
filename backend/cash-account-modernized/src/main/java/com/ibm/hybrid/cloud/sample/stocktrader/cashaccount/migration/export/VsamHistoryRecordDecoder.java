package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat.FixedField;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Decodes the fixed-length EBCDIC records of an IDCAMS REPRO copy of the VSAM HISTORY KSDS into VsamHistoryRecord values. */
public final class VsamHistoryRecordDecoder {

    private static final String IN_MEMORY_SOURCE = "the supplied byte array";

    private final Charset legacyCharset;

    // Declared from tool.history-record-length, never inferred from the file size, and both accepted
    // lengths decode: CASH00 writes a 57-byte WS-VSAM-RECORD (CASH00.cbl:L38-L45, L126-L131) into a
    // cluster defined RECSZ(100 100) (DEFKSDS.jcl:L11), and with only NOTOPEN and DUPREC suppressed
    // (L123-L124) a fixed-format CICS FILE definition would have abended on LENGERR where a
    // variable-format one holds 57 - which is on disk is AAP 0.11.2's open item. A guessed length divides
    // some exports evenly and then mis-frames every record, a wrong load rather than a failed one.
    private final int declaredRecordLength;

    public VsamHistoryRecordDecoder(int declaredRecordLength) {
        this(Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET), declaredRecordLength);
    }

    public VsamHistoryRecordDecoder(Charset legacyCharset, int declaredRecordLength) {
        if (legacyCharset == null) {
            throw new IllegalArgumentException("A legacy charset is required to decode a binary history export"
                    + " (tool.legacy-charset, default " + LegacyExportFormat.DEFAULT_LEGACY_CHARSET
                    + "); none was supplied");
        }
        this.legacyCharset = legacyCharset;
        this.declaredRecordLength = LegacyExportFormat.requireAcceptedHistoryRecordLength(declaredRecordLength);
    }

    // Capped because a list is the whole file resident at once. The path this reads is an IDCAMS REPRO of a
    // production KSDS, whose size nothing in this repository bounds (AAP 0.12.1), so a caller that asks for a
    // list is asking for a fixture or a reviewable window - LegacyExportFormat.MAX_WINDOW_RECORDS is what it
    // may be - while a real REPRO belongs on streamAll below. Counted as the records are collected, so the
    // list can never exceed the limit rather than being measured once it already does.
    public List<VsamHistoryRecord> decodeAll(Path file) {
        List<VsamHistoryRecord> records = new ArrayList<>();
        streamAll(LegacyExportFormat.ExportFile.named(file), record -> {
            LegacyExportFormat.requireWindowWithinLimit(records.size(), file);
            records.add(record);
        });
        return List.copyOf(records);
    }

    /**
     * Hands every record of a binary history export to {@code sink}, one decoded record at a time.
     *
     * @param file the binary history export to decode
     * @param sink the consumer each decoded record is handed to before the next is read
     * @return the number of records decoded
     */
    // The bounded form decodeAll(Path) is built on: a REPRO of a production KSDS is a file of unbounded
    // size (AAP 0.12.1), and reusing one record buffer keeps the decoder's footprint at one record rather
    // than holding the file's bytes and every decoded record at once.
    public long streamAll(Path file, Consumer<VsamHistoryRecord> sink) {
        if (file == null) {
            throw new IllegalArgumentException("A path to a binary history export (" + LegacyExportFormat.HISTORY_BINARY_FILE
                    + " under tool.input) is required; none was supplied");
        }
        return streamAll(LegacyExportFormat.ExportFile.named(file), sink);
    }

    /**
     * Hands every record of a binary history export opened inside its approved directory to {@code sink}.
     *
     * @return the number of records decoded
     */
    // The same containment the delimited reader applies, and for the same reason: the file is opened relative
    // to the approved directory itself with no link followed, so a link substituted for history.cp037.bin -
    // or a directory component substituted above it - is refused rather than decoded and staged into
    // legacy_history as legacy data (AAP 0.3.2). The framing length is then taken from the OPENED channel,
    // not from a pathname looked up beside it, so the size that decides the framing and the bytes that are
    // decoded are guaranteed to be the same object.
    public long streamAll(LegacyExportFormat.ExportFile file, Consumer<VsamHistoryRecord> sink) {
        if (file == null) {
            throw new IllegalArgumentException("A binary history export source (" + LegacyExportFormat.HISTORY_BINARY_FILE
                    + " under tool.input) is required; none was supplied");
        }
        if (sink == null) {
            throw new IllegalArgumentException("A record consumer is required to stream the binary history export '"
                    + file.path() + "'; none was supplied");
        }
        String source = "file '" + file.path() + "'";
        byte[] record = new byte[declaredRecordLength];
        long decoded = 0;
        try (SeekableByteChannel channel = file.openChannel();
             InputStream export = new BufferedInputStream(Channels.newInputStream(channel))) {
            // Checked before the first record is decoded rather than left to the short read below: a file
            // that does not divide by the declared length is mis-framed everywhere, and saying so is more
            // use to an operator than the field-level failure its first bad record would raise. The size comes
            // from the open channel, so it reads nothing and cannot describe a different object than the one
            // being decoded.
            long declaredBytes = channel.size();
            int framingRemainder = (int) (declaredBytes % declaredRecordLength);
            if (framingRemainder != 0) {
                throw notAWholeNumberOfRecords(declaredBytes, framingRemainder, source);
            }
            while (true) {
                int read = export.readNBytes(record, 0, declaredRecordLength);
                if (read == 0) {
                    return decoded;
                }
                // readNBytes returns short only at end of stream, so this is a file truncated or appended
                // to after the length check passed. Raised rather than absorbed: decoding the whole records
                // and dropping the remainder would turn a mis-declared length into a partial load.
                if (read < declaredRecordLength) {
                    throw notAWholeNumberOfRecords(decoded * declaredRecordLength + read, read, source);
                }
                decoded++;
                sink.accept(decodeAt(record, 0, decoded, source));
            }
        } catch (IOException unreadable) {
            // A NOFOLLOW open of a symbolic link fails as a plain IOException (ELOOP), which no exception type
            // separates from an ordinary I/O failure - so the path is re-examined rather than the message
            // parsed, and a link found here is one that replaced the file in the moment of opening.
            if (Files.isSymbolicLink(file.path())) {
                throw new IllegalArgumentException("The binary history export file '" + file.path() + "' could"
                        + " not be opened without following a symbolic link, so a symbolic link replaced the"
                        + " file between the check and the open; no tool input file is read through a link"
                        + " (AAP 0.3.2)", unreadable);
            }
            throw new UncheckedIOException("The binary history export file '" + file.path() + "' could not be"
                    + " read; tool.input must name a directory holding " + LegacyExportFormat.HISTORY_BINARY_FILE
                    + ", transferred in binary so its " + legacyCharset.name() + " bytes are preserved", unreadable);
        }
    }

    // No streaming form: the caller already holds every byte, so there is nothing left to bound. The
    // file-path form above is the bulk path.
    public List<VsamHistoryRecord> decodeAll(byte[] data) {
        return decode(data, IN_MEMORY_SOURCE);
    }

    public VsamHistoryRecord decodeRecord(byte[] record, int ordinal) {
        if (record == null) {
            throw new IllegalArgumentException("A history record is required; none was supplied for record " + ordinal);
        }
        if (record.length != declaredRecordLength) {
            throw new IllegalArgumentException("Record " + ordinal + " is " + record.length + " bytes, but"
                    + " tool.history-record-length declares " + declaredRecordLength + " bytes per record");
        }
        return decodeAt(record, 0, ordinal, IN_MEMORY_SOURCE);
    }

    private List<VsamHistoryRecord> decode(byte[] data, String source) {
        if (data == null) {
            throw new IllegalArgumentException("Binary history export content is required; none was supplied");
        }
        int remainder = data.length % declaredRecordLength;
        if (remainder != 0) {
            throw notAWholeNumberOfRecords(data.length, remainder, source);
        }
        List<VsamHistoryRecord> records = new ArrayList<>(data.length / declaredRecordLength);
        int ordinal = 1;
        for (int recordStart = 0; recordStart < data.length; recordStart += declaredRecordLength) {
            records.add(decodeAt(data, recordStart, ordinal, source));
            ordinal++;
        }
        return List.copyOf(records);
    }

    // One wording however the condition is detected, so a streamed run and an in-memory one hand an
    // operator the same two numbers and the same guidance.
    private IllegalArgumentException notAWholeNumberOfRecords(long totalBytes, int remainder, String source) {
        return new IllegalArgumentException("The binary history export is not a whole number of records: "
                + source + " holds " + totalBytes + " bytes, tool.history-record-length declares "
                + declaredRecordLength + " bytes per record, leaving " + remainder + " bytes over."
                + " Check the declared length against the CICS FILE definition of HISTORY and confirm the"
                + " export was transferred in binary, without record separators or code-page conversion");
    }

    private VsamHistoryRecord decodeAt(byte[] data, int recordStart, long ordinal, String source) {
        requirePaddedTail(data, recordStart, ordinal, source);
        Map<String, String> values = new HashMap<>();
        for (FixedField field : LegacyExportFormat.HISTORY_FIELDS) {
            values.put(field.name(), decodeField(data, recordStart, field));
        }
        // The name keeps the caller's casing unfolded (CASH00.cbl:L111), so two records differing only in
        // case stay two valid 29-byte keys; the uppercased join key is derived later, in
        // reconcile/LegacyHistory.ownerKey.
        return new VsamHistoryRecord(
                decoded(values, LegacyExportFormat.HISTORY_NAME),
                decoded(values, LegacyExportFormat.HISTORY_DATE),
                decoded(values, LegacyExportFormat.HISTORY_TIME),
                decoded(values, LegacyExportFormat.HISTORY_REQUEST_CODE),
                decodeBalance(decoded(values, LegacyExportFormat.HISTORY_BALANCE), ordinal, source),
                decoded(values, LegacyExportFormat.HISTORY_CURRENCY),
                decoded(values, LegacyExportFormat.HISTORY_RETCODE));
    }

    // A tail holding anything but EBCDIC blank or zero is the first evidence that the file was not framed
    // at the declared length - a 57-byte export read as 100, an added header or trailer, a code-page
    // conversion - each of which decodes into plausible values at the wrong offsets, so it is rejected
    // rather than skipped. Checked on every record and in the one method all three entry points funnel
    // through; at HISTORY_RECORD_LENGTH the range is empty, which is the 57-byte case passing untouched.
    private void requirePaddedTail(byte[] data, int recordStart, long ordinal, String source) {
        int tailStart = recordStart + LegacyExportFormat.HISTORY_RECORD_LENGTH;
        int recordEnd = recordStart + declaredRecordLength;
        int violation = LegacyExportFormat.paddingViolationOffset(data, tailStart, recordEnd);
        if (violation < 0) {
            return;
        }
        // The offset is record-relative, because that is what an operator compares against the layout and
        // the declared length; the byte is hex because it is not text.
        throw new IllegalArgumentException("Record " + ordinal + " of " + source + " carries 0x"
                + String.format("%02X", data[violation]) + " at offset " + (violation - recordStart)
                + " of its " + declaredRecordLength + "-byte frame, where a padded history record holds only"
                + " EBCDIC blank (0x40) or 0x00: the "
                + LegacyExportFormat.HISTORY_RECORD_LENGTH + " bytes CASH00 writes (CASH00.cbl:L38-L45, L128)"
                + " end at offset " + LegacyExportFormat.HISTORY_RECORD_LENGTH
                + ". Check tool.history-record-length (declaring " + declaredRecordLength
                + " bytes per record) against the CICS FILE definition of HISTORY, and confirm the export was"
                + " transferred in binary, without record separators or code-page conversion");
    }

    // Bound by field name, never by position in HISTORY_FIELDS: a positional read of a re-ordered layout
    // still appears to work and would quietly load the balance into the currency column.
    private static String decoded(Map<String, String> values, FixedField field) {
        String value = values.get(field.name());
        if (value == null) {
            throw new IllegalStateException("LegacyExportFormat.HISTORY_FIELDS does not declare the '"
                    + field.name() + "' field of WS-VSAM-RECORD (CASH00.cbl:L38-L45); the layout this decoder"
                    + " iterates and the layout it assembles must be one declaration");
        }
        return value;
    }

    // Per-field slice rather than one decode of the whole record: substringing holds only while the code
    // page is single-byte, and a String constructor without a Charset reads EBCDIC through the platform
    // default and yields text that parses but means nothing (AAP 0.12.2). The charset is configuration
    // because CCSID EBCDIC (DB2DDL.jcl:L22, L29, L51, L61) names a family, not a code page, and the
    // region's actual CCSID is AAP 0.11.2's open item - IBM037 is only the documented assumption.
    private String decodeField(byte[] data, int recordStart, FixedField field) {
        return LegacyExportFormat.trimPadding(
                new String(data, recordStart + field.offset(), field.length(), legacyCharset));
    }

    private BigDecimal decodeBalance(String digits, long ordinal, String source) {
        if (digits.length() != LegacyExportFormat.HISTORY_BALANCE_DIGITS || !isUnsignedDigits(digits)) {
            throw new IllegalArgumentException("Record " + ordinal + " of " + source + " carries '" + digits
                    + "' in the '" + LegacyExportFormat.HISTORY_BALANCE.name() + "' field, which is not the "
                    + LegacyExportFormat.HISTORY_BALANCE_DIGITS + " unsigned digits PIC 9(7)V99 writes"
                    + " (CASH00.cbl:L43). Check tool.legacy-charset (decoding as " + legacyCharset.name()
                    + ") and tool.history-record-length (declaring " + declaredRecordLength
                    + " bytes per record) against the CICS FILE definition of HISTORY");
        }
        // PIC 9(7)V99 is unsigned zoned decimal - no sign nibble, no separator, an implied point - so the
        // faithful read is the digits verbatim with the point shifted left by the declared scale. A
        // floating-point parse would reintroduce representation error into values COBOL held exactly
        // (AAP 0.7.1).
        return new BigDecimal(digits).movePointLeft(LegacyExportFormat.MONEY_SCALE);
    }

    private static boolean isUnsignedDigits(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
