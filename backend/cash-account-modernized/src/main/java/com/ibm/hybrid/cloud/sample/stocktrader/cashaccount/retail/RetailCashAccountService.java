package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.ExchangeRateSource;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.fx.ExchangeRateUnavailableException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashReservationRepository;

/** The retail contract surface: the six CASH00 request codes A/Q/U/X/C/D re-expressed over the ledger. */
@Service
public class RetailCashAccountService {

    private static final String ACCEPTED_CURRENCIES_PROPERTY = "cashaccount.fx.accepted-currencies";

    // Broker's own default account currency, which is why this is the default rather than a required value
    // [backend/broker/.../BrokerService.java:L357-L365].
    private static final String BASE_CURRENCY_PROPERTY = "cashaccount.fx.base-currency";

    private static final String DEFAULT_BASE_CURRENCY = "USD";

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    private static final String OWNER_PRIMARY_KEY = "pk_cash_account";

    private static final String OWNER_PRIMARY_KEY_GENERATED_NAME = "cash_account_pkey";

    // A bound rather than a while(true): a cycle in an exception chain must not turn a rejected create into a
    // hung request thread.
    private static final int MAX_CAUSE_DEPTH = 10;

    private final CashAccountRepository accounts;
    private final CashReservationRepository reservations;
    private final LedgerService ledgerService;
    private final ExchangeRateSource exchangeRateSource;
    private final String baseCurrency;
    private final Set<String> acceptedCurrencies;
    private final RetailCashAccountService self;

    /**
     * Container constructor.
     *
     * @param accounts           the only path to {@code cash_account}; no {@code EntityManager} or SQL is used here
     * @param reservations       consulted solely to refuse a write that would strand held funds
     * @param ledgerService      appends the audit row inside this service's own transaction
     * @param exchangeRateSource injected by interface so the migration tooling can replay these operations against
     *                           the staged legacy rate table instead of the live provider
     * @param environment        source of both configured values, each read through {@link Binder}: the currency a
     *                           caller's amount is denominated in ({@code cashaccount.fx.base-currency},
     *                           {@code USD} by default) and the accepted-currency set, a YAML sequence no
     *                           placeholder can bind
     * @param self               this bean through its own proxy
     * @throws IllegalStateException when {@code cashaccount.fx.accepted-currencies} names no usable code - no set
     *                               is compiled into this class to stand in for it - or when
     *                               {@code cashaccount.fx.base-currency} is not one of the codes it names
     */
    // The only constructor, so the bean cannot be assembled by hand: container injection only, with self
    // required, because that proxy is what applies applyRateChangeLocked's @Transactional and lets LedgerService's
    // Propagation.MANDATORY append find a transaction.
    @Autowired
    public RetailCashAccountService(CashAccountRepository accounts, CashReservationRepository reservations,
            LedgerService ledgerService, ExchangeRateSource exchangeRateSource, Environment environment,
            @Lazy RetailCashAccountService self) {

        this.accounts = requireCollaborator(accounts, "CashAccountRepository");
        this.reservations = requireCollaborator(reservations, "CashReservationRepository");
        this.ledgerService = requireCollaborator(ledgerService, "LedgerService");
        this.exchangeRateSource = requireCollaborator(exchangeRateSource, "ExchangeRateSource");
        this.baseCurrency = normalizeCode(baseCurrencyFrom(environment));
        this.acceptedCurrencies = normalizedCodes(acceptedCurrenciesFrom(environment));
        this.self = requireCollaborator(self, "RetailCashAccountService proxy");

        // A base currency outside the accepted set leaves same-currency traffic working while every cross-currency
        // conversion is unserviceable - a half-broken deployment that reaches production unnoticed - so start-up
        // fails instead, the same posture DataSourceGuardConfig takes to JDBC_KIND.
        if (!ISO_4217_CODE.matcher(this.baseCurrency).matches()
                || !this.acceptedCurrencies.contains(this.baseCurrency)) {
            throw new IllegalStateException(
                    "cashaccount.fx.base-currency must be one of cashaccount.fx.accepted-currencies");
        }
    }

    /**
     * Reads an account, replacing legacy request code {@code Q}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L136-L150].
     *
     * @param owner the owner, matched case-insensitively
     * @return the account's current wire shape
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} (400) for an unusable owner,
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND} (404) when no such account exists
     */
    // No ledger row, unlike the legacy, whose WRITE followed the EVALUATE unconditionally and so recorded history
    // for a Q too (CASH00.cbl:L111-L131); those rows are staged in legacy_history for reference and excluded from
    // transaction counts rather than reproduced (AAP 0.4.6).
    @Transactional(readOnly = true)
    public CashAccountResponse read(String owner) {
        String normalizedOwner = requireOwner(owner);
        return CashAccountResponse.from(requireAccount(normalizedOwner, accounts.findByOwner(normalizedOwner)));
    }

    /**
     * Creates an account, replacing legacy request code {@code A}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L151-L162].
     *
     * @param owner    the owner; stored uppercase, as the legacy INSERT stored {@code UPPER(:CUST-NAME-TEXT)}
     *                 (CASH00.cbl:L155)
     * @param balance  the opening available balance; {@code null} is read as zero
     * @param currency the account currency; {@code null} or blank defaults to the configured base currency
     * @return the created account's wire shape, carrying the stored uppercase owner
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER},
     *         {@link CashAccountErrorCode#INVALID_CURRENCY} or {@link CashAccountErrorCode#INVALID_AMOUNT} (400),
     *         {@link CashAccountErrorCode#AMOUNT_OUT_OF_RANGE} (422),
     *         {@link CashAccountErrorCode#ACCOUNT_ALREADY_EXISTS} (409)
     */
    // Deliberate deviation - the response carries the STORED uppercase owner, never the caller's casing, where the
    // legacy was inconsistent: Q answered with the database OWNER (CASH00.cbl:L144) while A echoed CUST-NAME-TEXT
    // as spelled (L158). Safe because broker maps only balance and currency out of the response
    // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L364-L365,
    // L500-L501, L542-L543).
    @Transactional
    public CashAccountResponse create(String owner, BigDecimal balance, String currency) {

        // Every validation precedes the first repository call: the legacy discovered bad input as an SQLCODE from
        // whichever statement failed last, reported through one sign-dropping X(10) field (CASH00.cbl:L104), so a
        // -302 truncation and a -803 duplicate were indistinguishable to the caller (AAP 0.12.3).
        String normalizedOwner = requireOwner(owner);
        String normalizedCurrency = requireCurrency(normalizedOwner, currency);
        Money openingBalance = requireBalance(normalizedOwner, balance);

        if (accounts.existsByOwner(normalizedOwner)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_ALREADY_EXISTS, normalizedOwner);
        }

        CashAccount account = CashAccount.open(normalizedOwner, normalizedCurrency, openingBalance);

        // saveAndFlush, not save: existsByOwner above is not atomic, so two concurrent creates of one owner can
        // both pass it, and flushing here turns the loser's primary-key collision - the modern equivalent of the
        // legacy INSERT's -803 (AAP 0.12.3) - into a failure at this line instead of an opaque one at commit,
        // after the method has returned 200. The collision arises at all because domain/CashAccount declares a
        // nullable @Version, which makes Spring Data INSERT rather than merge; a merge would have overwritten the
        // winner's row.
        try {
            account = accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException cause) {
            // Only the owner primary key is a duplicate account. cash_account's other constraints
            // (cash-account-schema.sql's uq_cash_account_incarnation and three CHECKs) each describe a defect in
            // this service, so reporting one as 409 ACCOUNT_ALREADY_EXISTS would hand the caller a plausible,
            // wrong explanation; anything else is rethrown for ApiExceptionHandler to render as 500 INTERNAL,
            // which AAP 0.12.3 assigns to any other negative SQLCODE.
            if (!isOwnerPrimaryKeyCollision(cause)) {
                throw cause;
            }
            throw CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_ALREADY_EXISTS, normalizedOwner, null,
                    cause);
        }

        // amount is the resulting available balance, not a delta: ACCOUNT_CREATED is an absolute-set event, so the
        // figure it must not lose is the balance the account now holds (AAP 0.6.3).
        ledgerService.append(account, LedgerEventType.ACCOUNT_CREATED, account.availableBalance(),
                LedgerEntry.Source.RETAIL);

        return CashAccountResponse.from(account);
    }

    /**
     * Overwrites an account's balance and currency, replacing legacy request code {@code U}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L164-L183].
     *
     * @param owner    the owner, matched case-insensitively
     * @param balance  the new absolute available balance; {@code null} is read as zero
     * @param currency the new currency; {@code null} or blank defaults to the configured base currency
     * @return the updated account's wire shape
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER},
     *         {@link CashAccountErrorCode#INVALID_CURRENCY} or {@link CashAccountErrorCode#INVALID_AMOUNT} (400),
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND} (404),
     *         {@link CashAccountErrorCode#AMOUNT_OUT_OF_RANGE} (422),
     *         {@link CashAccountErrorCode#RESERVATIONS_OUTSTANDING} (409)
     */
    // Both columns are overwritten because the legacy UPDATE set both - SET BALANCE=:BALANCE, CURRENCYC=:WS-CURRENCY
    // (CASH00.cbl:L176-L177). That is the one operation that may change an account's currency, and it is why credit
    // and debit must not (L229, L262 set the balance alone).
    @Transactional
    public CashAccountResponse update(String owner, BigDecimal balance, String currency) {
        String normalizedOwner = requireOwner(owner);
        String normalizedCurrency = requireCurrency(normalizedOwner, currency);
        Money newBalance = requireBalance(normalizedOwner, balance);

        CashAccount account = requireLockedAccount(normalizedOwner);
        requireNoHeldReservations(normalizedOwner);

        account.overwriteAvailableBalance(newBalance);
        account.changeCurrency(normalizedCurrency);
        accounts.save(account);

        ledgerService.append(account, LedgerEventType.ACCOUNT_UPDATED, account.availableBalance(),
                LedgerEntry.Source.RETAIL);

        return CashAccountResponse.from(account);
    }

    /**
     * Deletes an account, replacing legacy request code {@code X}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L185-L202].
     *
     * @param owner the owner, matched case-insensitively
     * @return the wire shape the account held immediately before deletion
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} (400),
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND} (404),
     *         {@link CashAccountErrorCode#RESERVATIONS_OUTSTANDING} (409)
     */
    // The response is snapshotted before the delete because the contract returns the deleted account's body
    // (AAP 0.6.2) and afterwards there is no entity to read it from. The ACCOUNT_DELETED row then outlives the
    // account, because ledger_entry holds no foreign key to cash_account (AAP 0.6.3) - where the legacy left only
    // a write-only VSAM history nothing ever read back (CASH00.cbl:L126-L131).
    @Transactional
    public CashAccountResponse delete(String owner) {
        String normalizedOwner = requireOwner(owner);

        CashAccount account = requireLockedAccount(normalizedOwner);
        requireNoHeldReservations(normalizedOwner);

        CashAccountResponse deleted = CashAccountResponse.from(account);

        ledgerService.appendAccountDeleted(account, LedgerEntry.Source.RETAIL);
        accounts.delete(account);

        return deleted;
    }

    /**
     * Credits an account, replacing legacy request code {@code C}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L204-L236].
     *
     * @param owner  the owner, matched case-insensitively
     * @param amount the amount to credit, denominated in the configured base currency; zero is legal
     * @return the credited account's wire shape
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} or
     *         {@link CashAccountErrorCode#INVALID_AMOUNT} (400),
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND} (404),
     *         {@link CashAccountErrorCode#AMOUNT_OUT_OF_RANGE} (422),
     *         {@link CashAccountErrorCode#EXCHANGE_RATE_UNAVAILABLE} (503),
     *         {@link CashAccountErrorCode#CONCURRENT_MODIFICATION} (409)
     */
    public CashAccountResponse credit(String owner, BigDecimal amount) {
        return applyRateChange(owner, amount, Money.SIGN_CREDIT, LedgerEventType.CREDIT);
    }

    /**
     * Debits an account, replacing legacy request code {@code D}
     * [backend/cash-account-cobol/COBOL/CASH00.cbl:L238-L269].
     *
     * @param owner  the owner, matched case-insensitively
     * @param amount the amount to debit, denominated in the configured base currency; zero is legal
     * @return the debited account's wire shape
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} or
     *         {@link CashAccountErrorCode#INVALID_AMOUNT} (400),
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND} (404),
     *         {@link CashAccountErrorCode#INSUFFICIENT_FUNDS} or
     *         {@link CashAccountErrorCode#AMOUNT_OUT_OF_RANGE} (422),
     *         {@link CashAccountErrorCode#EXCHANGE_RATE_UNAVAILABLE} (503),
     *         {@link CashAccountErrorCode#CONCURRENT_MODIFICATION} (409)
     */
    public CashAccountResponse debit(String owner, BigDecimal amount) {
        return applyRateChange(owner, amount, Money.SIGN_DEBIT, LedgerEventType.DEBIT);
    }

    /**
     * Applies the locked balance change {@code credit} and {@code debit} have already priced.
     *
     * <p>Public only so that Spring's proxy can apply {@code @Transactional} when {@link #credit} and
     * {@link #debit} call into it, and not part of this service's contract; its parameters arrive validated and
     * normalized, and it performs no exchange-rate lookup.</p>
     *
     * @param owner          the already-normalized owner
     * @param sign           {@link Money#SIGN_CREDIT} or {@link Money#SIGN_DEBIT}
     * @param rate           the base-to-account-currency multiplier, at its natural precision
     * @param quotedCurrency the account currency the rate was quoted against
     * @param amount         the already-validated amount, denominated in the base currency
     * @param eventType      {@link LedgerEventType#CREDIT} or {@link LedgerEventType#DEBIT}
     * @return the account's wire shape after the change
     */
    @Transactional
    public CashAccountResponse applyRateChangeLocked(String owner, int sign, BigDecimal rate, String quotedCurrency,
            Money amount, LedgerEventType eventType) {

        // Re-read under the row lock, which every path takes on the cash_account row before touching any other
        // row: the account may have been deleted or updated between the lock-free pre-read and this point, so the
        // pre-read decides nothing beyond which rate to fetch.
        CashAccount account = requireLockedAccount(owner);

        // The rate was quoted against the currency the account held a moment ago. If a concurrent retail PUT has
        // since changed it (CASH00.cbl:L176-L177 is the only operation that can), applying the stale rate would
        // convert into the wrong currency and be indistinguishable from a correct result afterwards. 409 with
        // Retry-After: 1 asks the caller to repeat the operation, which then prices the new currency.
        if (!account.currency().equals(quotedCurrency)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.CONCURRENT_MODIFICATION, owner);
        }

        Money before = account.availableBalance();

        // The single truncation point: Money keeps the product at full precision, does the signed addition and
        // scales once with RoundingMode.DOWN, as the legacy COMPUTE truncated the whole expression once into the
        // two-decimal WS-CALC (CASH00.cbl:L222 credit, L256 debit, field at L17) - truncating the product first
        // yields 100.00 where stored 100.00, rate 0.03 and amount 0.30 must yield 99.99 (AAP 0.12.5). The
        // multiplicand is the CALLER'S amount, moved through BALANC-RATE (L221, L255); FRANKFURT1.AMOUNT was
        // fetched by both rate SELECTs (L215, L249) and referenced by no arithmetic.
        //
        // Two authorized deviations surface from applyRateChecked and are deliberately uncaught: a negative result
        // is 422 INSUFFICIENT_FUNDS, where the unsigned WS-CALC (L17) with no ON SIZE ERROR committed an
        // overdraft's magnitude as a positive balance, and a result past 9,999,999.99 is 422 AMOUNT_OUT_OF_RANGE,
        // where the same field dropped high-order digits. Either rolls the transaction back, so the balance is
        // unchanged and no ledger row exists.
        Money after = Money.applyRateChecked(before, sign, rate, amount);

        // The write below can refuse a credit as well: CashAccount.overwriteAvailableBalance bounds the available
        // balance PLUS the funds an institutional hold reserved, because reserved funds must keep room to return,
        // and that decision belongs to the entity every balance write funnels through.
        //
        // The currency is deliberately untouched: the legacy credit and debit UPDATEs set BALANCE alone (L229,
        // L262), unlike the update paragraph which set both columns (L176-L177).
        account.overwriteAvailableBalance(after);
        accounts.save(account);

        // The magnitude applied to the available balance, not the amount the caller asked for: ledger_entry.amount
        // is constrained non-negative with its direction implied by event_type, and its currency labels the ACCOUNT
        // currency while the request is denominated in the base currency (AAP 0.6.3), so the requested figure
        // would mislabel the row. A consumer derives any signed delta from consecutive available_after values; the
        // requested amount and the rate are not retained, as the schema has no column for either. The abs() is on
        // that delta and never on the computed balance, which is the legacy behaviour being removed.
        Money applied = Money.of(after.amount().subtract(before.amount()).abs());

        // A zero amount writes its row rather than short-circuiting: legacy C/D with amount zero updated the row,
        // returned SQLCODE 0 and wrote a history record, so the transaction counts a reconciliation compares are
        // equal only if a zero-amount credit or debit is still one ledger row (AAP 0.4.5). Broker skipping
        // lastTrade == 0
        // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L488-L490)
        // is its own choice, not a reason to reject the call.
        ledgerService.append(account, eventType, applied, LedgerEntry.Source.RETAIL);

        return CashAccountResponse.from(account);
    }

    // Credit and debit share one implementation because the two COBOL paragraphs differ only in the + of L222 and
    // the - of L256.
    //
    // The rate is resolved before the transaction opens: the write holds the cash_account row under
    // PESSIMISTIC_WRITE, so an outbound HTTP call inside that window would queue every concurrent operation on the
    // owner behind a third party's latency. The lock-free pre-read costs one SELECT and bounds the lock to local
    // work; applyRateChangeLocked re-reads under the lock and re-checks the currency, so nothing is decided on the
    // unlocked read.
    private CashAccountResponse applyRateChange(String owner, BigDecimal amount, int sign,
            LedgerEventType eventType) {

        String normalizedOwner = requireOwner(owner);
        Money validatedAmount = requireAmount(normalizedOwner, amount);

        // Mirrors the legacy IF SQLCODE = 0 guard (L212 credit, L246 debit), which skipped the rate lookup and the
        // update when the account SELECT found nothing yet still echoed the caller's own amount back as the
        // balance (L104-L108). Here it is an explicit 404 with no echo.
        CashAccount account = requireAccount(normalizedOwner, accounts.findByOwner(normalizedOwner));
        String quotedCurrency = account.currency();

        BigDecimal rate = resolveRate(normalizedOwner, quotedCurrency);

        // Through the proxy, never this.: a self-call bypasses the transaction interceptor, leaving the write
        // non-transactional and making LedgerService's Propagation.MANDATORY append throw. Same
        // outer-non-transactional / inner-transactional shape AAP 0.6.3 mandates for ReservationService.
        return self.applyRateChangeLocked(normalizedOwner, sign, rate, quotedCurrency, validatedAmount, eventType);
    }

    // The same-currency short-circuit is a parity guarantee rather than an optimization - an account in the base
    // currency reconciles exactly against the legacy result and no rate-provider outage can reach an operation
    // that needs no conversion (AAP 0.7.2) - and it is the only bypass: a zero amount still fetches a rate,
    // because the legacy performed its rate SELECT unconditionally inside the SQLCODE = 0 branch (L214-L219,
    // L248-L253).
    //
    // Deliberate deviation - 503 EXCHANGE_RATE_UNAVAILABLE with Retry-After: 5 where the legacy reported silent
    // success over undefined arithmetic: a missing FRANKFURT1 row left SQLCODE 100 on the inner SELECT, the
    // following UPDATE reset it to 0, and the balance was committed from an uninitialized RATES host variable
    // (CASH00.cbl:L214-L231; backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L22 declares RATES with no VALUE
    // clause). Failing here, before the transaction opens, leaves the balance unchanged and writes no ledger row
    // because there is nothing yet to roll back. The mapping lives in this service because
    // ExchangeRateUnavailableException carries no status of its own and ApiExceptionHandler does not import it,
    // while a CashAccountException from the rate source - INVALID_CURRENCY - propagates untouched, so a rejected
    // input stays a 400 rather than a transient outage inviting a retry.
    private BigDecimal resolveRate(String owner, String accountCurrency) {
        if (baseCurrency.equals(accountCurrency)) {
            return BigDecimal.ONE;
        }
        try {
            return exchangeRateSource.rate(baseCurrency, accountCurrency);
        } catch (ExchangeRateUnavailableException cause) {
            throw CashAccountException.forOwner(CashAccountErrorCode.EXCHANGE_RATE_UNAVAILABLE, owner, null, cause);
        }
    }

    // Owners up to 32 characters, the width of the DB2 column (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9).
    // The legacy COMMAREA name field was X(15) (CASH00.cbl:L55), so a longer owner was silently truncated and two
    // owners sharing a 15-character prefix addressed one account; a longer owner is now 400 INVALID_OWNER.
    // Matching stays case-insensitive and storage uppercase, as the legacy LOWER()/UPPER() predicates and UPPER()
    // insert already amounted to (L141, L155, L178).
    private String requireOwner(String raw) {
        return OwnerNormalizer.normalize(raw);
    }

    // Defaulting an absent currency to the base currency is preserved behaviour, not an invention: broker already
    // substitutes USD before it calls
    // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L83,
    // L357-L365), and that same property IS broker's default account currency, so no second key is introduced
    // (AAP 0.7.2).
    //
    // The accepted set enforces ESTATE acceptance only - the allowed_currencies CHECK of
    // infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7 - and never promises
    // convertibility: the configured rate provider serves a subset of it, BGN among the codes it may omit. An
    // accepted code the provider does not serve is stored here and fails the next credit or debit as
    // 503 EXCHANGE_RATE_UNAVAILABLE with the balance untouched and no ledger row (AAP 0.9.1), never as
    // 400 INVALID_CURRENCY.
    private String requireCurrency(String owner, String raw) {
        if (raw == null || raw.isBlank()) {
            return baseCurrency;
        }
        String candidate = normalizeCode(raw);
        if (!ISO_4217_CODE.matcher(candidate).matches() || !acceptedCurrencies.contains(candidate)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        return candidate;
    }

    // Money owns the scale, the rounding and both bounds; duplicating any of them here would create a second
    // authority on what a legal amount is. Only the null case is decided here, because Money cannot name an owner
    // in its exception.
    private Money requireAmount(String owner, BigDecimal raw) {
        if (raw == null) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        return Money.of(raw);
    }

    // An absent balance on a create or an update is zero rather than an error: the legacy COMMAREA field
    // WS-BALANCE PIC 9(7)V99 (CASH00.cbl:L56) could not be null, so an unset field arrived as zeros and the
    // INSERT stored 0.00. A null in the JSON body is the same caller intent.
    private Money requireBalance(String owner, BigDecimal raw) {
        return raw == null ? Money.ZERO : requireAmount(owner, raw);
    }

    private CashAccount requireAccount(String owner, Optional<CashAccount> found) {
        return found.orElseThrow(
                () -> CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_NOT_FOUND, owner));
    }

    private CashAccount requireLockedAccount(String owner) {
        return requireAccount(owner, accounts.findByOwnerForUpdate(owner));
    }

    // New behaviour, since the legacy had one mutable balance and no held funds: an absolute overwrite or a delete
    // while funds are held would strand a reservation against a balance that no longer backs it, so both are
    // refused with 409. Retail reports the AVAILABLE balance, which equals the total whenever nothing is held, so
    // parity with the single-balance program stays exact (AAP 0.6.2).
    //
    // The state is read literally - an overdue HELD reservation is not expired here - because that transition
    // belongs to the expiry sweep and the institutional service, which this package must not import (AAP 0.8.2).
    private void requireNoHeldReservations(String owner) {
        if (reservations.existsByOwnerAndState(owner, ReservationState.HELD)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.RESERVATIONS_OUTSTANDING, owner);
        }
    }

    // The constraint name is taken from the Hibernate ConstraintViolationException inside the translated
    // exception, the only place it survives as data rather than prose, and the translated message is read only as
    // a fallback for a provider that reports no name at all. Both spellings denote one constraint:
    // pk_cash_account is what cash-account-schema.sql declares, cash_account_pkey what PostgreSQL generates for
    // an unnamed primary key, which a schema applied by another tool would carry. An unrecognized violation is
    // rethrown rather than guessed at.
    private static boolean isOwnerPrimaryKeyCollision(DataIntegrityViolationException violation) {
        Throwable cause = violation;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && namesOwnerPrimaryKey(constraintViolation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return namesOwnerPrimaryKey(violation.getMessage());
    }

    private static boolean namesOwnerPrimaryKey(String constraintNameOrMessage) {
        if (constraintNameOrMessage == null) {
            return false;
        }
        String candidate = constraintNameOrMessage.toLowerCase(Locale.ROOT);
        return candidate.contains(OWNER_PRIMARY_KEY) || candidate.contains(OWNER_PRIMARY_KEY_GENERATED_NAME);
    }

    // Locale.ROOT, never the default locale: a Turkish-locale uppercase turns the "i" of ILS into a dotted capital
    // and silently corrupts the code.
    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    // No shipped copy of the set to fall back to: application.yml's cashaccount.fx.accepted-currencies is its
    // single authority, and a compiled-in copy would be the one actually enforced wherever the property failed to
    // bind, so a deployment that narrowed the set would keep accepting the codes it had just excluded with
    // nothing to announce it. An absent or empty value fails start-up naming the property instead.
    private static Set<String> normalizedCodes(Collection<String> codes) {
        Set<String> normalized = new LinkedHashSet<>();
        if (codes != null) {
            for (String code : codes) {
                String candidate = normalizeCode(code);
                if (!candidate.isEmpty()) {
                    normalized.add(candidate);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    ACCEPTED_CURRENCIES_PROPERTY + " must name at least one currency code");
        }
        return Set.copyOf(normalized);
    }

    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so a base currency written as #{...} would execute while this service was being created. Binder
    // resolves ${...} and converts, evaluating nothing, so an unusable value stays text and is refused by the
    // start-up check in the constructor. A non-configurable Environment exposes no property sources, so it yields
    // the documented USD default exactly as an unset key does.
    private static String baseCurrencyFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return DEFAULT_BASE_CURRENCY;
        }
        return Binder.get(environment)
                .bind(BASE_CURRENCY_PROPERTY, Bindable.of(String.class))
                .orElse(DEFAULT_BASE_CURRENCY);
    }

    // Binder rather than @Value because the property is a YAML sequence, which @Value cannot bind; Binder also
    // accepts the comma-separated scalar form, so relaxed binding through an environment variable keeps working.
    // Reading the Environment rather than injecting the typed properties object keeps this package free of
    // config, which wires everything and is depended on by nothing (AAP 0.8.2).
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            throw new IllegalStateException(ACCEPTED_CURRENCIES_PROPERTY
                    + " cannot be read from a non-configurable Environment");
        }
        return normalizedCodes(Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(null));
    }

    private static <T> T requireCollaborator(T collaborator, String name) {
        if (collaborator == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return collaborator;
    }
}
