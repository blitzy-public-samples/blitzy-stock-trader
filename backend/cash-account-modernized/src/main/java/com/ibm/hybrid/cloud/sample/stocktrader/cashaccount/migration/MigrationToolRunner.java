package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.load.LegacyLoader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.MigrationRun;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.ReconciliationStatus;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.shadow.ShadowComparator;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationReconciliationRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.MigrationRunRepository;

/** Executes one migration-tooling command per invocation and turns its outcome into a process exit code. */
@Component
@Profile("tool")
public class MigrationToolRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationToolRunner.class);

    // The exit code is an evidence artifact: runbook Step 1 records it beside the migration_run and
    // migration_reconciliation rows, and a gate reads both, so a wrong code is a wrong sign-off. 2 is
    // reserved for "the run completed and recorded variances" and never stands in for an error.
    private static final int EXIT_CLEAN = 0;

    private static final int EXIT_ERROR = 1;

    private static final int EXIT_VARIANCE = 2;

    private static final String COMMAND_LOAD = "load";

    private static final String COMMAND_RECONCILE = "reconcile";

    private static final String COMMAND_SHADOW_COMPARE = "shadow-compare";

    private static final List<String> ACCEPTED_COMMANDS =
            List.of(COMMAND_LOAD, COMMAND_RECONCILE, COMMAND_SHADOW_COMPARE);

    // Named once here, so the bindings below and the typo guard are built from one spelling: a key spelled
    // differently in the two places would bind silently to a default and change behaviour.
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

    // The two defaults application-tool.yml ships, held here because the container constructor supplies them and
    // the file is the authority for why each is what it is.
    private static final String DEFAULT_RATE_SOURCE = "legacy-table";

    private static final String DEFAULT_LEGACY_TIMEZONE = "UTC";

    /** Escape hatch for a checkout whose working directory is neither the module root nor its parent. */
    private static final String CHARACTERIZATION_DOCUMENT_PROPERTY = "cashaccount.characterization-doc";

    // Authored under docs/ rather than src/main/resources, and copied onto the classpath by pom.xml's
    // copy-characterization-document execution, so the same relative name resolves both as a filesystem path
    // against the JVM's working directory and as a classpath resource inside the built artifact.
    private static final String CHARACTERIZATION_DOCUMENT_PATH = "docs/legacy-characterization.md";

    // The packaged copy, last in precedence. Absolute because a classpath resource name is resolved against the
    // declaring class's package unless it is.
    private static final String CHARACTERIZATION_DOCUMENT_RESOURCE = "/" + CHARACTERIZATION_DOCUMENT_PATH;

    private static final String PARENT_DIRECTORY = "..";

    private static final Pattern CHARACTERIZATION_STATUS_PATTERN =
            Pattern.compile("^Status:\\s*(DRAFT|ACCEPTED)\\s*$", Pattern.CASE_INSENSITIVE);

    // migration_run.source_path is VARCHAR(512) (schema/cash-account-schema.sql), checked before the row is
    // opened so an over-long path is an argument error naming the limit rather than an insert failure.
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

    /**
     * Container constructor, reading the seven {@code tool.*} values off the context's own environment.
     *
     * @param loader                the load command's implementation
     * @param reconciliationService the reconcile command's implementation
     * @param shadowComparator      the shadow-compare command's implementation
     * @param runs                  {@code migration_run} rows
     * @param reconciliations       {@code migration_reconciliation} rows
     * @param context               the context this runner closes with its exit code, and the source of the
     *                              {@code tool.*} values, which are bound through {@link Binder}
     */
    // BINDER, NEVER @Value, FOR EVERY ONE OF THESE SEVEN. A @Value placeholder is resolved and its RESOLVED TEXT is
    // then handed to Spring's expression resolver, so any tool.* value written as #{...} - passed on the very
    // command line an operator types, or sitting in a properties file a migration window inherited - would execute
    // while this runner was being created, before a single argument had been validated. Binder resolves ${...} and
    // converts, and evaluates nothing, so every value below reaches validate() as inert text and an unusable one
    // becomes this class's ordinary argument error. It also removes the last two inline #{null} defaults from the
    // module: an absent record length is now simply an unbound Integer.
    @Autowired
    public MigrationToolRunner(LegacyLoader loader,
                               ReconciliationService reconciliationService,
                               ShadowComparator shadowComparator,
                               MigrationRunRepository runs,
                               MigrationReconciliationRepository reconciliations,
                               ConfigurableApplicationContext context) {
        this(loader,
                reconciliationService,
                shadowComparator,
                runs,
                reconciliations,
                context,
                toolProperty(context, COMMAND_PROPERTY, ""),
                toolProperty(context, INPUT_PROPERTY, ""),
                toolProperty(context, BATCH_ID_PROPERTY, ""),
                toolProperty(context, RATE_SOURCE_PROPERTY, DEFAULT_RATE_SOURCE),
                toolProperty(context, LEGACY_CHARSET_PROPERTY, LegacyExportFormat.DEFAULT_LEGACY_CHARSET),
                toolProperty(context, LEGACY_TIMEZONE_PROPERTY, DEFAULT_LEGACY_TIMEZONE),
                // Left unbound rather than defaulted, so an absent record length stays distinguishable from a
                // declared one: application-tool.yml gives this key no value at all, because absence is what has
                // to be rejected for a binary history export.
                Binder.get(context.getEnvironment())
                        .bind(HISTORY_RECORD_LENGTH_PROPERTY, Bindable.of(Integer.class))
                        .orElse(null));
    }

    /**
     * Values constructor, for a caller that holds the seven {@code tool.*} values already.
     *
     * @param loader                the load command's implementation
     * @param reconciliationService the reconcile command's implementation
     * @param shadowComparator      the shadow-compare command's implementation
     * @param runs                  {@code migration_run} rows
     * @param reconciliations       {@code migration_reconciliation} rows
     * @param context               the context this runner closes with its exit code
     * @param command               {@code --tool.command}
     * @param input                 {@code --tool.input}
     * @param batchId               {@code --tool.batch-id}
     * @param rateSource            {@code --tool.rate-source}
     * @param legacyCharset         {@code --tool.legacy-charset}
     * @param legacyTimeZone        {@code --tool.legacy-timezone}
     * @param historyRecordLength   {@code --tool.history-record-length}, null when the operator declared none
     */
    public MigrationToolRunner(LegacyLoader loader,
                               ReconciliationService reconciliationService,
                               ShadowComparator shadowComparator,
                               MigrationRunRepository runs,
                               MigrationReconciliationRepository reconciliations,
                               ConfigurableApplicationContext context,
                               String command,
                               String input,
                               String batchId,
                               String rateSource,
                               String legacyCharset,
                               String legacyTimeZone,
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

    /** One validated invocation: every {@code tool.*} value the dispatch needs, already parsed. */
    record ToolInvocation(String command,
                          Path input,
                          UUID batchId,
                          MigrationRun.RateSource rateSource,
                          Charset legacyCharset,
                          ZoneId legacyTimeZone,
                          Integer historyRecordLength) {
    }

    /**
     * The verdict a completed invocation committed, together with the three counts recorded beside it.
     *
     * <p>Returned out of the run's transaction rather than re-read after it, so the exit code and the log
     * line report the values that are in the {@code migration_run} row and not a second reading of them.</p>
     */
    private record RunVerdict(MigrationRun.Status status,
                              int legacyRecordCount,
                              int migratedRecordCount,
                              int varianceCount) {
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int exitCode = execute(arguments);

        // Forced rather than returned: CashAccountApplication.main does not wrap the result of
        // SpringApplication.run, so a runner that simply returns leaves the JVM exiting 0 and a run that
        // recorded variances would report success to the gate. SpringApplication.exit closes the context
        // first, so Hikari and every other lifecycle bean shut down before the code is handed back, and it
        // is safe only because @Profile("tool") keeps this bean out of a context serving traffic. An
        // ExitCodeGenerator exception is the other documented route and is not used: it would report a
        // completed run with variances as a stack trace, and the variances are already rows.
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    /**
     * Runs one command and reports the code the process should exit with, without exiting.
     *
     * @param arguments the command line this invocation was started with
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
            // No run row: nothing was read and nothing applied, so a row would assert an invocation that
            // never happened and would sit in the batch an operator signs off. The code is 1, never 2.
            LOGGER.error("Migration tooling invocation rejected, nothing ran: {}", e.getMessage());
            return EXIT_ERROR;
        }

        // A new run_id for every invocation, never a reused one: a retry after a FAILED load is a fresh
        // run_id under the same batch_id, and ledger_entry's partial unique index (run_id, owner) WHERE
        // event_type = 'MIGRATION_LOAD' guarantees one load event per owner per run only because the run is
        // new. The batch_id is what ties a reconcile to the load it judges (AAP 0.6.3).
        UUID runId = UUID.randomUUID();
        MigrationRun.Mode mode = modeOf(invocation.command());
        MigrationRun.CharacterizationStatus characterization = characterizationStatus();

        // The canonical token rather than the raw property value, so a transcript names the source actually
        // applied - the same canonicalization picked the delegate that prices the replay.
        LOGGER.info("Migration tooling command '{}' starting: mode={}, run={}, batch={}, input={},"
                        + " characterization={}, {}={}",
                invocation.command(), mode, runId, invocation.batchId(), invocation.input(),
                characterization, RATE_SOURCE_PROPERTY, invocation.rateSource().token());

        MigrationRun run = MigrationRun.start(runId, invocation.batchId(), mode,
                invocation.input().toString(), characterization);

        // The run row is opened in its own transaction, before the one the command runs in: the row is the
        // evidence that the attempt happened, so it has to outlive the rollback of the work it describes - a
        // command that dies then leaves a row to close FAILED, and the retry is a new run_id under the same
        // batch_id (AAP 0.6.3). This class is still not @Transactional; nothing above or below this line runs
        // in an ambient transaction except the command itself.
        try {
            runs.save(run);
        } catch (RuntimeException e) {
            LOGGER.error("Could not open the migration_run row for run {}; no command was executed", runId, e);
            return EXIT_ERROR;
        }

        RunVerdict verdict;
        try {
            verdict = runToVerdict(invocation, run);
        } catch (RuntimeException e) {
            // FAILED is written after the rollback, in a fresh transaction, and the row is never left RUNNING.
            // The command's transaction has already rolled back by the time this is reached, so the row this
            // closes is the RUNNING one that committed above - and it must be closed, because a RUNNING row is
            // unsignable: the runbook step's gate reads status and counts, so the row would sit in the batch
            // looking like an invocation still in flight and a retry would be indistinguishable from a second
            // concurrent one. The code stays 1 and never 2, because 2 asserts a completed run whose verdict IS
            // recorded (AAP 0.6.3).
            LOGGER.error("Migration tooling command '{}' failed for run {}; its transaction rolled back, so"
                            + " nothing that shared it was applied. Recording the run FAILED - a retry is a"
                            + " new run under batch {}",
                    invocation.command(), runId, invocation.batchId(), e);
            recordFailure(run);
            return EXIT_ERROR;
        }

        int exitCode = verdict.varianceCount() == 0 ? EXIT_CLEAN : EXIT_VARIANCE;
        LOGGER.info("Migration tooling run {}: mode={}/status={}/legacy={}/migrated={}/variances={}/exit={}",
                runId, mode, verdict.status(), verdict.legacyRecordCount(), verdict.migratedRecordCount(),
                verdict.varianceCount(), exitCode);
        return exitCode;
    }

    /**
     * Runs the command and closes its run row in one transaction, reporting the verdict that committed.
     *
     * @return the terminal status and the three counts the {@code migration_run} row now carries
     * @throws RuntimeException when the command, the variance read or the close failed; the transaction has
     *         been rolled back by then, so nothing that shared it was applied
     */
    // One transaction for the work and for the verdict that judges it (AAP 0.6.3). "Either every row of the
    // export is applied and the run row is CLEAN/VARIANCE, or nothing is applied and the run row is FAILED" is
    // one outcome rather than two that can disagree. With the verdict written in a later transaction, a failure
    // between the two left the whole load committed under a FAILED row - and an operator reading that row would
    // conclude, correctly by its own wording and wrongly in fact, that nothing had been applied. The variance
    // count is read inside the same transaction for the same reason: the number the exit code comes from and the
    // rows it counts have to be one commit.
    //
    // PROGRAMMATIC, NOT @Transactional ON THIS BEAN. This bean is @Profile("tool") and an ApplicationRunner that
    // closes with System.exit, so no test may obtain it from a context (LoaderIT's header records why the tool
    // profile is never activated), and an annotation on a bean nothing can take from a context would leave the
    // boundary that carries this invariant unexercised by every test in the module. A TransactionTemplate is the
    // same single transaction and is entered identically in production and in the tests that inject a failure
    // into it. Nothing else here is transactional, so the run row above and the FAILED close below stay outside.
    //
    // What joins it: LegacyLoader.load and ReconciliationService.reconcile are @Transactional(REQUIRED), so they
    // join this transaction and audit/LedgerService's MANDATORY appends find it open; ShadowComparator.compare is
    // NOT_SUPPORTED, so it suspends this one exactly as it ran with none and its windows still commit one by
    // one - which is why a lost shadow window leaves its findings readable while a lost load leaves nothing.
    private RunVerdict runToVerdict(ToolInvocation invocation, MigrationRun run) {
        TransactionTemplate work = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        return work.execute(status -> {
            dispatch(invocation, run);

            // The one authority for the number is the persisted rows, read back rather than taken from the
            // count each command returns, so the exit code and the evidence an operator reviews cannot
            // disagree - a variance that never reached the database must not move the code, and one that did
            // must. Only VARIANCE counts: an ACCEPTED_EXCEPTION records an authorized difference, so the seeded
            // REJECTED_BY_TARGET row must not turn a clean run into a failed one (AAP 0.10.3).
            int varianceCount = Math.toIntExact(
                    reconciliations.countByRunIdAndStatus(run.runId(), ReconciliationStatus.VARIANCE));
            int legacyRecordCount = run.legacyRecordCount();
            int migratedRecordCount = run.migratedRecordCount();
            MigrationRun.Status finalStatus =
                    varianceCount == 0 ? MigrationRun.Status.CLEAN : MigrationRun.Status.VARIANCE;

            run.finish(finalStatus, legacyRecordCount, migratedRecordCount, varianceCount);
            runs.save(run);
            return new RunVerdict(finalStatus, legacyRecordCount, migratedRecordCount, varianceCount);
        });
    }

    /**
     * Executes the requested command, leaving the run row's verdict, counts and {@code finishedAt} to
     * {@link #runToVerdict(ToolInvocation, MigrationRun)}, which closes the row in the same transaction.
     */
    // Sequences and reports, never re-implements: each command's returned count is deliberately discarded,
    // because the transaction that closes the run re-derives it from the persisted rows.
    private void dispatch(ToolInvocation invocation, MigrationRun run) {
        switch (invocation.command()) {
            case COMMAND_LOAD -> {
                LegacyLoader.LoadResult result = loader.load(run,
                        LegacyLoader.LoadSources.inDirectory(invocation.input()),
                        invocation.legacyCharset(),
                        invocation.legacyTimeZone(),
                        invocation.historyRecordLength());
                // The loader reports its counts rather than writing them, so the row has one closer.
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

    /**
     * Re-reads the run row and closes it {@code FAILED} with the progress and the findings that survived, so
     * a lost command still leaves usable evidence.
     *
     * @param attempted the instance the lost command was mutating; its counts are the only statement of how
     *                  far the command got
     */
    private void recordFailure(MigrationRun attempted) {
        UUID runId = attempted.runId();

        // The findings decide the FAILED row's variance count, not the instance that died, because what
        // survives a failure differs by mode. A shadow window suspends any ambient transaction
        // (ShadowComparator.compare is Propagation.NOT_SUPPORTED), so its findings commit individually and a
        // window that dies late leaves them in the table with its counts still zero; closing on that zero
        // would tell the operator the attempt found nothing while its findings sit under the same run_id
        // (AAP 0.6.5). A load and a reconcile are each one transaction, so their findings roll back with the
        // work and the same read then yields zero, which is equally the truth.
        Integer varianceCountFromEvidence = persistedVarianceCount(runId);

        // A fresh read in a new transaction: the command's own has rolled back, so the persisted row is what
        // describes the state that committed. The lost instance's progress is merged in rather than replacing
        // it, and recordProgress only ever raises a count - a partially reported run is evidence, a zeroed
        // one is a claim.
        try {
            runs.findById(runId).ifPresentOrElse(failed -> {
                failed.recordProgress(attempted.legacyRecordCount(), attempted.migratedRecordCount());
                failed.fail(varianceCountFromEvidence != null
                        ? varianceCountFromEvidence
                        // Only when the findings could not be read at all: the greater of the two recorded
                        // counts, which is the most the run is known to have reported.
                        : Math.max(failed.varianceCount(), attempted.varianceCount()));
                runs.save(failed);
                LOGGER.info("Migration tooling run {} recorded FAILED: legacy={}/migrated={}/variances={}"
                                + " (variance count {})", runId, failed.legacyRecordCount(),
                        failed.migratedRecordCount(), failed.varianceCount(),
                        varianceCountFromEvidence != null ? "read back from the persisted findings"
                                : "carried over; the findings could not be read");
            }, () -> LOGGER.error("Migration tooling run {} failed and its migration_run row is absent;"
                    + " the attempt has no recorded evidence", runId));
        } catch (RuntimeException e) {
            // Swallowed and reported: the caller is already returning EXIT_ERROR, and the original failure,
            // logged with its stack, is the one an operator has to act on.
            LOGGER.error("Could not record migration tooling run {} as FAILED", runId, e);
        }
    }

    /**
     * The number of {@code VARIANCE} rows persisted under {@code runId}, or {@code null} when they cannot be
     * read.
     */
    // Null-returning rather than throwing, because this runs on a path already handling a failure - the same
    // query may be the one that just failed. A second exception would replace the original, logged failure
    // and cost the row its FAILED status as well.
    private Integer persistedVarianceCount(UUID runId) {
        try {
            return Math.toIntExact(
                    reconciliations.countByRunIdAndStatus(runId, ReconciliationStatus.VARIANCE));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the persisted findings of run {} while recording it FAILED; its"
                    + " variance count is carried over instead of derived: {}", runId, e.getMessage());
            return null;
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

    /**
     * Validates every {@code tool.*} value and returns them parsed, or raises naming what is wrong.
     *
     * @param arguments the command line this invocation was started with
     * @return every {@code tool.*} value, parsed
     * @throws CashAccountException when any value is missing, unrecognized or unusable
     */
    // Every check here runs before a single row is written, which is what keeps an argument error
    // distinguishable from a failed run: the former leaves no migration_run row at all.
    private ToolInvocation validate(ApplicationArguments arguments) {
        rejectUnknownToolOptions(arguments);

        // Ordered so that the cheapest, most likely operator mistakes are reported first, and the input
        // directory is resolved exactly once because the record-length rule has to look inside it.
        String requestedCommand = requireCommand();
        Path inputDirectory = requireInputDirectory();
        return new ToolInvocation(requestedCommand,
                inputDirectory,
                requireBatchId(),
                requireRateSource(),
                requireLegacyCharset(),
                requireLegacyTimeZone(),
                requireHistoryRecordLength(requestedCommand, inputDirectory));
    }

    // A mistyped key is silent otherwise: --tool.batchId=... resolves to no property at all, so the run
    // would proceed on a default the operator did not choose, behaviour changed with nothing failing. Only
    // tool.* names are inspected, so Spring's own options are untouched.
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

        // Fails closed on an unrecognized command, the deliberate replacement for the legacy dispatcher's
        // missing catch-all: EVALUATE WS-REQ branched on A/Q/U/X/C/D with no WHEN OTHER
        // (backend/cash-account-cobol/COBOL/CASH00.cbl:L89-L102), so an unknown code performed no SQL, echoed
        // the caller's own COMMAREA back (L104-L108) and still wrote a history record (L111-L131) - an
        // unrecognized request that looked exactly like a successful one. Matched exactly, with only
        // surrounding whitespace tolerated, because the value selects a command that writes to a database.
        if (!ACCEPTED_COMMANDS.contains(requested)) {
            throw argumentError(COMMAND_PROPERTY + " must be one of " + ACCEPTED_COMMANDS
                    + (requested.isEmpty() ? ", but was not supplied" : ", but was '" + requested + "'"));
        }
        return requested;
    }

    // Files beneath this directory are the only legacy source the tooling reads: no DB2 for z/OS or VSAM
    // connection, data-set name, host or credential exists in this module and none may be added, because the
    // mechanism is verified against fixtures and the live migration is a runbook step (AAP 0.3.2).
    private Path requireInputDirectory() {
        String requested = trimmedOrEmpty(input);
        if (requested.isEmpty()) {
            throw argumentError(INPUT_PROPERTY + " is required: it names the directory holding the export or"
                    + " the captured window to be processed, and it has no default so that a forgotten"
                    + " argument can never become a source the tool chose for itself");
        }

        // The trusted real path, never a normalized one: normalize() resolves '..' textually and resolves no
        // symbolic link at all, so it cannot say what directory a run actually read; toRealPath() inside
        // LegacyExportFormat.requireInputDirectory can, and its result is the root every child of this input
        // is then measured against (AAP 0.3.2). The directory and readability rules, and the wording of their
        // refusals, live there with the containment rules they belong to; this method keeps the property name
        // and the tool's own argument-error channel around them.
        Path resolved;
        try {
            resolved = LegacyExportFormat.requireInputDirectory(Path.of(requested));
        } catch (InvalidPathException e) {
            throw argumentError(INPUT_PROPERTY + " is not a usable path: '" + requested + "' (" + e.getReason()
                    + ")");
        } catch (IllegalArgumentException refused) {
            throw argumentError(refused.getMessage());
        }

        // Checked against the real path, because that is the string migration_run.source_path records as the
        // run's evidence - a shorter symbolic link to a long directory would otherwise pass here and then
        // overflow the column.
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

        // Required for every command, load included: each invocation records its own run_id, so the batch id
        // is the only thing tying a reconcile to the load it judges and a retry to the attempt it replaces
        // (AAP 0.6.3). A generated one would produce a run that reconciles nothing and looks clean doing it.
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

    private MigrationRun.RateSource requireRateSource() {
        // Canonicalized exactly once: MigrationRun.RateSource.of is the module's single reading of
        // tool.rate-source, so the classifiers that may attribute a difference to the rate and the delegate
        // that prices the replay cannot hold different understandings of it than the log line records.
        // Checking it here turns a misspelling into this class's ordinary argument error with the accepted
        // tokens named; the tool profile's rate-source bean refuses to start on the same value.
        try {
            return MigrationRun.RateSource.of(rateSource);
        } catch (IllegalStateException e) {
            throw argumentError(e.getMessage());
        }
    }

    private Charset requireLegacyCharset() {
        String requested = trimmedOrEmpty(legacyCharset);

        // Configuration rather than a constant because the region's exact CCSID is AAP 0.11.2's open item: a
        // wrong code page corrupts every decoded owner name, so it must be correctable without a rebuild.
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

        // Also AAP 0.11.2's open item: the history stamps are region local time, so a wrong zone shifts every
        // derived event_at by a fixed offset while each row still looks plausible.
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
        // Declared, never inferred: CASH00 writes 57 bytes (CASH00.cbl:L38-L45, L128) into a cluster defined
        // RECSZ(100 100) (backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L11), and which a real REPRO yields is
        // settled by the CICS FILE definition obtained in the runbook's migration-rehearsal step (AAP
        // 0.11.2). A guessed length divides a file cleanly often enough to look correct and then shifts every
        // field by a few bytes. Checked whatever the command, so a wrong value is refused at the first
        // invocation carrying it rather than at the one that happens to decode a record.
        if (historyRecordLength != null) {
            try {
                return LegacyExportFormat.requireAcceptedHistoryRecordLength(historyRecordLength);
            } catch (IllegalArgumentException e) {
                throw argumentError(e.getMessage());
            }
        }

        // Demanded of load alone, because load is the only command that decodes history. Runbook Step 1 runs
        // load and reconcile against the same export directory and passes the record length only to the load,
        // so requiring it of every command would reject the documented reconcile invocation for a file that
        // invocation never opens.
        if (!COMMAND_LOAD.equals(requestedCommand)) {
            return null;
        }

        // Resolved through the containment helper and judged through the approved directory itself: a
        // symbolic link named history.cp037.bin is refused rather than accepted as "a binary history export
        // is present", so this check can neither be satisfied nor evaded by a link, and a directory
        // substituted for tool.input cannot answer it at all (AAP 0.3.2).
        Path binaryHistory;
        boolean present;
        try {
            binaryHistory = LegacyExportFormat.resolveInputFile(inputDirectory,
                    LegacyExportFormat.HISTORY_BINARY_FILE);
            present = LegacyExportFormat.isExportFilePresent(
                    LegacyExportFormat.approveInputDirectory(inputDirectory),
                    LegacyExportFormat.HISTORY_BINARY_FILE);
        } catch (IllegalArgumentException refused) {
            throw argumentError(refused.getMessage());
        }
        if (present) {
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

    private static String toolProperty(ConfigurableApplicationContext context, String key, String fallback) {
        return Binder.get(context.getEnvironment()).bind(key, Bindable.of(String.class)).orElse(fallback);
    }

    // INVALID_QUERY carries the module's single exception type into a surface with no HTTP mapping: the tool
    // profile starts no web application, so the status is inert and only the message is read. A second
    // exception type for the CLI would split the error model in two.
    private static CashAccountException argumentError(String message) {
        return CashAccountException.of(CashAccountErrorCode.INVALID_QUERY, message);
    }

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

        // The copy pom.xml packaged into this artifact, consulted only after every filesystem candidate: it is
        // the revision the artifact was built from, so a document the data owner signed ACCEPTED afterwards -
        // named with -Dcashaccount.characterization-doc, or simply present under the working directory - has to
        // outrank it. Before it existed, an invocation from anywhere but the module root recorded DRAFT however
        // the real document read, which for a container run (the image carries the jar alone) was every
        // invocation; the column is a runbook sign-off criterion, so that degradation silently withheld a gate.
        MigrationRun.CharacterizationStatus packaged = statusInPackagedDocument();
        if (packaged != null) {
            LOGGER.info("Characterization document packaged at classpath:{} reads Status: {}",
                    CHARACTERIZATION_DOCUMENT_RESOURCE, packaged);
            return packaged;
        }

        // A missing document is DRAFT and can never be ACCEPTED: runbook Step 1's sign-off criterion includes
        // characterization_status = 'ACCEPTED', so the absence of the baseline must be unable to satisfy it,
        // where defaulting the other way would let a run with no characterization be signed off against a
        // real export. DRAFT still runs freely against the fixtures.
        LOGGER.warn("No characterization document with a recognizable 'Status: DRAFT|ACCEPTED' line was found"
                + " (tried {} and classpath:{}); this run records characterization_status {}. Set -D"
                + CHARACTERIZATION_DOCUMENT_PROPERTY + "=<path> when the document is elsewhere",
                candidates, CHARACTERIZATION_DOCUMENT_RESOURCE, MigrationRun.CharacterizationStatus.DRAFT);
        return MigrationRun.CharacterizationStatus.DRAFT;
    }

    /**
     * The acceptance state declared by the copy packaged into this artifact, or {@code null} when the artifact
     * carries none or it declares none.
     *
     * @return the packaged document's acceptance state, or {@code null}
     */
    // Package-private so the packaged copy is assertable without a database or a Spring context, which is what
    // makes "the artifact carries its own baseline" a checked claim rather than a build detail.
    static MigrationRun.CharacterizationStatus statusInPackagedDocument() {
        try (InputStream packaged =
                MigrationToolRunner.class.getResourceAsStream(CHARACTERIZATION_DOCUMENT_RESOURCE)) {
            if (packaged == null) {
                return null;
            }
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(packaged, StandardCharsets.UTF_8))) {
                return latestStatusIn(lines.lines().toList());
            }
        } catch (IOException | UncheckedIOException e) {
            // Unreadable is treated exactly as absent, and for the same reason as an unreadable file: an
            // unverifiable baseline may not be reported as an accepted one.
            LOGGER.warn("Could not read the packaged characterization document classpath:{}: {}",
                    CHARACTERIZATION_DOCUMENT_RESOURCE, e.getMessage());
            return null;
        }
    }

    /**
     * The filesystem paths the characterization document is looked for in, in precedence order; the copy
     * packaged into the artifact is consulted after all of them.
     */
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

        // The module root first, then its parent, which covers a JVM started one directory above it.
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

        return latestStatusIn(lines);
    }

    // The one parse both sources share, so the packaged copy and a file on disk cannot be read by different
    // rules: the last matching Status: line wins, making the closing Acceptance section authoritative over any
    // earlier mention of the field.
    private static MigrationRun.CharacterizationStatus latestStatusIn(List<String> lines) {
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
