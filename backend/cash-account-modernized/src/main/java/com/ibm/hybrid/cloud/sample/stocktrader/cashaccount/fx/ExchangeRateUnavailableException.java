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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

// Deliberate deviation: the legacy program failed open. CASH-ACCT-CREDIT and CASH-ACCT-DEBIT selected the rate row
// and then ran the COMPUTE and the UPDATE unconditionally [backend/cash-account-cobol/COBOL/CASH00.cbl:L214-L231],
// so a missing row's SQLCODE 100 was overwritten by the UPDATE's SQLCODE 0 and a balance computed from the
// uninitialized RATES host variable [backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L22] was committed under a
// success code. Failing closed instead is intentional, and being unchecked is what enforces it: the caller's
// transaction rolls back, so the balance is left unchanged and no ledger row is written.
//
// Deliberately not a CashAccountException subclass, which keeps the fx package free of the HTTP error taxonomy: the
// service layer translates this into EXCHANGE_RATE_UNAVAILABLE, so error/ApiExceptionHandler never renders it
// directly and there is exactly one route from a rate failure to the 503 response.
/** Signals that no exchange rate could be determined; the caller translates it to 503 EXCHANGE_RATE_UNAVAILABLE. */
public class ExchangeRateUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    // Optional context, so a translating catch block or log line can name the pair. Both are null when the failure
    // has no meaningful pair to report, such as a client-side configuration fault.
    private final String base;
    private final String quote;

    public ExchangeRateUnavailableException(String message) {
        this(message, null, null, null);
    }

    public ExchangeRateUnavailableException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    private ExchangeRateUnavailableException(String message, Throwable cause, String base, String quote) {
        super(message, cause);
        this.base = base;
        this.quote = quote;
    }

    public static ExchangeRateUnavailableException forPair(String base, String quote, String reason) {
        return forPair(base, quote, reason, null);
    }

    public static ExchangeRateUnavailableException forPair(String base, String quote, String reason, Throwable cause) {
        return new ExchangeRateUnavailableException(describe(base, quote, reason), cause, base, quote);
    }

    public String base() {
        return base;
    }

    public String quote() {
        return quote;
    }

    // Absent codes render as a question mark rather than failing, because this runs while an error is already being
    // reported. Callers keep the reason to a short phrase: the message reaches the logs, so it must never carry the
    // endpoint, a credential or a response body.
    private static String describe(String base, String quote, String reason) {
        StringBuilder message = new StringBuilder("no exchange rate for ")
                .append(base == null ? "?" : base)
                .append("->")
                .append(quote == null ? "?" : quote);
        if (reason != null && !reason.isBlank()) {
            message.append(": ").append(reason);
        }
        return message.toString();
    }
}
