package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.VsamHistoryRecord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Run-scoped staging row for one exported legacy VSAM HISTORY record: written and read by the migration
 * tooling only, never on the request path.
 */
@Entity
@Table(name = "legacy_history")
@IdClass(LegacyHistory.Key.class)
public class LegacyHistory implements Persistable<LegacyHistory.Key> {

    /*
     * The key is the raw 29-byte VSAM key (CASH00.cbl:L47-L50; DEFKSDS.jcl:L14 KEYS(29 0)) within one run,
     * and `name` keeps the casing the caller sent (CASH00.cbl:L111 folds no case, unlike the account table
     * at L155): "John"+stamp and "JOHN"+stamp were two valid KSDS keys, so collapsing them would lose
     * audit records the legacy system really held.
     */
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Id
    @Column(name = "name", nullable = false, length = 15)
    private String name;

    /*
     * The uppercased join key, at cash_account.owner's width rather than the history name's: the raw key
     * cannot be joined on, and EBCDIC collates differently from UTF-8, so the reconciler matches legacy to
     * migrated state on this column and never on the raw name, sort order or ordinal position.
     */
    @Column(name = "owner_key", nullable = false, length = 32)
    private String ownerKey;

    /*
     * The stamps stay raw CHAR text (FORMATTIME wrote YYYYMMDD/HHMMSS, CASH00.cbl:L82-L85, L112-L113)
     * because reformatting key material would change the row's identity. @JdbcTypeCode(SqlTypes.CHAR) is
     * mandatory here: a String maps to VARCHAR, which ddl-auto=validate rejects against the char(n)
     * columns cash-account-schema.sql declares for legacy_history.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_date", nullable = false, length = 8, columnDefinition = "char(8)")
    private String eventDate;

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_time", nullable = false, length = 6, columnDefinition = "char(6)")
    private String eventTime;

    /*
     * Raw text rather than a typed enum: the write at CASH00.cbl:L126-L131 follows an EVALUATE that has no
     * WHEN OTHER (L89-L102), so the KSDS legitimately holds codes outside A/Q/U/X/C/D — which is also why
     * a transaction count filters on this column and the return code instead of counting rows.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_code", nullable = false, length = 1, columnDefinition = "char(1)")
    private String requestCode;

    /*
     * Nullable although COBOL always wrote nine digits, because a delimited conversion of the export can
     * carry an empty field: staging it as NULL lets the reconciler classify the row (NULL_IN_LEGACY)
     * instead of the reader rejecting the whole file.
     */
    @Column(name = "balance", precision = 9, scale = 2)
    private BigDecimal balance;

    @Column(name = "currency", length = 8)
    private String currency;

    /*
     * Text with no numeric meaning: SQLCODE reaches WS-VR-RETCODE through an alphanumeric MOVE
     * (CASH00.cbl:L117), which drops the sign, so -803 and +803 both arrive as 000000803 and the value
     * belongs to the last SQL statement of the paragraph.
     */
    @Column(name = "retcode", length = 10)
    private String retcode;

    /*
     * Nullable on purpose: the CICS region's zone is an unresolved open item, so an export decoded without
     * a confirmed zone stages its raw stamps and leaves this empty rather than inventing an instant.
     */
    @Column(name = "event_at")
    private OffsetDateTime eventAt;

    // An assigned key cannot tell Spring Data whether a row is new, so its null-identifier test would merge
    // every staged row and spend an existence SELECT per row; tracking it here keeps save() an insert. Both
    // callbacks below set it, so neither a row read back nor one just inserted can be offered again.
    @Transient
    private boolean persisted;

    protected LegacyHistory() {
    }

    private LegacyHistory(UUID runId,
                          String name,
                          String ownerKey,
                          String eventDate,
                          String eventTime,
                          String requestCode,
                          BigDecimal balance,
                          String currency,
                          String retcode,
                          OffsetDateTime eventAt) {
        this.runId = runId;
        this.name = name;
        this.ownerKey = ownerKey;
        this.eventDate = eventDate;
        this.eventTime = eventTime;
        this.requestCode = requestCode;
        this.balance = balance;
        this.currency = currency;
        this.retcode = retcode;
        this.eventAt = eventAt;
    }

    /**
     * Stages one decoded history record under a run, taking the record whole so that the four same-typed
     * text fields of the layout — name, date, time and request code — cannot be transposed positionally.
     *
     * @param runId    the run this staging pass belongs to
     * @param record   the decoded history record, carried field for field
     * @param ownerKey the uppercased join key the record itself cannot supply
     * @param eventAt  the resolved instant, or {@code null} when the region's time zone is unconfirmed
     * @return the staged row, not yet persisted
     * @throws NullPointerException if {@code runId}, {@code record} or {@code ownerKey} is {@code null}
     */
    public static LegacyHistory staged(UUID runId,
                                       VsamHistoryRecord record,
                                       String ownerKey,
                                       OffsetDateTime eventAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(ownerKey, "ownerKey");
        return new LegacyHistory(runId,
                record.name(),
                ownerKey,
                record.eventDate(),
                record.eventTime(),
                record.requestCode(),
                record.balance(),
                record.currency(),
                record.retcode(),
                eventAt);
    }

    public UUID runId() {
        return runId;
    }

    public String name() {
        return name;
    }

    public String ownerKey() {
        return ownerKey;
    }

    public String eventDate() {
        return eventDate;
    }

    public String eventTime() {
        return eventTime;
    }

    public String requestCode() {
        return requestCode;
    }

    public BigDecimal balance() {
        return balance;
    }

    public String currency() {
        return currency;
    }

    public String retcode() {
        return retcode;
    }

    public OffsetDateTime eventAt() {
        return eventAt;
    }

    /* Identity is the primary key and nothing else, so that equality here agrees with Key's. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LegacyHistory that)) {
            return false;
        }
        return Objects.equals(runId, that.runId)
                && Objects.equals(name, that.name)
                && Objects.equals(eventDate, that.eventDate)
                && Objects.equals(eventTime, that.eventTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, name, eventDate, eventTime);
    }

    @Override
    public String toString() {
        return "LegacyHistory[runId=" + runId
                + ", name=" + name
                + ", eventDate=" + eventDate
                + ", eventTime=" + eventTime
                + ", requestCode=" + requestCode
                + "]";
    }

    @Override
    public Key getId() {
        return new Key(runId, name, eventDate, eventTime);
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

    /**
     * Composite identifier of a staging row, whose field names and types must mirror the entity's
     * {@code @Id} attributes exactly or the context fails to start with a metadata error.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID runId;

        private String name;

        private String eventDate;

        private String eventTime;

        public Key() {
        }

        public Key(UUID runId, String name, String eventDate, String eventTime) {
            this.runId = runId;
            this.name = name;
            this.eventDate = eventDate;
            this.eventTime = eventTime;
        }

        public UUID runId() {
            return runId;
        }

        public String name() {
            return name;
        }

        public String eventDate() {
            return eventDate;
        }

        public String eventTime() {
            return eventTime;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(runId, that.runId)
                    && Objects.equals(name, that.name)
                    && Objects.equals(eventDate, that.eventDate)
                    && Objects.equals(eventTime, that.eventTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runId, name, eventDate, eventTime);
        }

        @Override
        public String toString() {
            return "LegacyHistory.Key[runId=" + runId
                    + ", name=" + name
                    + ", eventDate=" + eventDate
                    + ", eventTime=" + eventTime
                    + "]";
        }
    }
}
