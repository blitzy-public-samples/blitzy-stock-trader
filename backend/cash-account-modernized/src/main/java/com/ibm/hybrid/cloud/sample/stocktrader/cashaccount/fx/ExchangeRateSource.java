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

import java.math.BigDecimal;

// Deliberate deviation: the legacy rate lookup was an in-database join on STOCKTRD.FRANKFURT1, keyed on the
// account's own currency truncated to five characters [backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L219].
// That table is a migration-source artifact, not a target-state dependency - target state is a live lookup at the
// chart-injected CURRENCY_API_URL. Putting the lookup behind an interface is what lets the migration tooling replay
// legacy arithmetic from the staged rate rows while the request path uses the live rate, with no branch in either
// caller. One jar serves both the web service and the tool CLI, so the staged-table implementation ships on the
// deployed classpath either way; what contains it is that it is never instantiated as a bean in the default
// (deployed) application context - profile activation plus the single-bean assertion below, not the contents of the
// jar - so no request path can reach it.
//
// Implementations are chosen by Spring profile, never by qualifier: FrankfurterExchangeRateClient carries no
// @Profile and is therefore the only bean in the deployed profile, while LegacyRateTableSource and
// ToolExchangeRateSource are both @Profile("tool"), the latter @Primary there. ExchangeRateSourceWiringTest
// enforces this by asserting that exactly one bean of this type exists in the default profile, so an
// unconditional @Component implementation would make the staged legacy table reachable from the request path.
/** The single seam through which a currency conversion rate is obtained. */
public interface ExchangeRateSource {

    /**
     * Returns the multiplier that converts one unit of {@code base} into {@code quote}, so that
     * {@code amountInQuote = amountInBase * rate(base, quote)}. The direction is fixed and must not be reversed:
     * the live implementation requests {@code ?from=<base>&to=<quote>} and reads {@code rates[quote]}, and the
     * legacy arithmetic being reproduced multiplied the caller's amount by the account currency's {@code RATES}
     * (backend/cash-account-cobol/COBOL/CASH00.cbl:L221-L222 for credit, L255-L256 for debit). The declared caller
     * invokes it as {@code rate(baseCurrency, accountCurrency)}, where the base currency defaults to {@code USD}.
     *
     * <p>The result is never {@code null} and is never a sentinel: an implementation that cannot determine a rate
     * throws instead. Returning {@code BigDecimal.ZERO} would turn every credit and debit into a silent no-op,
     * which is precisely the class of silently-wrong answer this service exists to eliminate.
     *
     * <p>The rate comes back at its natural precision - unscaled and untruncated. Implementations must not call
     * {@code setScale}, {@code round} or {@code stripTrailingZeros} on it, and must not pre-multiply it by an
     * amount. The legacy program truncated the whole expression exactly once, after the signed addition, into an
     * unsigned two-decimal field (CASH00.cbl:L17, L222, L256), and truncating the product first is not equivalent:
     * with a stored balance of 100.00, a rate of 0.03 and an amount of 0.30, the single final truncation yields
     * {@code truncate2(100.00 - 0.009) = 99.99} while a truncated product yields {@code 100.00 - 0.00 = 100.00}.
     * The module's {@code domain.Money.applyRate} owns that one truncation, so a rate pre-scaled here would break
     * legacy parity without failing anything.
     *
     * <p>No money path in this module uses binary floating point: a rate delivered as a JSON number is parsed as
     * {@code BigDecimal} from its text, and {@code BigDecimal.valueOf(double)} and {@code new BigDecimal(double)}
     * are prohibited on this path. Precision differs between sources and that difference is reported rather than
     * absorbed - the legacy column was {@code DECIMAL(3, 2)} / {@code PIC S9(1)V9(2) COMP-3}
     * (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12, L22), two decimals with a ceiling of 9.99, whereas live
     * rates carry four to six significant digits, which the reconciliation tooling classifies as a
     * {@code RATE_SOURCE} variance.
     *
     * <p>When {@code base} and {@code quote} are equal once trimmed and uppercased, every implementation must
     * return exactly {@code 1} and make no network call. That is a parity guarantee rather than an optimization:
     * a same-currency account reconciles exactly against the legacy result, and no exchange-rate outage can reach
     * an operation that needs no conversion.
     *
     * @param base  ISO 4217 three-letter code of the currency the amount is expressed in; implementations trim it
     *              and uppercase it with {@code Locale.ROOT}, so callers may pass either casing
     * @param quote ISO 4217 three-letter code of the currency to convert into, normalized the same way
     * @return the base-to-quote multiplier at its natural precision, never {@code null}
     * @throws ExchangeRateUnavailableException if no rate can be determined, because the endpoint is unreachable,
     *                                          times out, answers non-2xx or with a malformed body, omits the
     *                                          requested code, or the staged legacy table holds no row for the
     *                                          key. The service layer translates it to 503
     *                                          EXCHANGE_RATE_UNAVAILABLE with {@code Retry-After: 5}, leaving the
     *                                          balance unchanged and writing no ledger row
     * @throws com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException carrying
     *                                          {@code CashAccountErrorCode.INVALID_CURRENCY} if either code is not
     *                                          a supported three-letter ISO code. The two failures stay distinct
     *                                          on purpose: a rejected input is a 400 and an undeterminable rate is
     *                                          a 503, so reporting a caller error as a transient outage can never
     *                                          invite a pointless retry
     */
    BigDecimal rate(String base, String quote);
}
