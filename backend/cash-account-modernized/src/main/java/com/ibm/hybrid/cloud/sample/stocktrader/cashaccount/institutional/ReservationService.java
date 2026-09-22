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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.audit.LedgerService;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashAccount;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.CashReservation;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.Money;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.OwnerNormalizer;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationState;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.ReservationStateMachine;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountErrorCode;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.error.CashAccountException;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashAccountRepository;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence.CashReservationRepository;

/*
 * WHY THE LOCK ORDER IS FIXED AND STATED HERE. Every transaction that moves money in this module takes the
 * owner's cash_account row with PESSIMISTIC_WRITE first and only then reads the reservation row FOR UPDATE -
 * request paths and the scheduled expiry sweep alike. One order for every path is what makes the graph
 * cycle-free, so no hold, settle, release or sweep can deadlock against another on the same owner. Pessimistic
 * row locking is the estate's sanctioned strategy (backend/portfolio/src/main/resources/META-INF/
 * persistence.xml:L13-L14, eclipselink.pessimistic-lock=Lock with the shared cache off - "need this to scale
 * beyond one pod"), narrowed here to the two locking queries so plain reads stay lock-free.
 *
 * WHY hold, settle, release AND THE SWEEP ARE NOT TRANSACTIONAL, AND WHY A SELF-REFERENCE EXISTS. Each of them
 * has something to do OUTSIDE the transaction that writes: hold must re-read after PostgreSQL has aborted the
 * transaction its losing INSERT violated; a settle must raise its refusal only after the transaction that
 * committed a lapsed hold's expiry has committed, so the EXPIRY ledger row survives the 409; settle and
 * release decide a terminal reservation's non-mutating outcome with no lock at all; and the sweep must give
 * each candidate its own short transaction. A @Transactional method reached as a plain this.method(...) call
 * bypasses the proxy and would run with no transaction at all, so every such call goes through
 * self.getObject(). audit/LedgerService appends with Propagation.MANDATORY, which turns a lost boundary into
 * an immediate IllegalTransactionStateException rather than a silent no-op.
 *
 * WHY THE BALANCE EFFECTS ARE NOT WRITTEN DOWN HERE. domain/ReservationStateMachine decides and applies every
 * transition - hold moves available to reserved, a settle takes the settled portion out and returns the
 * remainder, a release or expiry returns the whole hold - and names the ledger rows it requires. This class
 * only locks, persists and appends: duplicating a rule here would create a second implementation of it.
 */
/** Orchestrates institutional holds, settlements, releases and expiry over the locked account and ledger. */
@Service
public class ReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationService.class);

    /** Bounds one sweep pass so a backlog drains over several short transactions instead of one long one. */
    private static final int EXPIRY_SWEEP_BATCH_SIZE = 200;

    /** The {@code cash_reservation.idempotency_key VARCHAR(128)} width. */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * The hold idempotency guard {@code UNIQUE (incarnation_id, idempotency_key)}
     * (schema/cash-account-schema.sql:L69), by name, because only a violation of THIS constraint is the race.
     */
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_cash_reservation_incarnation_key";

    /** The literal an omitted {@code expiresAt} contributes to the canonical hash. */
    private static final String DEFAULT_EXPIRY_TOKEN = "DEFAULT";

    private final CashAccountRepository accounts;
    private final CashReservationRepository reservations;
    private final LedgerService ledger;
    private final ObjectProvider<ReservationService> self;
    private final Duration defaultTtl;

    public ReservationService(CashAccountRepository accounts, CashReservationRepository reservations,
            LedgerService ledger, ObjectProvider<ReservationService> self,
            @Value("${cashaccount.reservation.default-ttl:PT24H}") Duration defaultTtl) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.self = Objects.requireNonNull(self, "self");
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl");
    }

    /**
     * A hold's result together with whether it replayed an earlier one.
     *
     * <p>Nested rather than declared as its own file: it exists only to tell the controller whether to answer
     * {@code 201} or {@code 200} with {@code Idempotent-Replayed: true}, exactly as {@code domain} nests
     * {@code ReservationStateMachine.Effect} beside the method that returns it.</p>
     *
     * @param reservation the reservation as it stands after the call
     * @param replayed {@code true} when the stored reservation was returned unchanged for a repeated key
     */
    public record HoldOutcome(ReservationResponse reservation, boolean replayed) {
    }

    /**
     * A settle's or release's committed outcome, and whether the transaction expired a lapsed hold instead of
     * performing the transition that was asked for.
     *
     * <p>Nested for the reason {@link HoldOutcome} is. It exists so that one transaction can both commit an
     * expiry and report it: a release answers with that outcome, while a settle refuses it with
     * {@code 409 INVALID_TRANSITION} raised after the commit, which is what keeps the {@code EXPIRY} ledger
     * row every transition owes the audit trail.</p>
     *
     * @param reservation the reservation as the committed transaction left it
     * @param expired {@code true} when this transaction expired a lapsed hold rather than settling or
     *        releasing it
     */
    public record TransitionOutcome(ReservationResponse reservation, boolean expired) {
    }

    /**
     * Places a hold on the owner's available funds, idempotently under the supplied key.
     *
     * @param owner the account owner, normalized here so a caller that already normalized costs nothing
     * @param idempotencyKey the required {@code Idempotency-Key} header value
     * @param request the hold to place
     * @return the new hold, or the stored one when the key and payload replay an earlier call
     * @throws CashAccountException {@link CashAccountErrorCode#IDEMPOTENCY_KEY_REQUIRED},
     *         {@link CashAccountErrorCode#INVALID_OWNER}, {@link CashAccountErrorCode#INVALID_AMOUNT},
     *         {@link CashAccountErrorCode#INVALID_CURRENCY}, {@link CashAccountErrorCode#CURRENCY_MISMATCH},
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND},
     *         {@link CashAccountErrorCode#INSUFFICIENT_FUNDS},
     *         {@link CashAccountErrorCode#IDEMPOTENCY_KEY_REUSED} or
     *         {@link CashAccountErrorCode#CONCURRENT_MODIFICATION}
     */
    public HoldOutcome hold(String owner, String idempotencyKey, HoldRequest request) {
        String normalizedOwner = OwnerNormalizer.normalize(owner);
        String key = requireIdempotencyKey(idempotencyKey, normalizedOwner);
        if (request == null) {
            // The controller's @Valid @RequestBody rejects a missing body first; of the closed hold error set,
            // INVALID_AMOUNT is the code for a payload that carries no amount.
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, normalizedOwner);
        }
        Money amount = requireHoldAmount(request.amount(), normalizedOwner);
        String currency = requireCurrency(request.currency(), normalizedOwner);
        String requestHash = canonicalRequestHash(request.orderReference(), amount, currency,
                request.expiresAt());

        // Read lock-free purely for the incarnation: the catch below runs after PostgreSQL aborted holdOnce's
        // transaction, so it cannot see anything that transaction read.
        UUID incarnationId = accounts.findByOwner(normalizedOwner)
                .orElseThrow(() -> CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_NOT_FOUND,
                        normalizedOwner))
                .incarnationId();

        ReservationService proxy = self.getObject();
        try {
            return proxy.holdOnce(normalizedOwner, key, requestHash, request);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE (incarnation_id, idempotency_key) is the first-writer-wins guard, so the loser of a real
            // race lands here. The winner's row is visible only to a transaction opened after this rollback,
            // which is why the comparison happens in replayFor and not in the aborted transaction above.
            //
            // Only THAT constraint, though: every other integrity violation this transaction can raise - a
            // ledger_entry CHECK, a reservation amount or state CHECK - would be answered by replayFor with a
            // replay or a retryable 409, both of which claim the write succeeded or can succeed when neither
            // is true. Rethrown, it reaches the handler's catch-all as 500 INTERNAL, the status AAP 0.6.2
            // assigns an unexpected failure, and the cause survives in the log instead of being swallowed.
            if (!violatesIdempotencyConstraint(race)) {
                throw race;
            }
            return proxy.replayFor(incarnationId, key, requestHash);
        }
    }

    /*
     * holdOnce, replayFor, settleOnce, releaseOnce and expireOneCandidate are public for one reason only: a
     * @Transactional method must be entered through the Spring proxy, and the proxy can only expose what is
     * public. They are the transactional units of the four callers above - not test conveniences, and not part
     * of the surface a controller should reach for.
     */

    /**
     * The transactional unit of {@link #hold}: locks the account, replays or creates, appends the ledger row.
     *
     * @param owner the normalized account owner
     * @param idempotencyKey the validated, trimmed key
     * @param requestHash the canonical request hash computed by {@link #hold}
     * @param request the hold to place
     * @return the new hold, or the stored one when this key and payload were already applied
     * @throws CashAccountException {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND}, or
     *         {@link CashAccountErrorCode#IDEMPOTENCY_KEY_REUSED} when the key was already spent - by this
     *         incarnation of the account under a different payload, or by any earlier one at all
     */
    @Transactional
    public HoldOutcome holdOnce(String owner, String idempotencyKey, String requestHash, HoldRequest request) {
        String normalizedOwner = OwnerNormalizer.normalize(owner);
        CashAccount account = accounts.findByOwnerForUpdate(normalizedOwner)
                .orElseThrow(() -> CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_NOT_FOUND,
                        normalizedOwner));

        // The ordinary, non-racing replay is settled here rather than by the unique violation: a sequential
        // repeat of the same call must not consume a transaction that is bound to fail.
        Optional<CashReservation> stored = reservations
                .findByIncarnationIdAndIdempotencyKey(account.incarnationId(), idempotencyKey);
        if (stored.isPresent()) {
            return replayOrReuse(stored.get(), requestHash);
        }

        // Every row this owner-scoped query can return belongs to a DIFFERENT incarnation, because a row of the
        // current one would already have been answered above. Such a key is reuse and never a replay, whatever
        // its payload hash: the retained hold reserved funds in an account life that no longer exists, so
        // replaying it would report a reservation against balance it never touched - and creating a second hold
        // under it would move the balance twice (AAP 0.6.3 cash_reservation, AAP 0.11.1). The UNIQUE
        // (incarnation_id, idempotency_key) guard cannot see this: the new incarnation_id makes the pair unique
        // again, which is why the rejection has to be decided here.
        //
        // Race-free where it sits and nowhere else: this transaction already holds the owner's cash_account row
        // under PESSIMISTIC_WRITE (above), and a retail DELETE takes that same row lock
        // (retail/RetailCashAccountService.delete), so no incarnation change can slip between this check and the
        // INSERT below. Run from hold's lock-free pre-read it would be a guess.
        List<CashReservation> retained = reservations.findByOwnerAndIdempotencyKey(normalizedOwner,
                idempotencyKey);
        if (!retained.isEmpty()) {
            throw CashAccountException.forReservation(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED,
                    mostRecentlyCreated(retained).reservationId());
        }

        Money amount = requireHoldAmount(request.amount(), normalizedOwner);
        String currency = requireCurrency(request.currency(), normalizedOwner);
        // A caller-supplied expiry already in the past is accepted as handed in - the hold is simply overdue
        // and the sweep terminates it - because the hold error set carries no code for a bad expiry.
        OffsetDateTime expiresAt = request.expiresAt() != null ? request.expiresAt() : now().plus(defaultTtl);

        // The order reference is handed over exactly as it arrived, which is also exactly what
        // canonicalRequestHash hashed: the entity stores it unaltered, so the value this response returns
        // hashes back to the same digest and a verbatim retry is recognized as the replay it is.
        CashReservation reservation = CashReservation.newHold(account, request.orderReference(), amount,
                currency, expiresAt, idempotencyKey, requestHash);
        ReservationStateMachine.Effect effect = ReservationStateMachine.hold(account, reservation);

        // Flushed before the ledger append so a unique violation surfaces inside this transaction, where the
        // proxy can roll it back and hold's catch can re-read, instead of at commit where nothing can.
        CashReservation persisted = reservations.saveAndFlush(reservation);
        accounts.save(account);
        appendLedger(account, persisted, effect, LedgerEntry.Source.INSTITUTIONAL);
        return new HoldOutcome(ReservationResponse.from(persisted), false);
    }

    // Two ways of asking the same question, because neither alone is dependable. Hibernate's PostgreSQL
    // violated-constraint-name extractor puts the name on a ConstraintViolationException in the cause chain -
    // the structured answer, used when it is there. The message scan is the fallback: Spring's translated
    // message embeds "constraint [<name>]", and a constraint name inside a PostgreSQL error message is not
    // localized even when the surrounding text is, so matching the name is safe where matching prose is not.
    // The chain is walked rather than the top exception inspected, since Spring wraps the JDBC and Hibernate
    // exceptions that actually carry the name. Case-insensitive because PostgreSQL folds unquoted identifiers.
    private static boolean violatesIdempotencyConstraint(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT)
                            .contains(IDEMPOTENCY_CONSTRAINT)) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_CONSTRAINT)) {
                return true;
            }
            // A self-referencing cause chain would otherwise loop forever; getCause() returning this is legal.
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    // The newest retained row is named in the rejection because it is the one an operator asked about: the key
    // was last used there. reservationId breaks a created_at tie so the ApiError payload is the same on every
    // call and on every pod, which a list whose row order the database never promised would not be.
    private static CashReservation mostRecentlyCreated(List<CashReservation> retained) {
        return retained.stream()
                .max(Comparator.comparing(CashReservation::createdAt)
                        .thenComparing(CashReservation::reservationId))
                // Unreachable: the only caller has already established the list is non-empty. Stated as a defect
                // rather than returned as a null the caller would dereference.
                .orElseThrow(() -> new IllegalArgumentException("retained must not be empty"));
    }

    /**
     * Re-reads a key in a fresh transaction after a losing {@code INSERT}, and answers replay or reuse.
     *
     * @param incarnationId the account incarnation the key was scoped to
     * @param idempotencyKey the validated, trimmed key
     * @param requestHash the canonical request hash of the call being answered
     * @return the stored reservation, marked as a replay
     * @throws CashAccountException {@link CashAccountErrorCode#IDEMPOTENCY_KEY_REUSED} when the stored hash
     *         differs, {@link CashAccountErrorCode#CONCURRENT_MODIFICATION} when no row matches the key
     */
    @Transactional(readOnly = true)
    public HoldOutcome replayFor(UUID incarnationId, String idempotencyKey, String requestHash) {
        return reservations.findByIncarnationIdAndIdempotencyKey(incarnationId, idempotencyKey)
                .map(stored -> replayOrReuse(stored, requestHash))
                // No row under this key means the violated constraint was not the idempotency guard, or the
                // owner was re-created between the read and the write. A retryable 409 states that honestly;
                // a 500 would blame the caller's request for a race it can simply repeat.
                .orElseThrow(() -> CashAccountException.of(CashAccountErrorCode.CONCURRENT_MODIFICATION));
    }

    /**
     * Settles a hold, releasing any unsettled remainder back to available funds.
     *
     * @param reservationId the reservation to settle
     * @param request the amount to settle, or {@code null} / a null amount for the full held amount
     * @return the reservation as it stands after the settlement
     * @throws CashAccountException {@link CashAccountErrorCode#RESERVATION_NOT_FOUND},
     *         {@link CashAccountErrorCode#INVALID_AMOUNT} when the amount exceeds the held amount, or
     *         {@link CashAccountErrorCode#INVALID_TRANSITION} from a released or expired reservation -
     *         including a hold that had lapsed, which this call expires and commits before refusing
     */
    public ReservationResponse settle(UUID reservationId, SettleRequest request) {
        // One unlocked read serves the whole call: it establishes that the reservation exists, which state it
        // is in, and which owner's account row a mutation would have to lock first.
        CashReservation stored = requireReservation(reservationId);
        // Parsed before anything is locked or written, because a request refused as INVALID_AMOUNT must leave
        // no state change behind it at all.
        Money settleAmount = requireSettleAmount(request == null ? null : request.amount(), reservationId);

        /*
         * WHY A TERMINAL RESERVATION IS ANSWERED WITHOUT THE ACCOUNT ROW. cash_reservation carries no foreign
         * key and its rows are retained after a retail DELETE (AAP 0.11.1), so a settled, released or expired
         * reservation legitimately outlives the account it names - and its outcome moves no money, being
         * either the idempotent 200 the contract promises a retrying caller or a 409. Locking the account
         * first would turn every such call on a deleted owner into a 409 about the account instead, so the
         * lock is taken only for the HELD case that actually moves funds. The decision itself stays in
         * domain/ReservationStateMachine, which owns the (state, command) table.
         */
        if (!stored.isHeld()) {
            ReservationStateMachine.settleFromTerminal(stored);
            return ReservationResponse.from(stored);
        }

        /*
         * WHY ONE TRANSACTION DECIDES, AND WHY THE REFUSAL IS RAISED AFTER IT. An overdue hold is expired by
         * the request that touched it rather than waiting for the sweep, and a settle from EXPIRED is refused
         * - but CashAccountException is unchecked, so raising that 409 inside the transaction would roll the
         * expiry back with it and lose the EXPIRY ledger row every transition owes the audit trail (AAP
         * 0.7.4). settleOnce therefore commits whichever outcome it reached under the one account-first lock
         * and reports which it was; the refusal is raised here, after the commit. A second transaction that
         * expired the hold first, as an earlier shape did, would re-read and re-lock the same two rows for no
         * added guarantee.
         */
        TransitionOutcome outcome = self.getObject().settleOnce(reservationId, stored.owner(), settleAmount);
        if (outcome.expired()) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_TRANSITION, reservationId,
                    "The hold expired before it could be settled.");
        }
        return outcome.reservation();
    }

    /**
     * The single transactional unit of {@link #settle}: locks the account, then the reservation, then acts.
     *
     * @param reservationId the reservation to settle
     * @param owner the reservation's owner, whose account row is locked first
     * @param settleAmount the amount to settle, or {@code null} for the full held amount
     * @return the committed outcome, flagged when a lapsed hold was expired instead of settled
     */
    @Transactional
    public TransitionOutcome settleOnce(UUID reservationId, String owner, Money settleAmount) {
        CashAccount account = lockAccountFor(owner, reservationId);
        // Re-read under the lock, never trusted from the unlocked read above: a settle, release or sweep may
        // have made the row terminal in between, and the state machine judges it as it stands here - which is
        // what keeps exactly one terminal transition and one ledger row per hold.
        CashReservation locked = lockReservation(reservationId);
        OffsetDateTime now = now();

        Optional<ReservationResponse> expired = expireLapsedHold(account, locked, now);
        if (expired.isPresent()) {
            return new TransitionOutcome(expired.get(), true);
        }

        // settleAmount stays null for a full settlement: the state machine resolves the default so that an
        // absent body, a null amount and the full held amount cannot be answered differently.
        ReservationStateMachine.Effect effect =
                ReservationStateMachine.settle(account, locked, settleAmount, now);
        return new TransitionOutcome(persist(account, locked, effect, LedgerEntry.Source.INSTITUTIONAL),
                false);
    }

    /**
     * Releases a hold, returning the whole held amount to available funds.
     *
     * @param reservationId the reservation to release
     * @return the reservation as it stands after the release
     * @throws CashAccountException {@link CashAccountErrorCode#RESERVATION_NOT_FOUND} or
     *         {@link CashAccountErrorCode#INVALID_TRANSITION} from a settled reservation
     */
    public ReservationResponse release(UUID reservationId) {
        CashReservation stored = requireReservation(reservationId);

        // Terminal outcomes are decided from the reservation alone, for the reason settle records above.
        if (!stored.isHeld()) {
            ReservationStateMachine.releaseFromTerminal(stored);
            return ReservationResponse.from(stored);
        }

        // No outcome of a release is a refusal once it reaches the lock: an expiry moves the money exactly
        // where a release would, so the caller's intent is already satisfied and the committed EXPIRED
        // outcome is the answer rather than a 409.
        return self.getObject().releaseOnce(reservationId, stored.owner()).reservation();
    }

    /**
     * The single transactional unit of {@link #release}: locks the account, then the reservation, then acts.
     *
     * @param reservationId the reservation to release
     * @param owner the reservation's owner, whose account row is locked first
     * @return the committed outcome, flagged when a lapsed hold was expired instead of released
     */
    @Transactional
    public TransitionOutcome releaseOnce(UUID reservationId, String owner) {
        CashAccount account = lockAccountFor(owner, reservationId);
        CashReservation locked = lockReservation(reservationId);
        OffsetDateTime now = now();

        Optional<ReservationResponse> expired = expireLapsedHold(account, locked, now);
        if (expired.isPresent()) {
            return new TransitionOutcome(expired.get(), true);
        }

        ReservationStateMachine.Effect effect = ReservationStateMachine.release(account, locked, now);
        return new TransitionOutcome(persist(account, locked, effect, LedgerEntry.Source.INSTITUTIONAL),
                false);
    }

    /**
     * Expires one overdue hold under the account lock, or declines when another actor already resolved it.
     *
     * @param reservationId the candidate reservation
     * @param owner the candidate's owner, whose account row is locked first
     * @return {@code true} when this call expired the reservation, {@code false} when nothing was done
     */
    @Transactional
    public boolean expireOneCandidate(UUID reservationId, String owner) {
        Optional<CashAccount> account = accounts.findByOwnerForUpdate(owner);
        if (account.isEmpty()) {
            return false;
        }
        // An empty claim is routine, not a failure: SKIP LOCKED passes over a row another actor holds rather
        // than queueing the sweep behind an in-flight settle that is about to make it terminal anyway.
        Optional<CashReservation> claimed = reservations.claimForUpdateSkipLocked(reservationId);
        if (claimed.isEmpty()) {
            return false;
        }

        CashReservation reservation = claimed.get();
        OffsetDateTime now = now();
        // Re-checked under the account lock, which is what keeps exactly one terminal transition and one
        // ledger row per hold: a second sweeper, or a settle or release that won the race, has already made
        // the row terminal by the time this runs, and this call then correctly does nothing.
        if (!reservation.isHeld() || !reservation.isExpiredAt(now)) {
            return false;
        }

        return expireLapsedHold(account.get(), reservation, now).isPresent();
    }

    /**
     * Expires overdue holds, one short transaction per candidate.
     *
     * @return the number of reservations this pass expired
     */
    @Scheduled(fixedRateString = "${cashaccount.reservation.expiry-sweep-interval:PT60S}")
    public int sweepExpiredReservations() {
        List<CashReservationRepository.ExpiryCandidate> candidates = reservations.findExpiryCandidates(
                ReservationState.HELD, now(), PageRequest.of(0, EXPIRY_SWEEP_BATCH_SIZE));

        ReservationService proxy = self.getObject();
        int expired = 0;
        for (CashReservationRepository.ExpiryCandidate candidate : candidates) {
            try {
                if (proxy.expireOneCandidate(candidate.getReservationId(), candidate.getOwner())) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                // Per candidate, so one unexpirable row cannot strand the funds of every later candidate in
                // the batch. The next pass retries it, and this is the only log line the class emits.
                LOG.warn("Expiry sweep skipped reservation {} of owner {}: {}", candidate.getReservationId(),
                        candidate.getOwner(), failure.toString());
            }
        }
        return expired;
    }

    /**
     * Reads one reservation without changing it.
     *
     * @param reservationId the reservation to read
     * @return the reservation as stored
     * @throws CashAccountException {@link CashAccountErrorCode#RESERVATION_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public ReservationResponse findReservation(UUID reservationId) {
        // Deliberately no lazy expiry: a read changes nothing, so it writes no ledger row, and an overdue
        // hold reported as HELD here is terminated by the next sweep with its own EXPIRY row.
        return ReservationResponse.from(requireReservation(reservationId));
    }

    /**
     * Reads an owner's available, reserved and total balances.
     *
     * @param owner the account owner, normalized here
     * @return the institutional view of the account
     * @throws CashAccountException {@link CashAccountErrorCode#INVALID_OWNER} or
     *         {@link CashAccountErrorCode#ACCOUNT_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public InstitutionalAccountResponse findAccount(String owner) {
        String normalizedOwner = OwnerNormalizer.normalize(owner);
        return accounts.findByOwner(normalizedOwner)
                .map(InstitutionalAccountResponse::from)
                .orElseThrow(() -> CashAccountException.forOwner(CashAccountErrorCode.ACCOUNT_NOT_FOUND,
                        normalizedOwner));
    }

    private HoldOutcome replayOrReuse(CashReservation stored, String requestHash) {
        // The key says "this is the same call"; the hash says whether that claim is true. A key presented
        // with a different payload is therefore reuse (422) rather than a replay, because answering it with
        // the stored reservation would silently discard a hold the caller genuinely asked for.
        if (!requestHash.equals(stored.requestHash())) {
            throw CashAccountException.forReservation(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED,
                    stored.reservationId());
        }
        // originalHold, not from: a replay owes the caller the answer its original call received (AAP 0.6.2,
        // 0.7.3), so the row's live state - settled, released or expired by now - must not reach a client that
        // is merely retrying the create. ReservationResponse records why that original body can be rebuilt
        // exactly from this row; GET .../reservations/{reservationId} is where current state is published.
        return new HoldOutcome(ReservationResponse.originalHold(stored), true);
    }

    /*
     * The one place a lapsed hold is expired, shared by the settle and release paths and by the sweep so that
     * a single implementation decides and records it. An empty Optional means nothing was overdue - the
     * ordinary case - and the caller then performs the transition it was asked for.
     *
     * SYSTEM rather than INSTITUTIONAL as the ledger source, even when a caller's settle or release is what
     * noticed it: the event is the TTL elapsing, not the request, which is the distinction LedgerEntry.Source
     * exists to record - and it leaves a lazily expired hold indistinguishable in the audit trail from one
     * the scheduled sweep reached first.
     */
    private Optional<ReservationResponse> expireLapsedHold(CashAccount account, CashReservation reservation,
            OffsetDateTime now) {

        ReservationStateMachine.Effect expiry = ReservationStateMachine.expire(account, reservation, now);
        if (expiry.idempotentNoOp()) {
            return Optional.empty();
        }
        return Optional.of(persist(account, reservation, expiry, LedgerEntry.Source.SYSTEM));
    }

    private ReservationResponse persist(CashAccount account, CashReservation reservation,
            ReservationStateMachine.Effect effect, LedgerEntry.Source source) {
        CashReservation persisted = reservations.save(reservation);
        accounts.save(account);
        appendLedger(account, persisted, effect, source);
        return ReservationResponse.from(persisted);
    }

    private void appendLedger(CashAccount account, CashReservation reservation,
            ReservationStateMachine.Effect effect, LedgerEntry.Source source) {
        // One row per effect, in the order the state machine named them, inside this transaction - so a
        // partial settlement's SETTLEMENT and RELEASE rows and the balance change commit together and are
        // queryable the instant they do. An idempotent no-op names no effect and therefore writes nothing.
        for (ReservationStateMachine.LedgerEffect ledgerEffect : effect.ledgerEffects()) {
            ledger.append(account, reservation, ledgerEffect.eventType(), ledgerEffect.amount(), source);
        }
    }

    private CashReservation requireReservation(UUID reservationId) {
        if (reservationId == null) {
            throw CashAccountException.of(CashAccountErrorCode.RESERVATION_NOT_FOUND);
        }
        return reservations.findById(reservationId)
                .orElseThrow(() -> CashAccountException.forReservation(
                        CashAccountErrorCode.RESERVATION_NOT_FOUND, reservationId));
    }

    private CashAccount lockAccountFor(String owner, UUID reservationId) {
        // Only the HELD paths reach this, and a HELD reservation's account cannot ordinarily be missing: a
        // retail DELETE is refused with RESERVATIONS_OUTSTANDING while any hold is HELD, and a terminal
        // reservation - whose row deliberately outlives its account, cash_reservation carrying no foreign key
        // - is answered by settle/release without ever asking for the account. An absent row here therefore
        // means a hold whose funds have nowhere to return to, most likely a row changed outside this service,
        // and INVALID_TRANSITION is the truthful answer: the transition cannot be performed. The ledger query
        // remains that owner's audit path either way.
        return accounts.findByOwnerForUpdate(owner)
                .orElseThrow(() -> CashAccountException.forReservation(
                        CashAccountErrorCode.INVALID_TRANSITION, reservationId,
                        "The account this reservation was placed against no longer exists."));
    }

    private CashReservation lockReservation(UUID reservationId) {
        return reservations.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> CashAccountException.forReservation(
                        CashAccountErrorCode.RESERVATION_NOT_FOUND, reservationId));
    }

    private static String requireIdempotencyKey(String raw, String owner) {
        if (raw == null || raw.isBlank()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED, owner);
        }
        String candidate = raw.strip();
        // Length is refused here rather than by the VARCHAR(128) column: as a constraint violation it would
        // reach hold's catch block, which reads one as the idempotency race and would replay the wrong answer.
        if (candidate.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw CashAccountException.forOwner(CashAccountErrorCode.IDEMPOTENCY_KEY_REQUIRED, owner,
                    "The Idempotency-Key header must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH
                            + " characters.");
        }
        return candidate;
    }

    /*
     * WHY THE AMOUNT IS NORMALIZED AND COMPARED BEFORE Money IS CONSTRUCTED. Money.of applies the
     * NUMERIC(9,2) storage ceiling and answers anything above it with AMOUNT_OUT_OF_RANGE (422) - a code the
     * closed hold error set does not contain (AAP 0.6.2). A hold of 50,000,000.00 against a 1,000.00 account
     * is simply a request for more than the account can cover, and INSUFFICIENT_FUNDS is the code the
     * contract names for that; answering with a range error would also tell the caller its amount was
     * unrepresentable when what it needs to know is that the funds are not there. No available balance can
     * exceed that ceiling, so the comparison is decisive here, and the authoritative sufficiency check stays
     * where both the retail debit and the hold can share it - Money.minus, under the account lock, through
     * domain/ReservationStateMachine.hold.
     */
    private static Money requireHoldAmount(BigDecimal raw, String owner) {
        if (raw == null) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        // Scaled the way Money scales, so the value judged is the value that would be stored: a hold of
        // 0.004 reserves nothing once stored and is refused as the zero it becomes.
        BigDecimal normalized = raw.setScale(Money.SCALE, Money.ROUNDING);
        // Money permits zero because a retail credit or debit of zero is legal; a hold of zero is not, so the
        // institutional path refuses it rather than reserving nothing under a live reservation. A negative
        // amount is the same 400, which is why one comparison covers both.
        if (normalized.signum() <= 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        if (normalized.compareTo(Money.MAX_VALUE) > 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INSUFFICIENT_FUNDS, owner,
                    "The hold exceeds the largest balance an account can hold.");
        }
        return Money.of(normalized);
    }

    /*
     * The settle counterpart of the same rule: above the ceiling Money.of raises AMOUNT_OUT_OF_RANGE, which
     * the closed settle error set does not contain either (AAP 0.6.2). No held amount can exceed the ceiling,
     * so an amount above it necessarily exceeds what was held - the contract's INVALID_AMOUNT (> held) case.
     * The comparison against the reservation's own held amount is deliberately NOT made here: it belongs to
     * domain/ReservationStateMachine, which judges it against the row it locked. A null amount is the
     * documented full settlement and is passed through untouched for the state machine to resolve.
     *
     * The sign is judged on the RAW value, before scaling, exactly as Money.of judges it: -0.001 truncates
     * DOWN to 0.00 at scale 2, and a sign read after that normalization would accept a negative caller value
     * as the legal zero settlement - settling nothing while releasing the whole hold (AAP 0.6.2 binds a
     * negative amount to 400 INVALID_AMOUNT). Only the ceiling is compared on the stored scale.
     */
    private static Money requireSettleAmount(BigDecimal raw, UUID reservationId) {
        if (raw == null) {
            return null;
        }
        if (raw.signum() < 0) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_AMOUNT, reservationId);
        }
        BigDecimal normalized = raw.setScale(Money.SCALE, Money.ROUNDING);
        if (normalized.compareTo(Money.MAX_VALUE) > 0) {
            throw CashAccountException.forReservation(CashAccountErrorCode.INVALID_AMOUNT, reservationId);
        }
        return Money.of(normalized);
    }

    private static String requireCurrency(String raw, String owner) {
        if (raw == null) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        // Shape only: membership of the accepted-currency set is settled when the account is created, and the
        // hold's currency must equal the account's, which the state machine checks.
        String candidate = raw.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(candidate).matches()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_CURRENCY, owner);
        }
        return candidate;
    }

    private static String canonicalRequestHash(String orderReference, Money amount, String currency,
            OffsetDateTime expiresAt) {
        // The canonical form makes replay detection insensitive to what does not matter and sensitive to what
        // does: orderReference is compared exactly with no case folding, 10 / 10.0 / 10.00 fix to one scale
        // and hash alike, and an omitted expiry contributes a literal so a replay that also omits it matches
        // despite the server-generated default being time-dependent.
        //
        // The expression is the one AAP 0.7.3 fixes, component for component, and nothing is normalized on
        // the way in beyond what that clause names: the order reference is hashed exactly as the caller sent
        // it - no case folding and no trimming - and an explicit expiry contributes its full instant. Two
        // payloads that differ anywhere in this string are two different requests under one key, which the
        // contract answers with 422 IDEMPOTENCY_KEY_REUSED rather than with the stored hold. What makes a
        // replay of the values the first response handed back match is therefore not a rule applied here but
        // domain/CashReservation storing the order reference exactly as it was hashed.
        String canonical = orderReference + '|'
                + amount.amount().setScale(Money.SCALE, Money.ROUNDING).toPlainString() + '|'
                + currency + '|'
                + (expiresAt == null ? DEFAULT_EXPIRY_TOKEN : expiresAt.toInstant().toString());
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(HASH_ALGORITHM + " is required of every Java platform",
                    unavailable);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
