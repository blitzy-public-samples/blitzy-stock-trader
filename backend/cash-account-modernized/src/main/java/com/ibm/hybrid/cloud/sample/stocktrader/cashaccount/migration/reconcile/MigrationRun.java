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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

// Mutable, unlike ledger_entry: a run row is the operational record of one command, not an audit event.
// MigrationToolRunner opens it RUNNING before the work starts and closes it with the counts, the final
// status and finished_at, so the row has to be updatable - which is why the append-only trigger in
// schema/cash-account-schema.sql guards ledger_entry alone and deliberately does not extend here. The
// evidence that must never change is the ledger; this row merely says which command produced it.
//
// Only the table name is declared. The (batch_id) index is idx_migration_run_batch_id in
// cash-account-schema.sql, and ddl-auto=validate never creates an index, so repeating it in @Table would
// add a second place to keep in step while enforcing nothing.
/** One row per migration-tooling invocation: what it read, what it found, and how it ended. */
@Entity
@Table(name = "migration_run")
public class MigrationRun {

    // The three enums below are nested rather than separate files because AAP 0.6.1 caps this
    // sub-package at nine files, the same reason domain/LedgerEntry nests its Source enum.

    /** Which tooling command the run executed. */
    public enum Mode {
        LOAD,
        RECONCILE,
        SHADOW
    }

    /**
     * Run-level outcome.
     *
     * <p>{@code VARIANCE} here is the roll-up over the run and is a distinct type from
     * {@link ReconciliationStatus#VARIANCE}, which is one row's disposition; a run is
     * {@code VARIANCE} because it produced at least one such row. The two stay separate so the
     * run-level verdict and the row-level finding can never be assigned to one another.</p>
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

    // Assigned, never generated: the identifier IS the invocation's identity. MigrationToolRunner mints it
    // with UUID.randomUUID() and echoes it in the runbook's evidence, so the value has to exist before the
    // insert rather than be handed back by the database afterwards.
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    // Separate from run_id because one runbook step is two invocations: a load and the reconcile that
    // judges it share --tool.batch-id. A retry after a FAILED load is therefore a new run_id under the
    // same batch_id, and MigrationRunRepository.findByBatchIdOrderByStartedAtAsc still reads the whole
    // step, failed attempts included, in the order it happened.
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

    // Carried on the run, not just in the document, because runbook Step 1's sign-off criterion includes
    // characterization_status = 'ACCEPTED'. A DRAFT characterization can still run against fixtures, but
    // every run it produces records that it was DRAFT, so it can never be accepted against a real export.
    @Enumerated(EnumType.STRING)
    @Column(name = "characterization_status", nullable = false, length = 8)
    private CharacterizationStatus characterizationStatus;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    protected MigrationRun() {
        // Required by JPA; every application-created instance comes from start(...).
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
     * <p>Null arguments are rejected with {@link NullPointerException} naming the argument. This is a
     * tooling entity with no HTTP surface, so it raises no service exception and maps to no status
     * code: a missing run identifier, batch identifier, mode, source path or characterization status is
     * a programming error in the caller, not a condition an operator can act on.</p>
     */
    public static MigrationRun start(UUID runId,
                                     UUID batchId,
                                     Mode mode,
                                     String sourcePath,
                                     CharacterizationStatus characterizationStatus) {
        return new MigrationRun(runId, batchId, mode, sourcePath, characterizationStatus);
    }

    /** Closes the run with its verdict and its three counts, stamping {@code finishedAt}. */
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
    // value-based equals would make an opened run unequal to the same row once it is closed.
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

    // Deliberately excludes source_path and the counts: this string reaches logs, and an export path is
    // the location of a full copy of production cash balances.
    @Override
    public String toString() {
        return "MigrationRun{runId=" + runId
                + ", batchId=" + batchId
                + ", mode=" + mode
                + ", status=" + status
                + '}';
    }
}
