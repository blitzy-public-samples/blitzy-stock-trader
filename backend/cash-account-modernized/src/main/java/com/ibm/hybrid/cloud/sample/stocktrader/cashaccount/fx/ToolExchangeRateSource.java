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
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;

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
// with the same default is read by ReconciliationService and ShadowComparator. Those three readings agree because
// all of them resolve the value through MigrationRun.RateSource.of(...), the single canonicalization: comparing the
// raw text in one place and canonicalizing it in another is how a " LIVE " run could price live while its own rows
// claimed the legacy-table parity gate.
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

    // A batch id is echoed into a log line, so it is echoed in a bounded form: enough to identify the step, too
    // little to carry an injected record. The rejected-value echo lives with the parsing, in
    // MigrationRun.RateSource.of(...), for the same reason the parsing does - one place decides what the property
    // says and what an operator is told when it says nothing usable.
    private static final int MAX_ECHOED_CHARS = 40;

    // Stands in for an absent batch id in the start-up line, so it does not render as an empty pair of quotes that
    // reads like a tool defect rather than a missing argument.
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
        // Canonicalized and validated by the policy, never by a comparison of this class's own: of(...) tolerates
        // only surrounding whitespace and letter case, and fails closed on anything else, so this constructor and
        // the two classifiers that read the same property cannot disagree about which source is in force.
        MigrationRun.RateSource requested = MigrationRun.RateSource.of(rateSource);

        this.delegate = switch (requested) {
            case LEGACY_TABLE -> Objects.requireNonNull(legacyRateTableSource, "legacyRateTableSource");
            case LIVE -> Objects.requireNonNull(liveExchangeRateClient, "liveExchangeRateClient");
        };

        // One line, at start-up, never per call: the shadow comparator replays whole transaction streams through
        // this object, so a per-call line would bury the run's own evidence. Runbook Steps 1 and 2 require showing
        // which rate source a run used, and this is the cheapest record of it that survives in a captured log. The
        // canonical token is logged rather than the raw value, so the transcript names the source that was applied.
        LOGGER.info("Migration tooling exchange rate source: {} (tool.batch-id={})", requested.token(),
                abbreviate(batchId == null || batchId.isBlank() ? UNSET : batchId.strip()));
    }

    @Override
    public BigDecimal rate(String base, String quote) {
        return delegate.rate(base, quote);
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_ECHOED_CHARS ? value : value.substring(0, MAX_ECHOED_CHARS) + "...";
    }
}
