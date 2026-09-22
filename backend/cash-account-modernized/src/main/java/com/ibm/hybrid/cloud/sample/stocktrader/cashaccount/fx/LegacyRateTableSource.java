package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

/**
 * Reproduces the legacy FRANKFURT1 rate join over staged export rows, kept off the request path by
 * {@code @Profile("tool")} - a containment boundary, because a retail credit or debit reaching this bean would
 * quietly apply a stale, two-decimal, possibly absent legacy rate in place of a live one.
 */
@Component
@Profile("tool")
public class LegacyRateTableSource implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyRateTableSource.class);

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    // Five characters, from WS-CURRENCY-KEY PIC X(5) (backend/cash-account-cobol/COBOL/CASH00.cbl:L19). Declared
    // here rather than imported from the migration package's characterization constants, which record the same
    // value: fx must not depend on migration, and a private constant costs less than a dependency cycle.
    private static final int LEGACY_RATE_KEY_LENGTH = 5;

    // Naming the variance kind in the failure message lets a runbook reader tie the exception to the
    // migration_reconciliation row beside it.
    private static final String NULL_RATE = "NULL_RATE";

    // The runbook step's own identifier, named once here so the reader below and every failure message above agree.
    private static final String BATCH_ID_PROPERTY = "tool.batch-id";

    // A rejected code and a malformed batch id both reach an error payload and a log line, so each is echoed in a
    // bounded printable form: enough to diagnose a typo, too little to carry an injected log record.
    private static final int MAX_ECHOED_CHARS = 40;

    private final LegacyRateTableRepository legacyRates;

    private final MigrationRunRepository migrationRuns;

    private final String configuredBatchId;

    // An identifier, never a cached rate: a rate cache would keep answering from a value the staging rows no
    // longer hold if an export were reloaded mid-run. Resolution is idempotent, so a lost race costs one query.
    private volatile UUID stagingRunId;

    /**
     * Container constructor.
     *
     * @param legacyRates   staging access to the exported rate rows; the key it is handed is already
     *                      truncated, because that repository deliberately normalizes nothing
     * @param migrationRuns consulted once per instance to resolve which staging run holds those rows
     * @param environment   source of {@code tool.batch-id}, read through {@link Binder} rather than a
     *                      {@code @Value} placeholder
     */
    @Autowired
    public LegacyRateTableSource(LegacyRateTableRepository legacyRates, MigrationRunRepository migrationRuns,
            Environment environment) {
        this(legacyRates, migrationRuns, batchIdFrom(environment));
    }

    /**
     * Values constructor, for a caller that holds the batch id already.
     *
     * @param legacyRates       staging access to the exported rate rows
     * @param migrationRuns     consulted once per instance to resolve which staging run holds those rows
     * @param configuredBatchId {@code --tool.batch-id} of the runbook step, empty being tolerated so a context
     *                          that never asks for a rate still starts; absence fails only at the lookup
     */
    public LegacyRateTableSource(LegacyRateTableRepository legacyRates, MigrationRunRepository migrationRuns,
            String configuredBatchId) {
        this.legacyRates = Objects.requireNonNull(legacyRates, "legacyRates");
        this.migrationRuns = Objects.requireNonNull(migrationRuns, "migrationRuns");
        this.configuredBatchId = configuredBatchId == null ? "" : configuredBatchId.trim();
    }

    @Override
    public BigDecimal rate(String base, String quote) {
        String from = normalizeCode(base);
        String to = normalizeCode(quote);
        requireIsoCode(from);
        requireIsoCode(to);

        // Short-circuited before the staging run is resolved, not after: a same-currency conversion needs no
        // staged row, so it must not need a staged run either - otherwise a USD-only replay would fail for want of
        // a batch id.
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        return rate(resolveStagingRunId(), from, to);
    }

    /**
     * Run-explicit variant for {@code migration} callers that already know which staging run they are judging, so
     * they depend on neither {@code tool.batch-id} nor this instance's resolution.
     *
     * @param stagingRunId {@code migration_run.run_id} of the load whose staged rate rows are to be read
     * @param base         ISO 4217 code the amount is expressed in, trimmed and uppercased here
     * @param quote        ISO 4217 code of the account, which supplies the legacy lookup key
     * @return the staged {@code RATES} value exactly as loaded, never {@code null}
     * @throws ExchangeRateUnavailableException if no row is staged for the key in that run, or its rate is null or
     *                                          not positive
     * @throws CashAccountException             carrying {@code INVALID_CURRENCY} if either code is malformed
     */
    public BigDecimal rate(UUID stagingRunId, String base, String quote) {
        Objects.requireNonNull(stagingRunId, "stagingRunId");
        String from = normalizeCode(base);
        String to = normalizeCode(quote);
        requireIsoCode(from);
        requireIsoCode(to);
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // The key is the ACCOUNT's currency, never the base: both legacy paragraphs move CURRENCYC into
        // WS-CURRENCY-KEY before selecting the rate row (CASH00.cbl:L213 credit, L247 debit), and `quote` is that
        // account currency here. Truncated, never padded: COBOL's left-justified MOVE into X(5) (CASH00.cbl:L19)
        // kept the first five characters, so a real export carrying a longer value joined on the truncation. A
        // validated three-letter code is unaffected.
        String key = to.length() > LEGACY_RATE_KEY_LENGTH ? to.substring(0, LEGACY_RATE_KEY_LENGTH) : to;

        Optional<LegacyRateTable> staged = legacyRates.findByRunIdAndCurrnkey(stagingRunId, key);
        if (staged.isEmpty()) {
            // Fail closed. The legacy ran its COMPUTE and UPDATE regardless of the rate SELECT's outcome, so a
            // balance derived from the uninitialized RATES host variable was committed under SQLCODE 0
            // (CASH00.cbl:L214-L231; COBOL/DCLFRANK.cpy:L22 declares it with no VALUE clause). Substituting any
            // rate here would rebuild that defect inside the tool built to detect it; the absent row becomes a
            // RATE_SOURCE variance, making the replay REJECTED_BY_TARGET rather than quietly wrong.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    "no staged FRANKFURT1 row for key " + key + " in run " + stagingRunId, null);
        }

        BigDecimal rates = staged.get().rates();
        if (rates == null) {
            // Nullable because the legacy DDL declared no NOT NULL on the column
            // (backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L58) and the loader stages the export as it stands.
            // Legacy behaviour was the -305 the program never checked, so there is nothing to reproduce.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    NULL_RATE + " - staged FRANKFURT1 row " + key + " in run " + stagingRunId + " carries no rate",
                    null);
        }
        if (rates.signum() <= 0) {
            // A zero rate would make every credit and debit a silent no-op and a negative one would invert the
            // sign: the same class of quietly wrong answer as the absent row above.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    "staged FRANKFURT1 rate for key " + key + " in run " + stagingRunId + " is not positive", null);
        }

        // Returned exactly as staged - no rescaling, no rounding, no pre-multiplication by an amount: the legacy
        // truncated the whole expression once, after the signed addition (CASH00.cbl:L222 credit, L256 debit), and
        // domain.Money owns that truncation. Pre-scaling here would turn 100.00 - 0.03 x 0.30 from the correct
        // 99.99 into 100.00 and break parity without failing anything.
        //
        // Its two decimals are the legacy ceiling, not a choice: RATES is DECIMAL(3,2) / PIC S9(1)V9(2) COMP-3
        // (COBOL/DCLFRANK.cpy:L12, L22), which could not exceed 9.99 and left JPY and INR unrepresentable, so the
        // divergence from four-to-six-digit live rates is reported as a RATE_SOURCE variance, never absorbed.
        //
        // CURRNBASE, AMOUNT and LOADDT are deliberately never read: the legacy SELECT fetched all five columns yet
        // no arithmetic or MOVE referenced any of the three (CASH00.cbl:L214-L222), so the multiplicand is the
        // COMMAREA amount and not FRANKFURT1.AMOUNT (AAP 0.4.1).
        return rates;
    }

    // Cached because it cannot change within one tool invocation: the runner executes one command against one
    // batch. Twice-checked publication over the volatile field keeps the common path lock-free.
    private UUID resolveStagingRunId() {
        UUID resolved = stagingRunId;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (stagingRunId == null) {
                stagingRunId = loadStagingRunId();
            }
            return stagingRunId;
        }
    }

    // The batch's COMPLETED load is the run whose rows are actually staged, and the repository's shared selector
    // decides which run that is - the same one reconcile/ReconciliationService and shadow/ShadowComparator resolve
    // through, so the three cannot disagree. Deciding it here again is what let a RUNNING attempt pass as
    // completed and mask the last load that really staged.
    private UUID loadStagingRunId() {
        UUID batchId = requireBatchId();

        // Fails closed rather than falling back to an older load or to the live client: a replay whose expected
        // values came from a run the operator did not name proves nothing about the run they did.
        MigrationRun stagingRun = migrationRuns.findLatestCompletedLoad(batchId)
                .orElseThrow(() -> new ExchangeRateUnavailableException("no completed load run for --tool.batch-id="
                        + batchId + "; a load must stage STOCKTRD.FRANKFURT1 and end CLEAN or VARIANCE before a"
                        + " reconcile or shadow-compare runs under the same batch id"));

        LOGGER.info("Legacy rate lookups resolve against staging run {} of batch {}", stagingRun.runId(), batchId);
        return stagingRun.runId();
    }

    // Fails closed on a configuration fault, and deliberately does not fall back to the live client: a reconcile
    // whose expected values came from a source nobody selected proves nothing, so that choice belongs to
    // tool.rate-source and ToolExchangeRateSource alone.
    private UUID requireBatchId() {
        if (configuredBatchId.isEmpty()) {
            throw new ExchangeRateUnavailableException("tool.batch-id is not set; a reconcile or shadow-compare must"
                    + " name the batch whose completed load staged the legacy rate rows (--tool.batch-id=<uuid>)");
        }
        try {
            return UUID.fromString(configuredBatchId);
        } catch (IllegalArgumentException malformed) {
            throw new ExchangeRateUnavailableException("tool.batch-id is not a UUID: "
                    + boundedEcho(configuredBatchId), malformed);
        }
    }

    // Locale.ROOT, never the no-argument toUpperCase(): under a Turkish default locale "i" maps to a dotted capital,
    // which would fold TRY and ILS into codes no staged row carries.
    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    // Shape only, and deliberately NOT membership of cashaccount.fx.accepted-currencies: this source reads a
    // legacy export, where a currency outside the accepted set is a CURRENCY variance of kind INVALID_IN_LEGACY
    // (AAP 0.6.3, 0.7.2), so rejecting it here would hide the very variance the tooling exists to report. It stays
    // a CashAccountException so a malformed code remains 400 INVALID_CURRENCY and never a transient 503.
    private static void requireIsoCode(String code) {
        if (!ISO_4217_CODE.matcher(code).matches()) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_CURRENCY,
                    "Unsupported currency code: " + boundedEcho(code));
        }
    }

    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so a batch id of the form #{...} would execute while this bean was being created. Binder resolves
    // ${...} and converts, and evaluates nothing, so an operator's typo stays a typo and is rejected by
    // requireBatchId above. A non-configurable Environment exposes no property sources, which is indistinguishable
    // from an unset batch id and fails at the first lookup rather than at start-up, exactly as an unset one does.
    private static String batchIdFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return "";
        }
        return Binder.get(environment).bind(BATCH_ID_PROPERTY, Bindable.of(String.class)).orElse("");
    }

    private static String boundedEcho(String value) {
        StringBuilder safe = new StringBuilder(MAX_ECHOED_CHARS);
        for (int index = 0; index < value.length() && safe.length() < MAX_ECHOED_CHARS; index++) {
            char candidate = value.charAt(index);
            if (candidate < 128 && (Character.isLetterOrDigit(candidate) || candidate == '-')) {
                safe.append(candidate);
            }
        }
        return safe.isEmpty() ? "<empty>" : safe.toString();
    }
}
