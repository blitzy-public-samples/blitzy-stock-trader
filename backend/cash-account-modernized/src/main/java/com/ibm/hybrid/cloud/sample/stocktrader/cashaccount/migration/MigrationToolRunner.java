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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowComparator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

/*
 * NO PATH TO THE LIVE MAINFRAME EXISTS HERE, AND NONE MAY BE ADDED (AAP 0.3.2). This class reaches exactly
 * two things: ordinary files beneath the directory named by tool.input, and the configured PostgreSQL
 * datasource. It opens no connection to DB2 for z/OS, names no VSAM cluster, data-set name, z/OS host or
 * credential, reads no deployment value and writes none - not cashAccount.enabled, not cashAccount.url, not
 * database.kind, not broker's CASH_ACCOUNT_URL, not the StockTrader custom resource - and it retires,
 * exports-for-retention or deletes no CICS, DB2 or VSAM asset.
 *
 * The reason is that this deliverable builds the migration mechanism and is verified against the synthetic
 * fixtures alone. Bulk migration against the real exports, the live dual-run, the cutover and the
 * decommission are steps of docs/operational-runbook.md, which this module documents and never executes;
 * the prohibition holds regardless of what access the executing environment happens to have, so the only
 * durable form of it is a class that has no such capability to misuse.
 */
/** Executes one migration-tooling command per invocation and turns its outcome into a process exit code. */
// @Profile("tool") is load-bearing twice over. It keeps this bean out of the default (web) profile, so a
// deployed pod cannot run a migration command however it is invoked; and it is what makes the System.exit in
// run(...) safe, because the bean cannot exist in a context that is serving traffic.
@Component
@Profile("tool")
public class MigrationToolRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationToolRunner.class);

    // The exit code is an evidence artifact, not a convenience: runbook Step 1 records it beside the
    // migration_run and migration_reconciliation rows and the export checksums, and a gate reads both. A
    // wrong code is a wrong sign-off, which is why 2 is reserved for "the run completed and recorded
    // variances" and can never stand in for an error.
    private static final int EXIT_CLEAN = 0;

    private static final int EXIT_ERROR = 1;

    private static final int EXIT_VARIANCE = 2;

    private static final String COMMAND_LOAD = "load";

    private static final String COMMAND_RECONCILE = "reconcile";

    private static final String COMMAND_SHADOW_COMPARE = "shadow-compare";

    private static final List<String> ACCEPTED_COMMANDS =
            List.of(COMMAND_LOAD, COMMAND_RECONCILE, COMMAND_SHADOW_COMPARE);

    // Each property is named once, here, and both the @Value expressions below and the typo guard are built
    // from these constants. A key spelled differently in the two places would bind silently to a default and
    // change behaviour without failing anything, which is the one failure mode a CLI contract cannot absorb.
    private static final String COMMAND_PROPERTY = "tool.command";

    private static final String INPUT_PROPERTY = "tool.input";

    private static final String BATCH_ID_PROPERTY = "tool.batch-id";

    private static final String RATE_SOURCE_PROPERTY = "tool.rate-source";

    private static final String LEGACY_CHARSET_PROPERTY = "tool.legacy-charset";

    private static final String LEGACY_TIMEZONE_PROPERTY = "tool.legacy-timezone";

    private static final String HISTORY_RECORD_LENGTH_PROPERTY = "tool.history-record-length";

    private static final Set<String> DECLARED_TOOL_PROPERTIES = Set.of(COMMAND_PROPERTY,
            INPUT_PROPERTY,
            BATCH_ID_PROPERTY,
            RATE_SOURCE_PROPERTY,
            LEGACY_CHARSET_PROPERTY,
            LEGACY_TIMEZONE_PROPERTY,
            HISTORY_RECORD_LENGTH_PROPERTY);

    private static final String TOOL_PROPERTY_PREFIX = "tool.";

    /** Escape hatch for a checkout whose working directory is neither the module root nor its parent. */
    private static final String CHARACTERIZATION_DOCUMENT_PROPERTY = "cashaccount.characterization-doc";

    // Under docs/ and deliberately not in src/main/resources, so it is not on the classpath and is resolved
    // as a filesystem path relative to the JVM's working directory.
    private static final String CHARACTERIZATION_DOCUMENT_PATH = "docs/legacy-characterization.md";

    private static final String PARENT_DIRECTORY = "..";

    private static final Pattern CHARACTERIZATION_STATUS_PATTERN =
            Pattern.compile("^Status:\\s*(DRAFT|ACCEPTED)\\s*$", Pattern.CASE_INSENSITIVE);

    // migration_run.source_path is VARCHAR(512) (schema/cash-account-schema.sql). Checked before the row is
    // opened so an over-long export path is an argument error naming the limit, rather than a constraint
    // violation on the insert that would look like a database failure.
    private static final int SOURCE_PATH_MAX_LENGTH = 512;

    private final LegacyLoader loader;

    private final ReconciliationService reconciliationService;

    private final ShadowComparator shadowComparator;

    private final MigrationRunRepository runs;

    private final MigrationReconciliationRepository reconciliations;

    private final ConfigurableApplicationContext context;

    private final String command;

    private final String input;

    private final String batchId;

    private final String rateSource;

    private final String legacyCharset;

    private final String legacyTimeZone;

    private final Integer historyRecordLength;

    public MigrationToolRunner(LegacyLoader loader,
                               ReconciliationService reconciliationService,
                               ShadowComparator shadowComparator,
                               MigrationRunRepository runs,
                               MigrationReconciliationRepository reconciliations,
                               ConfigurableApplicationContext context,
                               @Value("${" + COMMAND_PROPERTY + ":}") String command,
                               @Value("${" + INPUT_PROPERTY + ":}") String input,
                               @Value("${" + BATCH_ID_PROPERTY + ":}") String batchId,
                               @Value("${" + RATE_SOURCE_PROPERTY + ":legacy-table}") String rateSource,
                               @Value("${" + LEGACY_CHARSET_PROPERTY + ":"
                                       + LegacyExportFormat.DEFAULT_LEGACY_CHARSET + "}") String legacyCharset,
                               @Value("${" + LEGACY_TIMEZONE_PROPERTY + ":UTC}") String legacyTimeZone,
                               // Nullable on purpose, so an absent record length is distinguishable from a
                               // declared one: application-tool.yml gives this key no default at all, because
                               // absence is what has to be rejected for a binary history export.
                               @Value("${" + HISTORY_RECORD_LENGTH_PROPERTY + ":#{null}}")
                               Integer historyRecordLength) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService");
        this.shadowComparator = Objects.requireNonNull(shadowComparator, "shadowComparator");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.context = Objects.requireNonNull(context, "context");
        this.command = command;
        this.input = input;
        this.batchId = batchId;
        this.rateSource = rateSource;
        this.legacyCharset = legacyCharset;
        this.legacyTimeZone = legacyTimeZone;
        this.historyRecordLength = historyRecordLength;
    }

    /**
     * One validated invocation: every {@code tool.*} value the dispatch needs, already parsed.
     *
     * <p>Nothing reaches the database until an instance of this exists, which is what makes an argument error
     * distinguishable from a run that failed: the former leaves no {@code migration_run} row at all.</p>
     */
    record ToolInvocation(String command,
                          Path input,
                          UUID batchId,
                          Charset legacyCharset,
                          ZoneId legacyTimeZone,
                          Integer historyRecordLength) {
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int exitCode = execute(arguments);

        // WHY THE CODE IS FORCED RATHER THAN RETURNED. CashAccountApplication.main calls
        // SpringApplication.run(args) and does not wrap its result, so an ApplicationRunner that simply
        // returns leaves the JVM exiting 0 - a run that recorded variances would then report success to the
        // gate that reads the code. SpringApplication.exit closes the context first, so Hikari, the
        // persistence unit and every other lifecycle bean shut down cleanly before the code is handed back;
        // a bare System.exit would cut the process off mid-shutdown. Throwing an ExitCodeGenerator exception
        // is the other documented route and is deliberately not used: it reports a completed run with
        // variances as a stack trace, and the variances are already rows.
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    /**
     * Runs one command and reports the code the process should exit with, without exiting.
     *
     * @return {@link #EXIT_CLEAN} when the run closed {@code CLEAN}, {@link #EXIT_VARIANCE} when it closed
     *         {@code VARIANCE}, {@link #EXIT_ERROR} for an argument error or any failure
     */
    // Package-private and side-effect-free with respect to the JVM, so a test can assert the code a given
    // set of arguments produces. run(...) is the only public entry point.
    int execute(ApplicationArguments arguments) {
        Objects.requireNonNull(arguments, "arguments");

        ToolInvocation invocation;
        try {
            invocation = validate(arguments);
        } catch (RuntimeException e) {
            // AN ARGUMENT ERROR WRITES NO RUN ROW. Nothing was read and nothing was applied, so a row would
            // assert an invocation that never happened and would then sit in the batch an operator signs off.
            // The code is 1 and never 2, because 2 means a completed run that recorded variances.
            LOGGER.error("Migration tooling invocation rejected, nothing ran: {}", e.getMessage());
            return EXIT_ERROR;
        }

        // A NEW run_id FOR EVERY INVOCATION, NEVER A REUSED ONE. The identifier is the invocation's identity:
        // a retry after a FAILED load is a fresh run_id under the same batch_id, and the partial unique index
        // on ledger_entry (run_id, owner) WHERE event_type = 'MIGRATION_LOAD' guarantees one load event per
        // owner per run only because the run is new. The batch_id is what ties a reconcile to the load it
        // judges (AAP 0.6.3).
        UUID runId = UUID.randomUUID();
        MigrationRun.Mode mode = modeOf(invocation.command());
        MigrationRun.CharacterizationStatus characterization = characterizationStatus();

        LOGGER.info("Migration tooling command '{}' starting: mode={}, run={}, batch={}, input={},"
                        + " characterization={}, {}={}",
                invocation.command(), mode, runId, invocation.batchId(), invocation.input(),
                characterization, RATE_SOURCE_PROPERTY, rateSource);

        MigrationRun run = MigrationRun.start(runId, invocation.batchId(), mode,
                invocation.input().toString(), characterization);

        // THE RUN ROW IS COMMITTED BEFORE THE COMMAND STARTS, AND THIS CLASS IS NOT @Transactional. A load
        // applies the whole export in one transaction or none of it (AAP 0.6.3), and audit/LedgerService's
        // append methods are @Transactional(propagation = MANDATORY), so the loader's own @Transactional must
        // be the single open transaction the work runs in - an outer transaction here would swallow it and
        // take the run row down with the rollback. Opening the row outside that boundary is also what leaves
        // a FAILED row behind when a command dies: the evidence of the attempt survives the loss of its work.
        try {
            runs.save(run);
        } catch (RuntimeException e) {
            LOGGER.error("Could not open the migration_run row for run {}; no command was executed", runId, e);
            return EXIT_ERROR;
        }

        try {
            dispatch(invocation, run);
        } catch (RuntimeException e) {
            LOGGER.error("Migration tooling command '{}' failed for run {}; recording the run FAILED",
                    invocation.command(), runId, e);
            recordFailure(runId);
            return EXIT_ERROR;
        }

        // THE ONE AUTHORITY FOR THE NUMBER IS THE PERSISTED ROWS. Read back rather than taken from the count
        // each command returns, so the exit code and the evidence an operator reviews cannot disagree - a
        // variance that never reached the database must not move the code, and one that did must. Only
        // VARIANCE counts: an ACCEPTED_EXCEPTION records an authorized difference, so the seeded
        // REJECTED_BY_TARGET row must not turn a clean run into a failed one (AAP 0.10.3).
        int varianceCount;
        try {
            varianceCount = Math.toIntExact(
                    reconciliations.countByRunIdAndStatus(runId, ReconciliationStatus.VARIANCE));
        } catch (RuntimeException e) {
            LOGGER.error("Could not read the variance rows of run {}; its verdict cannot be established", runId, e);
            recordFailure(runId);
            return EXIT_ERROR;
        }

        MigrationRun.Status finalStatus =
                varianceCount == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE;
        int legacyRecordCount = run.legacyRecordCount();
        int migratedRecordCount = run.migratedRecordCount();

        try {
            run.finish(finalStatus, legacyRecordCount, migratedRecordCount, varianceCount);
            runs.save(run);
        } catch (RuntimeException e) {
            LOGGER.error("Run {} completed with {} variance rows but its migration_run row could not be"
                    + " closed; the row remains RUNNING and the step cannot be signed off", runId,
                    varianceCount, e);
            return EXIT_ERROR;
        }

        int exitCode = varianceCount == 0 ? EXIT_CLEAN : EXIT_VARIANCE;
        LOGGER.info("Migration tooling run {}: mode={}/status={}/legacy={}/migrated={}/variances={}/exit={}",
                runId, mode, finalStatus, legacyRecordCount, migratedRecordCount, varianceCount, exitCode);
        return exitCode;
    }

    /**
     * Executes the requested command, leaving the run row's verdict, counts and {@code finishedAt} to
     * {@link #execute(ApplicationArguments)}.
     */
    // Parses, sequences and reports - never re-implements. Loading, reconciling and comparing belong to their
    // services, and each command's returned count is deliberately discarded because execute(...) re-derives
    // it from the rows themselves.
    private void dispatch(ToolInvocation invocation, MigrationRun run) {
        switch (invocation.command()) {
            case COMMAND_LOAD -> {
                LegacyLoader.LoadResult result = loader.load(run,
                        LegacyLoader.LoadSources.inDirectory(invocation.input()),
                        invocation.legacyCharset(),
                        invocation.legacyTimeZone(),
                        invocation.historyRecordLength());
                // The loader reports its counts instead of writing them, so the row has exactly one closer.
                // reconcile and shadow-compare set the same two fields on the run themselves.
                run.setLegacyRecordCount(result.legacyRecordCount());
                run.setMigratedRecordCount(result.migratedRecordCount());
            }
            case COMMAND_RECONCILE -> reconciliationService.reconcile(run, invocation.input());
            case COMMAND_SHADOW_COMPARE -> shadowComparator.compare(run, invocation.input());
            // Unreachable: validate(...) is the gate on the command value, so arriving here means the two
            // disagree about the accepted set - a programming error, and one that must not be absorbed.
            default -> throw new IllegalStateException(
                    "Unroutable command '" + invocation.command() + "' passed validation");
        }
    }

    /** Re-reads the run row and closes it {@code FAILED}, so a lost command still leaves its evidence. */
    private void recordFailure(UUID runId) {
        // A FRESH READ IN A NEW TRANSACTION. The command's own transaction has rolled back, so the instance
        // this class holds may carry counts that never reached the database; the row must describe what
        // actually committed. The counts already on the row are preserved rather than zeroed, because a
        // partially reported run is evidence and a zeroed one is a claim.
        try {
            runs.findById(runId).ifPresentOrElse(failed -> {
                failed.finish(MigrationRun.Status.FAILED, failed.legacyRecordCount(),
                        failed.migratedRecordCount(), failed.varianceCount());
                runs.save(failed);
                LOGGER.info("Migration tooling run {} recorded FAILED", runId);
            }, () -> LOGGER.error("Migration tooling run {} failed and its migration_run row is absent;"
                    + " the attempt has no recorded evidence", runId));
        } catch (RuntimeException e) {
            // Deliberately swallowed and reported: the caller is already returning EXIT_ERROR, and a
            // datastore that cannot accept this update is exactly the case where the original failure - which
            // has been logged with its stack - is the one an operator has to act on.
            LOGGER.error("Could not record migration tooling run {} as FAILED", runId, e);
        }
    }

    private static MigrationRun.Mode modeOf(String command) {
        // Mapped explicitly rather than by name: the SHADOW mode's command is spelled shadow-compare, so a
        // valueOf over the upper-cased command would fail on the one value that differs.
        return switch (command) {
            case COMMAND_LOAD -> MigrationRun.Mode.LOAD;
            case COMMAND_RECONCILE -> MigrationRun.Mode.RECONCILE;
            case COMMAND_SHADOW_COMPARE -> MigrationRun.Mode.SHADOW;
            default -> throw new IllegalStateException("Unmapped command '" + command + "'");
        };
    }

    // ----------------------------------------------------------------------------------------------
    // Argument validation - every check below runs before a single row is written
    // ----------------------------------------------------------------------------------------------

    /**
     * Validates every {@code tool.*} value and returns them parsed, or raises naming what is wrong.
     *
     * @throws CashAccountException when any value is missing, unrecognized or unusable
     */
    private ToolInvocation validate(ApplicationArguments arguments) {
        rejectUnknownToolOptions(arguments);

        // Ordered so that the cheapest, most likely operator mistakes are reported first, and the input
        // directory is resolved exactly once because the record-length rule has to look inside it.
        String requestedCommand = requireCommand();
        Path inputDirectory = requireInputDirectory();
        return new ToolInvocation(requestedCommand,
                inputDirectory,
                requireBatchId(),
                requireLegacyCharset(),
                requireLegacyTimeZone(),
                requireHistoryRecordLength(requestedCommand, inputDirectory));
    }

    /**
     * Rejects any {@code tool.}-prefixed command-line option that is not one of the seven declared keys.
     */
    // A MISTYPED KEY IS SILENT OTHERWISE. --tool.batchId=... resolves to no property at all, so the run would
    // proceed on the empty default of tool.batch-id or, worse for a value that has one, on a default the
    // operator did not choose - behaviour changed with nothing failing. Only tool.* names are inspected, so
    // Spring's own options (--spring.profiles.active and the rest) are untouched.
    private void rejectUnknownToolOptions(ApplicationArguments arguments) {
        List<String> unknown = arguments.getOptionNames().stream()
                .filter(name -> name.startsWith(TOOL_PROPERTY_PREFIX))
                .filter(name -> !DECLARED_TOOL_PROPERTIES.contains(name))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw argumentError("Unrecognized option(s) " + unknown + "; the accepted tool options are "
                    + new TreeSet<>(DECLARED_TOOL_PROPERTIES)
                    + ". A misspelled key binds to a default instead of the value you passed, so it is"
                    + " rejected rather than ignored");
        }
    }

    private String requireCommand() {
        String requested = trimmedOrEmpty(command);

        // FAIL CLOSED ON AN UNRECOGNIZED COMMAND, which is the deliberate replacement for the legacy
        // dispatcher's missing catch-all: EVALUATE WS-REQ branched on A/Q/U/X/C/D with no WHEN OTHER
        // (backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102), so an unknown code performed no SQL, left
        // SQLCODE at whatever the SQLCA already held, echoed the caller's own COMMAREA back as the result
        // (L104-L108) and still wrote a history record (L111-L131) - an unrecognized request that looked
        // exactly like a successful one. Matched exactly, with only surrounding whitespace tolerated: the
        // value selects a command that writes to a database, and a lenient match is one more way a mistyped
        // command can be interpreted as a different one.
        if (!ACCEPTED_COMMANDS.contains(requested)) {
            throw argumentError(COMMAND_PROPERTY + " must be one of " + ACCEPTED_COMMANDS
                    + (requested.isEmpty() ? ", but was not supplied" : ", but was '" + requested + "'"));
        }
        return requested;
    }

    private Path requireInputDirectory() {
        String requested = trimmedOrEmpty(input);
        if (requested.isEmpty()) {
            throw argumentError(INPUT_PROPERTY + " is required: it names the directory holding the export or"
                    + " the captured window to be processed, and it has no default so that a forgotten"
                    + " argument can never become a source the tool chose for itself");
        }

        Path resolved;
        try {
            resolved = Path.of(requested).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw argumentError(INPUT_PROPERTY + " is not a usable path: '" + requested + "' (" + e.getReason()
                    + ")");
        }

        if (!Files.isDirectory(resolved)) {
            throw argumentError(INPUT_PROPERTY + " must name an existing directory, but '" + resolved
                    + "' is not one");
        }
        if (!Files.isReadable(resolved)) {
            throw argumentError(INPUT_PROPERTY + " directory '" + resolved + "' is not readable by this"
                    + " process");
        }
        if (resolved.toString().length() > SOURCE_PATH_MAX_LENGTH) {
            throw argumentError(INPUT_PROPERTY + " resolves to a path of "
                    + resolved.toString().length() + " characters, which migration_run.source_path cannot"
                    + " record; the column holds " + SOURCE_PATH_MAX_LENGTH + ". Use a shorter path so the"
                    + " run's source stays part of its evidence");
        }
        return resolved;
    }

    private UUID requireBatchId() {
        String requested = trimmedOrEmpty(batchId);

        // REQUIRED FOR EVERY COMMAND, load included. Each invocation records its own run_id, so the batch id
        // is the only thing that ties a reconcile to the load it judges and a retry to the attempt it
        // replaces (AAP 0.6.3); a generated one would produce a run that reconciles nothing and looks clean
        // doing it.
        if (requested.isEmpty()) {
            throw argumentError(BATCH_ID_PROPERTY + " is required for every command: it is what ties a"
                    + " reconcile to the load it judges and a retry to the attempt it replaces");
        }

        UUID parsed;
        try {
            parsed = UUID.fromString(requested);
        } catch (IllegalArgumentException e) {
            throw argumentError(BATCH_ID_PROPERTY + " must be a UUID, but was '" + requested + "'");
        }

        // UUID.fromString accepts some shorter groupings that do not round-trip, so the parsed value is
        // compared back: a batch id that reads differently in the row than on the command line cannot be
        // joined to the step it belongs to.
        if (!parsed.toString().equalsIgnoreCase(requested)) {
            throw argumentError(BATCH_ID_PROPERTY + " '" + requested + "' is not a canonical UUID; it would"
                    + " be recorded as '" + parsed + "', which no longer matches the value you passed");
        }
        return parsed;
    }

    private Charset requireLegacyCharset() {
        String requested = trimmedOrEmpty(legacyCharset);

        // A property rather than a constant because the CICS region's exact CCSID is an open item the
        // mainframe team answers (AAP 0.11.2): a wrong code page corrupts every decoded owner name, so it
        // has to be correctable inside a migration window rather than by a rebuild.
        if (requested.isEmpty()) {
            throw argumentError(LEGACY_CHARSET_PROPERTY + " must name a code page (the documented assumption"
                    + " is " + LegacyExportFormat.DEFAULT_LEGACY_CHARSET + "), but was blank");
        }
        try {
            return Charset.forName(requested);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw argumentError(LEGACY_CHARSET_PROPERTY + " '" + requested + "' is not a charset this JVM"
                    + " supports; the documented assumption is " + LegacyExportFormat.DEFAULT_LEGACY_CHARSET);
        }
    }

    private ZoneId requireLegacyTimeZone() {
        String requested = trimmedOrEmpty(legacyTimeZone);

        // Also an open item (AAP 0.11.2). The history stamps are region local time, so a wrong zone shifts
        // every derived event_at by a fixed offset while each individual row still looks plausible - which
        // would make a shadow window's boundaries meaningless.
        if (requested.isEmpty()) {
            throw argumentError(LEGACY_TIMEZONE_PROPERTY + " must name a time zone (the documented assumption"
                    + " is UTC), but was blank");
        }
        try {
            return ZoneId.of(requested);
        } catch (DateTimeException e) {
            throw argumentError(LEGACY_TIMEZONE_PROPERTY + " '" + requested + "' is not a time zone this JVM"
                    + " recognizes: " + e.getMessage());
        }
    }

    private Integer requireHistoryRecordLength(String requestedCommand, Path inputDirectory) {
        // DECLARED, NEVER INFERRED. CASH00 writes 57 bytes (CASH00.cbl:L38-L45, L128) into a cluster defined
        // RECSZ(100 100) (backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L11), and which of the two a real REPRO
        // yields is settled by the CICS FILE definition, which is not in this repository and is obtained
        // during the runbook's migration-rehearsal step (AAP 0.11.2). A guessed length divides a file cleanly
        // often enough to look correct and then shifts every field of every record by a few bytes.
        //
        // A declared value is checked whatever the command, so a wrong one is refused at the first
        // invocation that carries it rather than at the one that happens to decode a record.
        if (historyRecordLength != null) {
            try {
                return LegacyExportFormat.requireAcceptedHistoryRecordLength(historyRecordLength);
            } catch (IllegalArgumentException e) {
                throw argumentError(e.getMessage());
            }
        }

        // DEMANDED OF load ALONE, because load is the only command that decodes history: reconcile reads the
        // account and rate exports, and shadow-compare reads the two captured streams. The runbook's Step 1
        // runs both commands against the SAME export directory and passes the record length only to the load
        // (docs/operational-runbook.md, Step 1 step 4), so requiring it of every command would reject the
        // documented reconcile invocation for a file that invocation never opens.
        if (!COMMAND_LOAD.equals(requestedCommand)) {
            return null;
        }

        Path binaryHistory = inputDirectory.resolve(LegacyExportFormat.HISTORY_BINARY_FILE);
        if (Files.isRegularFile(binaryHistory)) {
            throw argumentError(HISTORY_RECORD_LENGTH_PROPERTY + " is mandatory whenever a binary history"
                    + " export is the input, and '" + binaryHistory + "' is present. Declare "
                    + LegacyExportFormat.HISTORY_RECORD_LENGTH + " or "
                    + LegacyExportFormat.HISTORY_PADDED_RECORD_LENGTH
                    + " from the CICS FILE definition obtained in the runbook's migration-rehearsal step;"
                    + " the record length of a binary history export is never inferred from the file");
        }
        return null;
    }

    private static String trimmedOrEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    // INVALID_QUERY carries the module's single exception type into a surface that has no HTTP mapping: the
    // tool profile starts no web application, so the code's status is inert here and only its message is
    // read. A second exception type for the CLI would buy nothing and split the error model in two.
    private static CashAccountException argumentError(String message) {
        return CashAccountException.of(CashAccountErrorCode.INVALID_QUERY, message);
    }

    // ----------------------------------------------------------------------------------------------
    // Characterization status - the runtime half of the AAP 0.10.1 gate
    // ----------------------------------------------------------------------------------------------

    /** Reads the characterization document's acceptance state, defaulting to {@code DRAFT}. */
    private MigrationRun.CharacterizationStatus characterizationStatus() {
        List<Path> candidates = characterizationDocumentCandidates();
        for (Path candidate : candidates) {
            MigrationRun.CharacterizationStatus status = statusIn(candidate);
            if (status != null) {
                LOGGER.info("Characterization document {} reads Status: {}",
                        candidate.toAbsolutePath().normalize(), status);
                return status;
            }
        }

        // WHY A MISSING DOCUMENT IS DRAFT AND CAN NEVER BE ACCEPTED. Runbook Step 1's sign-off criterion
        // includes characterization_status = 'ACCEPTED', so the absence of the baseline must be unable to
        // satisfy it: defaulting the other way would let a run with no characterization at all be signed off
        // against a real export. DRAFT still runs freely against the synthetic fixtures, which is the whole
        // point - the reconciliation results are only as trustworthy as the baseline they are judged against.
        LOGGER.warn("No characterization document with a recognizable 'Status: DRAFT|ACCEPTED' line was found"
                + " (tried {}); this run records characterization_status {}. Set -D"
                + CHARACTERIZATION_DOCUMENT_PROPERTY + "=<path> when the document is elsewhere",
                candidates, MigrationRun.CharacterizationStatus.DRAFT);
        return MigrationRun.CharacterizationStatus.DRAFT;
    }

    /** The paths the characterization document is looked for in, in precedence order. */
    // Package-private so the resolution order is assertable without a database or a Spring context.
    static List<Path> characterizationDocumentCandidates() {
        List<Path> candidates = new ArrayList<>(3);

        String override = System.getProperty(CHARACTERIZATION_DOCUMENT_PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                candidates.add(Path.of(override.strip()));
            } catch (InvalidPathException e) {
                LOGGER.warn("-D{}='{}' is not a usable path and is ignored: {}",
                        CHARACTERIZATION_DOCUMENT_PROPERTY, override, e.getReason());
            }
        }

        // The module root first, then its parent, which covers a JVM started one directory above it. The
        // document is under docs/ and not in src/main/resources, so it is never a classpath resource.
        candidates.add(Path.of(CHARACTERIZATION_DOCUMENT_PATH));
        candidates.add(Path.of(PARENT_DIRECTORY, CHARACTERIZATION_DOCUMENT_PATH));
        return List.copyOf(candidates);
    }

    /**
     * The acceptance state declared by {@code document}, or {@code null} when it declares none.
     *
     * <p>The last matching {@code Status:} line wins, so the closing Acceptance section is authoritative
     * over any earlier mention of the field.</p>
     */
    // Package-private so the parse is assertable directly. Read whole rather than streamed: the document is a
    // few hundred lines, and holding it makes "the last match wins" a plain loop.
    static MigrationRun.CharacterizationStatus statusIn(Path document) {
        if (document == null || !Files.isRegularFile(document) || !Files.isReadable(document)) {
            return null;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Unreadable is treated exactly as absent, and for the same reason: an unverifiable baseline may
            // not be reported as an accepted one.
            LOGGER.warn("Could not read the characterization document {}: {}", document, e.getMessage());
            return null;
        }

        MigrationRun.CharacterizationStatus latest = null;
        for (String line : lines) {
            Matcher matcher = CHARACTERIZATION_STATUS_PATTERN.matcher(line);
            if (matcher.matches()) {
                latest = MigrationRun.CharacterizationStatus
                        .valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return latest;
    }
}
