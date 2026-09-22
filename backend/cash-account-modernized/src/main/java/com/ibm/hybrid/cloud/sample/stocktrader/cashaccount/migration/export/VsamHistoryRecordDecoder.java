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
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat.FixedField;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Decodes the fixed-length EBCDIC records of an IDCAMS REPRO copy of the VSAM HISTORY KSDS into VsamHistoryRecord values. */
public final class VsamHistoryRecordDecoder {

    private static final String IN_MEMORY_SOURCE = "the supplied byte array";

    private final Charset legacyCharset;

    // The record length is declared by the caller and never inferred from the file, and the two lengths
    // LegacyExportFormat accepts are both decoded here, because the legacy artifacts disagree and the
    // artifact that would reconcile them is not in this repository: CASH00 lays out a 57-byte WS-VSAM-RECORD
    // (CASH00.cbl:L38-L45) and writes exactly LENGTH OF WS-VSAM-RECORD (CASH00.cbl:L126-L131) into a
    // cluster defined RECSZ(100 100) (DEFKSDS.jcl:L11). The program suppresses only NOTOPEN and DUPREC
    // (CASH00.cbl:L123-L124), so a fixed-format CICS FILE definition would have raised LENGERR and
    // abended, while a variable-format one holds the 57-byte records as written. Which of the two is on
    // disk is AAP 0.11.2's open item, settled by the mainframe team's FCT/CSD RECORDFORMAT/RECORDSIZE
    // attributes or a real REPRO/PRINT sample - so MigrationToolRunner binds this from
    // tool.history-record-length (no default; mandatory whenever the binary history file is present)
    // and an unaccepted value fails here, before a byte is read. Inferring it from the file size would
    // mis-frame every record of an export that happened to divide evenly, which is a wrong load rather
    // than a failed one. Bytes past the last declared field of a padded record are never read: they
    // carry nothing the program wrote (LegacyExportFormat.isPaddingByte records what such a tail holds),
    // and demanding that they be padding would reject an export whose tail carries residue.
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

    public List<VsamHistoryRecord> decodeAll(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("A path to a binary history export (" + LegacyExportFormat.HISTORY_BINARY_FILE
                    + " under tool.input) is required; none was supplied");
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("The binary history export file '" + file + "' could not be read;"
                    + " tool.input must name a directory holding " + LegacyExportFormat.HISTORY_BINARY_FILE
                    + ", transferred in binary so its " + legacyCharset.name() + " bytes are preserved", unreadable);
        }
        return decode(data, "file '" + file + "'");
    }

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
            throw new IllegalArgumentException("The binary history export is not a whole number of records: "
                    + source + " holds " + data.length + " bytes, tool.history-record-length declares "
                    + declaredRecordLength + " bytes per record, leaving " + remainder + " bytes over."
                    + " Check the declared length against the CICS FILE definition of HISTORY and confirm the"
                    + " export was transferred in binary, without record separators or code-page conversion");
        }
        List<VsamHistoryRecord> records = new ArrayList<>(data.length / declaredRecordLength);
        int ordinal = 1;
        for (int recordStart = 0; recordStart < data.length; recordStart += declaredRecordLength) {
            records.add(decodeAt(data, recordStart, ordinal, source));
            ordinal++;
        }
        return List.copyOf(records);
    }

    private VsamHistoryRecord decodeAt(byte[] data, int recordStart, int ordinal, String source) {
        Map<String, String> values = new HashMap<>();
        for (FixedField field : LegacyExportFormat.HISTORY_FIELDS) {
            values.put(field.name(), decodeField(data, recordStart, field));
        }
        // The name is returned with the caller's own casing, unfolded: CASH00.cbl:L111 moves WS-NAME into
        // the record untouched, so "John"+stamp and "JOHN"+stamp are two distinct, equally valid 29-byte
        // keys and both must survive the import; the uppercased join key is a later derivation
        // (reconcile/LegacyHistory.ownerKey).
        return new VsamHistoryRecord(
                decoded(values, LegacyExportFormat.HISTORY_NAME),
                decoded(values, LegacyExportFormat.HISTORY_DATE),
                decoded(values, LegacyExportFormat.HISTORY_TIME),
                decoded(values, LegacyExportFormat.HISTORY_REQUEST_CODE),
                decodeBalance(decoded(values, LegacyExportFormat.HISTORY_BALANCE), ordinal, source),
                decoded(values, LegacyExportFormat.HISTORY_CURRENCY),
                decoded(values, LegacyExportFormat.HISTORY_RETCODE));
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

    // Each field is converted from its own byte slice with the configured charset. Decoding the whole
    // record once and substringing it is correct only while the code page is single-byte and silently
    // mis-splits every field the moment it is not, and any String constructor without a Charset would
    // read EBCDIC bytes through the platform default and yield text that still parses but means nothing
    // (AAP 0.12.2). The charset is configurable rather than constant because CCSID EBCDIC
    // (DB2DDL.jcl:L22, L29, L51, L61) names an encoding family, not a code page, and the CICS region's
    // actual CCSID is nowhere in this repository: IBM037 is only LegacyExportFormat's default and
    // remains an assumption pending the mainframe team's answer (AAP 0.11.2).
    private String decodeField(byte[] data, int recordStart, FixedField field) {
        return LegacyExportFormat.trimPadding(
                new String(data, recordStart + field.offset(), field.length(), legacyCharset));
    }

    private BigDecimal decodeBalance(String digits, int ordinal, String source) {
        if (digits.length() != LegacyExportFormat.HISTORY_BALANCE_DIGITS || !isUnsignedDigits(digits)) {
            throw new IllegalArgumentException("Record " + ordinal + " of " + source + " carries '" + digits
                    + "' in the '" + LegacyExportFormat.HISTORY_BALANCE.name() + "' field, which is not the "
                    + LegacyExportFormat.HISTORY_BALANCE_DIGITS + " unsigned digits PIC 9(7)V99 writes"
                    + " (CASH00.cbl:L43). Check tool.legacy-charset (decoding as " + legacyCharset.name()
                    + ") and tool.history-record-length (declaring " + declaredRecordLength
                    + " bytes per record) against the CICS FILE definition of HISTORY");
        }
        // PIC 9(7)V99 is unsigned zoned decimal: no sign nibble, no separator and an implied point, so
        // the only faithful read is the digits verbatim with the point shifted left by the declared
        // scale. A floating-point parse would reintroduce representation error into values COBOL held
        // exactly and scatter penny-level differences across a reconciliation run (AAP 0.7.1).
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
