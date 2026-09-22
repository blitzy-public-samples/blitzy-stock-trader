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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LegacyRateTableRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

// MIGRATION-SOURCE ONLY, and @Profile("tool") is what enforces it. STOCKTRD.FRANKFURT1 is an artifact of the system
// being replaced, not a dependency of the replacement: target state is the live lookup in
// FrankfurterExchangeRateClient against the chart-injected CURRENCY_API_URL. This bean exists so the reconciler and
// the shadow comparator can recompute expected balances from the very rate rows the legacy arithmetic used. Were it
// reachable from the request path, a retail credit or debit would quietly apply a stale, two-decimal, possibly
// absent legacy rate in place of a live one - so the profile is a containment boundary and not a convenience, and
// ExchangeRateSourceWiringTest enforces it by booting the default profile and asserting that exactly one
// ExchangeRateSource bean exists there, that it is the Frankfurter client, and that this bean is absent.
//
// Why parity is judged on legacy rates: tool.rate-source=legacy-table is the default precisely so both sides of a
// comparison are computed from identical inputs (AAP 0.12.5). Judging a migrated balance against a live rate would
// report every intervening ECB fixing as a balance variance. Under tool.rate-source=live, a difference the rate
// difference fully explains is reclassified RATE_SOURCE / ACCEPTED_EXCEPTION and anything left over stays
// BALANCE / VARIANCE. Neither classification is decided here: this class supplies the staged rate and nothing else,
// and ToolExchangeRateSource - not this class - chooses between the two sources.
//
// Dependency direction, one-way by construction: LegacyRateTable and MigrationRun are imported as entity data types
// only, because the already-declared repository signatures return them. No migration service, loader, reconciler,
// comparator or runner is imported, since each of those depends on this class and importing one would close a
// cycle. Nothing from config, retail, institutional, audit or domain is imported either, which is why the batch id
// arrives through @Value rather than through CashAccountProperties, and why the rate key length is declared below
// instead of being taken from the reconciler's characterization constants.
/** Reproduces the legacy FRANKFURT1 rate join over staged export rows; tooling only, never on the request path. */
@Component
@Profile("tool")
public class LegacyRateTableSource implements ExchangeRateSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyRateTableSource.class);

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    // Five characters, from WS-CURRENCY-KEY PIC X(5) (backend/cash-account-cobol/COBOL/CASH00.cbl:L19). Declared
    // here rather than imported from the migration package's characterization constants, which record the same
    // value: fx must not depend on migration for logic, and a private constant costs less than a dependency cycle.
    private static final int LEGACY_RATE_KEY_LENGTH = 5;

    // The variance kind the tooling records for the condition this class refuses to paper over. Naming it in the
    // failure message lets a runbook reader tie the exception to the migration_reconciliation row beside it.
    private static final String NULL_RATE = "NULL_RATE";

    // A rejected code and a malformed batch id both reach an error payload and a log line, so each is echoed only
    // in a bounded, printable form: enough to diagnose a typo, too little to carry an injected log record.
    private static final int MAX_ECHOED_CHARS = 40;

    private final LegacyRateTableRepository legacyRates;

    private final MigrationRunRepository migrationRuns;

    private final String configuredBatchId;

    // The only state this class holds, and it is an identifier rather than a rate. Rates are deliberately not
    // cached: the tool reads them inside the caller's transaction, and a cache would keep answering from a value
    // the staging rows no longer hold if an export were reloaded mid-run. Resolution is idempotent, so the worst a
    // lost race costs is a repeated query.
    private volatile UUID stagingRunId;

    /**
     * Container constructor.
     *
     * @param legacyRates       staging access to the exported rate rows. The key it is handed is already truncated,
     *                          because that repository deliberately normalizes nothing
     * @param migrationRuns     consulted once per instance to resolve which staging run holds those rows
     * @param configuredBatchId {@code --tool.batch-id} of the runbook step, declared with an empty fallback so a
     *                          context that never asks for a rate still starts; an absent value fails only at the
     *                          point a lookup actually needs it
     */
    public LegacyRateTableSource(LegacyRateTableRepository legacyRates, MigrationRunRepository migrationRuns,
            @Value("${tool.batch-id:}") String configuredBatchId) {
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

        // Short-circuited before the staging run is resolved, not after. A same-currency conversion needs no staged
        // row, so it must not need a staged run either - otherwise a USD-only replay would fail for want of a batch
        // id. The ExchangeRateSource contract requires exactly 1 with no lookup, and it is also exact legacy
        // parity: a USD account against a USD 1.00 rate row computed the same value in the program being replaced.
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        return rate(resolveStagingRunId(), from, to);
    }

    /**
     * Run-explicit variant for callers in the {@code migration} packages that already know which staging run they
     * are judging. They neither depend on {@code tool.batch-id} nor share this instance's resolution, which is what
     * keeps this class free of a setter and of mutable ambient state; the interface method above delegates here
     * once it has resolved the run itself.
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

        // The key is the ACCOUNT's currency, never the base: CASH-ACCT-CREDIT and CASH-ACCT-DEBIT both move
        // CURRENCYC - the account row's own CHAR(8) currency - into WS-CURRENCY-KEY before selecting the rate row
        // (CASH00.cbl:L213 credit, L247 debit), and `quote` is that account currency here.
        //
        // Truncated, never padded and never re-trimmed: the target field is X(5) (CASH00.cbl:L19) and COBOL's
        // left-justified alphanumeric MOVE keeps the first five characters and discards the rest. A validated
        // three-letter code is unaffected, so this is a no-op for every code the API accepts; the rule is
        // reproduced because the legacy column is CHAR(5) and a real export may carry a longer value, for which
        // the legacy join resolved the truncation and not the full string.
        String key = to.length() > LEGACY_RATE_KEY_LENGTH ? to.substring(0, LEGACY_RATE_KEY_LENGTH) : to;

        Optional<LegacyRateTable> staged = legacyRates.findByRunIdAndCurrnkey(stagingRunId, key);
        if (staged.isEmpty()) {
            // Fail closed. The program being replaced ran its COMPUTE and its UPDATE regardless of the rate
            // SELECT's outcome: a missing row's SQLCODE 100 was overwritten by the UPDATE's SQLCODE 0
            // (CASH00.cbl:L214-L231), so a balance derived from the uninitialized RATES host variable
            // (COBOL/DCLFRANK.cpy:L22, declared with no VALUE clause) was committed under a success code.
            // Substituting 1 - or any rate - here would rebuild that defect inside the very tool built to detect
            // it. The absent row is reported as a RATE_SOURCE variance instead, which makes a C/D replay for this
            // currency REJECTED_BY_TARGET rather than quietly wrong.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    "no staged FRANKFURT1 row for key " + key + " in run " + stagingRunId, null);
        }

        BigDecimal rates = staged.get().rates();
        if (rates == null) {
            // Nullable because the legacy DDL declared no NOT NULL on the column
            // (backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L58) and the loader stages the export as it stands.
            // Legacy behaviour for this case was the -305 the program never checked, so there is no value to
            // reproduce - only a finding to report.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    NULL_RATE + " - staged FRANKFURT1 row " + key + " in run " + stagingRunId + " carries no rate",
                    null);
        }
        if (rates.signum() <= 0) {
            // A zero rate would turn every credit and debit into a silent no-op and a negative one would invert
            // the sign of the operation: the same class of quietly wrong answer as the absent row above.
            throw ExchangeRateUnavailableException.forPair(from, to,
                    "staged FRANKFURT1 rate for key " + key + " in run " + stagingRunId + " is not positive", null);
        }

        // Returned exactly as staged - no rescaling, no rounding, no trailing-zero stripping, and no
        // pre-multiplication by an amount. The legacy program truncated the whole expression once, after the signed
        // addition, into an unsigned two-decimal field (CASH00.cbl:L222 credit, L256 debit), and domain.Money owns
        // that single truncation toward zero. Pre-scaling the rate or the product here would turn
        // 100.00 - 0.03 x 0.30 from the correct 99.99 into 100.00 and break parity without failing anything.
        //
        // Two decimals are the legacy ceiling rather than a choice: RATES is DECIMAL(3,2) / PIC S9(1)V9(2) COMP-3
        // (COBOL/DCLFRANK.cpy:L12, L22), so it could never exceed 9.99 and could never express a currency worth
        // less than a tenth of the base unit - JPY and INR were unrepresentable. Live rates carry four to six
        // significant digits, and that divergence is reported as a RATE_SOURCE variance, never absorbed.
        //
        // CURRNBASE, AMOUNT and LOADDT are deliberately never read. The legacy SELECT fetched all five columns into
        // :DCLFRANKFURT1, yet no arithmetic and no MOVE in the program referenced any of the three
        // (CASH00.cbl:L214-L222), so the multiplicand is the caller-supplied COMMAREA amount and not
        // FRANKFURT1.AMOUNT (AAP 0.4.1); reading one here would contradict the characterization.
        return rates;
    }

    // Resolved once and cached, because it cannot change within one tool invocation: the runner executes one
    // command against one batch. Twice-checked publication over a volatile field keeps the common path lock-free
    // while ensuring a concurrent caller sees a fully resolved identifier.
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

    // The latest non-FAILED LOAD under the batch is the run whose rows are actually staged. A load applies its
    // whole export in one database transaction (AAP 0.6.3): it ends CLEAN or VARIANCE with every row applied, or
    // FAILED with none applied, and a retry is a new run_id under the same batch_id. Reading a FAILED run would
    // therefore query a run that staged nothing, and because the list arrives ordered by started_at ascending, the
    // last match is the most recent successful load - exactly the data a reconcile or a shadow-compare judges.
    private UUID loadStagingRunId() {
        UUID batchId = requireBatchId();
        List<MigrationRun> runsInBatch = migrationRuns.findByBatchIdOrderByStartedAtAsc(batchId);

        MigrationRun stagingRun = null;
        for (MigrationRun run : runsInBatch) {
            if (run.mode() == MigrationRun.Mode.LOAD && run.status() != MigrationRun.Status.FAILED) {
                stagingRun = run;
            }
        }
        if (stagingRun == null) {
            throw new ExchangeRateUnavailableException("no completed load run for --tool.batch-id=" + batchId
                    + "; a load must stage STOCKTRD.FRANKFURT1 before a reconcile or shadow-compare runs under the"
                    + " same batch id");
        }

        LOGGER.info("Legacy rate lookups resolve against staging run {} of batch {}", stagingRun.runId(), batchId);
        return stagingRun.runId();
    }

    // Fails closed on a configuration fault for the same reason a missing row does: the alternative is a tool that
    // quietly answers from a place the operator never named. It is deliberately not a fall-back to the live client
    // either - that choice belongs to tool.rate-source and ToolExchangeRateSource, never to a recovery path hidden
    // in here, because a reconcile whose expected values came from a source nobody selected proves nothing.
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

    // Shape only, and deliberately NOT membership of cashaccount.fx.accepted-currencies. This source reads a legacy
    // export, where a currency outside the accepted set is a finding rather than an input error:
    // the reconciler records it as a CURRENCY variance of kind INVALID_IN_LEGACY (AAP 0.6.3, 0.7.2), so
    // rejecting it here would hide the very variance the tooling exists to report. Keeping this failure a
    // CashAccountException also keeps the two classes distinct - a malformed code stays 400 INVALID_CURRENCY and
    // only an undeterminable rate becomes 503, so a caller's typo can never look like a transient outage.
    private static void requireIsoCode(String code) {
        if (!ISO_4217_CODE.matcher(code).matches()) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_CURRENCY,
                    "Unsupported currency code: " + boundedEcho(code));
        }
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
