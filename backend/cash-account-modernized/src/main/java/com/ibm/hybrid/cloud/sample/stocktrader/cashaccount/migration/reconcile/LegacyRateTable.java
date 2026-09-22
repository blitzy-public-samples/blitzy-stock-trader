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

/**
 * Run-scoped staging of one exported {@code STOCKTRD.FRANKFURT1} row: a migration-source artefact read by
 * the tooling only, never on the request path.
 */
@Entity
@Table(name = "legacy_rate_table")
@IdClass(LegacyRateTable.Key.class)
public class LegacyRateTable implements Persistable<LegacyRateTable.Key> {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Id
    @Column(name = "currnkey", nullable = false, length = 5)
    private String currnkey;

    // Spelled after the copybook (CURRNBASE, backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L10), not after
    // the DDL, which spells it `cyrrnbase` (backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L56): the
    // mismatch is absorbed where the export is parsed, because LegacyExportFormat accepts either header.
    @Column(name = "currnbase", length = 5)
    private String currnbase;

    // Staged for fidelity and read by nothing: the legacy SELECT fetched this column (CASH00.cbl:L215,
    // L249) and no COMPUTE or MOVE ever referenced it, the multiplicand being the caller's COMMAREA
    // amount, so treating it as a rate input would invalidate every reconciliation expectation here.
    @Column(name = "amount", precision = 9, scale = 2)
    private BigDecimal amount;

    // The only column the legacy arithmetic read, at DECIMAL(3,2) / PIC S9(1)V9(2) COMP-3 (DCLFRANK.cpy:L12,
    // L22): two decimals and a ceiling of 9.99. Never defaulted when null - ReconciliationService records a
    // RATE_SOURCE variance of kind NULL_RATE and leaves the row unstaged rather than inventing arithmetic.
    @Column(name = "rates", precision = 3, scale = 2)
    private BigDecimal rates;

    // Nullable in staging although the legacy column is NOT NULL (DB2DDL.jcl:L59), so a staging pass can
    // hold whatever an export actually produced.
    @Column(name = "loaddt")
    private LocalDate loaddt;

    // An assigned key cannot tell Spring Data whether a row is new, so its null-identifier test would merge
    // every staged row and spend an existence SELECT per row; tracking it here keeps save() an insert. Both
    // callbacks below set it, so neither a row read back nor one just inserted can be offered again.
    @Transient
    private boolean persisted;

    protected LegacyRateTable() {
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
     * Stages one exported rate row under a run; the single construction path.
     *
     * @param runId  the run this staging pass belongs to
     * @param record the exported rate row, carried column for column
     * @return the staged row, not yet persisted
     * @throws NullPointerException     if {@code runId} or {@code record} is null
     * @throws IllegalArgumentException if the exported {@code currnkey} is blank
     */
    public static LegacyRateTable staged(UUID runId, LegacyRateRecord record) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(record, "record");
        String key = record.currnkey();
        // Only the key is guarded, and only for presence: it is half of the primary key, so a blank one
        // would collapse distinct currencies into one staged row. Judging a value - a null rate, a currency
        // outside the accepted set - is the reconciler's decision, so nothing else is rejected here.
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

    // Identity is the primary key alone, so a row read back equals the instance that staged it even though
    // its rate is a distinct BigDecimal.
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

    @Override
    public Key getId() {
        return new Key(runId, currnkey);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    /** Composite identifier of a staged rate row: the run that staged it and the legacy five-character key. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID runId;

        private String currnkey;

        public Key() {
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
