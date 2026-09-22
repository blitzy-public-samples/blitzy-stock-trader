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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.retail;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    // The accepted set as shipped, so a context that binds no property sequence still refuses a code this service
    // could never convert rather than storing it and discovering that later. Same 31 codes the estate already
    // enforces through its allowed_currencies CHECK
    // (infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L7), which is also the set the rate
    // provider serves.
    private static final Set<String> DEFAULT_ACCEPTED_CURRENCIES = Set.of(
            "AUD", "BGN", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "EUR", "GBP", "HKD", "HUF", "IDR", "ILS", "INR",
            "ISK", "JPY", "KRW", "MXN", "MYR", "NOK", "NZD", "PHP", "PLN", "RON", "SEK", "SGD", "THB", "TRY", "USD",
            "ZAR");

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
     * @param baseCurrency       the currency a caller's amount is denominated in, {@code USD} by default
     * @param environment        source of the accepted-currency set, read through {@link Binder} because the
     *                           property is written as a YAML sequence, which {@code @Value} cannot bind
     * @param self               this bean through its proxy, needed because {@code credit} and {@code debit} resolve
     *                           an exchange rate before their transaction opens and then call into it
     */
    @Autowired
    public RetailCashAccountService(CashAccountRepository accounts, CashReservationRepository reservations,
            LedgerService ledgerService, ExchangeRateSource exchangeRateSource,
            @Value("${cashaccount.fx.base-currency:USD}") String baseCurrency, Environment environment,
            @Lazy RetailCashAccountService self) {

        this(accounts, reservations, ledgerService, exchangeRateSource, baseCurrency,
                acceptedCurrenciesFrom(environment), self);
    }

    /** For a caller that assembles the collaborators itself and holds no {@link Environment}. */
    public RetailCashAccountService(CashAccountRepository accounts, CashReservationRepository reservations,
            LedgerService ledgerService, ExchangeRateSource exchangeRateSource, String baseCurrency,
            Collection<String> acceptedCurrencies, RetailCashAccountService self) {

        this.accounts = requireCollaborator(accounts, "CashAccountRepository");
        this.reservations = requireCollaborator(reservations, "CashReservationRepository");
        this.ledgerService = requireCollaborator(ledgerService, "LedgerService");
        this.exchangeRateSource = requireCollaborator(exchangeRateSource, "ExchangeRateSource");
        this.baseCurrency = normalizeCode(baseCurrency);
        this.acceptedCurrencies = normalizedCodes(acceptedCurrencies);
        this.self = self == null ? this : self;

        // A base currency outside the accepted set would make every cross-currency conversion unserviceable while
        // leaving same-currency traffic working, which is the kind of half-broken deployment that reaches production
        // unnoticed. Failing at start-up instead is the same posture DataSourceGuardConfig takes to JDBC_KIND.
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
    // No ledger row: the ledger records state changes and a read changes nothing. The legacy wrote a history
    // record for every request including Q, because the WRITE followed the EVALUATE unconditionally
    // (CASH00.cbl:L111-L131); those rows are staged in legacy_history for reference and excluded from transaction
    // counts rather than reproduced (AAP 0.4.6).
    //
    // findByOwner rather than findByOwnerForUpdate: a read takes no row lock, so concurrent reads of one owner
    // never queue behind each other.
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
    // Deliberate deviation - the response carries the STORED uppercase owner, never the caller's casing. The
    // legacy was inconsistent about this: Q answered with the database OWNER (CASH00.cbl:L144) while A echoed
    // CUST-NAME-TEXT as the caller had spelled it (L158), an accident of COMMAREA plumbing rather than a
    // contract. Normalizing is safe because broker maps only balance and currency out of the response and never
    // reads the owner
    // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L364-L365,
    // L500-L501, L542-L543).
    @Transactional
    public CashAccountResponse create(String owner, BigDecimal balance, String currency) {

        // Every validation precedes the first repository call, which is the point of the exercise: the legacy
        // discovered bad input as an SQLCODE from whichever statement happened to fail last and reported it
        // through a single sign-dropping X(10) field (CASH00.cbl:L104), so a -302 truncation and a -803 duplicate
        // were indistinguishable to the caller (AAP 0.12.3).
        String normalizedOwner = requireOwner(owner);
        String normalizedCurrency = requireCurrency(normalizedOwner, currency);
        Money openingBalance = requireBalance(normalizedOwner, balance);

        if (accounts.existsByOwner(normalizedOwner)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_ALREADY_EXISTS, normalizedOwner);
        }

        CashAccount account = CashAccount.open(normalizedOwner, normalizedCurrency, openingBalance);

        // saveAndFlush, not save: the existsByOwner check above is not atomic, so two concurrent creates of one
        // owner can both pass it. Flushing here turns the loser's primary-key collision into a
        // DataIntegrityViolationException at this line - the modern equivalent of the legacy INSERT's -803
        // (AAP 0.12.3) - instead of an opaque failure at commit, after this method has already returned 200.
        try {
            accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException cause) {
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
    // (AAP 0.6.2), and afterwards there is no entity left to read it from.
    //
    // The ACCOUNT_DELETED row is appended while the entity is still loaded and is then outlived by nothing:
    // ledger_entry holds no foreign key to cash_account precisely so the audit trail survives the account
    // (AAP 0.6.3). The legacy alternative was a write-only VSAM history nothing ever read back
    // (CASH00.cbl:L126-L131).
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
     * <p><strong>Not part of this service's public contract.</strong> It is public only so that Spring's proxy can
     * apply {@code @Transactional} when {@link #credit} and {@link #debit} call into it; callers use those two
     * methods. Its parameters are pre-validated and pre-normalized, and it performs no exchange-rate lookup.</p>
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

        // Re-read under the row lock: the account may have been deleted or updated between the lock-free pre-read
        // and this point, so the pre-read decides nothing beyond which rate to fetch.
        CashAccount account = requireLockedAccount(owner);

        // The rate was quoted against the currency the account held a moment ago. If a concurrent retail PUT has
        // since changed it (CASH00.cbl:L176-L177 is the only operation that can), applying the stale rate would
        // convert into the wrong currency and be indistinguishable from a correct result afterwards. 409 with
        // Retry-After: 1 asks the caller to repeat the operation, which then prices the new currency.
        if (!account.currency().equals(quotedCurrency)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.CONCURRENT_MODIFICATION, owner);
        }

        Money before = account.availableBalance();

        // THE SINGLE TRUNCATION POINT. Money keeps the product at full precision, performs the signed addition and
        // scales the result once with RoundingMode.DOWN, exactly as the legacy COMPUTE evaluated the whole
        // expression and truncated once into the two-decimal WS-CALC (CASH00.cbl:L222 credit, L256 debit, field at
        // L17). Truncating the product first is not equivalent for a debit: stored 100.00, rate 0.03, amount 0.30
        // gives 99.99 one way and 100.00 the other (AAP 0.12.5). Nothing here scales, rounds or divides the rate or
        // the product.
        //
        // The multiplicand is the CALLER'S amount. FRANKFURT1.AMOUNT was fetched by both rate SELECTs (L215, L249)
        // and referenced by no arithmetic and no MOVE in the entire program; the value that reached the
        // multiplication was the COMMAREA amount, moved through BALANC-RATE (L221, L255).
        //
        // Two authorized deviations surface from applyRateChecked and are deliberately not caught here. A negative
        // result is 422 INSUFFICIENT_FUNDS: WS-CALC is unsigned (L17) with no ON SIZE ERROR on the COMPUTE, so an
        // over-debit committed the magnitude of the overdraft as a positive balance. A result past 9,999,999.99 is
        // 422 AMOUNT_OUT_OF_RANGE: the same unsigned field silently dropped high-order digits. Either way the
        // exception rolls the transaction back, so the balance is unchanged and no ledger row exists.
        Money after = Money.applyRateChecked(before, sign, rate, amount);

        // The currency is deliberately untouched: the legacy credit and debit UPDATEs set BALANCE alone (L229,
        // L262), unlike the update paragraph which set both columns (L176-L177).
        account.overwriteAvailableBalance(after);
        accounts.save(account);

        // amount is the magnitude actually applied to the available balance, not the amount the caller asked for.
        // ledger_entry.currency labels the ACCOUNT currency while the request is denominated in the base currency,
        // so recording the requested figure would mislabel the row; the column is constrained non-negative with the
        // direction implied by event_type (AAP 0.6.3), which makes the applied magnitude the only self-consistent
        // value. Any signed delta a consumer needs is derived from consecutive available_after values.
        //
        // Accepted limitation, recorded: the requested amount and the rate used are not retained, because the
        // schema has no column for either. Reconstructing a conversion after the fact therefore needs the rate
        // source's own history, not the ledger.
        //
        // abs() is on the delta for that non-negative column and is not a clamp of the computed balance - the
        // balance itself is never abs()-ed, which is exactly the legacy behaviour being removed.
        Money applied = Money.of(after.amount().subtract(before.amount()).abs());

        // A zero amount writes its row rather than short-circuiting: legacy C/D with amount zero updated the row,
        // returned SQLCODE 0 and wrote a history record, so the transaction counts the reconciliation compares are
        // only equal if a zero-amount credit or debit is still one ledger row (AAP 0.4.5). Broker skips lastTrade
        // == 0 on its own side
        // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L488-L490),
        // which is its choice and not a reason for this service to reject the call.
        ledgerService.append(account, eventType, applied, LedgerEntry.Source.RETAIL);

        return CashAccountResponse.from(account);
    }

    // Both codes differ only in the sign of the COMPUTE and the event they record, exactly as the two COBOL
    // paragraphs differ only in the + of L222 and the - of L256, so they share one implementation.
    //
    // WHY THE RATE IS RESOLVED BEFORE THE TRANSACTION OPENS. The write below holds the cash_account row under
    // PESSIMISTIC_WRITE, and an outbound HTTP call inside that window would hold the lock for the rate provider's
    // latency - every concurrent operation on the owner queueing behind a third party. The lock-free pre-read costs
    // one extra SELECT and bounds the lock to local work; applyRateChangeLocked re-reads under the lock and
    // re-checks the currency, so nothing is decided on the unlocked read.
    private CashAccountResponse applyRateChange(String owner, BigDecimal amount, int sign,
            LedgerEventType eventType) {

        String normalizedOwner = requireOwner(owner);
        Money validatedAmount = requireAmount(normalizedOwner, amount);

        // Mirrors the legacy IF SQLCODE = 0 guard (L212 credit, L246 debit), which skipped the rate lookup and the
        // update entirely when the account SELECT found nothing - while still echoing the caller's own amount back
        // as the balance (L104-L108). Here it is an explicit 404 with no echo.
        CashAccount account = requireAccount(normalizedOwner, accounts.findByOwner(normalizedOwner));
        String quotedCurrency = account.currency();

        BigDecimal rate = resolveRate(normalizedOwner, quotedCurrency);

        // Through the proxy, never this.: a self-call would bypass Spring's transaction interceptor, leaving the
        // write non-transactional and making LedgerService's Propagation.MANDATORY append throw. Same
        // outer-non-transactional / inner-transactional shape AAP 0.6.3 mandates for ReservationService.
        return self.applyRateChangeLocked(normalizedOwner, sign, rate, quotedCurrency, validatedAmount, eventType);
    }

    // The same-currency short-circuit is a parity guarantee rather than an optimization: an account in the base
    // currency reconciles exactly against the legacy result, and no rate-provider outage can reach an operation
    // that needs no conversion (AAP 0.7.2). It is also the only bypass - a zero amount still fetches a rate,
    // because the legacy performed its rate SELECT unconditionally inside the SQLCODE = 0 branch (L214-L219,
    // L248-L253) and uniform 503 semantics are worth more than a saved call.
    //
    // WHY THIS TRANSLATION LIVES HERE. ExchangeRateUnavailableException is deliberately not a CashAccountException
    // and the fx package carries no HTTP taxonomy, so ApiExceptionHandler does not import it - the service layer
    // owns the mapping. The legacy alternative is the defect being removed: a missing FRANKFURT1 row left SQLCODE
    // 100 on the inner SELECT, the following UPDATE reset it to 0, and the balance was committed using an
    // uninitialized RATES host variable, so the caller saw success over undefined arithmetic
    // (CASH00.cbl:L214-L231; backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L22 declares RATES with no VALUE
    // clause). Failing with 503 and Retry-After: 5 before the transaction opens means the balance is unchanged and
    // no ledger row is written, because there is nothing yet to roll back.
    //
    // A CashAccountException from the rate source - INVALID_CURRENCY for a code it will not serve - propagates
    // untouched: a rejected input is a 400 and must not be re-dressed as a transient outage inviting a retry.
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

    // Owners up to 32 characters are accepted, the width of the DB2 column
    // (backend/cash-account-cobol/COBOL/DCLCASH.cpy:L9). The legacy COMMAREA name field was X(15)
    // (CASH00.cbl:L55), so a longer owner was silently truncated before it ever reached the column and two owners
    // sharing a 15-character prefix addressed one account; a longer owner is now 400 INVALID_OWNER instead.
    // Matching stays case-insensitive and storage uppercase, which is what the legacy LOWER()/UPPER() predicates
    // and UPPER() insert already amounted to (L141, L155, L178).
    private String requireOwner(String raw) {
        return OwnerNormalizer.normalize(raw);
    }

    // Defaulting an absent currency to the base currency is preserved behaviour, not an invention: broker already
    // substitutes USD before it calls
    // (backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L83,
    // L357-L365), and the service defaults again so a caller that is not broker gets the same account shape. The
    // base-currency property is reused rather than a second key introduced, because that property IS broker's
    // default account currency (AAP 0.7.2).
    //
    // Unlike the legacy nullable CHAR(8) whose first five characters became the rate-table key (L213, L247), the
    // stored value is a validated three-letter code from the accepted set, so a currency that cannot be converted
    // can never be stored and then discovered unconvertible at the next credit.
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
    // authority on what a legal amount is. Only the null case is decided here, because Money cannot attribute its
    // exception to an owner.
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

    // New behaviour with no legacy analogue, because the legacy had one mutable balance and no concept of held
    // funds. Retail reports the AVAILABLE balance - the spendable amount, which equals the total whenever nothing
    // is held, so parity with the single-balance program is exact (AAP 0.6.2). An absolute overwrite or a delete
    // while funds are held would strand a reservation against a balance that no longer backs it, so both are
    // refused with 409 rather than corrupting it.
    //
    // The state is read literally: an overdue HELD reservation is not expired on this path. The expiry sweep and
    // the institutional service own that transition, and reaching for it from here would mean importing the
    // institutional package, which the module's dependency direction forbids (AAP 0.8.2).
    private void requireNoHeldReservations(String owner) {
        if (reservations.existsByOwnerAndState(owner, ReservationState.HELD)) {
            throw CashAccountException.forOwner(CashAccountErrorCode.RESERVATIONS_OUTSTANDING, owner);
        }
    }

    // Locale.ROOT, never the default locale: a Turkish-locale uppercase turns the "i" of ILS into a dotted capital
    // and silently corrupts the code.
    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> normalizedCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return DEFAULT_ACCEPTED_CURRENCIES;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            String candidate = normalizeCode(code);
            if (!candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        return normalized.isEmpty() ? DEFAULT_ACCEPTED_CURRENCIES : Set.copyOf(normalized);
    }

    // Binder rather than @Value because the property is a YAML sequence, which @Value cannot bind; Binder also
    // accepts the comma-separated scalar form, so relaxed binding through an environment variable keeps working.
    // Reading it from the Environment rather than injecting the typed properties object is what keeps this package
    // free of the config package, which wires everything and is depended on by nothing (AAP 0.8.2) - the same
    // approach FrankfurterExchangeRateClient takes to the same property. A non-configurable Environment cannot
    // expose property sources at all, so that case takes the shipped set instead of failing start-up.
    private static Set<String> acceptedCurrenciesFrom(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return DEFAULT_ACCEPTED_CURRENCIES;
        }
        return Binder.get(environment)
                .bind(ACCEPTED_CURRENCIES_PROPERTY, Bindable.setOf(String.class))
                .orElse(DEFAULT_ACCEPTED_CURRENCIES);
    }

    private static <T> T requireCollaborator(T collaborator, String name) {
        if (collaborator == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return collaborator;
    }
}
