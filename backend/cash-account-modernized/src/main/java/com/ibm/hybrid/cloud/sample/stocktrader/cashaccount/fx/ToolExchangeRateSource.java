package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
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
    // live does not, so a source that changed mid-run would leave rows readable as neither. Null only while
    // tool.rate-source names neither documented value, which the rejection below carries.
    private final ExchangeRateSource delegate;

    // Non-null exactly when the delegate is null: the refusal MigrationRun.RateSource.of raised, kept so a lookup
    // still fails closed with that message while this constructor does NOT fail the context. Resolving the
    // property here used to abort the refresh, which turned the runner's one-line "Migration tooling invocation
    // rejected" into the innermost Caused-by of a 90-line bean-creation trace - the same message, in the place an
    // operator reads last. MigrationToolRunner.validate() rejects the value before any command runs, so this
    // field is the fail-closed backstop for a caller that bypasses the runner rather than a second error channel.
    private final String rateSourceRejection;

    /**
     * Container constructor.
     *
     * @param legacyRateTableSource  the {@code legacy-table} delegate, injected as a concrete type because asking
     *                               for the interface would resolve to this {@code @Primary} bean itself
     * @param liveExchangeRateClient the {@code live} delegate, as a provider rather than an instance: it is
     *                               obtained only under {@code tool.rate-source=live}, so an offline
     *                               {@code load}, {@code reconcile} or parity-gate window never constructs it
     *                               and never inherits its endpoint requirements
     * @param environment            source of both configured values, each read through {@link Binder}.
     *                               {@code --tool.rate-source} is defaulted to {@code legacy-table} as
     *                               application-tool.yml declares it: parity can only be judged on identical
     *                               inputs, so omitting the flag prices both sides of a comparison from the legacy
     *                               rows (AAP 0.12.5); under {@code live} a difference the rate difference fully
     *                               explains is reclassified RATE_SOURCE / ACCEPTED_EXCEPTION.
     *                               {@code --tool.batch-id} is echoed in the start-up line below so a captured
     *                               transcript shows which rate source priced which batch; its empty fallback keeps
     *                               a context that never asks for a rate startable
     */
    public ToolExchangeRateSource(LegacyRateTableSource legacyRateTableSource,
            ObjectProvider<FrankfurterExchangeRateClient> liveExchangeRateClient, Environment environment) {
        Objects.requireNonNull(legacyRateTableSource, "legacyRateTableSource");
        Objects.requireNonNull(liveExchangeRateClient, "liveExchangeRateClient");
        String batchId = toolProperty(environment, BATCH_ID_PROPERTY, "");
        String configuredRateSource = toolProperty(environment, RATE_SOURCE_PROPERTY, DEFAULT_RATE_SOURCE);

        ExchangeRateSource selected = null;
        String rejection = null;
        try {
            // Canonicalized by the policy, never by a comparison of this class's own, so this constructor and
            // the two classifiers reading the same property cannot disagree about which source is in force -
            // comparing raw text in one place and canonicalizing in another is how a " LIVE " run could price
            // live while its own rows claimed the legacy-table parity gate.
            MigrationRun.RateSource requested = MigrationRun.RateSource.of(configuredRateSource);

            // getObject() under live alone, so the live client is built for the one command class that prices
            // from it - and eagerly for that class rather than at the first lookup, because a run that WILL
            // price live must fail on an unusable endpoint at start-up, while the operator is still watching,
            // instead of part-way through a replayed transaction stream.
            selected = switch (requested) {
                case LEGACY_TABLE -> legacyRateTableSource;
                case LIVE -> liveExchangeRateClient.getObject();
            };

            // One line at start-up, never per call: the shadow comparator replays whole transaction streams
            // through this object, so a per-call line would bury the run's own evidence. The canonical token is
            // logged rather than the raw value, so the transcript names the source that was actually applied.
            LOGGER.info("Migration tooling exchange rate source: {} (tool.batch-id={})", requested.token(),
                    abbreviate(batchId == null || batchId.isBlank() ? UNSET : batchId.strip()));
        } catch (IllegalStateException unusable) {
            // Held rather than rethrown, and deliberately not logged: MigrationToolRunner prints this same
            // message as its one-line argument error before any command runs, and a line emitted during refresh
            // would arrive above it and read as a second, separate fault.
            rejection = unusable.getMessage();
        }

        this.delegate = selected;
        this.rateSourceRejection = rejection;
    }

    /**
     * Makes the live client's bean definition lazy for the tool profile alone.
     *
     * @return the post-processor that marks it, registered only while the {@code tool} profile is active
     */
    // A definition-level change is the only thing that can prevent the construction: the live client is an
    // unprofiled singleton @Component, so the container builds it during every refresh whatever asks for it, and
    // its endpoint requirements then decided whether an offline load or reconcile - neither of which performs a
    // currency conversion at all - could start. Scoped to this profile because the deployed service must keep
    // failing its refresh on an unusable CURRENCY_API_URL, where a rate multiplies into money that reaches the
    // ledger; fx/ExchangeRateSourceWiringTest holds both halves. Declared on the bean that selects the rate
    // source, because "the live client is needed only under live" is this class's rule; static so marking it
    // costs no instance of this class, and the class-level @Profile("tool") gates the method with it.
    @Bean
    static BeanFactoryPostProcessor toolProfileLiveExchangeRateClientLazyInitializer() {
        return beanFactory -> {
            // allowEagerInit false: naming the beans of a type must not create any of them, which is the whole
            // point of the pass. Every matching definition is marked, so a second live client would be covered.
            for (String name : beanFactory.getBeanNamesForType(FrankfurterExchangeRateClient.class, true, false)) {
                beanFactory.getBeanDefinition(name).setLazyInit(true);
            }
        };
    }

    // Bare delegation, with no fallback from one delegate to the other and no rescaling of the rate: if a missing
    // staged row quietly fell back to a live rate, a reconciliation run would compare legacy balances against
    // live-rate arithmetic and report a false variance or a false match, so every exception propagates untouched
    // into the RATE_SOURCE / REJECTED_BY_TARGET row that records it.
    @Override
    public BigDecimal rate(String base, String quote) {
        return requireDelegate().rate(base, quote);
    }

    // Fails closed at the lookup with the canonicalization's own message, for the caller that reached a rate
    // without passing the runner's validation. Not a second wording: the string is the one
    // MigrationRun.RateSource.of produced, so the two surfaces cannot state different accepted values.
    private ExchangeRateSource requireDelegate() {
        if (rateSourceRejection != null) {
            throw new IllegalStateException(rateSourceRejection);
        }
        return delegate;
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_ECHOED_CHARS ? value : value.substring(0, MAX_ECHOED_CHARS) + "...";
    }

    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so either of these values written as #{...} would execute while this bean was being created - and
    // this bean selects which rates price a whole migration run. Binder resolves ${...} and converts, evaluating
    // nothing, so an unusable value stays text until MigrationRun.RateSource.of refuses it. A non-configurable
    // Environment exposes no property sources, so it yields the documented default exactly as an unset key does.
    private static String toolProperty(Environment environment, String key, String fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(String.class)).orElse(fallback);
    }
}
