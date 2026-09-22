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

/*
 * WHY TWO BALANCE COLUMNS WHERE THE LEGACY HAD ONE. STOCKTRD.CASHACCOUNTY carried a single mutable
 * BALANCE NUMERIC(9,2) per OWNER CHAR(32) [backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L52;
 * backend/cash-account-cobol/COBOL/DCLCASH.cpy:L8-L12], so funds an institutional caller intended to commit
 * later were indistinguishable from funds a retail debit could spend now: the only way to reserve money was
 * to debit it, which destroyed the information needed to release it again. Splitting the one column into
 * available_balance and reserved_balance is what makes the reservation lifecycle expressible at all.
 * Retail parity survives the split because the retail wire field reports available_balance, which equals the
 * total whenever no reservation is outstanding - the only state the legacy program could ever be in.
 *
 * WHY THERE IS NO ASSOCIATION TO CashReservation OR LedgerEntry - no @OneToMany, no mappedBy. Both of those
 * tables retain their rows as the audit record after a retail DELETE of the account, so neither carries a
 * foreign key to this table (schema/cash-account-schema.sql states the same reason above cash_reservation);
 * an association here would imply the key that deliberately does not exist, and ddl-auto=validate would then
 * demand a join column the schema script never creates.
 *
 * WHY @Table NAMES THE TABLE AND NOTHING ELSE. No schema is declared, so every statement resolves through
 * the connection's search path and the runbook's rehearsal override (spring.datasource.hikari.schema=
 * cash_account_rehearsal) reaches these same entities without a code change. Indexes and constraints are
 * declared once, in schema/cash-account-schema.sql; repeating them in annotations would create a second
 * source of truth that ddl-auto=validate does not even read.
 */
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

    // WHY AN INCARNATION IDENTIFIER EXISTS AT ALL (AAP 0.11.1). A retail DELETE followed by a POST of the same
    // owner produces a new account that happens to reuse a primary key whose reservations and ledger rows were
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

    // WHY precision AND scale ARE BOTH DECLARED on the two monetary columns: Hibernate applies its own default
    // scale when the mapping is silent, which would make the entity describe a column the schema does not have
    // and turn ddl-auto=validate from a guard into a source of start-up failures. The declared 9 and 2 are the
    // legacy NUMERIC(9,2) precision [DB2DDL.jcl:L48] that schema/cash-account-schema.sql reproduces.
    //
    // The fields are BigDecimal rather than Money so the mapping needs no AttributeConverter; the accessors
    // below hand out Money, which keeps Money the module's only money arithmetic type.
    @Column(name = "available_balance", precision = 9, scale = 2, nullable = false)
    private BigDecimal availableBalance;

    @Column(name = "reserved_balance", precision = 9, scale = 2, nullable = false)
    private BigDecimal reservedBalance;

    // A NULLABLE Long, and that nullability is load-bearing: it is how this entity declares that it has not been
    // written yet. Spring Data decides between EntityManager.persist and EntityManager.merge from the version
    // attribute, and it consults the attribute only when the attribute can be null - a PRIMITIVE version makes
    // JpaMetamodelEntityInformation fall back to "is the @Id null?", and the @Id here is the assigned owner, which
    // is never null. A fresh account would therefore be judged already-persisted and every save() would MERGE:
    // Hibernate would SELECT the row, find the one a concurrent create had just committed, and UPDATE it - losing
    // that account's balance and currency and answering 200 - instead of INSERTing and raising the primary-key
    // violation that RetailCashAccountService.create translates into 409 ACCOUNT_ALREADY_EXISTS - the modern
    // reading of the legacy INSERT's -803 against PRIMARY KEY(owner)
    // [backend/cash-account-cobol/COBOL/CASH00.cbl:L153-L155;
    // backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L50; AAP 0.12.3]. Null until written keeps creation an
    // INSERT, which is what makes the owner primary key - and not the non-atomic existsByOwner pre-check - the
    // authority on whether an account already exists.
    //
    // Nothing is given up by moving off the primitive: the column is NOT NULL DEFAULT 0, so a row inserted by psql
    // loads with 0 exactly as a row inserted by this service does, and the two remain indistinguishable to the
    // version check. Optimistic locking backs the pessimistic account lock taken by
    // CashAccountRepository.findByOwnerForUpdate - a conflict that still slips through surfaces as
    // 409 CONCURRENT_MODIFICATION rather than a lost update.
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
     * <p>Every write through this entity bounds the pair - {@link #overwriteAvailableBalance} and
     * {@link #moveBalances} both refuse a result whose {@code available + reserved} exceeds
     * {@link Money#MAX_VALUE} - so for any row the application created this sum is a representable amount,
     * and a held reservation's funds always have room to come back.</p>
     *
     * <p>The return type stays {@link BigDecimal} rather than {@link Money} because a read must not fail on a
     * row the application did not write: each column is independently {@code NUMERIC(9,2)}, so a row inserted
     * or corrected with {@code psql} - or stored before the write guard existed - can still hold a pair that
     * sums past the ceiling, and {@link Money#plus} would raise AMOUNT_OUT_OF_RANGE on what is only a query.
     * The sum is never stored, so no column has to hold it.</p>
     */
    public BigDecimal totalBalance() {
        // Both operands are already at scale 2, so this setScale discards nothing and cannot round; it is here
        // so the returned value serializes as 0.00 rather than as 0 for a zero-balance account.
        return availableBalance.add(reservedBalance).setScale(Money.SCALE, Money.ROUNDING);
    }

    /**
     * The optimistic-locking version, {@code null} until the account has been written.
     *
     * <p>It returns the field verbatim and <strong>must keep doing so</strong>. Spring Data reads the version
     * through property access when it decides whether to insert or to merge an entity, and this accessor is what
     * it finds; coalescing the unsaved {@code null} to {@code 0} here would report every new account as already
     * stored and send its creation through a merge - the very lost-update path the field's comment describes. The
     * value is nullable for the same reason the field is, and a caller that wants the stored number should read it
     * off an account it loaded.</p>
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

    /**
     * Sets the available balance to an absolute value, leaving the reserved balance untouched.
     *
     * <p>This is the legacy write, preserved: the update paragraph overwrote BALANCE outright
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L172-L177], and credit and debit computed a new absolute
     * balance before storing it the same way. Reserved funds are out of its reach by construction, so a retail
     * write can neither spend nor disturb money an institutional caller is holding.</p>
     *
     * @param newAvailableBalance the new absolute available balance
     * @throws CashAccountException {@link CashAccountErrorCode#AMOUNT_OUT_OF_RANGE} when this balance plus the
     *         funds already reserved would leave the representable range
     */
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
     * Sets the currency, accepting surrounding blanks or lower case and storing the canonical code.
     *
     * <p>Refusing a currency change while a reservation is held (409 RESERVATIONS_OUTSTANDING) is the service's
     * decision, not this method's: the entity carries no association to cash_reservation and so cannot see the
     * held rows it would have to judge.</p>
     */
    public void changeCurrency(String newCurrency) {
        this.currency = normalizeCurrency(newCurrency, owner);
        touch();
    }

    /*
     * WHY THIS IS PACKAGE-PRIVATE AND MUST STAY THAT WAY. Moving both balances at once is the mechanics of a
     * reservation, and the module keeps those mechanics in exactly one place - ReservationStateMachine, the only
     * type in this package that has reason to call it. Package-private makes that a compile-time fact rather
     * than a convention: retail, institutional and migration code sits in other packages and physically cannot
     * reach this method, so no second implementation of the hold, settle, release or expiry balance effects can
     * come into existence. Widening it to public would silently give that guarantee away, which is why it is
     * documented here rather than left to be discovered.
     *
     * Both values arrive as Money, so each has already passed Money's scale, floor and ceiling rules; the
     * invariant that the pair is conserved across a transition belongs to the state machine that computed them.
     * The pair's own ceiling is checked here rather than there, because it is the one rule every balance write
     * shares and a second copy of it would be free to disagree.
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
     * WHY THE MUTATORS STAMP updated_at INSTEAD OF A @PreUpdate CALLBACK: @PreUpdate runs at flush, so a read
     * taken between a mutation and the flush - which is every read in the same transaction, and the audit
     * immediacy the ledger promises depends on such reads - would still see the previous value. Stamping inside
     * the mutator makes the in-memory entity correct the instant it changes, and the column's DEFAULT now()
     * remains the safety net for a row some other tool inserts.
     */
    private void touch() {
        updatedAt = OffsetDateTime.now();
    }

    /*
     * THE ONE CEILING EVERY BALANCE WRITE PASSES, AND WHY IT BOUNDS THE PAIR RATHER THAN EACH COLUMN. Money
     * already bounds each value it carries to the legacy result field's range - WS-CALC is pic 9(7)V99 with no
     * S in its picture [backend/cash-account-cobol/COBOL/CASH00.cbl:L17] - and the two columns are
     * independently NUMERIC(9,2), so nothing below this method would refuse a pair that sums past that range.
     * Such a pair is not a legal state of an account: reserved funds must always be able to come home, and a
     * settle, release or expiry hands the held amount back onto the available balance through Money.plus. Let
     * a retail credit fill the available side while a hold is outstanding and that credit-back has nowhere to
     * land - the reservation can never reach a terminal state, the money stays in reserved_balance for good,
     * and retail PUT/DELETE answer RESERVATIONS_OUTSTANDING indefinitely. Bounding available + reserved on
     * every write is therefore what keeps HELD's terminal states reachable at all (AAP 0.6.3), and it lives
     * here because this entity is the single point both the absolute write and the reservation write funnel
     * through: retail create/update/credit/debit, the migration loader and all four reservation transitions.
     *
     * WHY 422 AND NOT 400: the request is well formed and nothing about the caller's input is wrong - it is
     * the resulting balance that cannot be represented, which is exactly what AMOUNT_OUT_OF_RANGE states
     * ("the result leaves 0.00 ... 9999999.99", AAP 0.6.2). The owner is attached because it is known here and
     * not inside Money, and ApiError.owner is what tells an operator whose account refused the write.
     *
     * The comparison is against Money.MAX_VALUE, never a literal: widening the ceiling for institutional
     * volumes is a recorded open item - one DDL change plus that constant (AAP 0.11.2) - and stays surgical
     * only while the number exists in one place. open() needs no check of its own: the reserved balance starts
     * at zero there and the opening balance is already a bounded Money.
     *
     * A null operand means an entity that reached a mutator without passing open() or a JPA load, since
     * reserved_balance is NOT NULL in the schema; that is a defect in this module and is reported as one
     * rather than dereferenced.
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
     * CHECK (currency ~ '^[A-Z]{3}$') accepts. Rejecting here turns a padded or lower-case value into
     * 400 INVALID_CURRENCY at the point it is set rather than a constraint violation rendered as 500 at flush.
     * Whether the code is one the exchange-rate provider serves is policy and belongs to the service layer with
     * cashaccount.fx.accepted-currencies, so this method must not consult that list.
     *
     * Locale.ROOT, never the no-argument toUpperCase(): a Turkish default locale maps "i" to U+0130, which would
     * fail the DDL check on one pod and pass on another. The digits are walked rather than matched with a regex
     * because the check runs on the write path of every account and needs no pattern machinery.
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
