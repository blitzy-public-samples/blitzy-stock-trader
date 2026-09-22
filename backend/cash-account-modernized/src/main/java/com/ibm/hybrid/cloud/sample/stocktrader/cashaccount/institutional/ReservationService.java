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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
 * needs MORE THAN ONE transaction to do its job: hold must re-read after PostgreSQL has aborted the
 * transaction its losing INSERT violated, settle and release must commit a lazy expiry before evaluating the
 * transition, and the sweep must give each candidate its own short transaction. A @Transactional method
 * reached as a plain this.method(...) call bypasses the proxy and would run with no transaction at all, so
 * every such call goes through self.getObject(). audit/LedgerService appends with Propagation.MANDATORY, which
 * turns a lost boundary into an immediate IllegalTransactionStateException rather than a silent no-op.
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

        Money amount = requireHoldAmount(request.amount(), normalizedOwner);
        String currency = requireCurrency(request.currency(), normalizedOwner);
        // A caller-supplied expiry already in the past is accepted as handed in - the hold is simply overdue
        // and the sweep terminates it - because the hold error set carries no code for a bad expiry.
        OffsetDateTime expiresAt = request.expiresAt() != null ? request.expiresAt() : now().plus(defaultTtl);

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
     *         {@link CashAccountErrorCode#INVALID_TRANSITION} from a released or expired reservation
     */
    public ReservationResponse settle(UUID reservationId, SettleRequest request) {
        String owner = requireReservation(reservationId).owner();
        Money settleAmount = request == null || request.amount() == null ? null : Money.of(request.amount());

        /*
         * WHY THE LAZY EXPIRY COMMITS IN ITS OWN TRANSACTION FIRST. The state machine expires an overdue hold
         * in memory and then evaluates the transition from EXPIRED, which rejects a settle with
         * INVALID_TRANSITION. That exception is unchecked, so a single transaction would roll the expiry back
         * with it and lose the EXPIRY ledger row every transition is required to leave behind. Committing the
         * expiry first makes both specified outcomes fall out: a settle then fails with 409 against a row that
         * is genuinely, durably EXPIRED, and a release returns 200 reporting EXPIRED. The amount above is
         * parsed before that commit for the same reason in reverse - a request rejected as INVALID_AMOUNT must
         * leave no state change behind it at all.
         */
        ReservationService proxy = self.getObject();
        proxy.expireOneCandidate(reservationId, owner);
        return proxy.settleOnce(reservationId, settleAmount);
    }

    /**
     * The transactional unit of {@link #settle}.
     *
     * @param reservationId the reservation to settle
     * @param settleAmount the amount to settle, or {@code null} for the full held amount
     * @return the reservation as it stands after the settlement
     */
    @Transactional
    public ReservationResponse settleOnce(UUID reservationId, Money settleAmount) {
        String owner = requireReservation(reservationId).owner();
        CashAccount account = lockAccountFor(owner, reservationId);
        CashReservation locked = lockReservation(reservationId);

        ReservationStateMachine.Effect effect = ReservationStateMachine.settle(account, locked,
                settleAmount != null ? settleAmount : locked.amount(), now());
        return persist(account, locked, effect, LedgerEntry.Source.INSTITUTIONAL);
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
        String owner = requireReservation(reservationId).owner();
        ReservationService proxy = self.getObject();
        proxy.expireOneCandidate(reservationId, owner);
        return proxy.releaseOnce(reservationId);
    }

    /**
     * The transactional unit of {@link #release}.
     *
     * @param reservationId the reservation to release
     * @return the reservation as it stands after the release
     */
    @Transactional
    public ReservationResponse releaseOnce(UUID reservationId) {
        String owner = requireReservation(reservationId).owner();
        CashAccount account = lockAccountFor(owner, reservationId);
        CashReservation locked = lockReservation(reservationId);

        ReservationStateMachine.Effect effect = ReservationStateMachine.release(account, locked, now());
        return persist(account, locked, effect, LedgerEntry.Source.INSTITUTIONAL);
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

        ReservationStateMachine.Effect effect = ReservationStateMachine.expire(account.get(), reservation, now);
        // SYSTEM, not INSTITUTIONAL: an expiry is the service acting on its own schedule with no caller
        // behind it, which is the distinction LedgerEntry.Source exists to record.
        persist(account.get(), reservation, effect, LedgerEntry.Source.SYSTEM);
        return true;
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
        return new HoldOutcome(ReservationResponse.from(stored), true);
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
        // Reservation rows deliberately outlive their account: cash_reservation carries no foreign key, and a
        // retail DELETE is refused with RESERVATIONS_OUTSTANDING while any hold is HELD. This is therefore
        // reachable only for an already-terminal reservation whose account was deleted afterwards, where
        // INVALID_TRANSITION is the truthful answer - and the ledger query remains that owner's audit path.
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

    private static Money requireHoldAmount(BigDecimal raw, String owner) {
        Money amount;
        try {
            amount = Money.of(raw);
        } catch (CashAccountException rejected) {
            // Only the owner is added, never the decision: Money owns what a valid amount is, and ApiError's
            // owner field is what tells an operator whose hold was refused.
            throw CashAccountException.forOwner(rejected.errorCode(), owner, rejected.getMessage(), rejected);
        }
        // Money permits zero because a retail credit or debit of zero is legal; a hold of zero is not, so the
        // institutional path refuses it rather than reserving nothing under a live reservation.
        if (amount.isZero()) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        return amount;
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
