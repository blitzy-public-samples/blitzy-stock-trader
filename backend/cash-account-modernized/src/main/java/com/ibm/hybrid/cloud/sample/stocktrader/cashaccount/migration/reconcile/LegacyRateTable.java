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
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyRateRecord;

/** Run-scoped staging of one exported {@code STOCKTRD.FRANKFURT1} row: a migration-source artefact only. */
// The legacy in-database rate join is never a target-state dependency: the live service resolves rates
// over HTTP, and these rows exist solely so the reconciler and the shadow comparator can judge parity on
// the very inputs the legacy arithmetic used. Staging is therefore scoped by run_id - a re-run is a new
// run, never an edit of an earlier one - and this class stays a data holder. Resolving a rate for a
// currency, including the five-character truncation of the account currency the legacy SELECT performed
// (backend/cash-account-cobol/COBOL/CASH00.cbl:L213 and L247) and the decision to fail when no usable row
// is staged, belongs to fx/LegacyRateTableSource, which is @Profile("tool") so it cannot reach the
// request path.
@Entity
@Table(name = "legacy_rate_table")
// @IdClass rather than @EmbeddedId because LegacyRateTableRepository derives queries from the top-level
// attribute names - findByRunId(UUID), findByRunIdAndCurrnkey(UUID, String) - which an embedded id would
// nest out of reach; and Key is a class rather than a record because an @IdClass needs a no-arg
// constructor, which a record cannot have.
//
// WHY Persistable IS IMPLEMENTED. The key is assigned by the caller, so Spring Data's default newness test -
// "is the identifier null?" - reports every staged row as already existing and its save path merges: one
// existence SELECT per row before that row's INSERT, on a staging pass whose input is a whole DB2 unload.
// Declaring newness explicitly makes save() persist instead, which issues no SELECT and keeps the write on
// the repository path the module's data access is required to run through. An instance that came from the
// database reports itself as not new (@PostLoad), so this can never turn a genuine update into an insert;
// a duplicate (run_id, currnkey) then fails on the primary key, which is the right outcome - staging is
// run-scoped, so a repeat can only mean a malformed export or a defect, never a row to absorb silently.
@IdClass(LegacyRateTable.Key.class)
public class LegacyRateTable implements Persistable<LegacyRateTable.Key> {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Id
    @Column(name = "currnkey", nullable = false, length = 5)
    private String currnkey;

    // Spelled after the copybook (CURRNBASE, backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L10) and the
    // program's SELECT (CASH00.cbl:L215, L249), not after the DDL, which spells the column `cyrrnbase`
    // (backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L56). As written the program could not precompile
    // against that DDL, so one artefact does not describe the real catalog; until the catalog settles it,
    // the mismatch is absorbed where the export is parsed - migration/LegacyExportFormat accepts either
    // header - and never here. Renaming this attribute would only move the discrepancy into the schema.
    @Column(name = "currnbase", length = 5)
    private String currnbase;

    // currnbase and amount are staged but consulted by nothing. Both are fetched by the rate SELECT
    // (CASH00.cbl:L215 for credit, L249 for debit) and then referenced by no COMPUTE and no MOVE anywhere
    // in the program: the multiplicand of the legacy arithmetic is the caller's COMMAREA amount, not
    // FRANKFURT1.AMOUNT. They are carried so the staged rows are a faithful copy of the export the
    // reconciliation was judged against, and reading amount as a rate input would invalidate every
    // reconciliation expectation in this module.
    @Column(name = "amount", precision = 9, scale = 2)
    private BigDecimal amount;

    // The only column the legacy arithmetic ever read. NUMERIC(3,2) mirrors DECIMAL(3,2) / PIC
    // S9(1)V9(2) COMP-3 (DCLFRANK.cpy:L12 and L22) exactly: two decimals and a ceiling of 9.99, so the
    // legacy table could never express a currency worth less than a tenth of a base unit - JPY and INR
    // were simply unrepresentable - and every staged rate carries at most two decimals of precision.
    // Nullable, and deliberately never defaulted: the legacy DDL declares no NOT NULL here, and a null
    // rate is a finding rather than a value, reported by ReconciliationService.validateSource() as a
    // RATE_SOURCE variance of kind NULL_RATE with the row left unstaged. Substituting any number here -
    // 1, 0 or otherwise - would silently invent the arithmetic the legacy program left undefined.
    @Column(name = "rates", precision = 3, scale = 2)
    private BigDecimal rates;

    // LocalDate for a SQL DATE: the column carries a calendar day with no zone, unlike the ledger's
    // TIMESTAMPTZ instants. Nullable per the target staging DDL even though the legacy column is NOT NULL
    // (DB2DDL.jcl:L59), because staging must be able to hold whatever an export actually produced.
    @Column(name = "loaddt")
    private LocalDate loaddt;

    // Not a column: it records whether this instance has reached the database yet, which is the one thing an
    // assigned key cannot tell Spring Data. It starts false on a staged(...) instance and is set by the
    // lifecycle callbacks below, so newness is a fact about the instance rather than a guess about the key.
    @Transient
    private boolean persisted;

    protected LegacyRateTable() {
        // Required by JPA; every application-side instance comes from staged(...).
    }

    private LegacyRateTable(UUID runId, String currnkey, String currnbase,
                            BigDecimal amount, BigDecimal rates, LocalDate loaddt) {
        this.runId = runId;
        this.currnkey = currnkey;
        this.currnbase = currnbase;
        this.amount = amount;
        this.rates = rates;
        this.loaddt = loaddt;
    }

    /**
     * Stages one exported rate row under {@code runId}; the single construction path.
     *
     * @throws NullPointerException     if {@code runId} or {@code record} is null
     * @throws IllegalArgumentException if the exported {@code currnkey} is blank
     */
    public static LegacyRateTable staged(UUID runId, LegacyRateRecord record) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(record, "record");
        String key = record.currnkey();
        // Only the key is guarded, and only for presence: it is half of the primary key, so a blank one
        // would collapse distinct currencies into a single staged row. Padding is already trimmed by the
        // export readers, and judging a value - a null rate, a currency outside the accepted set - is the
        // reconciler's decision, so nothing else is rejected, rescaled or normalized on the way in.
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("currnkey of an exported rate row must not be blank");
        }
        return new LegacyRateTable(runId, key, record.currnbase(), record.amount(),
                record.rates(), record.loaddt());
    }

    public UUID runId() {
        return runId;
    }

    public String currnkey() {
        return currnkey;
    }

    public String currnbase() {
        return currnbase;
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigDecimal rates() {
        return rates;
    }

    public LocalDate loaddt() {
        return loaddt;
    }

    // Identity is the primary key and nothing else, so an entity read back from the database equals the
    // instance that staged it even though the staged rate is a distinct BigDecimal instance.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LegacyRateTable that)) {
            return false;
        }
        return Objects.equals(runId, that.runId) && Objects.equals(currnkey, that.currnkey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, currnkey);
    }

    @Override
    public String toString() {
        return "LegacyRateTable[runId=" + runId + ", currnkey=" + currnkey + ", rates=" + rates + "]";
    }

    /** Composite identity of a staged rate row: the run that staged it and the legacy five-character key. */
    @Override
    public Key getId() {
        return new Key(runId, currnkey);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    // Both callbacks, deliberately: @PostLoad covers a row the reconciler or the comparator read back, and
    // @PostPersist covers the row this staging pass has just inserted, so neither can be offered to save()
    // a second time as an insert.
    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID runId;

        private String currnkey;

        public Key() {
            // Required of an @IdClass; Hibernate instantiates it before assigning the key attributes.
        }

        public Key(UUID runId, String currnkey) {
            this.runId = runId;
            this.currnkey = currnkey;
        }

        public UUID getRunId() {
            return runId;
        }

        public String getCurrnkey() {
            return currnkey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(runId, that.runId) && Objects.equals(currnkey, that.currnkey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runId, currnkey);
        }

        @Override
        public String toString() {
            return "LegacyRateTable.Key[runId=" + runId + ", currnkey=" + currnkey + "]";
        }
    }
}
