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

/*
 * Why this table exists at all: nothing in the legacy program ever reads the HISTORY KSDS back — the
 * single application-level access is the EXEC CICS WRITE at
 * backend/cash-account-cobol/COBOL/CASH00.cbl:L126-L131, and the cluster is otherwise named only by its
 * IDCAMS definition (backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L9-L16). There was therefore no
 * extraction path to adapt, so the exported records are staged here and the reconciler and the shadow
 * comparator read them from the relational side instead of from a sequential data set.
 *
 * Why the rows are run-scoped rather than accumulated: staging is an input to one tool invocation, so
 * every row carries the `run_id` that produced it. A retry is a new run, never an update of an existing
 * row, which is why this entity exposes no setter and carries no @Version — a re-read of the same export
 * under a new `run_id` cannot collide with what an earlier run staged.
 *
 * Why the legacy counts derived from these rows are a LOWER BOUND, not a total: the write is guarded by
 * `EXEC CICS IGNORE CONDITION DUPREC` (CASH00.cbl:L124), and the key is name + date + time to the second
 * (L47-L50), so a second request for the same owner inside one second was silently discarded by the
 * legacy program and never reached the KSDS. A target transaction count above the legacy count is
 * consequently an ACCEPTED_EXCEPTION rather than a variance; only target-below-legacy is a real one.
 * The counting rule that follows from the unconditional write at L111-L131 is equally non-obvious: the
 * record was written for EVERY request, including reads (`Q`) and unrecognized request codes, so a
 * transaction count has to filter on `request_code` and the return code rather than count rows.
 *
 * Scope: migration tooling only. Written by migration/load/LegacyLoader, read by ReconciliationService
 * and migration/shadow/ShadowComparator; never touched on the request path, and the only data it is ever
 * proven against is src/test/resources/fixtures/** — the real SYSD.STOCK.HISTORY is out of reach by
 * design, and producing the export from it is a runbook step this module does not execute.
 *
 * Why this entity declares its own newness (Persistable): the key below is assigned by the caller rather
 * than generated, so Spring Data's default test — "is the identifier null?" — reports every staged row as
 * already existing and its save path merges, which costs one existence SELECT per row before that row's
 * INSERT on a staging pass whose input is a whole VSAM history. Declaring newness explicitly makes save()
 * persist, so the write stays on the repository path while issuing no SELECT. A row read back from the
 * database reports itself as not new, so an update can never be mistaken for an insert; a repeated key then
 * fails on the primary key, which is the outcome this table needs — the key IS the legacy record's identity
 * and staging is run-scoped, so a repeat means a malformed export, never a row to absorb.
 */

/** Run-scoped staging row for one exported legacy VSAM HISTORY record, read only by the migration tooling. */
@Entity
@Table(name = "legacy_history")
/*
 * @IdClass rather than @EmbeddedId, and a plain class rather than a record, are both forced choices.
 * @EmbeddedId would nest the key attributes behind a path (`key.runId`), which stops the derived queries
 * declared over this entity — findByRunId(UUID), findByRunIdAndOwnerKey(UUID, String), countByRunId(UUID),
 * countByRunIdAndRequestCodeIn(UUID, Collection<String>), all test-only readers on the test tree's
 * LegacyHistoryTestQueries; the production LegacyHistoryRepository declares no finder of its own — from
 * resolving against top-level attribute names; and a Java record has no no-argument constructor, which an
 * @IdClass is required to provide.
 */
@IdClass(LegacyHistory.Key.class)
public class LegacyHistory implements Persistable<LegacyHistory.Key> {

    /*
     * The primary key is the raw 29-byte VSAM key (CASH00.cbl:L47-L50; DEFKSDS.jcl:L14 KEYS(29 0))
     * prefixed by the run scope, and it deliberately carries the name in the casing the caller sent:
     * CASH00.cbl:L111 moves WS-NAME into the record with no case folding, unlike the account table which
     * normalizes (UPPER on insert at L155, LOWER on match at L141). "John" and "JOHN" with one stamp were
     * therefore two distinct, equally valid KSDS keys, and both have to survive an import as separate
     * rows — collapsing them would silently lose audit records that the legacy system really held.
     */
    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    /*
     * WS-VR-NAME PIC X(15) (CASH00.cbl:L39), stored exactly as decoded: no uppercasing, no normalization
     * and no trimming beyond the CHAR padding the decoder already removed, because this is half of the
     * key identity above.
     */
    @Id
    @Column(name = "name", nullable = false, length = 15)
    private String name;

    /*
     * A second, uppercased column exists because the raw key cannot be joined on. Legacy owners are
     * stored uppercase in CASHACCOUNTY (CASH00.cbl:L155) while history names keep the caller's casing, and
     * EBCDIC collation differs from UTF-8 anyway, so the reconciler joins legacy to migrated state on this
     * normalized key and never on the raw name, on ordinal position or on sort order. Width 32 is the
     * account owner's width, not the history name's: the join key has to be comparable with
     * cash_account.owner, whose legacy source is OWNER CHAR(32).
     */
    @Column(name = "owner_key", nullable = false, length = 32)
    private String ownerKey;

    /*
     * The stamps stay raw CHAR text — WS-VR-DATE X(08) YYYYMMDD and WS-VR-TIME X(06) HHMMSS as written
     * from EXEC CICS FORMATTIME (CASH00.cbl:L40-L41, L82-L85, L112-L113) — because they are key material:
     * reformatting them would change the identity of the row. Resolving them to an instant needs the CICS
     * region's time zone, which is not in this repository (a carried open item, supplied as
     * tool.legacy-timezone), so that derivation lands in the separate, nullable event_at below.
     *
     * @JdbcTypeCode(SqlTypes.CHAR) is mandatory on every CHAR(n) column here, not cosmetic: a String maps
     * to VARCHAR by default, and against the CHAR(8)/CHAR(6)/CHAR(1) columns of
     * src/main/resources/schema/cash-account-schema.sql that mismatch fails start-up under
     * spring.jpa.hibernate.ddl-auto=validate with a bpchar-versus-varchar type-code error.
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
     * Kept as the single character the program wrote (WS-VR-REQ X(1), CASH00.cbl:L42, L114) rather than a
     * typed enum: the write at L111-L131 follows the EVALUATE unconditionally and that EVALUATE has no
     * WHEN OTHER (L89-L102), so the KSDS legitimately holds codes outside A/Q/U/X/C/D. An enum would
     * reject exactly the rows a reconciliation most needs to see.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_code", nullable = false, length = 1, columnDefinition = "char(1)")
    private String requestCode;

    /*
     * BigDecimal with precision and scale declared explicitly, never float or double: the legacy field is
     * WS-VR-BALANCE PIC 9(7)V99 (CASH00.cbl:L43) and the column is NUMERIC(9,2), and an undeclared scale
     * would let Hibernate's default apply and drift from the DDL. Nullable although COBOL always wrote
     * nine digits, because a delimited conversion of the export can carry an empty field; staging it as
     * NULL lets the reconciler classify the row instead of the reader rejecting the whole file.
     */
    @Column(name = "balance", precision = 9, scale = 2)
    private BigDecimal balance;

    /* VARCHAR(8) holds the legacy WS-VR-CURRENCY X(8) width (CASH00.cbl:L44) after padding is trimmed. */
    @Column(name = "currency", length = 8)
    private String currency;

    /*
     * WS-VR-RETCODE X(10) (CASH00.cbl:L45) receives SQLCODE through an alphanumeric MOVE (L117), which
     * renders the absolute digits and drops the sign, so this is text with no numeric meaning: -803 and
     * +803 both arrive as 000000803 and the value belongs to the last SQL statement of the paragraph.
     */
    @Column(name = "retcode", length = 10)
    private String retcode;

    /*
     * Nullable on purpose: the CICS region's zone is an unresolved open item, so an export decoded
     * without a confirmed zone stages its stamps and leaves this empty rather than inventing an instant.
     * OffsetDateTime is what TIMESTAMPTZ requires under ddl-auto=validate.
     */
    @Column(name = "event_at")
    private OffsetDateTime eventAt;

    // Not a column: it records whether this instance has reached the database yet, which is the one thing an
    // assigned key cannot tell Spring Data. False on a staged(...) instance and set by the lifecycle
    // callbacks below, so newness is a fact about the instance rather than a guess about the key.
    @Transient
    private boolean persisted;

    /** JPA requires a no-argument constructor; {@link #staged} is the only construction path for callers. */
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
     * Stages one decoded history record under a run.
     *
     * <p>A single factory taking the decoded record whole is the only construction path, so a loader
     * cannot transpose the four same-typed String fields of the layout — name, date, time and request
     * code are all text, and a positional constructor would accept them in any order. The two arguments
     * beside the record are exactly the values the carrier cannot know: {@code ownerKey}, the uppercased
     * join key, and {@code eventAt}, which needs the configured legacy time zone.
     *
     * @param eventAt the resolved instant, or {@code null} when the region's time zone is unconfirmed
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

    // Both callbacks, deliberately: @PostLoad covers a row the reconciler or the comparator read back, and
    // @PostPersist covers the row this staging pass has just inserted, so neither can be offered to save()
    // a second time as an insert.
    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    /**
     * Composite identifier of a staging row: the raw 29-byte VSAM key within one run.
     *
     * <p>Field names and types mirror the enclosing entity's {@code @Id} attributes exactly; any
     * divergence fails context start-up with an opaque metadata error rather than at compile time.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID runId;

        private String name;

        private String eventDate;

        private String eventTime;

        /** Required by JPA, which instantiates the identifier reflectively. */
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
