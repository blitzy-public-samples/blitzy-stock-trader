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
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// Selection by profile, never by qualifier (AAP 0.6.5). RetailCashAccountService injects the bare ExchangeRateSource
// and must keep doing so: the whole point of the shadow comparator is that a replay exercises the REAL service code
// path, so the service cannot hold a branch, a qualifier or a second constructor for replay. @Profile("tool") keeps
// this bean out of the deployed context entirely - ExchangeRateSourceWiringTest boots the default profile and asserts
// that exactly one ExchangeRateSource bean exists there and that it is the Frankfurter client - and @Primary makes it
// the one that wins inside the tool profile, where three beans of that type exist (this one and its two delegates).
// Without @Primary every injection of the interface in that profile would fail as a NoUniqueBeanDefinitionException.
//
// The delegates are injected as their concrete types, which is deliberate and not an oversight: this class IS an
// ExchangeRateSource and is @Primary, so asking the container for the interface - directly, or as a List, Map or
// ObjectProvider of it - would resolve to, or include, this very bean and produce a self-referential bean or
// unbounded recursion on the first lookup. The concrete types are the only injection that cannot close that loop.
//
// The value arrives through @Value because the fx package depends on nothing in config (AAP 0.8.2); tool.rate-source
// is the key application-tool.yml declares and the runbook's Step 1 and Step 2 command lines pass, and the same key
// with the same default is read by ReconciliationService and ShadowComparator, so no two of them can disagree about
// which rate priced an expected balance.
//
// Delegation is bare, and each omission is load-bearing. Both delegates already normalize and validate their codes,
// short-circuit a same-currency pair to exactly 1 and raise ExchangeRateUnavailableException when no rate can be
// determined; the live client already retries once on a transport failure. There is deliberately no fallback from
// one delegate to the other: if a missing staged row quietly fell back to a live rate, a reconciliation run would
// compare legacy balances against live-rate arithmetic and report a false variance or, worse, a false match. The
// tooling has to see the failure, so every exception propagates untouched into the RATE_SOURCE / REJECTED_BY_TARGET
// row that records it. The rate also passes through unscaled: the legacy program truncated the whole expression
// exactly once, after the signed addition, into the unsigned two-decimal WS-CALC
// (backend/cash-account-cobol/COBOL/CASH00.cbl:L17, L222 for credit, L256 for debit), and domain.Money.applyRate
// owns that single truncation. Rounding a rate here would break parity in silence: 100.00 - 0.03 x 0.30 is 99.99
// with one final truncation and 100.00 with a truncated product.
/** Tool-profile delegate selecting the staged legacy rate table or the live client per tool.rate-source. */
@Component
@Profile("tool")
@Primary
public class ToolExchangeRateSource implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolExchangeRateSource.class);

    // The two legal values, spelled exactly as application-tool.yml documents them. Held as compile-time constants
    // so they are legal switch case labels and so the rejection message below can list precisely what is accepted.
    // No tolerant alias is recognized, because none is documented: accepting legacy_table or legacytable here would
    // make the accepted spelling depend on which class read the property, and ReconciliationService and
    // ShadowComparator match the documented tokens literally.
    private static final String LEGACY_TABLE_SOURCE = "legacy-table";

    private static final String LIVE_SOURCE = "live";

    // A rejected value is echoed into an exception message that reaches an operator's console and a batch id into a
    // log line, so each is echoed in a bounded form: enough to spot a typo, too little to carry an injected record.
    private static final int MAX_ECHOED_CHARS = 40;

    // Stands in for an absent value in both the rejection message and the start-up line, so neither renders as an
    // empty pair of quotes that reads like a tool defect rather than a missing argument.
    private static final String UNSET = "<unset>";

    // Resolved once, in the constructor, and never re-read per call. The active rate source has to be constant for
    // the whole run: a migration_run's variance rows are only interpretable against a single source, since with
    // legacy-table both sides of every comparison were priced from the same staged RATES rows while with live they
    // were not. A source that could change mid-run would leave a run whose rows cannot be read as either.
    private final ExchangeRateSource delegate;

    /**
     * Container constructor.
     *
     * @param legacyRateTableSource  the {@code legacy-table} delegate, reproducing the legacy FRANKFURT1 join over
     *                               the staged export rows
     * @param liveExchangeRateClient the {@code live} delegate, looking the rate up at the configured endpoint
     * @param rateSource             {@code --tool.rate-source}, defaulted to {@code legacy-table} to match
     *                               application-tool.yml: parity can only be judged on identical inputs, so the
     *                               mode an operator gets by omitting the flag is the one that prices both sides
     *                               of a comparison from the legacy rows (AAP 0.12.5). Under {@code live}, a
     *                               difference the rate difference fully explains is reclassified RATE_SOURCE /
     *                               ACCEPTED_EXCEPTION and anything left over stays BALANCE / VARIANCE, which is
     *                               why the runbook's live shadow step still gates on {@code legacy-table} and
     *                               reports RATE_SOURCE rows for information only
     * @param batchId                {@code --tool.batch-id} of the runbook step, echoed in the one start-up line
     *                               below so a captured transcript shows which rate source priced which batch; an
     *                               empty fallback keeps a context that never asks for a rate startable
     * @throws IllegalStateException if {@code rateSource} is blank or is neither documented value
     */
    public ToolExchangeRateSource(LegacyRateTableSource legacyRateTableSource,
            FrankfurterExchangeRateClient liveExchangeRateClient,
            @Value("${tool.rate-source:legacy-table}") String rateSource,
            @Value("${tool.batch-id:}") String batchId) {
        String requested = rateSource == null ? "" : rateSource.trim().toLowerCase(Locale.ROOT);

        // Fail closed, exactly as the module fails closed on an unmapped path, an unknown auth type and a
        // non-postgres JDBC_KIND (AAP 0.6.5). Defaulting a misspelling such as --tool.rate-source=leagcy-table to
        // either source would let a whole reconciliation run judge parity against rates the legacy program never
        // saw - producing a wall of unexplained BALANCE variances, or worse a clean-looking run for the wrong
        // reason, with nothing in the evidence to show which happened. Refusing to start costs one restart.
        this.delegate = switch (requested) {
            case LEGACY_TABLE_SOURCE -> Objects.requireNonNull(legacyRateTableSource, "legacyRateTableSource");
            case LIVE_SOURCE -> Objects.requireNonNull(liveExchangeRateClient, "liveExchangeRateClient");
            default -> throw new IllegalStateException("tool.rate-source must be '" + LEGACY_TABLE_SOURCE + "' or '"
                    + LIVE_SOURCE + "', but was '" + abbreviate(requested.isEmpty() ? UNSET : requested) + "'");
        };

        // One line, at start-up, never per call: the shadow comparator replays whole transaction streams through
        // this object, so a per-call line would bury the run's own evidence. Runbook Steps 1 and 2 require showing
        // which rate source a run used, and this is the cheapest record of it that survives in a captured log.
        LOGGER.info("Migration tooling exchange rate source: {} (tool.batch-id={})", requested,
                abbreviate(batchId == null || batchId.isBlank() ? UNSET : batchId.trim()));
    }

    @Override
    public BigDecimal rate(String base, String quote) {
        return delegate.rate(base, quote);
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_ECHOED_CHARS ? value : value.substring(0, MAX_ECHOED_CHARS) + "...";
    }
}
