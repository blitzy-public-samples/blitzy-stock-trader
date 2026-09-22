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

/** One captured legacy reply from a shadow-mode window. */
public record ShadowLegacyResponse(

        /* The join key. A captured reply is matched to its replayed transaction on seq plus the
           normalized owner and never on file order or ordinal position, because EBCDIC-to-ASCII
           conversion changes collation (AAP 0.12.2). long rather than int so a long capture window
           cannot overflow it, and numeric rather than text so ascending order is the capture's own
           chronological order. */
        long seq,

        /* Deliberately the owner exactly as captured. ShadowComparator normalizes when it builds its
           join key, so a capture whose casing differs from the uppercase owner the legacy INSERT stored
           (CASH00.cbl:L155) stays visible for review instead of being silently rewritten here. */
        String owner,

        /* The legacy status channel, kept as raw text. CASH00.cbl:L104 and L117 move the numeric SQLCODE
           into an X(10) alphanumeric field, which renders the absolute digits and drops the sign, and
           always carries the last SQL statement's code - so -803 and +803 both arrive as "000000803".
           Parsing it to an int here would invent a sign and discard the leading zeros; the only
           defensible test is a zero-parse, implemented once in LegacyExportFormat.isSuccessRetcode.
           Null when a capture omits it. */
        String retcode,

        /* Fixed-point BigDecimal only: AAP 0.7.1 bars binary approximation types from every balance,
           amount, rate and variance path, so the value is built upstream from the digit text of the
           legacy WS-BALANCE PIC 9(7)V99 (CASH00.cbl:L56, echoed into the reply at L105). Null when the
           reply carries no meaningful balance - a not-found Q echoed the caller's own amount rather
           than an account balance (AAP 0.12.3). */
        BigDecimal balance) {
}
