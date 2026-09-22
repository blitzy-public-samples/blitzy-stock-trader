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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * WHAT THIS REPLACES. The legacy audit trail was WS-VSAM-RECORD, a 57-byte fixed layout of NAME X(15),
 * DATE X(8), TIME X(6), REQ X(1), BALANCE 9(7)V99, CURRENCY X(8) and RETCODE X(10)
 * [backend/cash-account-cobol/COBOL/CASH00.cbl:L38-L45], written to the HISTORY KSDS on every request
 * [CASH00.cbl:L126-L131] under a 29-byte key of name plus date plus time [CASH00.cbl:L47-L50].
 *
 * WHY THAT TRAIL WAS LOSSY AND THIS ONE IS NOT. The writer was preceded by
 * "EXEC CICS IGNORE CONDITION DUPREC" [CASH00.cbl:L123-L124], so a second event for the same owner inside
 * the same second collided on that one-second-resolution key and was discarded with no error to the caller
 * and no trace anywhere - which is why migrated legacy history counts can only ever be a lower bound
 * (AAP 0.11.1) and why identity here is a generated surrogate rather than a natural key: two events one
 * microsecond apart are two rows, always. Nothing in the repository ever read the file back either (the
 * WRITE is its only application access), so a queryable audit record is new capability, not a port.
 *
 * WHY INSERT-ONLY IS STRUCTURAL RATHER THAN CONVENTIONAL. Immutability is enforced twice, and the halves
 * are deliberately different in kind. In Java, every mapped column is updatable = false and the class has
 * no setter, so Hibernate's dirty check has nothing it could ever turn into an UPDATE; in the database,
 * the trigger ledger_entry_immutable (BEFORE UPDATE OR DELETE, RAISE EXCEPTION) in
 * src/main/resources/schema/cash-account-schema.sql refuses the statement outright. The Java half is the
 * one that matters for correctness of live traffic: were an updatable column to slip in, an accidental
 * mutation would reach the trigger during flush, and the resulting exception would roll back the caller's
 * entire transaction - so a stray field edit would surface as a lost business operation rather than as a
 * rejected audit edit. The database half is what makes the guarantee hold for anything that is not this
 * code path.
 *
 * WHY THERE IS NO ASSOCIATION TO ANY OTHER ENTITY. owner, incarnation_id, reservation_id and run_id are
 * plain scalar columns: the schema declares no foreign key from ledger_entry to cash_account because these
 * rows outlive the account (AAP 0.6.3), and the ledger query must still return them after a retail DELETE
 * (AAP 0.6.2). A @ManyToOne would also make ddl-auto=validate demand a join column the SQL file does not
 * create, and would put a lazy load on the append path that LedgerService runs inside the caller's
 * transaction.
 */
/** The append-only audit record: one immutable, timestamped row per balance-changing state transition. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    /*
     * Nested rather than a file of its own because AAP 0.6.1 fixes this package at eight files and lists no
     * LedgerSource.java; consumers therefore write LedgerEntry.Source.RETAIL. Extracting it later would
     * break that inventory, which is the only reason it is not a top-level type.
     *
     * The four constants are the closed set of AAP 0.6.3, and the longest, INSTITUTIONAL at 13 characters,
     * fits the VARCHAR(16) column with room the set does not need.
     */
    public enum Source {

        /** The retail seam broker calls: the six endpoints that replace request codes A/Q/U/X/C/D. */
        RETAIL,

        /** The additive hold, settle and release surface. */
        INSTITUTIONAL,

        /** The migration tooling's loader, which writes one MIGRATION_LOAD row per owner per run. */
        MIGRATION,

        /** The service itself acting without a caller - today, the reservation expiry sweep. */
        SYSTEM
    }

    /*
     * GenerationType.IDENTITY, never SEQUENCE and never AUTO. The column is
     * BIGINT GENERATED ALWAYS AS IDENTITY (schema/cash-account-schema.sql:L76), which rejects any
     * client-supplied value outright unless the statement carries OVERRIDING SYSTEM VALUE; AUTO resolves to
     * a sequence generator on PostgreSQL and would try to supply one. IDENTITY makes Hibernate omit the
     * column from the INSERT and read the assigned value back, which is also what lets a just-persisted row
     * be ordered and returned within the same transaction.
     *
     * insertable = false states that intent in the mapping as well, so the column cannot be pulled into an
     * INSERT by a later edit of this class.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id", insertable = false, updatable = false)
    private Long entryId;

    @Column(name = "owner", length = 32, nullable = false, updatable = false)
    private String owner;

    /*
     * The account incarnation this event belongs to, not merely the owner name. cash_account issues a fresh
     * incarnation_id on every create (AAP 0.6.3), so a deleted-and-recreated owner's history stays
     * attributable to the account life that produced it even though both lives share one primary key.
     */
    @Column(name = "incarnation_id", nullable = false, updatable = false)
    private UUID incarnationId;

    /*
     * EnumType.STRING is mandatory rather than stylistic. The partial index
     * UNIQUE (run_id, owner) WHERE event_type = 'MIGRATION_LOAD'
     * (schema/cash-account-schema.sql:L162-L163) matches the persisted text as a SQL string literal, and it
     * is what guarantees one load row per owner per run - the migration loader's retry-deduplication rule
     * (AAP 0.6.3). ORDINAL would store an integer the index could never match, and renaming the
     * LedgerEventType constant would silently disarm it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 24, nullable = false, updatable = false)
    private LedgerEventType eventType;

    /*
     * The magnitude of the event, never a signed delta - the column is NUMERIC(9,2) under
     * CHECK (amount >= 0) (schema/cash-account-schema.sql:L80, L90), the legacy DECIMAL(9,2) precision of
     * backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48. Direction comes from eventType alone; see the
     * factory for the full mapping.
     */
    @Column(name = "amount", precision = 9, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    /*
     * VARCHAR(8) rather than the three characters the API accepts, because the migrated width is the legacy
     * CHAR(8) of DB2DDL.jcl:L49; the accepted-code set is enforced where values enter, not here.
     */
    @Column(name = "currency", length = 8, nullable = false, updatable = false)
    private String currency;

    /*
     * The post-transition balances, which is what makes the ledger self-sufficient: a consumer needing a
     * signed delta derives it from consecutive available_after / reserved_after values (AAP 0.6.3) instead
     * of trusting a second stored representation that could drift from the balances it claims to explain.
     * It is also what lets AuditImmediacyIT compare a ledger row against the institutional account view
     * with no wait and no recomputation.
     */
    @Column(name = "available_after", precision = 9, scale = 2, nullable = false, updatable = false)
    private BigDecimal availableAfter;

    @Column(name = "reserved_after", precision = 9, scale = 2, nullable = false, updatable = false)
    private BigDecimal reservedAfter;

    /** Set on the reservation events (HOLD, SETTLEMENT, RELEASE, EXPIRY) and null on the retail ones. */
    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    /** The institutional caller's own order identifier, carried so a settlement can be traced to it. */
    @Column(name = "order_reference", length = 64, updatable = false)
    private String orderReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false, updatable = false)
    private Source source;

    /** The migration_run that produced the row; null for every event a caller or the sweep produced. */
    @Column(name = "run_id", updatable = false)
    private UUID runId;

    /*
     * OffsetDateTime, never LocalDateTime: the column is TIMESTAMPTZ, and LocalDateTime maps to
     * "timestamp without time zone", which ddl-auto=validate rejects at start-up.
     *
     * The value is stamped by the application clock in the factory rather than left to the column's
     * DEFAULT now(). AuditImmediacyIT reads the row back in the very next request with no entity refresh,
     * so the in-memory instance has to carry the timestamp the row carries; a database default would leave
     * the field null until something re-read it. The default stays in the DDL as the safety net for rows
     * inserted by psql during a migration rehearsal.
     *
     * Clock skew between pods is not a correctness problem here because this value is not an ordering key
     * on its own: both the index (owner, recorded_at DESC, entry_id DESC) and the ledger query's
     * "recordedAt DESC, entryId DESC" (AAP 0.6.2) break ties on the monotonic entry_id, so same-instant
     * rows still have one stable order and no database round trip for a clock is needed.
     *
     * The stamp is truncated to microseconds, the resolution of the column, so that the in-memory row and
     * the stored row carry the identical instant. Verified on PostgreSQL 12.22: a nanosecond stamp is
     * ROUNDED on storage, not truncated, so an untruncated 08:01:16.512733795Z came back as
     * ...512734Z - an entity still in the persistence context would then disagree with its own row, and a
     * "since" lower bound taken from that entity could exclude the very event it was read from. Truncating
     * toward the past is also the module's standing choice wherever precision is discarded (Money.ROUNDING).
     */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    /** For JPA only; every other construction goes through {@link #of}. */
    protected LedgerEntry() {
    }

    /*
     * THE MAGNITUDE-PLUS-IMPLIED-DIRECTION CONTRACT. amount is always non-negative and eventType alone says
     * which way the money moved, which is why Money is the parameter type: it is non-negative by
     * construction, so the DDL's CHECK (amount >= 0) can never be the first thing to notice a bad value.
     * The mapping, which the names do not reveal (AAP 0.6.3):
     *
     *   CREDIT                                     inflow to available
     *   DEBIT                                      outflow from available
     *   HOLD                                       inflow to reserved, the matching fall in available being
     *                                              visible in available_after
     *   RELEASE, EXPIRY                            inflow to available, reserved falling by the same
     *   SETTLEMENT                                 outflow - the settled portion leaves reserved for good
     *   ACCOUNT_CREATED, ACCOUNT_UPDATED,          absolute-set events: amount is the RESULTING available
     *   MIGRATION_LOAD                             balance, not a delta
     *   ACCOUNT_DELETED                            amount is the balance removed
     *
     * WHY THIS IS THE ONLY CONSTRUCTOR. A builder or a second overload would let a caller omit an attribute
     * the row must carry, and the parameter order deliberately mirrors the column order of
     * schema/cash-account-schema.sql:L75-L91 so that a mapping review reads as a single column-by-column
     * pass (AAP 0.7.6).
     *
     * WHY THE TWO FAILURE KINDS DIFFER. A null eventType, source, incarnationId or Money argument is a
     * programming error and raises NullPointerException naming the argument, which the exception handler's
     * catch-all renders as 500 INTERNAL - a bug must not be dressed up as a plausible 4xx. A rejected owner
     * or currency is a caller-reachable condition and raises CashAccountException with its explicit code, as
     * everywhere else in the module.
     */
    public static LedgerEntry of(String owner,
            UUID incarnationId,
            LedgerEventType eventType,
            Money amount,
            String currency,
            Money availableAfter,
            Money reservedAfter,
            UUID reservationId,
            String orderReference,
            Source source,
            UUID runId) {

        LedgerEntry entry = new LedgerEntry();
        entry.owner = OwnerNormalizer.normalize(owner);
        entry.incarnationId = Objects.requireNonNull(incarnationId, "incarnationId");
        entry.eventType = Objects.requireNonNull(eventType, "eventType");
        entry.amount = Objects.requireNonNull(amount, "amount").amount();
        entry.currency = normalizeCurrency(currency);
        entry.availableAfter = Objects.requireNonNull(availableAfter, "availableAfter").amount();
        entry.reservedAfter = Objects.requireNonNull(reservedAfter, "reservedAfter").amount();
        entry.reservationId = reservationId;
        entry.orderReference = trimToNull(orderReference);
        entry.source = Objects.requireNonNull(source, "source");
        entry.runId = runId;
        entry.recordedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return entry;
    }

    public Long entryId() {
        return entryId;
    }

    public String owner() {
        return owner;
    }

    public UUID incarnationId() {
        return incarnationId;
    }

    public LedgerEventType eventType() {
        return eventType;
    }

    /*
     * The stored columns are BigDecimal so the mapping can declare precision and scale, while the three
     * monetary accessors hand back Money: a caller comparing a ledger row with a balance must do it in the
     * module's one money type, and a raw BigDecimal would invite a scale-sensitive equals.
     */
    public Money amount() {
        return Money.of(amount);
    }

    public String currency() {
        return currency;
    }

    public Money availableAfter() {
        return Money.of(availableAfter);
    }

    public Money reservedAfter() {
        return Money.of(reservedAfter);
    }

    public UUID reservationId() {
        return reservationId;
    }

    public String orderReference() {
        return orderReference;
    }

    public Source source() {
        return source;
    }

    public UUID runId() {
        return runId;
    }

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    // JavaBean aliases of the accessors above, for the same reason CashAccountException publishes them: the
    // row's readers - the ledger DTO, the audit service and the reconciliation reports - are separate
    // classes, and the aliases mean none of them is edited over an accessor-naming preference. There is
    // deliberately no matching mutator for any of them, and no delta() helper: a signed delta is derived
    // from consecutive available_after / reserved_after values, never stored (AAP 0.6.3).
    public Long getEntryId() {
        return entryId;
    }

    public String getOwner() {
        return owner;
    }

    public UUID getIncarnationId() {
        return incarnationId;
    }

    public LedgerEventType getEventType() {
        return eventType;
    }

    public Money getAmount() {
        return amount();
    }

    public String getCurrency() {
        return currency;
    }

    public Money getAvailableAfter() {
        return availableAfter();
    }

    public Money getReservedAfter() {
        return reservedAfter();
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public Source getSource() {
        return source;
    }

    public UUID getRunId() {
        return runId;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    /*
     * Identity is the generated entry_id and nothing else, which is the only identity a row that records a
     * repeatable event can have: two HOLD events of the same amount on the same owner in the same instant
     * are distinct rows, and value equality would collapse exactly the duplicates the legacy DUPREC drop
     * lost [CASH00.cbl:L123-L124].
     *
     * A not-yet-persisted instance has no id and is therefore equal only to itself, never to another
     * id-less instance. The comparison reads the other side through its accessor rather than its field so
     * that an uninitialized Hibernate proxy answers with its identifier instead of a null field; instanceof
     * accepts a proxy because a proxy is a subclass.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LedgerEntry that)) {
            return false;
        }
        return entryId != null && entryId.equals(that.entryId());
    }

    /*
     * A fixed value rather than the id's hash, and the class literal rather than getClass(): the id is null
     * until the INSERT returns, so hashing it would change the bucket of an instance already held in a hash
     * collection, and getClass() would differ between an entity and its proxy, which equals treats as
     * equal. Constant for every instance, so it satisfies the contract in both cases; collections of ledger
     * rows are ordered query results, not hash sets, so the degenerate bucket costs nothing here.
     */
    @Override
    public int hashCode() {
        return LedgerEntry.class.hashCode();
    }

    /*
     * Deliberately carries no amount and no balance: this string reaches logs, where a per-owner cash
     * position is exactly what an audit record must not leak. The three fields it does carry are what a
     * diagnostic needs to find the row itself.
     */
    @Override
    public String toString() {
        return "LedgerEntry{entryId=" + entryId
                + ", owner=" + owner
                + ", eventType=" + eventType
                + '}';
    }

    /*
     * Trimmed and upper-cased for the same reason owners are: legacy CHAR(8) values arrive blank-padded
     * from an export or an EBCDIC record, and a padded or lower-case code would otherwise become a distinct
     * currency in the ledger. Locale.ROOT, never the no-argument toUpperCase(), so identity cannot depend
     * on the JVM's default locale.
     *
     * The length check is the cheap half of a contract the database states expensively: the column is
     * VARCHAR(8) NOT NULL, so a longer value would surface at flush as a driver-level truncation error
     * naming neither the field nor the caller. INVALID_CURRENCY (400) names both. The accepted three-letter
     * ISO set is enforced at the boundaries that admit currencies (AAP 0.7.2); this is the ledger's own
     * storable-width guard, and it deliberately still admits the wider migrated values the tooling loads.
     */
    private static String normalizeCurrency(String raw) {
        if (raw == null) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_CURRENCY);
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 8) {
            throw CashAccountException.of(CashAccountErrorCode.INVALID_CURRENCY);
        }
        return normalized;
    }

    /*
     * A blank order reference is stored as NULL rather than as an empty string, because the column is
     * nullable precisely to say "this event had no order behind it", and two spellings of absence would
     * make every later query test for both.
     */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
