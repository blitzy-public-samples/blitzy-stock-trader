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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import org.junit.jupiter.api.Test;

/** Unit tests for owner identity normalization: trim, upper case, and the 1-32 character bound. */
class OwnerNormalizerTest {

    @Test
    void normalizesToOneUpperCaseFormRegardlessOfCallerCasing() {
        // Upper case is the legacy storage form rather than an invention of this service: the insert wrote
        // UPPER(:CUST-NAME-TEXT) [backend/cash-account-cobol/COBOL/CASH00.cbl:L155] into a CHAR(32) column
        // [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9, L17], so every owner the legacy table ever held was
        // already upper case.
        assertThat(OwnerNormalizer.normalize("  john  ")).isEqualTo("JOHN");
        assertThat(OwnerNormalizer.normalize("\tkarri\n")).isEqualTo("KARRI");

        // Already-canonical input must come back untouched: the loader and the reconciler re-normalize owners that
        // were normalized on the way in, so a non-idempotent fold would make the reconciliation join key depend on
        // how many times a row had been read.
        assertThat(OwnerNormalizer.normalize("JOHN")).isEqualTo("JOHN");

        // PRESERVED (AAP 0.4.6): legacy owner identity was case-insensitive on every path - reads, deletes,
        // credits and debits matched LOWER(Owner) = LOWER(:CUST-NAME-TEXT) [CASH00.cbl:L141] and updates matched
        // UPPER(Owner) = UPPER(:CUST-NAME-TEXT) [CASH00.cbl:L178]. Collapsing the casings to one stored form keeps
        // every lookup that worked before working, now against a single primary key.
        //
        // DELIBERATELY CHANGED (AAP 0.4.6): the value handed back is always the stored form. The legacy echo was
        // inconsistent - Q returned the database's upper-case column [CASH00.cbl:L144] while A returned the
        // caller's own casing [CASH00.cbl:L158] - which costs no caller compatibility to drop, because broker maps
        // only balance and currency out of the response.
        assertThat(OwnerNormalizer.normalize("john"))
                .isEqualTo(OwnerNormalizer.normalize("John"))
                .isEqualTo(OwnerNormalizer.normalize("JOHN"))
                .isEqualTo("JOHN");

        // Locale.ROOT, not the no-argument toUpperCase(): under a Turkish default locale the latter maps "i" to
        // U+0130 (dotted capital I), which would put a non-ASCII character into the primary key and make identity
        // depend on the JVM's locale. The ASCII outcome is asserted rather than the locale switched, because a test
        // that mutates the JVM default locale corrupts every test sharing the JVM.
        assertThat(OwnerNormalizer.normalize("iris")).isEqualTo("IRIS");

        // Only the edges are stripped; interior characters survive as given, so owners carrying separators are not
        // silently rewritten into a different account key.
        assertThat(OwnerNormalizer.normalize("  john.doe-1  ")).isEqualTo("JOHN.DOE-1");
    }

    @Test
    void acceptsTheFullThirtyTwoCharacterWidthAndRejectsLonger() {
        assertThat(OwnerNormalizer.MAX_LENGTH).isEqualTo(32);

        assertThat(OwnerNormalizer.normalize("j")).isEqualTo("J");

        // DELIBERATELY CHANGED (AAP 0.4.6): the legacy 15-character ceiling was a COMMAREA artifact - WS-NAME was
        // PIC X(15) [CASH00.cbl:L55] while the column behind it was CHAR(32) [DCLCASH.cpy:L9, L17] - so a longer
        // owner was silently cut at the interface boundary and two owners sharing a 15-character prefix collapsed
        // into one account with nothing reporting it. A twenty-character owner surviving whole is that truncation
        // gone; it would have reached the table as "INSTITUTIONAL-D".
        assertThat(OwnerNormalizer.normalize("institutional-desk-7"))
                .isEqualTo("INSTITUTIONAL-DESK-7")
                .hasSize(20);

        String fullWidth = "institutional-custody-account-01";
        assertThat(OwnerNormalizer.normalize(fullWidth))
                .isEqualTo("INSTITUTIONAL-CUSTODY-ACCOUNT-01")
                .hasSize(OwnerNormalizer.MAX_LENGTH);

        // The bound is measured after stripping, so a full-width owner still carrying the blank padding of a
        // CHAR(32) export column is accepted rather than refused for being 38 characters of raw text.
        assertThat(OwnerNormalizer.normalize("   " + fullWidth + "   ")).hasSize(OwnerNormalizer.MAX_LENGTH);

        // Built by repetition so the input is one character past the bound by construction rather than by a
        // hand-counted literal.
        assertThat(rejectionCodeFor("A".repeat(OwnerNormalizer.MAX_LENGTH + 1)))
                .isEqualTo(CashAccountErrorCode.INVALID_OWNER);
    }

    @Test
    void rejectsNullAndBlankOwners() {
        assertThat(rejectionCodeFor(null)).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);
        assertThat(rejectionCodeFor("   ")).isEqualTo(CashAccountErrorCode.INVALID_OWNER);

        // The code is only half the contract; 400 is the status AAP 0.6.2 pins for this condition, and
        // CashAccountErrorCode is the single place it is decided, so the rejection is tied here to the response a
        // caller actually receives.
        assertThat(CashAccountErrorCode.INVALID_OWNER.status().value()).isEqualTo(400);
    }

    // Yields the rejected condition's code so each call site reads as one fact. The exception message is asserted
    // nowhere: it is a human-facing default the error code supplies, not part of the contract.
    private static CashAccountErrorCode rejectionCodeFor(String raw) {
        CashAccountException thrown =
                catchThrowableOfType(() -> OwnerNormalizer.normalize(raw), CashAccountException.class);
        assertThat(thrown).as("normalize(\"%s\") must be rejected", raw).isNotNull();
        return thrown.errorCode();
    }
}
