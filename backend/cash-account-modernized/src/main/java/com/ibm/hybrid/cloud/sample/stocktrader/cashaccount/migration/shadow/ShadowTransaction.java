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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow;

import java.math.BigDecimal;

/** One captured legacy request replayed in a shadow-mode window. */
public record ShadowTransaction(
        // The join key. Legacy and target rows are matched on seq together with the normalized owner and
        // never on file order or ordinal position, because EBCDIC-to-ASCII conversion changes collation
        // (AAP 0.12.2). A long rather than an int or a String so the key stays numeric and unbounded by
        // the window size.
        long seq,

        // owner and currency are carried exactly as captured: unnormalized, untrimmed, un-uppercased.
        // Legacy currency arrived blank-padded in WS-CURRENCY PIC X(8) (CASH00.cbl:L57) and the owner was
        // silently truncated to WS-NAME PIC X(15) (CASH00.cbl:L55) - both legacy facts recorded in the
        // characterization, not rules to re-impose on a capture. Uppercasing belongs to the comparator's
        // join key and trimming and validation to the service layer, so a capture that disagrees with the
        // stored uppercase owner stays visible instead of being silently rewritten.
        String owner,

        // One single-character, case-sensitive legacy request code. Deliberately a String, not a char and
        // not an enum: EVALUATE WS-REQ (CASH00.cbl:L89-L102) has no WHEN OTHER, so an unrecognized code -
        // a lowercase 'a' included - is a real captured value the comparator has to see in order to
        // classify it fail-closed. A char could not carry an absent field and an enum would reject exactly
        // the input the comparison exists to flag.
        String req,

        // The caller-supplied COMMAREA amount (WS-BALANCE, CASH00.cbl:L56), which is the multiplicand of
        // the legacy arithmetic: MOVE WS-BALANCE TO BALANC-RATE then COMPUTE WS-CALC = BALANCE + (RATES *
        // BALANC-RATE) on credit (CASH00.cbl:L221-L222) and the same with subtraction on debit
        // (CASH00.cbl:L255-L256). It is NOT FRANKFURT1.AMOUNT, which the program selects (CASH00.cbl:L215,
        // L249) and never references (AAP 0.4.1). BigDecimal is the only permitted type on a money path:
        // IEEE-754 types, and any decimal derived from one, are prohibited on every balance, amount, rate
        // and variance path (AAP 0.7.1). The value is built upstream from the captured text and is kept
        // exactly as read, never rescaled here - the parity fixtures carry two-decimal amounts so that no
        // expected value depends on the open input-rounding question (AAP 0.10.3, 0.11.2). Null on Q and X
        // capture lines, which carry no amount; a required value that is missing must surface from the
        // reader with its file and line context rather than be defaulted by this carrier.
        BigDecimal amount,

        String currency) {
}
