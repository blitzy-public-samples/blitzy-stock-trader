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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.LegacyExportFormat.FixedField;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.VsamHistoryRecord;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.VsamHistoryRecordDecoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/*
 * WHY THIS TEST WRITES DOWN NO PART OF THE LAYOUT. Every offset, length, digit count, scale, charset and
 * accepted record length below is read from LegacyExportFormat, which is the single declaration of the
 * legacy structure (WS-VSAM-RECORD, backend/cash-account-cobol/COBOL/CASH00.cbl:L38-L45, and the cluster's
 * own attributes, backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L11, L14). A literal retyped here would let
 * the decoder and this test agree with each other while both drifted from the characterized record, which
 * is the one failure a layout test exists to catch. The EBCDIC blank is derived the same way -- encoding a
 * space with the configured charset -- rather than typed as a byte value.
 *
 * WHY THE SYNTHETIC RECORD IS ENCODED FIELD BY FIELD WITH AN EXPLICIT CHARSET. The legacy record is a
 * concatenation of independently encoded fixed-width fields, not a string: encoding it as one text run
 * through the platform default would produce bytes that still decode into plausible-looking values and
 * mis-frame every field the moment the code page is not single-byte (AAP 0.12.2). The producing side here
 * mirrors the consuming side of the decoder for exactly that reason.
 *
 * WHY THE RECORD LENGTH IS ALWAYS DECLARED AND NEVER INFERRED. CASH00 writes a 57-byte record
 * (CASH00.cbl:L38-L45, L126-L131) into a cluster defined RECSZ(100 100) (DEFKSDS.jcl:L11), and the CICS
 * FILE definition that would settle which shape is on disk is not in this repository -- AAP 0.11.2's open
 * item. So both lengths are decodable, tool.history-record-length carries the operator's answer, and a
 * third value is refused before a byte is read; inferring the length from the file size would mis-frame
 * every record of an export that happened to divide evenly, which is a wrong load rather than a failed one.
 *
 * WHY NO BALANCE TOUCHES A FLOATING-POINT TYPE. PIC 9(7)V99 is unsigned zoned decimal that COBOL held
 * exactly; every expected value here is a BigDecimal built from text, so these assertions turn on the
 * decoder's arithmetic rather than on binary representation error (AAP 0.7.1).
 *
 * The three scenarios are the export-decoding budget of AAP 0.7.6 -- 57-byte record, 100-byte padded
 * record, bad length -- and nothing here reads a real VSAM data set or any other mainframe resource: the
 * tooling is proven against synthetic bytes and committed fixtures only (AAP 0.3.2).
 */

/** Unit tests pinning {@code VsamHistoryRecordDecoder} to the characterized 57-byte WS-VSAM-RECORD layout. */
class VsamHistoryRecordDecoderTest {

    /** The code page the decoder is exercised with: LegacyExportFormat's default, never a literal. */
    private static final Charset LEGACY_CHARSET = Charset.forName(LegacyExportFormat.DEFAULT_LEGACY_CHARSET);

    /**
     * The blank a fixed-length legacy record is padded with, obtained by encoding a space in the legacy
     * code page so that no byte value is written down here.
     */
    private static final byte LEGACY_BLANK = " ".getBytes(LEGACY_CHARSET)[0];

    private static final String BINARY_HISTORY_FIXTURE_RESOURCE =
            "fixtures/legacy-export/matched/" + LegacyExportFormat.HISTORY_BINARY_FILE;

    /** The fixture holds one record per row of its history.csv sibling. */
    private static final int EXPECTED_FIXTURE_RECORDS = 8;

    /**
     * A declared record length that is neither of the two the legacy artifacts name; the scenario that uses
     * it asserts through LegacyExportFormat that it really is unaccepted, so the value cannot go stale.
     */
    private static final int UNACCEPTED_RECORD_LENGTH = 80;

    private static final String UPPER_CASE_NAME = "JOHN";

    private static final String MIXED_CASE_NAME = "John";

    private static final String EVENT_DATE = "20240115";

    private static final String EVENT_TIME = "091501";

    private static final String REQUEST_CODE = "C";

    /** The significant digits of 1000.00; the encoder zero-fills them to the field's declared width. */
    private static final String BALANCE_DIGITS = "100000";

    private static final BigDecimal EXPECTED_BALANCE = new BigDecimal("1000.00");

    private static final String CURRENCY = "USD";

    /** SQLCODE 0 as the program renders it: unsigned digits, no sign (CASH00.cbl:L117). */
    private static final String RETCODE = "000000000";

    @Test
    void decodesA57ByteRecord() {
        byte[] record = encodeHistoryRecord(UPPER_CASE_NAME, EVENT_DATE, EVENT_TIME, REQUEST_CODE,
                BALANCE_DIGITS, CURRENCY, RETCODE);

        List<VsamHistoryRecord> decoded = decoderFor(LegacyExportFormat.HISTORY_RECORD_LENGTH).decodeAll(record);

        assertThat(decoded).hasSize(1);
        VsamHistoryRecord only = decoded.get(0);
        // The CHAR fields come back with their blank padding removed and the stamps as the raw text
        // FORMATTIME produced (CASH00.cbl:L112-L113), because resolving a stamp to an instant needs the
        // region's time zone, which is a tool property rather than anything this decoder may assume.
        assertThat(only.name()).isEqualTo(UPPER_CASE_NAME);
        assertThat(only.eventDate()).isEqualTo(EVENT_DATE);
        assertThat(only.eventTime()).isEqualTo(EVENT_TIME);
        assertThat(only.requestCode()).isEqualTo(REQUEST_CODE);
        assertThat(only.currency()).isEqualTo(CURRENCY);
        assertThat(only.retcode()).isEqualTo(RETCODE);
        // Scale-sensitive equality plus the explicit scale: together they evidence both the value and the
        // two implied decimal places of PIC 9(7)V99, which isEqualByComparingTo would have accepted away.
        assertThat(only.balance()).isEqualTo(EXPECTED_BALANCE);
        assertThat(only.balance().scale()).isEqualTo(LegacyExportFormat.MONEY_SCALE);

        // The caller's own casing survives unfolded: CASH00.cbl:L111 moves WS-NAME into the record with no
        // case folding, so "John"+stamp and "JOHN"+stamp are two distinct, equally valid 29-byte keys
        // (WS-VSAM-KEY, CASH00.cbl:L47-L50; KEYS, DEFKSDS.jcl:L14) and an import that folded either away
        // would silently merge two legacy rows. Asserting the whole record proves the casing is the only
        // difference the name change makes.
        VsamHistoryRecord mixedCase = decoderFor(LegacyExportFormat.HISTORY_RECORD_LENGTH).decodeRecord(
                encodeHistoryRecord(MIXED_CASE_NAME, EVENT_DATE, EVENT_TIME, REQUEST_CODE, BALANCE_DIGITS,
                        CURRENCY, RETCODE),
                1);
        assertThat(mixedCase).isEqualTo(new VsamHistoryRecord(MIXED_CASE_NAME, only.eventDate(),
                only.eventTime(), only.requestCode(), only.balance(), only.currency(), only.retcode()));
    }

    @Test
    void decodesA100BytePaddedRecordIdenticallyAndReadsTheBinaryFixture() {
        byte[] data = encodeHistoryRecord(UPPER_CASE_NAME, EVENT_DATE, EVENT_TIME, REQUEST_CODE,
                BALANCE_DIGITS, CURRENCY, RETCODE);
        byte[] padded = new byte[LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH];
        Arrays.fill(padded, LEGACY_BLANK);
        System.arraycopy(data, 0, padded, 0, data.length);
        assertThat(LegacyExportFormat.isPaddingByte(LEGACY_BLANK))
                .as("the blank this test pads a record's unused tail with must be padding to the format")
                .isTrue();

        VsamHistoryRecord fromWritten =
                decoderFor(LegacyExportFormat.HISTORY_RECORD_LENGTH).decodeRecord(data, 1);
        VsamHistoryRecord fromPadded =
                decoderFor(LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH).decodeRecord(padded, 1);
        // Record equality across the two declared lengths is the whole assertion: it proves at once that the
        // tail past the last declared field is padding, that nothing from it bleeds into a field, and that
        // no field shifts -- which re-asserting the seven components a second time would only restate.
        assertThat(fromPadded).isEqualTo(fromWritten);

        Path fixture = fixture(BINARY_HISTORY_FIXTURE_RESOURCE);
        byte[] fixtureBytes = readAllBytes(fixture);
        assertThat(fixtureBytes.length % LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH)
                .as("%s must be a whole number of %d-byte records", BINARY_HISTORY_FIXTURE_RESOURCE,
                        LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH)
                .isZero();

        List<VsamHistoryRecord> records =
                decoderFor(LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH).decodeAll(fixture);

        // The fixture is the committed padded case, so what it proves here is framing: every record is
        // found, and the two rows that differ only in the casing of one name survive as two. Its fields are
        // checked value for value against history.csv by LoaderIT, which owns that comparison; repeating it
        // here would add nothing and would breach the test budget of AAP 0.7.6.
        assertThat(records).hasSize(EXPECTED_FIXTURE_RECORDS);
        assertThat(records).extracting(VsamHistoryRecord::name).startsWith(MIXED_CASE_NAME, UPPER_CASE_NAME);
    }

    @Test
    void rejectsABadRecordLength() {
        assertThat(LegacyExportFormat.isAcceptedHistoryRecordLength(UNACCEPTED_RECORD_LENGTH))
                .as("%d must be neither accepted length for this rejection to mean anything",
                        UNACCEPTED_RECORD_LENGTH)
                .isFalse();
        // Refused at construction rather than at the first record: the length is the operator's declaration
        // (tool.history-record-length, mandatory for binary input), and a run that starts with a wrong one
        // has already mis-framed everything it will read.
        assertThatThrownBy(() -> new VsamHistoryRecordDecoder(LEGACY_CHARSET, UNACCEPTED_RECORD_LENGTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(UNACCEPTED_RECORD_LENGTH));

        byte[] notAWholeNumberOfRecords = new byte[LegacyExportFormat.HISTORY_RECORD_LENGTH + 1];
        Arrays.fill(notAWholeNumberOfRecords, LEGACY_BLANK);
        VsamHistoryRecordDecoder decoder = decoderFor(LegacyExportFormat.HISTORY_RECORD_LENGTH);

        // A stream that does not divide by the declared length is a mis-declared length, a text-mode
        // transfer that inserted separators, or a truncated export -- none of which may be papered over by
        // decoding the whole records and discarding the remainder, so the failure names both numbers the
        // operator has to compare.
        assertThatThrownBy(() -> decoder.decodeAll(notAWholeNumberOfRecords))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(notAWholeNumberOfRecords.length))
                .hasMessageContaining(String.valueOf(LegacyExportFormat.HISTORY_RECORD_LENGTH));
    }

    private static VsamHistoryRecordDecoder decoderFor(int declaredRecordLength) {
        return new VsamHistoryRecordDecoder(LEGACY_CHARSET, declaredRecordLength);
    }

    /**
     * Encodes one WS-VSAM-RECORD as the legacy program laid it out: a blank-filled fixed-length buffer with
     * each declared field written at its own offset in the legacy code page.
     */
    private static byte[] encodeHistoryRecord(String name, String eventDate, String eventTime,
            String requestCode, String balanceDigits, String currency, String retcode) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(LegacyExportFormat.HISTORY_NAME_COLUMN, name);
        values.put(LegacyExportFormat.HISTORY_DATE_COLUMN, eventDate);
        values.put(LegacyExportFormat.HISTORY_TIME_COLUMN, eventTime);
        values.put(LegacyExportFormat.HISTORY_REQUEST_CODE_COLUMN, requestCode);
        // The only zero-filled field of the record: the CHAR fields are blank-padded, but PIC 9(7)V99 is
        // unsigned zoned decimal of fixed width with an implied point, so its unused high-order positions
        // carry digits rather than blanks and a blank there would not decode as a number at all.
        values.put(LegacyExportFormat.HISTORY_BALANCE_COLUMN, zeroFilled(balanceDigits));
        values.put(LegacyExportFormat.HISTORY_CURRENCY_COLUMN, currency);
        values.put(LegacyExportFormat.HISTORY_RETCODE_COLUMN, retcode);
        assertThat(values.keySet())
                .as("this encoder must supply a value for every field LegacyExportFormat.HISTORY_FIELDS"
                        + " declares, so a layout change fails here rather than producing a short record")
                .containsExactlyInAnyOrderElementsOf(
                        LegacyExportFormat.HISTORY_FIELDS.stream().map(FixedField::name).toList());

        byte[] record = new byte[LegacyExportFormat.HISTORY_RECORD_LENGTH];
        Arrays.fill(record, LEGACY_BLANK);
        for (FixedField field : LegacyExportFormat.HISTORY_FIELDS) {
            byte[] encoded = values.get(field.name()).getBytes(LEGACY_CHARSET);
            assertThat(encoded.length)
                    .as("field '%s' holds %d bytes, so the test value supplied for it must fit",
                            field.name(), field.length())
                    .isLessThanOrEqualTo(field.length());
            System.arraycopy(encoded, 0, record, field.offset(), encoded.length);
        }
        return record;
    }

    private static String zeroFilled(String digits) {
        int missing = LegacyExportFormat.HISTORY_BALANCE_DIGITS - digits.length();
        assertThat(missing)
                .as("'%s' does not fit the %d digit positions of the balance field", digits,
                        LegacyExportFormat.HISTORY_BALANCE_DIGITS)
                .isGreaterThanOrEqualTo(0);
        return "0".repeat(missing) + digits;
    }

    private static byte[] readAllBytes(Path file) {
        try {
            // Read as bytes, never through a Reader or a String: the fixture is EBCDIC, and any decoding
            // step outside the decoder would silently re-encode it before the assertion could see it.
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return fail("the binary history fixture at %s could not be read".formatted(file.toAbsolutePath()), e);
        }
    }

    /**
     * Resolves a test-classpath fixture to a filesystem path.
     *
     * <p>Fails rather than skips when the fixture is missing: a decoder test with nothing to decode proves
     * nothing, and a skipped test reports as success.
     */
    private static Path fixture(String resource) {
        URL location = VsamHistoryRecordDecoderTest.class.getClassLoader().getResource(resource);
        if (location == null) {
            return fail(("the fixture %s is not on the test classpath; it is required by this test and is"
                    + " expected under src/test/resources").formatted(resource));
        }
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException | FileSystemNotFoundException | IllegalArgumentException e) {
            return fail("the fixture %s resolved to %s, which is not a readable filesystem path"
                    .formatted(resource, location), e);
        }
    }
}
