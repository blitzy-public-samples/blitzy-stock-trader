package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;

/** The owner's cash position: what is spendable now, what is held against a reservation, and since when. */
@Entity
@Table(name = "cash_account")
public class CashAccount {

    // The natural key, not a surrogate: the legacy primary key was the owner itself
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L50], every caller addresses the account by owner, and
    // the reconciliation join is on that value. updatable = false because renaming an owner would silently
    // re-point every retained reservation and ledger row at a different identity; a rename is a delete and a
    // create, which is exactly what the incarnation below then records.
    @Id
    @Column(name = "owner", length = 32, nullable = false, updatable = false)
    private String owner;

    // An incarnation identifier exists at all (AAP 0.11.1) because a retail DELETE followed by a POST of the
    // same owner produces a new account reusing a primary key whose reservations and ledger rows were
    // deliberately retained. Hold idempotency is guarded by UNIQUE (incarnation_id, idempotency_key) on
    // cash_reservation, so scoping the key by the incarnation is what stops an Idempotency-Key from the owner's
    // previous life from replaying a reservation that reserved nothing in the new account: that request is
    // 422 IDEMPOTENCY_KEY_REUSED instead. Every create issues a fresh value, and nothing may ever update one -
    // hence updatable = false. The unique flag is documentation of the DDL's uq_cash_account_incarnation
    // constraint, which is the guard that actually enforces it; no code path depends on this annotation.
    @Column(name = "incarnation_id", nullable = false, updatable = false, unique = true)
    private UUID incarnationId;

    // VARCHAR(8) is the legacy CURRENCYC CHAR(8) width [DB2DDL.jcl:L49; DCLCASH.cpy:L11] kept so a migrated
    // value fits unaltered, while the DDL's CHECK (currency ~ '^[A-Z]{3}$') and normalizeCurrency below hold
    // stored values to a three-letter code. Membership of the accepted currency set is a service-layer concern
    // (cashaccount.fx.accepted-currencies), deliberately not enforced here: this entity knows shape, not policy.
    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    // Two columns where the legacy had one: STOCKTRD.CASHACCOUNTY carried a single mutable BALANCE per OWNER
    // [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L52; backend/cash-account-cobol/COBOL/DCLCASH.cpy:L8-L12],
    // so the only way to reserve funds was to debit them, which destroyed the information needed to release
    // them again. Retail parity survives the split because the retail wire field reports available_balance,
    // which equals the total whenever no reservation is outstanding - the only state the legacy could be in -
    // and a retail write reaches available_balance alone, as the legacy update paragraph overwrote BALANCE
    // outright [backend/cash-account-cobol/COBOL/CASH00.cbl:L172-L177].
    //
    // precision AND scale are both declared because Hibernate substitutes its own default scale when the
    // mapping is silent, which would make the entity describe a column the schema does not have and turn
    // ddl-auto=validate from a guard into a start-up failure. The declared 9 and 2 are the legacy NUMERIC(9,2)
    // precision [DB2DDL.jcl:L48] that schema/cash-account-schema.sql reproduces. The fields are BigDecimal
    // rather than Money so the mapping needs no AttributeConverter; the accessors below hand out Money.
    @Column(name = "available_balance", precision = 9, scale = 2, nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "reserved_balance", precision = 9, scale = 2, nullable = false)
    private BigDecimal reservedBalance;

    // The nullability is load-bearing: it is how the entity declares it has not been written yet. Spring Data
    // picks persist over merge from the version attribute and consults it only when it can be null - a
    // primitive makes JpaMetamodelEntityInformation fall back to "is the @Id null?", and the @Id here is the
    // assigned owner, which never is. Every create would then MERGE, silently UPDATING the row a concurrent
    // create had just committed instead of raising the primary-key violation that becomes
    // 409 ACCOUNT_ALREADY_EXISTS, the modern reading of the legacy INSERT's -803 against PRIMARY KEY(owner)
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L153-L155;
    // backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L50; AAP 0.12.3]. Nothing is given up: the column is
    // NOT NULL DEFAULT 0, so a row inserted by psql is indistinguishable to the version check, and optimistic
    // locking backs the pessimistic lock of CashAccountRepository.findByOwnerForUpdate, a conflict through it
    // surfacing as 409 CONCURRENT_MODIFICATION rather than a lost update.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Required by JPA; application code opens an account through {@link #open(String, String, Money)}. */
    protected CashAccount() {
    }

    /**
     * Opens a new account for {@code owner} in {@code currency} with {@code openingBalance} available.
     *
     * @param owner the owner as it arrived from a caller, an export row or a replay stream; normalized here
     * @param currency a three-letter ISO code, accepted with surrounding blanks or in lower case
     * @param openingBalance the opening available balance; the reserved balance always starts at zero
     * @return a transient account carrying a fresh incarnation identifier
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} for an unusable owner,
     *         {@link CashAccountErrorCode#INVALID_CURRENCY} for a code that is not three letters
     */
    public static CashAccount open(String owner, String currency, Money openingBalance) {
        // A null Money is a programming error rather than a caller condition - the service builds it from
        // validated input - so it is reported the way Money.applyRateChecked reports one, which the exception
        // handler's catch-all renders as 500 INTERNAL instead of dressing a bug up as a plausible 4xx.
        if (openingBalance == null) {
            throw new IllegalArgumentException("openingBalance is required");
        }
        CashAccount account = new CashAccount();
        account.owner = OwnerNormalizer.normalize(owner);
        account.incarnationId = UUID.randomUUID();
        account.currency = normalizeCurrency(currency, account.owner);
        account.availableBalance = openingBalance.amount();
        account.reservedBalance = Money.ZERO.amount();
        OffsetDateTime now = OffsetDateTime.now();
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public String owner() {
        return owner;
    }

    public UUID incarnationId() {
        return incarnationId;
    }

    public String currency() {
        return currency;
    }

    // Money.of cannot fail on a value read back from this column: NUMERIC(9,2) cannot hold a magnitude past
    // Money.MAX_VALUE, and the DDL's CHECK (available_balance >= 0) excludes the negative case.
    public Money availableBalance() {
        return Money.of(availableBalance);
    }

    public Money reservedBalance() {
        return Money.of(reservedBalance);
    }

    /**
     * Returns available plus reserved funds, the figure the institutional account view reports.
     *
     * <p>The return type is {@link BigDecimal} rather than {@link Money} because a read must not fail on a
     * row the application did not write: each column is independently {@code NUMERIC(9,2)}, so a row inserted
     * with {@code psql} can hold a pair that sums past the ceiling, and {@link Money#plus} would raise
     * AMOUNT_OUT_OF_RANGE on what is only a query. The sum is never stored, so no column has to hold it.</p>
     *
     * @return the total balance at scale 2, which for any row the application wrote is representable
     */
    public BigDecimal totalBalance() {
        // Both operands are already at scale 2, so this setScale discards nothing and cannot round; it is here
        // so the returned value serializes as 0.00 rather than as 0 for a zero-balance account.
        return availableBalance.add(reservedBalance).setScale(Money.SCALE, Money.ROUNDING);
    }

    /**
     * The optimistic-locking version, {@code null} until the account has been written.
     *
     * <p>It returns the field verbatim and <strong>must keep doing so</strong>: Spring Data reads the version
     * through property access when it decides between insert and merge, so coalescing the unsaved
     * {@code null} to {@code 0} here would send every creation through the lost-update path the field's own
     * comment describes.</p>
     *
     * @return the stored version, or {@code null} for an account that has never been written
     */
    public Long version() {
        return version;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    public void overwriteAvailableBalance(Money newAvailableBalance) {
        if (newAvailableBalance == null) {
            throw new IllegalArgumentException("newAvailableBalance is required");
        }
        // The pair is judged even though only one side moves: the reserved balance this write leaves alone is
        // exactly what the returning funds of a held reservation will need room for. The stored field is read
        // rather than reservedBalance(), so a row written outside the application is judged by the check below
        // instead of failing earlier inside Money.of.
        requireRepresentablePair(owner, newAvailableBalance.amount(), reservedBalance);
        this.availableBalance = newAvailableBalance.amount();
        touch();
    }

    /**
     * Refusing a currency change while a reservation is held (409 RESERVATIONS_OUTSTANDING) is the service's
     * decision, not this method's: the entity carries no association to cash_reservation and so cannot see
     * the held rows it would have to judge.
     *
     * @param newCurrency the new currency, accepted with surrounding blanks or in lower case
     */
    public void changeCurrency(String newCurrency) {
        this.currency = normalizeCurrency(newCurrency, owner);
        touch();
    }

    /*
     * Package-private and must stay that way: moving both balances at once is the mechanics of a reservation,
     * and ReservationStateMachine is the one type the module lets know them (AAP 0.6.5). Retail,
     * institutional and migration code sits in other packages and so physically cannot reach this method,
     * which makes "no second implementation of the hold, settle, release and expiry balance effects" a
     * compile-time fact rather than a convention. Conserving the pair across a transition belongs to the
     * state machine that computed these values; the pair's ceiling is checked here, because it is the one
     * rule every balance write shares and a second copy of it would be free to disagree.
     */
    void moveBalances(Money newAvailableBalance, Money newReservedBalance) {
        if (newAvailableBalance == null || newReservedBalance == null) {
            throw new IllegalArgumentException("newAvailableBalance and newReservedBalance are required");
        }
        requireRepresentablePair(owner, newAvailableBalance.amount(), newReservedBalance.amount());
        this.availableBalance = newAvailableBalance.amount();
        this.reservedBalance = newReservedBalance.amount();
        touch();
    }

    /*
     * A safety net for rows created outside the application - the DDL's DEFAULT now() covers a psql insert, and
     * this covers an entity persisted without the factory - never the primary mechanism: the timestamps are
     * stamped by the factory and by touch() instead.
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
     * The mutators stamp updated_at instead of a @PreUpdate callback, which runs at flush, so a read taken
     * between a mutation and the flush - which is every read in the same transaction, and the audit
     * immediacy the ledger promises depends on such reads - would still see the previous value. Stamping inside
     * the mutator makes the in-memory entity correct the instant it changes, and the column's DEFAULT now()
     * remains the safety net for a row some other tool inserts.
     */
    private void touch() {
        updatedAt = OffsetDateTime.now();
    }

    /*
     * The ceiling bounds the pair rather than each column, because Money already bounds each value to the
     * legacy result field's range [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] while the two columns are
     * independently NUMERIC(9,2). A pair that sums past that range is not a legal account state: a settle,
     * release or expiry hands the held amount back onto the available balance through Money.plus, so a retail
     * credit that filled the available side while a hold was outstanding would leave the credit-back nowhere
     * to land - reserved_balance stuck for good and retail PUT/DELETE answering RESERVATIONS_OUTSTANDING
     * indefinitely. Bounding the pair on every write is what keeps HELD's terminal states reachable
     * (AAP 0.6.3), and it lives here because this entity is the one point retail create/update/credit/debit,
     * the migration loader and all four reservation transitions funnel through.
     *
     * 422 and not 400: the request is well formed and it is the resulting balance that cannot be represented.
     * The owner is attached because it is known here and not inside Money, and ApiError.owner is what tells
     * an operator whose account refused the write. The comparison is against Money.MAX_VALUE and never a
     * literal, since widening the ceiling for institutional volumes is a recorded open item of one DDL change
     * plus that constant (AAP 0.11.2). A null operand means an entity that reached a mutator without passing
     * open() or a JPA load, which is a defect in this module and is reported as one.
     */
    private static void requireRepresentablePair(String owner, BigDecimal newAvailableBalance,
            BigDecimal newReservedBalance) {
        if (newAvailableBalance == null || newReservedBalance == null) {
            throw new IllegalArgumentException("both balances are required to judge the account ceiling");
        }
        if (newAvailableBalance.add(newReservedBalance).compareTo(Money.MAX_VALUE) > 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.AMOUNT_OUT_OF_RANGE, owner);
        }
    }

    /*
     * Shape only, deliberately: three letters after stripping and upper-casing, which is what the DDL's
     * ck_cash_account_currency_iso accepts, so a padded or lower-case value becomes 400 INVALID_CURRENCY
     * where it is set rather than a constraint violation rendered as 500 at flush. Whether the code is one
     * the provider serves is policy and belongs to the service layer's cashaccount.fx.accepted-currencies
     * (AAP 0.7.2), so this method must not consult that list. Locale.ROOT, never the no-argument
     * toUpperCase(): a Turkish default locale maps "i" to U+0130, failing the DDL check on one pod only.
     */
    private static String normalizeCurrency(String raw, String owner) {
        if (raw == null) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        if (candidate.length() != 3) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        for (int index = 0; index < candidate.length(); index++) {
            char letter = candidate.charAt(index);
            if (letter < 'A' || letter > 'Z') {
                throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
            }
        }
        return candidate;
    }

    /*
     * Identity is the owner, the natural primary key, and never a balance: two references to the same account
     * must stay equal across every credit, debit, hold and release, and a value-based equals would make an
     * entity stop matching its own earlier self mid-transaction. instanceof rather than getClass() comparison so
     * a Hibernate proxy - a generated subclass - still compares equal to the loaded instance it stands for.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CashAccount that)) {
            return false;
        }
        return owner != null && owner.equals(that.owner);
    }

    @Override
    public int hashCode() {
        return owner == null ? 0 : owner.hashCode();
    }

    /*
     * Balances are excluded on purpose: this string reaches logs and exception context, and a customer's cash
     * position is not log material. Owner and currency identify the row for anyone reading a trace.
     */
    @Override
    public String toString() {
        return "CashAccount{owner=" + owner + ", currency=" + currency + '}';
    }
}
