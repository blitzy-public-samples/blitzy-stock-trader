package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;

/** The single seam through which a currency conversion rate is obtained. */
public interface ExchangeRateSource {

    /**
     * Returns the {@code base}-to-{@code quote} multiplier, so that
     * {@code amountInQuote = amountInBase * rate(base, quote)} - the direction the legacy arithmetic used
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L221-L222 credit, L255-L256 debit] and must not be reversed. A
     * {@code BigDecimal} at its natural precision, never floating point and never a sentinel, because
     * {@code domain.Money} owns this module's one truncation (CASH00.cbl:L17, L222) and a pre-scaled rate would
     * break parity without failing anything; equal codes return exactly {@code 1} with no outbound call.
     *
     * @param base  ISO 4217 code the amount is expressed in, trimmed and uppercased by the implementation
     * @param quote ISO 4217 code to convert into, normalized the same way
     * @return the base-to-quote multiplier, never {@code null}
     * @throws ExchangeRateUnavailableException if no rate can be determined; the service renders it as 503
     *                                          EXCHANGE_RATE_UNAVAILABLE with {@code Retry-After: 5}, the balance
     *                                          unchanged and no ledger row written
     */
    BigDecimal rate(String base, String quote);
}
