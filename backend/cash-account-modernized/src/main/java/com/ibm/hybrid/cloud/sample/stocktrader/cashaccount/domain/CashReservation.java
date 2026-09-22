package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** An institutional hold on part of an owner's cash: how much is reserved, for which order, and until when. */
@Entity
@Table(name = "cash_reservation")
public class CashReservation {

    /** Bound by VARCHAR(128) on idempotency_key; a longer key is refused rather than truncated. */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    private static final int ORDER_REFERENCE_MAX_LENGTH = 64;

    /** Bound by VARCHAR(8) on currency, the legacy CURRENCYC CHAR(8) width kept for migrated values. */
    private static final int CURRENCY_MAX_LENGTH = 8;

    /** The hex length of a SHA-256 digest, which is what CHAR(64) on request_hash exists to hold. */
    private static final int REQUEST_HASH_LENGTH = 64;

    // A surrogate key, unlike CashAccount's natural one: a reservation has no business identifier that is
    // unique on its own - order_reference may repeat across owners and retries - and the value is handed to
    // institutional callers in every settle and release URL. Assigned by the factory rather than by the
    // database so the identity exists before the INSERT, which is what makes hashCode below stable.
    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    // Copied from the account, never pointed at it: there is no @ManyToOne and no foreign key, because these
    // rows are retained as the audit record after a retail DELETE of the account (AAP 0.6.3, 0.11.1) and a
    // foreign key would make that promised deletion fail as soon as a terminal reservation existed. An
    // instance may therefore legitimately name an owner with no cash_account row, and no code here may assume
    // the account still exists. updatable = false on both because re-pointing a reservation at another owner
    // or another incarnation would move money the ledger has already accounted for.
    @Column(name = "owner", length = 32, nullable = false, updatable = false)
    private String owner;

    @Column(name = "incarnation_id", nullable = false, updatable = false)
    private UUID incarnationId;

    // The caller's own reference for the order this hold backs. Opaque here on purpose: the service hashes it
    // into request_hash for replay detection and compares it exactly, without case folding (AAP 0.7.3).
    @Column(name = "order_reference", length = 64, nullable = false, updatable = false)
    private String orderReference;

    // precision AND scale are both declared because Hibernate substitutes its own default scale when a
    // mapping is silent, which would make this entity describe a column the schema does not have and turn
    // ddl-auto=validate from a guard into a start-up failure. 9 and 2 are the legacy NUMERIC(9,2) precision
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48] that schema/cash-account-schema.sql reproduces.
    // The held amount is fixed for the life of the row - a hold is settled or released, never resized - so
    // updatable = false, which is also what makes CHECK (settled_amount <= amount) a stable invariant.
    @Column(name = "amount", precision = 9, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    // The one mutable money column, and deliberately nullable: NULL means "not settled", which is a different
    // fact from a settled amount of zero. A zero settlement is legal (AAP 0.6.2) and stores 0.00 here while
    // releasing the whole hold, so collapsing the two would lose the distinction the ledger rows record.
    @Column(name = "settled_amount", precision = 9, scale = 2)
    private BigDecimal settledAmount;

    // No shape constraint is declared on this column, unlike cash_account.currency's CHECK (currency ~
    // '^[A-Z]{3}$'): the institutional path never converts, so the service's own CURRENCY_MISMATCH check
    // against the account currency is the real guard (AAP 0.7.2), and that account currency has already
    // passed the stricter check.
    @Column(name = "currency", length = 8, nullable = false, updatable = false)
    private String currency;

    // EnumType.STRING, never the ORDINAL default: the column is VARCHAR(16) under
    // CHECK (state IN ('HELD','SETTLED','RELEASED','EXPIRED')), so ordinal integers would fail that check on
    // the first insert - a mistake the compiler cannot catch and the schema validator does not report,
    // because the column type still matches.
    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 16, nullable = false)
    private ReservationState state;

    // One half of UNIQUE (incarnation_id, idempotency_key), the atomic first-writer-wins guard the hold path
    // is built on (AAP 0.7.3): two concurrent holds with one key race to the INSERT and the loser re-reads in
    // a FRESH transaction - a PostgreSQL unique violation aborts the transaction it occurs in - then replays
    // the winner's response when request_hash matches or answers 422 IDEMPOTENCY_KEY_REUSED when it does not.
    // Scoping by the account's incarnation is what stops a key from a deleted-and-recreated owner's previous
    // life from replaying a reservation that reserved nothing in the new account.
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    /*
     * @JdbcTypeCode(SqlTypes.CHAR) must stay: the column is CHAR(64), for which a plain String field makes
     * Hibernate expect varchar(64) while pgJDBC reports bpchar, and the schema validator's
     * "varchar(64)".startsWith("bpchar") is false - so without it ddl-auto=validate fails and the application
     * does not start. columnDefinition = "char(64)" is not the fix, because it replaces the type text without
     * reliably correcting the code, and widening to VARCHAR(64) is not available: AAP 0.6.3 specifies
     * CHAR(64) and schema/cash-account-schema.sql declares it.
     *
     * PostgreSQL blank-pads bpchar on read. A hex SHA-256 digest is exactly 64 characters and the factory
     * rejects any other length, but the accessor strips regardless, so a replay comparison can never turn on
     * trailing blanks a shorter value would have acquired.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    // OffsetDateTime for every TIMESTAMPTZ column, as in CashAccount: a LocalDateTime field maps to
    // timestamp without time zone and ddl-auto=validate rejects it against these columns. Instants are stored
    // in UTC by hibernate.jdbc.time_zone, so an expiry evaluated on one pod means the same on every other.
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // A primitive long, not a Long, so a new instance starts at 0 - the value the column's DEFAULT 0 holds -
    // and a row inserted by psql is indistinguishable from one inserted by the service to the version check.
    // Optimistic locking backs the pessimistic account lock the service takes first; a conflict that still
    // slips through surfaces as 409 CONCURRENT_MODIFICATION rather than a lost settlement.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA; every other construction goes through {@link #newHold}. */
    protected CashReservation() {
    }

    /**
     * Places a hold on {@code account} for {@code amount}, bound to that account's current incarnation.
     *
     * <p>This factory creates only the record of the hold; {@code ReservationStateMachine} owns the balance
     * effects that must accompany it in the same transaction. Two values are deliberately computed
     * elsewhere: {@code requestHash}, because only {@code institutional/ReservationService} sees the request
     * as the caller sent it, and {@code expiresAt}, whose default comes from configuration a domain type
     * does not read - this package stays free of Spring.</p>
     *
     * @param account the account whose funds are held; supplies both {@code owner} and {@code incarnationId}
     * @param orderReference the caller's reference for the backing order, 1 to 64 characters
     * @param amount the amount to hold, strictly positive
     * @param currency the hold currency, accepted with surrounding blanks or in lower case
     * @param expiresAt the instant after which an unsettled hold may be expired; already defaulted
     * @param idempotencyKey the caller's Idempotency-Key header value, 1 to 128 characters
     * @param requestHash the hex SHA-256 of the canonical request payload, exactly 64 characters
     * @return a transient reservation in state {@code HELD}
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_AMOUNT} when {@code amount} is not
     *         positive, {@link CashAccountErrorCode#IDEMPOTENCY_KEY_REQUIRED} when {@code idempotencyKey} is
     *         missing, blank or longer than 128 characters,
     *         {@link CashAccountErrorCode#INVALID_CURRENCY} when {@code currency} is missing or does not fit
     *         the column
     * @throws IllegalArgumentException when a value the service is responsible for producing is absent or
     *         malformed, which is a defect in this module rather than a caller error
     */
    public static CashReservation newHold(CashAccount account, String orderReference, Money amount,
            String currency, OffsetDateTime expiresAt, String idempotencyKey, String requestHash) {
        // These three arrive already built by this module's own code, never straight from a caller, so an
        // absent one is a defect and is reported the way Money reports one: the exception handler's catch-all
        // renders it as 500 INTERNAL instead of dressing a bug up as a plausible 4xx the caller could act on.
        if (account == null) {
            throw new IllegalArgumentException("account is required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }

        CashReservation reservation = new CashReservation();
        // Normalized defensively even though the account's own owner is already canonical: this is the value
        // the reconciler and the ledger join on, and a single unnormalized row would break those joins
        // silently rather than loudly.
        reservation.owner = OwnerNormalizer.normalize(account.owner());
        reservation.incarnationId = account.incarnationId();

        // The caller-facing conditions are judged before the module-internal ones below, so a request that is
        // both malformed and mis-assembled still answers with the 400 the caller can act on.
        //
        // signum() rather than isZero(): it mirrors the DDL's CHECK (amount > 0) literally and stays correct
        // if Money's non-negative floor is ever relaxed. A hold of zero reserves nothing and would leave the
        // settle path with no amount to move, so it is a 400 (AAP 0.6.2) and not a no-op.
        if (amount.amount().signum() <= 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, reservation.owner);
        }
        reservation.currency = requireCurrency(currency, reservation.owner);
        reservation.idempotencyKey = requireIdempotencyKey(idempotencyKey, reservation.owner);
        reservation.orderReference = requireOrderReference(orderReference);
        reservation.requestHash = requireRequestHash(requestHash);

        reservation.reservationId = UUID.randomUUID();
        reservation.amount = amount.amount();
        reservation.settledAmount = null;
        reservation.state = ReservationState.HELD;
        // All three stamps pass through storedInstant for the reason recorded on it, and the two below are one
        // instant rather than two clock readings: a replay rebuilds the original response's updated_at from
        // created_at (institutional/ReservationResponse.originalHold), which is exact only while a new hold's
        // two timestamps hold the same value.
        reservation.expiresAt = storedInstant(expiresAt);
        OffsetDateTime now = storedInstant(OffsetDateTime.now());
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    /*
     * Package-private and must stay that way: which (state, command) pairs are legal, idempotent or rejected
     * is knowledge the module keeps only in ReservationStateMachine (AAP 0.6.5), and retail, institutional
     * and migration code physically cannot reach this method, which turns "entities never mutate state
     * outside the state machine" into a compile-time fact.
     *
     * This method applies, it does not judge: the caller has already decided the transition is legal and
     * computed the balances that move with it. The settled amount is authoritative rather than merged -
     * passing null clears it - so a release or an expiry leaves the column NULL, the "never settled" fact
     * the ledger rows rely on.
     */
    void applyTransition(ReservationState newState, Money settledAmount) {
        if (newState == null) {
            throw new IllegalArgumentException("newState is required");
        }
        this.state = newState;
        this.settledAmount = settledAmount == null ? null : settledAmount.amount();
        touch();
    }

    public UUID reservationId() {
        return reservationId;
    }

    public String owner() {
        return owner;
    }

    public UUID incarnationId() {
        return incarnationId;
    }

    public String orderReference() {
        return orderReference;
    }

    // Money.of cannot fail on a value read back from these columns: NUMERIC(9,2) cannot hold a magnitude past
    // Money.MAX_VALUE, and CHECK (amount > 0) together with CHECK (settled_amount <= amount) excludes the
    // negative case for both.
    public Money amount() {
        return Money.of(amount);
    }

    /**
     * The null is the contract, not an oversight, and an {@link java.util.Optional} is deliberately not
     * returned: "never settled" and "settled for 0.00" are different facts - a zero settlement is legal and
     * releases the whole hold - and wrapping the column would invite callers to treat the two as one.
     *
     * @return the settled portion, or {@code null} while the reservation has never been settled
     */
    public Money settledAmount() {
        return settledAmount == null ? null : Money.of(settledAmount);
    }

    public String currency() {
        return currency;
    }

    public ReservationState state() {
        return state;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    // Stripped on read for the bpchar reason given on the field: a replay decision compares this value for
    // equality, and that comparison must never depend on padding.
    public String requestHash() {
        return requestHash == null ? null : requestHash.strip();
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /**
     * HELD is the only state that constrains anything outside itself: it is what makes a retail {@code PUT}
     * or {@code DELETE} answer 409 RESERVATIONS_OUTSTANDING (AAP 0.6.2) and what makes the loader leave an
     * existing owner untouched with a {@code STATE} variance rather than overwrite a balance that is partly
     * committed elsewhere (AAP 0.6.3).
     *
     * @return whether funds are still held
     */
    public boolean isHeld() {
        return state == ReservationState.HELD;
    }

    /**
     * True only for a held reservation: the three terminal states are already resolved, and reporting one of
     * them as expirable would let the sweep write a second terminal transition for the same hold.
     *
     * @param now the instant to judge against, passed rather than read from the clock so the scheduled sweep
     *        and the lazy check a settle or release performs first judge one reservation against one instant
     * @return whether this reservation is overdue as at {@code now} and therefore expirable
     */
    public boolean isExpiredAt(OffsetDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return state == ReservationState.HELD && expiresAt.isBefore(now);
    }

    /*
     * A safety net for an instance persisted without the factory - the DDL's DEFAULT now() covers a psql
     * insert and this covers a JPA one - never the primary mechanism: newHold stamps both timestamps and
     * applyTransition stamps updated_at.
     */
    @PrePersist
    void applyTimestampDefaults() {
        OffsetDateTime now = storedInstant(OffsetDateTime.now());
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    /*
     * Every timestamp this class stores passes through here so the in-memory entity cannot disagree with its
     * own row: the columns are TIMESTAMPTZ at microsecond resolution and application.yml pins
     * hibernate.jdbc.time_zone to UTC, so a hold created with expiresAt 2099-06-01T14:00:00.123456789+02:00
     * would answer with those nanoseconds and that offset while every later read - including the replay that
     * AAP 0.6.2 and 0.7.3 require to return the original body - answered 2099-06-01T12:00:00.123456Z.
     * Truncation rather than rounding, applied before the value is sent: pgJDBC rounds sub-microsecond digits
     * on the way out, and domain/LedgerEntry stamps recorded_at by the same UTC-at-microseconds rule, so the
     * two tables that publish timestamps agree on what an instant is.
     *
     * The rule covers what the service stores and publishes, never what it compares: the idempotency hash is
     * taken from the payload as the caller sent it (AAP 0.7.3), so a retry of a hold that named an explicit
     * expiry resends that payload rather than the normalized instant this returns.
     */
    private static OffsetDateTime storedInstant(OffsetDateTime value) {
        if (value == null) {
            return null;
        }
        return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /*
     * The mutator stamps updated_at instead of a @PreUpdate callback, which runs at flush, so every read
     * taken between the transition and the flush - including the ReservationResponse the institutional
     * endpoint builds from this instance before its transaction commits - would still carry the previous
     * value. The column's DEFAULT now() remains the safety net for a row some other tool inserts.
     */
    private void touch() {
        updatedAt = storedInstant(OffsetDateTime.now());
    }

    /*
     * Validation only, never normalization: the value is stored exactly as it arrives, blanks and all,
     * because request_hash is the SHA-256 of a canonical payload whose first component is this reference
     * taken verbatim (AAP 0.7.3, which compares it exactly). Trimming or case-folding it would make the
     * reference this reservation hands back hash to something other than the digest stored beside it, so a
     * caller replaying its hold with that reference would receive 422 IDEMPOTENCY_KEY_REUSED for an
     * identical request, and "  ORD  " would stop being a distinct request from "ORD".
     *
     * HoldRequest's @NotBlank @Size(max = 64) has already rejected a caller's bad value as a 400, so
     * anything unusable here is a defect in this module and IllegalArgumentException is the honest report;
     * checking at all is what stops an opaque 500 from a flush-time DataIntegrityViolationException from
     * being the first sign.
     */
    private static String requireOrderReference(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("orderReference is required");
        }
        if (raw.length() > ORDER_REFERENCE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "orderReference must be at most " + ORDER_REFERENCE_MAX_LENGTH + " characters");
        }
        return raw;
    }

    /*
     * Deliberately less strict than cash_account.currency, which carries ck_cash_account_currency_iso while
     * this column carries no shape constraint: the institutional path never converts, so the service's
     * CURRENCY_MISMATCH check against the already-validated account currency is the real guard (AAP 0.7.2).
     * Enforced here is only that the value exists and fits VARCHAR(8), the legacy CURRENCYC width
     * [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L49], so the failure is an explicit 400 rather than a
     * constraint violation surfacing as 500 at flush. Locale.ROOT, never the no-argument toUpperCase(): a
     * Turkish default locale maps "i" to U+0130 and would store a different code on one pod than on another.
     */
    private static String requireCurrency(String raw, String owner) {
        if (raw == null || raw.isBlank()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        if (candidate.length() > CURRENCY_MAX_LENGTH) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        return candidate;
    }

    /*
     * IDEMPOTENCY_KEY_REQUIRED covers the over-length case as well as the missing one: both are 400s about
     * the same header, the code set defines no second idempotency-key 400 (AAP 0.6.2), and an explicit
     * message distinguishes them for the caller. The alternative - letting a 129-character header reach
     * VARCHAR(128) - is a 500 for what is plainly a bad request.
     */
    private static String requireIdempotencyKey(String raw, String owner) {
        if (raw == null || raw.isBlank()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED, owner);
        }
        String candidate = raw.strip();
        if (candidate.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw CashAccountException.forOwner(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED, owner,
                    "The Idempotency-Key header must be at most " + IDEMPOTENCY_KEY_MAX_LENGTH
                            + " characters.");
        }
        return candidate;
    }

    /*
     * A digest of the wrong length is a programming error and not a caller condition - the caller never sends
     * it; the service computes it from the canonical payload - so no error code applies and the defect is
     * raised as such. The length is also what keeps the CHAR(64) column free of the blank padding the field
     * comment warns about.
     */
    private static String requireRequestHash(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("requestHash is required");
        }
        String candidate = raw.strip();
        if (candidate.length() != REQUEST_HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "requestHash must be exactly " + REQUEST_HASH_LENGTH + " characters");
        }
        return candidate;
    }

    /*
     * Identity is the reservation identifier and nothing else: the same hold must stay equal to itself across
     * a settle, a release and an expiry, and a value-based equals would make an instance stop matching its
     * own earlier self mid-transaction. The comparison reads the other side through its accessor rather than
     * its field so an uninitialized Hibernate proxy answers with its identifier instead of a null field;
     * instanceof accepts a proxy because a proxy is a generated subclass.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CashReservation that)) {
            return false;
        }
        return reservationId != null && reservationId.equals(that.reservationId());
    }

    /*
     * Hashing the identifier is safe here, unlike in LedgerEntry: newHold assigns it before the INSERT, so it
     * cannot change while the instance sits in a hash collection. A null guard remains for the JPA-only
     * constructor's window before Hibernate populates the field.
     */
    @Override
    public int hashCode() {
        return reservationId == null ? 0 : reservationId.hashCode();
    }

    /*
     * Carries neither amount nor settled amount, and neither the idempotency key nor the request hash: this
     * string reaches logs and exception context, where a customer's cash position has no place and where a
     * caller-supplied key - replayable by anyone holding it - would be a credential in a log file. The three
     * fields it does carry are what a diagnostic needs to find the row and see where in its lifecycle it is.
     */
    @Override
    public String toString() {
        return "CashReservation{reservationId=" + reservationId
                + ", owner=" + owner
                + ", state=" + state
                + '}';
    }
}

