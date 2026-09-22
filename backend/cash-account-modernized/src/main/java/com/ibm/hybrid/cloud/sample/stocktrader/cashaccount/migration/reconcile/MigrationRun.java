package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One row per migration-tooling invocation: what it read, what it found, and how it ended. */
@Entity
@Table(name = "migration_run")
public class MigrationRun {

    /** Which tooling command the run executed. */
    public enum Mode {
        LOAD,
        RECONCILE,
        SHADOW
    }

    /**
     * Run-level outcome, a distinct type from {@link ReconciliationStatus} so that a run's verdict and a
     * row's disposition can never be assigned to one another.
     */
    public enum Status {
        RUNNING,
        CLEAN,
        VARIANCE,
        FAILED
    }

    /** Acceptance state of {@code docs/legacy-characterization.md} as the run observed it. */
    public enum CharacterizationStatus {
        DRAFT,
        ACCEPTED
    }

    /**
     * The one canonicalization of {@code tool.rate-source}: every reader parses through this type, because
     * a rate source read differently by the delegate that prices a replay and by the classifiers that judge
     * it would record live-priced differences as genuine balance variances.
     */
    public enum RateSource {

        /** The parity gate and the default: both sides of a comparison are priced from the staged legacy RATES. */
        LEGACY_TABLE("legacy-table"),

        /** Live pricing, under which a difference the rate fully explains is reclassified {@code RATE_SOURCE}. */
        LIVE("live");

        // A rejected value is echoed to an operator's console, so the echo is bounded: enough to spot a
        // typo, too little to carry an injected record.
        private static final int MAX_ECHOED_CHARS = 40;

        // So an absent value does not render as an empty pair of quotes, which reads like a tool defect.
        private static final String UNSET = "<unset>";

        private final String token;

        RateSource(String token) {
            this.token = token;
        }

        /**
         * The source a {@code tool.rate-source} value names, tolerating only surrounding whitespace and
         * letter case and failing closed on anything else: defaulting a misspelling would let a whole run
         * judge parity against rates the legacy program never saw.
         *
         * @param rawValue the property value as configuration supplied it, possibly {@code null}
         * @return the source it names
         * @throws IllegalStateException when the value is absent or is neither documented token
         */
        public static RateSource of(String rawValue) {
            String requested = rawValue == null ? "" : rawValue.strip().toLowerCase(Locale.ROOT);
            for (RateSource candidate : values()) {
                if (candidate.token.equals(requested)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("tool.rate-source must be '" + LEGACY_TABLE.token + "' or '"
                    + LIVE.token + "', but was '"
                    + abbreviate(requested.isEmpty() ? UNSET : requested) + "'");
        }

        /**
         * The canonical spelling of this mode.
         *
         * @return the spelling exactly as {@code application-tool.yml} and the runbook use it
         */
        public String token() {
            return token;
        }

        /**
         * Whether this mode prices against the live provider.
         *
         * @return true for the one mode that opens {@code RATE_SOURCE} reclassification
         */
        public boolean isLive() {
            return this == LIVE;
        }

        private static String abbreviate(String value) {
            return value.length() <= MAX_ECHOED_CHARS ? value : value.substring(0, MAX_ECHOED_CHARS) + "...";
        }
    }

    // Assigned, never generated: one run_id per tool invocation, minted by the runner and echoed in the
    // runbook's evidence, so the value has to exist before the insert rather than come back from it.
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    // Separate from run_id because one runbook step is two invocations: a load and the reconcile that
    // judges it share --tool.batch-id, so a retry after a FAILED load is a new run_id under the same
    // batch_id and the batch still reads as the whole step, failed attempts included.
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private Mode mode;

    @Column(name = "source_path", nullable = false, length = 512)
    private String sourcePath;

    @Column(name = "legacy_record_count", nullable = false)
    private int legacyRecordCount;

    @Column(name = "migrated_record_count", nullable = false)
    private int migratedRecordCount;

    // Only ReconciliationStatus.VARIANCE rows are counted here: an ACCEPTED_EXCEPTION is a real but
    // pre-approved difference and must not make a clean run look like a failed one.
    @Column(name = "variance_count", nullable = false)
    private int varianceCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    // Carried on the run, not just in the document, because the runbook's sign-off criterion includes
    // characterization_status = 'ACCEPTED': a DRAFT characterization still runs against fixtures, but every
    // run it produces records that it was DRAFT and so can never be accepted against a real export.
    @Enumerated(EnumType.STRING)
    @Column(name = "characterization_status", nullable = false, length = 8)
    private CharacterizationStatus characterizationStatus;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected MigrationRun() {
    }

    private MigrationRun(UUID runId,
                         UUID batchId,
                         Mode mode,
                         String sourcePath,
                         CharacterizationStatus characterizationStatus) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.characterizationStatus =
                Objects.requireNonNull(characterizationStatus, "characterizationStatus");
        this.status = Status.RUNNING;
        this.startedAt = OffsetDateTime.now();
        this.legacyRecordCount = 0;
        this.migratedRecordCount = 0;
        this.varianceCount = 0;
    }

    /**
     * Opens a run: status {@code RUNNING}, zero counts and no {@code finishedAt} until
     * {@link #finish(Status, int, int, int)} closes it.
     *
     * @param runId                  the identifier of this invocation
     * @param batchId                the runbook step this invocation belongs to
     * @param mode                   the command being executed
     * @param sourcePath             the export this invocation reads
     * @param characterizationStatus the acceptance state of the characterization document at start-up
     * @return the open run, not yet persisted
     * @throws NullPointerException if any argument is {@code null}
     */
    public static MigrationRun start(UUID runId,
                                     UUID batchId,
                                     Mode mode,
                                     String sourcePath,
                                     CharacterizationStatus characterizationStatus) {
        return new MigrationRun(runId, batchId, mode, sourcePath, characterizationStatus);
    }

    /**
     * Closes the run, stamping {@code finishedAt}.
     *
     * @param finalStatus          the verdict the command reached
     * @param legacyRecordCount    rows read from the legacy side
     * @param migratedRecordCount  rows applied or replayed on the target side
     * @param varianceCount        outstanding {@code VARIANCE} rows, accepted exceptions excluded
     * @throws NullPointerException if {@code finalStatus} is {@code null}
     */
    public void finish(Status finalStatus,
                       int legacyRecordCount,
                       int migratedRecordCount,
                       int varianceCount) {
        this.status = Objects.requireNonNull(finalStatus, "finalStatus");
        this.legacyRecordCount = legacyRecordCount;
        this.migratedRecordCount = migratedRecordCount;
        this.varianceCount = varianceCount;
        this.finishedAt = OffsetDateTime.now();
    }

    /**
     * Raises the recorded counts to the progress a command reached, never lowering either, because a total
     * only grows within one run and a lost command's instance is the only statement of how far it got.
     *
     * @param reachedLegacyRecordCount   rows the command had read when it was lost
     * @param reachedMigratedRecordCount rows it had applied or replayed
     */
    public void recordProgress(int reachedLegacyRecordCount, int reachedMigratedRecordCount) {
        this.legacyRecordCount = Math.max(this.legacyRecordCount, reachedLegacyRecordCount);
        this.migratedRecordCount = Math.max(this.migratedRecordCount, reachedMigratedRecordCount);
    }

    /**
     * Closes the run {@code FAILED}, keeping the counts it has already recorded so that no failure path can
     * reset them, and taking its variance count from the rows that committed: a shadow window's findings
     * commit individually, so zeroed counters would state that an attempt found nothing while its findings
     * sit in the table.
     *
     * @param varianceCountFromEvidence the number of {@code VARIANCE} rows persisted under this run, read
     *                                  back from {@code migration_reconciliation}
     */
    public void fail(int varianceCountFromEvidence) {
        finish(Status.FAILED, legacyRecordCount, migratedRecordCount, varianceCountFromEvidence);
    }

    public UUID runId() {
        return runId;
    }

    public UUID batchId() {
        return batchId;
    }

    public Mode mode() {
        return mode;
    }

    public String sourcePath() {
        return sourcePath;
    }

    public int legacyRecordCount() {
        return legacyRecordCount;
    }

    public int migratedRecordCount() {
        return migratedRecordCount;
    }

    public int varianceCount() {
        return varianceCount;
    }

    public Status status() {
        return status;
    }

    public CharacterizationStatus characterizationStatus() {
        return characterizationStatus;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public OffsetDateTime finishedAt() {
        return finishedAt;
    }

    // The run row is the operational record of one command rather than an audit event, so it stays
    // updatable and the schema's append-only trigger guards ledger_entry alone.
    public void setSourcePath(String sourcePath) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
    }

    public void setLegacyRecordCount(int legacyRecordCount) {
        this.legacyRecordCount = legacyRecordCount;
    }

    public void setMigratedRecordCount(int migratedRecordCount) {
        this.migratedRecordCount = migratedRecordCount;
    }

    public void setVarianceCount(int varianceCount) {
        this.varianceCount = varianceCount;
    }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void setCharacterizationStatus(CharacterizationStatus characterizationStatus) {
        this.characterizationStatus =
                Objects.requireNonNull(characterizationStatus, "characterizationStatus");
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    // Identity is the assigned run_id alone: every other attribute is mutated as the command runs, so a
    // value-based equality would make an opened run unequal to the same row once it is closed.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationRun that)) {
            return false;
        }
        return runId != null && runId.equals(that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(runId);
    }

    // Excludes source_path deliberately: this string reaches logs, and an export path names the location
    // of a full copy of production cash balances.
    @Override
    public String toString() {
        return "MigrationRun{runId=" + runId
                + ", batchId=" + batchId
                + ", mode=" + mode
                + ", status=" + status
                + '}';
    }
}
