package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;

/**
 * Tool-profile delegate selecting the staged legacy rate table or the live client per {@code tool.rate-source},
 * kept out of the deployed context by {@code @Profile("tool")} and made {@code @Primary} because the tool profile
 * holds three beans of this type - this one and its two delegates (AAP 0.6.5).
 */
@Component
@Profile("tool")
@Primary
public class ToolExchangeRateSource implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolExchangeRateSource.class);

    // A batch id echoed into a log line is echoed in a bounded form: enough to identify the step, too little to
    // carry an injected record. The rejected-value echo lives with the parsing, in MigrationRun.RateSource.of(...).
    private static final int MAX_ECHOED_CHARS = 40;

    // Stands in for an absent batch id in the start-up line, so it does not render as empty quotes that read like
    // a tool defect rather than a missing argument.
    private static final String UNSET = "<unset>";

    // The two keys and the documented default, named once so the reader below and application-tool.yml agree.
    private static final String RATE_SOURCE_PROPERTY = "tool.rate-source";

    private static final String BATCH_ID_PROPERTY = "tool.batch-id";

    private static final String DEFAULT_RATE_SOURCE = "legacy-table";

    // Resolved once and never re-read per call: a migration_run's variance rows are interpretable only against a
    // single source, since legacy-table prices both sides of every comparison from the same staged RATES rows and
    // live does not, so a source that changed mid-run would leave rows readable as neither.
    private final ExchangeRateSource delegate;

    /**
     * Container constructor.
     *
     * @param legacyRateTableSource  the {@code legacy-table} delegate, injected as a concrete type because asking
     *                               for the interface would resolve to this {@code @Primary} bean itself
     * @param liveExchangeRateClient the {@code live} delegate, injected as a concrete type for the same reason
     * @param environment            source of both configured values, each read through {@link Binder}.
     *                               {@code --tool.rate-source} is defaulted to {@code legacy-table} as
     *                               application-tool.yml declares it: parity can only be judged on identical
     *                               inputs, so omitting the flag prices both sides of a comparison from the legacy
     *                               rows (AAP 0.12.5); under {@code live} a difference the rate difference fully
     *                               explains is reclassified RATE_SOURCE / ACCEPTED_EXCEPTION.
     *                               {@code --tool.batch-id} is echoed in the start-up line below so a captured
     *                               transcript shows which rate source priced which batch; its empty fallback keeps
     *                               a context that never asks for a rate startable
     * @throws IllegalStateException if the rate source is blank or is neither documented value
     */
    public ToolExchangeRateSource(LegacyRateTableSource legacyRateTableSource,
            FrankfurterExchangeRateClient liveExchangeRateClient, Environment environment) {
        String batchId = toolProperty(environment, BATCH_ID_PROPERTY, "");

        // Canonicalized by the policy, never by a comparison of this class's own, so this constructor and the two
        // classifiers reading the same property cannot disagree about which source is in force - comparing raw
        // text in one place and canonicalizing in another is how a " LIVE " run could price live while its own
        // rows claimed the legacy-table parity gate.
        MigrationRun.RateSource requested =
                MigrationRun.RateSource.of(toolProperty(environment, RATE_SOURCE_PROPERTY, DEFAULT_RATE_SOURCE));

        this.delegate = switch (requested) {
            case LEGACY_TABLE -> Objects.requireNonNull(legacyRateTableSource, "legacyRateTableSource");
            case LIVE -> Objects.requireNonNull(liveExchangeRateClient, "liveExchangeRateClient");
        };

        // One line at start-up, never per call: the shadow comparator replays whole transaction streams through
        // this object, so a per-call line would bury the run's own evidence. The canonical token is logged rather
        // than the raw value, so the transcript names the source that was actually applied.
        LOGGER.info("Migration tooling exchange rate source: {} (tool.batch-id={})", requested.token(),
                abbreviate(batchId == null || batchId.isBlank() ? UNSET : batchId.strip()));
    }

    // Bare delegation, with no fallback from one delegate to the other and no rescaling of the rate: if a missing
    // staged row quietly fell back to a live rate, a reconciliation run would compare legacy balances against
    // live-rate arithmetic and report a false variance or a false match, so every exception propagates untouched
    // into the RATE_SOURCE / REJECTED_BY_TARGET row that records it.
    @Override
    public BigDecimal rate(String base, String quote) {
        return delegate.rate(base, quote);
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_ECHOED_CHARS ? value : value.substring(0, MAX_ECHOED_CHARS) + "...";
    }

    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so either of these values written as #{...} would execute while this bean was being created - and
    // this bean selects which rates price a whole migration run. Binder resolves ${...} and converts, evaluating
    // nothing, so an unusable value stays text and is refused by MigrationRun.RateSource.of below. A non-configurable
    // Environment exposes no property sources, so it yields the documented default exactly as an unset key does.
    private static String toolProperty(Environment environment, String key, String fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(String.class)).orElse(fallback);
    }
}
