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

/** The append-only audit record: one immutable, timestamped row per balance-changing state transition. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    /** Nested because AAP 0.6.1 fixes this package at eight files and lists no LedgerSource.java. */
    public enum Source {

        RETAIL,

        INSTITUTIONAL,

        MIGRATION,

        /** The service itself acting without a caller - the reservation expiry sweep. */
        SYSTEM
    }

    /*
     * A generated surrogate rather than the legacy's natural key: the HISTORY KSDS keyed history on name plus
     * date plus time at one-second resolution and dropped a same-second collision silently
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L47-L50, L123-L124], so migrated counts are only a lower
     * bound (AAP 0.11.1) while here two events a microsecond apart are two rows, always.
     *
     * GenerationType.IDENTITY, never SEQUENCE and never AUTO: entry_id is declared
     * BIGINT GENERATED ALWAYS AS IDENTITY in schema/cash-account-schema.sql, which rejects a client-supplied
     * value unless the statement carries OVERRIDING SYSTEM VALUE, and AUTO resolves to a sequence generator
     * on PostgreSQL that would try to supply one. IDENTITY also makes Hibernate read the assigned value back,
     * which is what lets a just-persisted row be ordered and returned within the same transaction;
     * insertable = false states the same intent in the mapping, so no later edit can pull the column into an
     * INSERT.
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
     * EnumType.STRING is mandatory rather than stylistic: the partial index uq_ledger_entry_migration_load
     * in schema/cash-account-schema.sql matches event_type against the SQL string literal 'MIGRATION_LOAD',
     * and it is what guarantees one load row per owner per run - the loader's retry-deduplication rule
     * (AAP 0.6.3). ORDINAL would store an integer the index could never match, and renaming the
     * LedgerEventType constant would silently disarm it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 24, nullable = false, updatable = false)
    private LedgerEventType eventType;

    /*
     * The magnitude of the event, never a signed delta - the column is NUMERIC(9,2) under
     * ck_ledger_entry_amount_nonneg in schema/cash-account-schema.sql, the legacy DECIMAL(9,2) precision of
     * backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48. Direction comes from eventType alone; the factory
     * carries the mapping.
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

    /*
     * Set on the reservation events (HOLD, SETTLEMENT, RELEASE, EXPIRY) and null on the retail ones. This
     * column, and owner, incarnation_id and run_id with it, is a plain scalar and not a @ManyToOne: these
     * rows outlive the account (AAP 0.6.3) and the ledger query must still return them after a retail DELETE
     * (AAP 0.6.2), so the schema declares no foreign key for an association to map, ddl-auto=validate would
     * demand a join column the SQL file never creates, and a lazy load would land on the append path
     * LedgerService runs inside the caller's transaction.
     */
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
     * "timestamp without time zone", which ddl-auto=validate rejects at start-up. The value is stamped by
     * the application clock in the factory rather than left to the column's DEFAULT now(), because the row
     * is read back in the very next request with no entity refresh and the in-memory instance therefore has
     * to carry the timestamp the row carries; the DDL default stays as the safety net for rows inserted by
     * psql during a migration rehearsal.
     *
     * The stamp is truncated to the column's microsecond resolution so the in-memory row and the stored row
     * carry the identical instant. Verified on PostgreSQL 12.22: a nanosecond stamp is ROUNDED on storage,
     * not truncated, so an untruncated 08:01:16.512733795Z came back as ...512734Z, after which a "since"
     * lower bound taken from that entity could exclude the very event it was read from. Clock skew between
     * pods is harmless because this is not an ordering key on its own - both the (owner, recorded_at DESC,
     * entry_id DESC) index and the ledger query break ties on the monotonic entry_id (AAP 0.6.2).
     */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    /** For JPA only; every other construction goes through {@link #of}. */
    protected LedgerEntry() {
    }

    /*
     * amount is a magnitude and eventType alone says which way the money moved, which is why Money is the
     * parameter type: it is non-negative by construction, so ck_ledger_entry_amount_nonneg can never be the
     * first thing to notice a bad value. The mapping, which the names do not reveal (AAP 0.6.3):
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
     * The only constructor, because a builder or a second overload would let a caller omit an attribute the
     * row must carry; the parameter order follows the ledger_entry column order of
     * schema/cash-account-schema.sql so that a mapping review reads as one column-by-column pass.
     *
     * The two failure kinds differ deliberately: a null eventType, source, incarnationId or Money argument
     * is a programming error and raises NullPointerException naming the argument, which the handler's
     * catch-all renders as 500 INTERNAL, while a rejected owner or currency is caller-reachable and raises
     * CashAccountException with its explicit code, as everywhere else in the module.
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
        entry.orderReference = storableOrderReference(orderReference);
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
     * The columns are BigDecimal so the mapping can declare precision and scale, while the three monetary
     * accessors hand back Money: a caller comparing a ledger row with a balance must do it in the module's
     * one money type, and a raw BigDecimal would invite a scale-sensitive equals.
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
    // classes, and the aliases mean none of them is edited over an accessor-naming preference.
    //
    // No mutator accompanies any of them, and that absence together with updatable = false on every mapped
    // column is the Java half of the immutability guarantee: Hibernate's dirty check then has nothing it
    // could turn into an UPDATE. It is the half that matters for live traffic, because an accidental
    // mutation would otherwise reach the ledger_entry_immutable trigger during flush and roll back the
    // caller's whole transaction, surfacing a stray field edit as a lost business operation. The trigger in
    // schema/cash-account-schema.sql is what makes the guarantee hold for everything that is not this code
    // path. There is no delta() helper either: a signed delta is derived from consecutive available_after /
    // reserved_after values, never stored (AAP 0.6.3).
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
     * Identity is the generated entry_id and nothing else, the only identity a row recording a repeatable
     * event can have: two HOLD events of the same amount on the same owner in the same instant are distinct
     * rows, and value equality would collapse exactly the duplicates the legacy DUPREC drop lost
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L123-L124]. An unpersisted instance therefore equals only
     * itself. The other side is read through its accessor so an uninitialized Hibernate proxy answers with
     * its identifier instead of a null field.
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
     * Trimmed and upper-cased for the same reason owners are: legacy CHAR(8) values arrive blank-padded from
     * an export or an EBCDIC record, and a padded or lower-case code would become a distinct currency in the
     * ledger. Locale.ROOT, never the no-argument toUpperCase(), so identity cannot depend on the JVM's
     * default locale. The length check turns what the VARCHAR(8) NOT NULL column would report at flush as a
     * driver-level truncation error, naming neither field nor caller, into INVALID_CURRENCY (400), which
     * names both; the accepted three-letter ISO set is enforced at the boundaries that admit currencies
     * (AAP 0.7.2), so this guard deliberately still admits the wider migrated values the tooling loads.
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
     * The reference is recorded exactly as the reservation holds it, blanks and all - the one place this
     * class deliberately does not normalize a String. cash_reservation stores the caller's order reference
     * verbatim because request_hash is the SHA-256 of a canonical payload whose first component is that
     * reference taken exactly, with no trimming and no case folding (AAP 0.7.3), which is what makes
     * "  ORD  " and "ORD" two different requests under one Idempotency-Key. Altering it on the way into this
     * row would give one hold two identities - the reservation's, and a silently different one in the
     * immutable record of the very same event - and the audit record is the half that cannot be corrected
     * afterwards, since ledger_entry accepts no UPDATE (AAP 0.7.4).
     *
     * Absence still has exactly one spelling, NULL: the column is nullable precisely to say "this event had
     * no order behind it", which is every retail, migration and account-level event, and a blank string is no
     * reference either. A blank cannot arrive from a caller in any case - HoldRequest declares @NotBlank and
     * CashReservation.requireOrderReference rejects it.
     *
     * The width check is this class's own storable-width guard, for the reason normalizeCurrency carries one:
     * the value reaches a VARCHAR(64) column unaltered, so its stored length is its raw length, and an
     * over-long reference would otherwise surface at flush as a driver-level truncation error naming neither
     * the field nor the caller - one that, because the append runs inside the caller's transaction, would roll
     * back the business operation it was only meant to record. No caller can reach it: HoldRequest's
     * @Size(max = 64) bounds the raw string and CashReservation bounds it again, so a value arriving here
     * over-length is a defect in this module, which IllegalArgumentException reports as one exactly as that
     * method does instead of dressing it up as a caller-facing error code.
     */
    private static String storableOrderReference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > 64) {
            throw new IllegalArgumentException("orderReference must be at most 64 characters");
        }
        return raw;
    }
}
