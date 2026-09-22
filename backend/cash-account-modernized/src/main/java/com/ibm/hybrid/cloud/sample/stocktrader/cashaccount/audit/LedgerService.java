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
// TWO DELIBERATE DEPARTURES FROM THE LEGACY TRAIL. Lossless: IGNORE CONDITION NOTOPEN and IGNORE CONDITION
// DUPREC preceded the legacy write [CASH00.cbl:L123-L124], so a closed file, or a second event under the same
// one-second key [CASH00.cbl:L47-L50], lost the record with no error to anyone; nothing here is dropped or
// deduplicated. State changes only: the legacy record was written unconditionally after dispatch
// [CASH00.cbl:L111-L131], so reads and even unrecognized request codes produced one, and a read changes
// nothing worth recording (AAP 0.4.6) - which is why LedgerEventType has no READ constant to reach for.
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
     * Appends one ledger row; the canonical primitive every other append here delegates to.
     *
     * @param reservationId  the reservation the event belongs to, or null on a retail or migration event
     * @param orderReference the institutional caller's order identifier, or null when no order is behind it
     * @param runId          the migration run that produced the row, or null for caller and sweep events
     * @return the saved row, carrying the generated {@code entryId}
     */
    // MANDATORY, never REQUIRED and never REQUIRES_NEW or an @Async hand-off: the row has to commit with the
    // balance change it records, or the audit becomes eventually consistent and the next request can read a
    // balance whose event has not landed (AAP 0.7.4). REQUIRED would quietly open a transaction of its own for a
    // caller that forgot one, committing the audit row even where the balance change then rolled back - the one
    // failure mode that would leave the ledger disagreeing with the account. MANDATORY makes it a loud failure.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(String owner, UUID incarnationId, LedgerEventType eventType,
            Money amount, String currency,
            Money availableAfter, Money reservedAfter,
            UUID reservationId, String orderReference,
            LedgerEntry.Source source, UUID runId) {

        // A null in any of these slots is a wiring error, not a caller-reachable condition: each one arrives from
        // an aggregate the caller already loaded or from a constant it chose. Failing fast with the argument's
        // name - rendered as 500 INTERNAL by the exception handler's catch-all - beats defaulting the value and
        // beats a half-populated row, because an audit row that guessed one of its own fields is worse than none.
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(incarnationId, "incarnationId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(availableAfter, "availableAfter");
        Objects.requireNonNull(reservedAfter, "reservedAfter");
        Objects.requireNonNull(source, "source");

        // Both normalizations are idempotent, and doing them here as well as in the factory keeps the key this row
        // is filed under part of this service's own contract rather than an internal choice of the entity.
        // The accepted three-letter ISO set is deliberately NOT checked here: the request path owns that
        // validation (AAP 0.7.2), and the ledger must still be able to record the wider VARCHAR(8) values the
        // migration tooling loads from a legacy CHAR(8) column.
        String normalizedOwner = OwnerNormalizer.normalize(owner);
        String normalizedCurrency = currency.strip().toUpperCase(Locale.ROOT);

        // amount stays exactly as given: it is a magnitude, eventType alone carries the direction, and any signed
        // delta is derived by the consumer from consecutive availableAfter/reservedAfter values, never stored
        // (AAP 0.6.3). So no negation and no abs - and no skipping a zero amount either, since a zero credit or
        // debit was a real legacy transaction that wrote its own history record and counts for parity (AAP 0.4.5).
        LedgerEntry entry = LedgerEntry.of(normalizedOwner, incarnationId, eventType, amount, normalizedCurrency,
                availableAfter, reservedAfter, reservationId, orderReference, source, runId);

        // recordedAt is stamped by the factory, so the timestamp has exactly one owner and the in-memory row
        // carries the instant its stored row carries.
        return ledgerEntries.save(entry);
    }

    /**
     * Appends an account-level event, reading the after-state off {@code account}, which the caller must
     * already have mutated.
     */
    // The caller holds the cash_account row PESSIMISTIC_WRITE and has applied the transition before calling, so
    // the aggregate's balances ARE the post-transition state - the same state AuditImmediacyIT compares the row
    // against the account view. Reading them here rather than taking them as parameters removes the only way the
    // two could disagree; the obligation to mutate first is the price of that, and it is stated above.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(CashAccount account, LedgerEventType eventType, Money amount,
            LedgerEntry.Source source) {

        Objects.requireNonNull(account, "account");
        return append(account.owner(), account.incarnationId(), eventType, amount, account.currency(),
                account.availableBalance(), account.reservedBalance(), null, null, source, null);
    }

    /**
     * Appends a reservation event, tying the row to {@code reservation} and to the already-mutated
     * {@code account}'s after-state.
     */
    // Called once per effect the state machine names, so a partial settlement lands as two rows - SETTLEMENT x
    // then RELEASE (amount - x) - in the one transaction. This method neither chooses nor collapses nor reorders
    // those effects: the state machine is the single place that knows which a transition produces.
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(CashAccount account, CashReservation reservation, LedgerEventType eventType,
            Money amount, LedgerEntry.Source source) {

        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reservation, "reservation");
        return append(account.owner(), account.incarnationId(), eventType, amount, account.currency(),
                account.availableBalance(), account.reservedBalance(), reservation.reservationId(),
                reservation.orderReference(), source, null);
    }

    /**
     * Appends the {@code ACCOUNT_DELETED} event; the caller must call this before deleting the
     * {@code cash_account} row, and the row is retained afterwards because {@code ledger_entry} holds no
     * foreign key to it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry appendAccountDeleted(CashAccount account, LedgerEntry.Source source) {
        Objects.requireNonNull(account, "account");

        // The after-state is zero rather than the aggregate's balances because the account does not exist after
        // this event: the rollback-replay derivation reads an owner's last ledger row as its absolute end state
        // and fails unless reserved_after is 0.00 (AAP 0.12.1). Zero is always the truth here - a retail DELETE is
        // already refused with 409 RESERVATIONS_OUTSTANDING while any HELD reservation exists. amount carries the
        // balance removed, which is the one figure the event would otherwise lose (AAP 0.6.3).
        return append(account.owner(), account.incarnationId(), LedgerEventType.ACCOUNT_DELETED,
                account.availableBalance(), account.currency(), Money.ZERO, Money.ZERO, null, null, source, null);
    }

    /**
     * Appends the {@code MIGRATION_LOAD} event recording the balance {@code runId} loaded for this owner.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry appendMigrationLoad(CashAccount account, UUID runId) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(runId, "runId");

        // No existence check, deliberately: the partial index uq_ledger_entry_migration_load on (run_id, owner)
        // WHERE event_type = 'MIGRATION_LOAD' is the deduplication rule WITHIN one run - one load event per owner
        // per run (AAP 0.6.3) - and a read-then-write check here would both duplicate it and lose to a concurrent
        // run. It says nothing across runs, because a retry carries a new run_id: a retry is safe because the
        // loader's load is one transaction, so a failed run leaves no row to duplicate, and because an owner whose
        // balance and currency already match the export is left alone and writes no event at all. So the
        // constraint arbitrates rather than a guard here, and the DataIntegrityViolationException it raises
        // propagates, failing the loader's transaction whole - either every export row is applied or the run is
        // FAILED (AAP 0.6.3). amount is the resulting available balance, absolute rather than a delta, as for
        // every load event.
        return append(account.owner(), account.incarnationId(), LedgerEventType.MIGRATION_LOAD,
                account.availableBalance(), account.currency(), account.availableBalance(),
                account.reservedBalance(), null, null, LedgerEntry.Source.MIGRATION, runId);
    }

    // There is deliberately no method that could rewrite history - nothing named update, delete, remove, purge,
    // truncate, correct or reverse, no @Modifying query, no EntityManager or JdbcTemplate, and no bulk save that
    // might pass for a fix-up. A correction is a new compensating row, which the event types already express.
    // This is the Java half of the immutability guarantee; the trigger ledger_entry_immutable is the other half,
    // and it is the half that also binds anything reaching the table outside this code path (AAP 0.6.5).

    /**
     * Returns an owner's ledger rows, newest first, as the audit query surface behind
     * {@code GET /cash-account/institutional/accounts/{owner}/ledger}.
     *
     * @param since inclusive lower bound on {@code recordedAt}, or null for the most recent rows
     * @param limit rows to return, or null for {@link #DEFAULT_LEDGER_LIMIT}
     * @return the rows as wire DTOs - empty, never null, for an owner with no rows or no account
     * @throws CashAccountException with {@link CashAccountErrorCode#INVALID_QUERY} (HTTP 400) when
     *         {@code limit} is below 1 or above {@link #MAX_LEDGER_LIMIT}
     */
    // readOnly with the DEFAULT propagation, not MANDATORY: the controller calls this with no ambient
    // transaction, and readOnly lets the driver and Hibernate skip dirty checking on rows nothing may mutate.
    // The bound is rejected rather than clamped so a caller paging with limit=5000 learns its page size was
    // wrong, instead of silently receiving 1000 rows and concluding the owner has no more.
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> findLedger(String owner, OffsetDateTime since, Integer limit) {

        String normalizedOwner = OwnerNormalizer.normalize(owner);

        int effectiveLimit = limit == null ? DEFAULT_LEDGER_LIMIT : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LEDGER_LIMIT) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_QUERY, normalizedOwner,
                    "limit must be between 1 and " + MAX_LEDGER_LIMIT + ".");
        }

        // No Sort on the PageRequest: the derived method names already carry OrderByRecordedAtDescEntryIdDesc -
        // the stable tie-break AAP 0.6.2 mandates, served by the index (owner, recorded_at DESC, entry_id DESC) -
        // and adding one would emit a second, conflicting ORDER BY. since is applied as given, an inclusive lower
        // bound, so a caller resuming from the recordedAt of a row it has already seen sees that row again rather
        // than risking the loss of a same-instant sibling.
        PageRequest page = PageRequest.of(0, effectiveLimit);
        List<LedgerEntry> rows = since == null
                ? ledgerEntries.findByOwnerOrderByRecordedAtDescEntryIdDesc(normalizedOwner, page)
                : ledgerEntries.findByOwnerAndRecordedAtGreaterThanEqualOrderByRecordedAtDescEntryIdDesc(
                        normalizedOwner, since, page);

        return LedgerEntryResponse.fromAll(rows);
    }
}
