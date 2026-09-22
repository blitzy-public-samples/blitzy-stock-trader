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
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/*
 * WHY THIS TABLE HAS NO LEGACY COUNTERPART. STOCKTRD.CASHACCOUNTY held one mutable BALANCE per OWNER
 * [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L52; backend/cash-account-cobol/COBOL/DCLCASH.cpy:L8-L12]
 * and the program offered no way to earmark part of it: the only means of reserving funds was the debit
 * paragraph, which destroyed the information needed to give them back. A reservation row is therefore a new
 * capability rather than a migrated one (AAP 0.14.2), and nothing in this class is a parity requirement -
 * while every column, width and constraint below is a schema requirement, because ddl-auto=validate compares
 * this mapping against schema/cash-account-schema.sql at start-up and refuses to boot on any difference.
 *
 * WHY THERE IS NO ASSOCIATION TO CashAccount - no @ManyToOne, no @JoinColumn, no foreign key. These rows are
 * retained as the audit record after a retail DELETE of the account (AAP 0.6.3, 0.11.1), so a foreign key
 * would make that promised deletion fail as soon as a terminal reservation existed. owner and incarnation_id
 * are plain scalar columns, and an instance may legitimately name an owner that has no cash_account row at
 * all; no code here may assume the account still exists. An association would additionally make
 * ddl-auto=validate demand a join column the schema script never creates.
 *
 * WHY THE IDEMPOTENCY KEY IS SCOPED BY incarnation_id. UNIQUE (incarnation_id, idempotency_key) is not
 * hygiene, it is the atomic first-writer-wins guard the hold path is built on (AAP 0.7.3): two concurrent
 * holds with one key race to the INSERT, the loser catches the DataIntegrityViolationException the constraint
 * raises and re-reads in a FRESH transaction - a PostgreSQL unique violation aborts the transaction it occurs
 * in, so the re-read cannot happen inside it - then replays the winner's response when request_hash matches
 * or answers 422 IDEMPOTENCY_KEY_REUSED when it does not. Scoping by the account's incarnation rather than by
 * the owner is what stops a key from a deleted-and-recreated owner's previous life from replaying a
 * reservation that reserved nothing in the new account.
 *
 * WHY @Table NAMES THE TABLE AND NOTHING ELSE. No schema is declared, so statements resolve through the
 * connection's search path and the runbook's rehearsal override (spring.datasource.hikari.schema=
 * cash_account_rehearsal) reaches this entity without a code change. The unique constraint above and the
 * (owner) and (state, expires_at) indexes are declared once, in schema/cash-account-schema.sql; repeating
 * them in annotations would create a second source of truth that ddl-auto=validate does not even read.
 */
/** An institutional hold on part of an owner's cash: how much is reserved, for which order, and until when. */
@Entity
@Table(name = "cash_reservation")
public class CashReservation {

    /** Bound by VARCHAR(128) on idempotency_key; a longer key is refused rather than truncated. */
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    /** Bound by VARCHAR(64) on order_reference. */
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

    // Copied from the account, never pointed at it: see the no-foreign-key note above. updatable = false on
    // both because re-pointing a reservation at another owner or another incarnation would move money the
    // ledger has already accounted for under the original identity.
    @Column(name = "owner", length = 32, nullable = false, updatable = false)
    private String owner;

    @Column(name = "incarnation_id", nullable = false, updatable = false)
    private UUID incarnationId;

    // The caller's own reference for the order this hold backs. Opaque here on purpose: the service hashes it
    // into request_hash for replay detection and compares it exactly, without case folding (AAP 0.7.3).
    @Column(name = "order_reference", length = 64, nullable = false, updatable = false)
    private String orderReference;

    // WHY precision AND scale ARE BOTH DECLARED: Hibernate substitutes its own default scale when a mapping
    // is silent, which would make this entity describe a column the schema does not have and turn
    // ddl-auto=validate from a guard into a start-up failure. 9 and 2 are the legacy NUMERIC(9,2) precision
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48] that schema/cash-account-schema.sql reproduces.
    //
    // The held amount is fixed for the life of the row - a hold is never resized, it is settled or released -
    // so updatable = false, which is also what makes CHECK (settled_amount <= amount) a stable invariant.
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

    // The caller's Idempotency-Key header, one half of the unique constraint described above.
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    /*
     * WHY THIS MAPPING CARRIES @JdbcTypeCode(SqlTypes.CHAR) AND MUST KEEP IT. The column is CHAR(64). A plain
     * String field makes Hibernate expect varchar(64) with JDBC type code VARCHAR, while pgJDBC reports the
     * column as bpchar with type code CHAR. The schema validator accepts a column when the expected code
     * equals the reported code or the expected type text startsWith the reported type name, and
     * "varchar(64)".startsWith("bpchar") is false - so without this annotation ddl-auto=validate fails and
     * the application does not start. Declaring the code directly is the fix; columnDefinition = "char(64)"
     * is not, because it replaces the type text Hibernate validates against without reliably correcting the
     * code, and widening the column to VARCHAR(64) is not available either - AAP 0.6.3 specifies CHAR(64)
     * and schema/cash-account-schema.sql already declares it.
     *
     * PostgreSQL blank-pads bpchar on read. A SHA-256 hex digest is exactly 64 characters, so no padding can
     * arise from a value this class accepts - the factory rejects any other length - but the accessor strips
     * regardless, so a replay comparison can never turn on trailing blanks a shorter value would acquire.
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
     * <p>The returned reservation is transient and in state {@link ReservationState#HELD} with no settled
     * amount. Moving the account's available and reserved balances is the caller's transaction to complete:
     * this factory creates the record of the hold, and {@code ReservationStateMachine} owns the balance
     * effects that must accompany it.</p>
     *
     * <p>Two values are deliberately computed elsewhere. {@code requestHash} is the SHA-256 of the canonical
     * payload defined in AAP 0.7.3, which belongs to {@code institutional/ReservationService} because only
     * the service sees the request as the caller sent it; this entity stores the result and never recomputes
     * it. {@code expiresAt} is derived from {@code cashaccount.reservation.default-ttl} when the caller omits
     * it, and reading configuration is not something a domain type does - the package stays free of Spring
     * and of property lookups.</p>
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
        reservation.expiresAt = expiresAt;
        OffsetDateTime now = OffsetDateTime.now();
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    /*
     * WHY THIS IS PACKAGE-PRIVATE AND MUST STAY THAT WAY. Which (state, command) pairs are legal, which are
     * idempotent no-ops and which are rejected is knowledge the module keeps in exactly one place -
     * ReservationStateMachine, the only type in this package with reason to call this method (AAP 0.6.5).
     * Package-private turns "entities never mutate state outside the state machine" into a compile-time fact:
     * the retail, institutional and migration packages physically cannot reach it, so no second
     * implementation of the hold, settle, release or expiry rules can come into existence. Widening the
     * visibility would give that guarantee away silently, which is why the reason is recorded here.
     *
     * This method applies, it does not judge: it asks nothing about the current state, because the caller has
     * already decided the transition is legal and has computed the balances that must move with it. The
     * settled amount is authoritative rather than merged - passing null clears it - so a release or an expiry
     * leaves the column NULL, which is the "never settled" fact the ledger rows rely on.
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
     * Returns the settled portion, or {@code null} while the reservation has never been settled.
     *
     * <p>The null is the contract, not an oversight, and an {@link java.util.Optional} is deliberately not
     * returned: "never settled" and "settled for 0.00" are different facts - a zero settlement is legal and
     * releases the whole hold - and an entity accessor that wrapped the column would invite callers to treat
     * the two as one.</p>
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
     * Reports whether funds are still held, which is the only state that constrains anything outside itself.
     *
     * <p>A held reservation is what makes a retail {@code PUT} or {@code DELETE} answer
     * 409 RESERVATIONS_OUTSTANDING (AAP 0.6.2) and what makes the loader leave an existing owner untouched
     * with a {@code STATE} variance instead of overwriting a balance that is partly committed elsewhere
     * (AAP 0.6.3).</p>
     */
    public boolean isHeld() {
        return state == ReservationState.HELD;
    }

    /**
     * Reports whether this reservation is overdue as at {@code now} and therefore expirable.
     *
     * <p>True only for a held reservation: the three terminal states are already resolved, and reporting one
     * of them as expirable would let the sweep write a second terminal transition for the same hold. The
     * instant is a parameter rather than a call to the clock so the scheduled sweep and the lazy check a
     * settle or release performs first can judge one reservation against one instant.</p>
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
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    /*
     * WHY THE MUTATOR STAMPS updated_at INSTEAD OF A @PreUpdate CALLBACK: @PreUpdate runs at flush, so every
     * read taken between the transition and the flush - which includes the ReservationResponse the
     * institutional endpoint builds from this instance before its transaction commits - would still carry the
     * previous value. Stamping inside the mutator makes the in-memory entity correct the instant it changes,
     * and the column's DEFAULT now() remains the safety net for a row some other tool inserts.
     */
    private void touch() {
        updatedAt = OffsetDateTime.now();
    }

    /*
     * The service is responsible for producing a usable order reference - HoldRequest already declares
     * @NotBlank @Size(max = 64), so a caller's bad value is rejected as a 400 by bean validation long before
     * this point - which makes anything unusable here a defect in this module rather than a caller error, and
     * IllegalArgumentException the honest report. Checking it at all is what stops a silent
     * DataIntegrityViolationException at flush, rendered as an opaque 500, from being the first sign.
     */
    private static String requireOrderReference(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("orderReference is required");
        }
        String candidate = raw.strip();
        if (candidate.length() > ORDER_REFERENCE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "orderReference must be at most " + ORDER_REFERENCE_MAX_LENGTH + " characters");
        }
        return candidate;
    }

    /*
     * Shape only, and less strict than cash_account.currency deliberately: that column carries
     * CHECK (currency ~ '^[A-Z]{3}$') while this one carries none, because the institutional path never
     * converts and the service's CURRENCY_MISMATCH check against the already-validated account currency is
     * the real guard (AAP 0.7.2). What is enforced here is only that the value exists and fits VARCHAR(8) -
     * the legacy CURRENCYC width [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L49] - so the failure is an
     * explicit 400 rather than a constraint violation surfacing as 500 at flush.
     *
     * Locale.ROOT, never the no-argument toUpperCase(): a Turkish default locale maps "i" to U+0130, which
     * would store a different code on one pod than on another.
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

