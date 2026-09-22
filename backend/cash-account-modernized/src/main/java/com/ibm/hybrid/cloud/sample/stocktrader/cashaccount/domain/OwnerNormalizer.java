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

import java.util.Locale;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The single authority on owner identity: one canonical form reaches the database and the wire. */
public final class OwnerNormalizer {

    // 32 is the width the stored column always had - CHAR(32) in the DCLGEN declaration
    // [backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9] and in the DDL
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47] - and it is the width of every owner column in
    // schema/cash-account-schema.sql. Stating the bound once here means the entities' @Column(length = 32)
    // can be reviewed against a single constant rather than four independent literals.
    public static final int MAX_LENGTH = 32;

    private OwnerNormalizer() {
    }

    /**
     * Returns the canonical stored form of {@code raw}: stripped and upper-cased.
     *
     * @param raw the owner exactly as it arrived from a caller, an export row or a replay stream
     * @return the canonical owner, 1 to {@value #MAX_LENGTH} characters, upper case
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_OWNER} (HTTP 400) when
     *         {@code raw} is null, blank, or longer than {@value #MAX_LENGTH} characters once stripped
     */
    public static String normalize(String raw) {
        // Null carries no value worth echoing back, so the code travels without an owner field; the
        // remaining rejections use forOwner so ApiError can name what was refused.
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_OWNER);
        }

        // strip() rather than trim(): legacy CHAR columns and the fixed-layout history record are
        // blank-padded (EBCDIC 0x40), and an export or replay row may still carry that padding after code-page
        // conversion, so the Unicode-aware superset is the safer choice for the same job.
        String stripped = raw.strip();

        if (stripped.isEmpty()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_OWNER, raw);
        }

        // Rejecting instead of truncating is a deliberate change from the legacy program: the 15-character
        // limit was purely a COMMAREA artifact - WS-NAME was PIC X(15) [CASH00.cbl:L55] while the host variable
        // it fed was PIC X(32) [CASH00.cbl:L36] and the column was CHAR(32) - so a longer name was silently cut
        // at the interface boundary and two owners sharing a 15-character prefix collapsed into one account with
        // nothing reporting it. Silent truncation is data loss; 400 INVALID_OWNER makes it visible. The length is
        // measured after stripping so that a full-width owner padded with spaces is accepted rather than refused.
        if (stripped.length() > MAX_LENGTH) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_OWNER, raw);
        }

        // Upper case is the legacy storage form, not an invention: the insert stored UPPER(:CUST-NAME-TEXT)
        // [CASH00.cbl:L155] and every lookup folded case - LOWER(Owner) = LOWER(:CUST-NAME-TEXT) on read and
        // delete [CASH00.cbl:L141], UPPER(Owner) = UPPER(:CUST-NAME-TEXT) on update [CASH00.cbl:L178]. Folding
        // here preserves every lookup that ever worked while making this value usable as the primary key and as
        // the reconciliation join key. Returning it is the one deliberate change: the legacy echo was
        // inconsistent - Q returned the upper-case database column [CASH00.cbl:L144], A returned the caller's own
        // casing [CASH00.cbl:L158] and U/X/C/D returned whatever the caller sent - and the retail caller reads
        // only balance and currency out of the response, so consistency costs nothing.
        //
        // Locale.ROOT, never the no-argument toUpperCase(): under a Turkish default locale that maps "i" to
        // U+0130 (dotted capital I), which would put a non-ASCII character into a primary key and make identity
        // depend on the JVM's locale - the same owner normalizing two ways in two pods.
        return stripped.toUpperCase(Locale.ROOT);
    }
}
