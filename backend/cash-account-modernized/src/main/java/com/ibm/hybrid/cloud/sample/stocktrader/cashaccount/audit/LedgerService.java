package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashReservation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.LedgerEntryRepository;

/** The only writer of the ledger: one immutable row per state change, appended in the caller's transaction. */
@Service
public class LedgerService {

    /** Rows returned by {@link #findLedger} when the caller names no {@code limit}. */
    public static final int DEFAULT_LEDGER_LIMIT = 100;

    /** The largest {@code limit} {@link #findLedger} accepts; above it the query is rejected, not clamped. */
    public static final int MAX_LEDGER_LIMIT = 1000;

    private final LedgerEntryRepository ledgerEntries;

    public LedgerService(LedgerEntryRepository ledgerEntries) {
        this.ledgerEntries = Objects.requireNonNull(ledgerEntries, "ledgerEntries");
    }

    /**
     * Appends one ledger row inside the caller's own transaction; the canonical primitive every other append
     * here delegates to.
     *
     * @param owner          the account owner, normalized to the key the ledger is filed under
     * @param incarnationId  the account lifetime the row belongs to
     * @param eventType      the transition being recorded; it alone carries the direction of {@code amount}
     * @param amount         the event's non-negative magnitude
     * @param currency       the account currency, uppercased here
     * @param availableAfter the available balance once the transition has been applied
     * @param reservedAfter  the reserved balance once the transition has been applied
     * @param reservationId  the reservation the event belongs to, or null on a retail or migration event
     * @param orderReference the institutional caller's order identifier, or null when no order is behind it
     * @param source         the seam that initiated the event
     * @param runId          the migration run that produced the row, or null for caller and sweep events
     * @return the saved row, carrying the generated {@code entryId}
     */
    // MANDATORY, never REQUIRED, REQUIRES_NEW or an async hand-off: the row has to commit with the balance
    // change it records, or the next request can read a balance whose event has not landed (AAP 0.7.4).
    // REQUIRED would open a transaction of its own for a caller that forgot one and commit the audit row even
    // where the balance change then rolled back; MANDATORY makes that a loud failure instead.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(String owner, UUID incarnationId, LedgerEventType eventType,
            Money amount, String currency,
            Money availableAfter, Money reservedAfter,
            UUID reservationId, String orderReference,
            LedgerEntry.Source source, UUID runId) {

        // A null in any of these slots is a wiring error, not a caller-reachable condition, so it fails fast as
        // a 500: an audit row that defaulted one of its own fields is worse than no row at all.
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(incarnationId, "incarnationId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(availableAfter, "availableAfter");
        Objects.requireNonNull(reservedAfter, "reservedAfter");
        Objects.requireNonNull(source, "source");

        // The accepted three-letter ISO set is deliberately not checked here: the request path owns that
        // validation (AAP 0.7.2), and the ledger must still record the wider VARCHAR(8) values the migration
        // tooling loads from a legacy CHAR(8) column.
        String normalizedOwner = OwnerNormalizer.normalize(owner);
        String normalizedCurrency = currency.strip().toUpperCase(Locale.ROOT);

        // amount stays exactly as given - no negation, no abs and no skipping a zero: a signed delta is derived
        // by the consumer from consecutive availableAfter/reservedAfter values and never stored (AAP 0.6.3),
        // and a zero credit or debit was a real legacy transaction that counts for parity (AAP 0.4.5).
        LedgerEntry entry = LedgerEntry.of(normalizedOwner, incarnationId, eventType, amount, normalizedCurrency,
                availableAfter, reservedAfter, reservationId, orderReference, source, runId);

        // Nothing is dropped or deduplicated here, where IGNORE CONDITION NOTOPEN and DUPREC preceded the legacy
        // write [backend/cash-account-cobol/COBOL/CASH00.cbl:L123-L124], so a closed file or a second event under
        // the same one-second key [CASH00.cbl:L47-L50] silently lost its record. recordedAt is stamped by the
        // factory, so the in-memory row carries the instant its stored row carries.
        return ledgerEntries.save(entry);
    }

    // The caller must hold the cash_account row PESSIMISTIC_WRITE and have applied the transition before calling:
    // the after-state is read off the aggregate rather than passed in, which removes the only way the row and the
    // account view AuditImmediacyIT compares it against could disagree.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(CashAccount account, LedgerEventType eventType, Money amount,
            LedgerEntry.Source source) {

        Objects.requireNonNull(account, "account");
        return append(account.owner(), account.incarnationId(), eventType, amount, account.currency(),
                account.availableBalance(), account.reservedBalance(), null, null, source, null);
    }

    // Called once per effect the state machine names, so a partial settlement lands as two rows - SETTLEMENT x
    // then RELEASE (amount - x) - in the one transaction. Nothing here chooses, collapses or reorders those
    // effects; the state machine is the single place that knows which a transition produces.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(CashAccount account, CashReservation reservation, LedgerEventType eventType,
            Money amount, LedgerEntry.Source source) {

        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reservation, "reservation");
        return append(account.owner(), account.incarnationId(), eventType, amount, account.currency(),
                account.availableBalance(), account.reservedBalance(), reservation.reservationId(),
                reservation.orderReference(), source, null);
    }

    // The caller must append this before deleting the cash_account row; the ledger row survives the deletion
    // because ledger_entry holds no foreign key to the account (AAP 0.6.3).
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry appendAccountDeleted(CashAccount account, LedgerEntry.Source source) {
        Objects.requireNonNull(account, "account");

        // Zero after-state, not the aggregate's balances: the rollback-replay derivation reads an owner's last
        // row as its absolute end state and fails unless reserved_after is 0.00 (AAP 0.12.1), which holds
        // because a retail DELETE is refused while any HELD reservation exists. amount carries the balance
        // removed, the one figure the event would otherwise lose (AAP 0.6.3).
        return append(account.owner(), account.incarnationId(), LedgerEventType.ACCOUNT_DELETED,
                account.availableBalance(), account.currency(), Money.ZERO, Money.ZERO, null, null, source, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry appendMigrationLoad(CashAccount account, UUID runId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(runId, "runId");

        // No existence check, deliberately: the partial index uq_ledger_entry_migration_load on (run_id, owner)
        // WHERE event_type = 'MIGRATION_LOAD' arbitrates one load event per owner per run (AAP 0.6.3) where a
        // read-then-write guard would lose to a concurrent run, and constrains nothing across runs since a
        // retry carries a new run_id. Its DataIntegrityViolationException propagates, failing the loader's
        // transaction whole. amount is the resulting available balance, absolute rather than a delta.
        return append(account.owner(), account.incarnationId(), LedgerEventType.MIGRATION_LOAD,
                account.availableBalance(), account.currency(), account.availableBalance(),
                account.reservedBalance(), null, null, LedgerEntry.Source.MIGRATION, runId);
    }

    // Nothing here can rewrite history - no update, delete, purge, truncate or reverse, no @Modifying query, no
    // EntityManager or JdbcTemplate - because a correction is a new compensating row, which the event types
    // already express. The trigger ledger_entry_immutable is the other half of that guarantee, and the half that
    // also binds anything reaching the table outside this code path (AAP 0.6.5).

    /**
     * Returns an owner's ledger rows, newest first, as the audit query surface behind
     * {@code GET /cash-account/institutional/accounts/{owner}/ledger}.
     *
     * @param owner the account owner, normalized before the lookup so casing cannot hide an owner's rows
     * @param since inclusive lower bound on {@code recordedAt}, or null for the most recent rows
     * @param limit rows to return, or null for {@link #DEFAULT_LEDGER_LIMIT}
     * @return the rows as wire DTOs - empty, never null, for an owner with no rows or no account
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_QUERY} (HTTP 400) when
     *         {@code limit} is below 1 or above {@link #MAX_LEDGER_LIMIT}
     */
    // readOnly and DEFAULT propagation, not MANDATORY: the controller calls this with no ambient transaction,
    // and readOnly lets Hibernate skip dirty checking on rows nothing may mutate. The bound is rejected rather
    // than clamped so a caller paging with limit=5000 learns its page size was wrong instead of receiving 1000
    // rows and concluding the owner has no more.
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> findLedger(String owner, OffsetDateTime since, Integer limit) {

        String normalizedOwner = OwnerNormalizer.normalize(owner);

        int effectiveLimit = limit == null ? DEFAULT_LEDGER_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LEDGER_LIMIT) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_QUERY, normalizedOwner,
                    "limit must be between 1 and " + MAX_LEDGER_LIMIT + ".");
        }

        // No Sort on the PageRequest: the repository method names already carry the stable tie-break AAP 0.6.2
        // mandates, and a Sort would emit a second, conflicting ORDER BY. since stays an inclusive lower bound,
        // so a caller resuming from a row it has already seen sees that row again rather than risking the loss
        // of a same-instant sibling.
        PageRequest page = PageRequest.of(0, effectiveLimit);
        List<LedgerEntry> rows = since == null
                ? ledgerEntries.findByOwnerOrderByRecordedAtDescEntryIdDesc(normalizedOwner, page)
                : ledgerEntries.findByOwnerAndRecordedAtGreaterThanEqualOrderByRecordedAtDescEntryIdDesc(
                        normalizedOwner, since, page);

        return LedgerEntryResponse.fromAll(rows);
    }
}
