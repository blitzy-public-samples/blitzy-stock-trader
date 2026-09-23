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
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
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
     * The {@code cash_reservation} idempotency guard {@code UNIQUE (incarnation_id, idempotency_key)} by name,
     * because only a violation of this constraint is the hold race.
     */
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_cash_reservation_incarnation_key";

    /** The literal an omitted {@code expiresAt} contributes to the canonical hash. */
    private static final String DEFAULT_EXPIRY_TOKEN = "DEFAULT";

    // The two reservation durations, named once here so the readers, the guard and the @Scheduled expression below
    // cannot drift apart. Both defaults are the ones application.yml ships (AAP 0.6.1).
    private static final String DEFAULT_TTL_PROPERTY = "cashaccount.reservation.default-ttl";

    private static final String EXPIRY_SWEEP_INTERVAL_PROPERTY = "cashaccount.reservation.expiry-sweep-interval";

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private static final Duration DEFAULT_EXPIRY_SWEEP_INTERVAL = Duration.ofSeconds(60);

    private final CashAccountRepository accounts;
    private final CashReservationRepository reservations;
    private final LedgerService ledger;
    private final ObjectProvider<ReservationService> self;
    private final Duration defaultTtl;

    /**
     * Container constructor.
     *
     * @param accounts     the locked-first account rows
     * @param reservations the reservation rows, locked after the account
     * @param ledger       appends every transition's row inside this service's own transaction
     * @param self         this bean through its proxy, because the transactional units are reached from
     *                     non-transactional methods of this same class
     * @param environment  source of both reservation durations, each read through {@link Binder}
     * @throws IllegalStateException if either duration is unparseable or not positive
     */
    public ReservationService(CashAccountRepository accounts, CashReservationRepository reservations,
            LedgerService ledger, ObjectProvider<ReservationService> self, Environment environment) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.self = Objects.requireNonNull(self, "self");
        this.defaultTtl = positiveDuration(DEFAULT_TTL_PROPERTY, durationFrom(environment, DEFAULT_TTL_PROPERTY,
                DEFAULT_TTL));

        // Read here only to refuse it, which is why the value is not kept: the sweep below is declared with
        // @Scheduled(fixedRateString = ...) as AAP 0.6.3 requires, and Spring resolves that placeholder and then
        // hands the RESOLVED TEXT to its expression resolver - the same sink a @Value carries - so an interval
        // written as #{...} would execute. Binding the identical key here, inertly, closes that path: a Duration
        // bind fails on anything that is not a parseable positive duration, and a constructor always runs before
        // ScheduledAnnotationBeanPostProcessor processes the annotation, so by the time the annotation is read the
        // value has already been proven to be a duration and cannot be an expression.
        positiveDuration(EXPIRY_SWEEP_INTERVAL_PROPERTY,
                durationFrom(environment, EXPIRY_SWEEP_INTERVAL_PROPERTY, DEFAULT_EXPIRY_SWEEP_INTERVAL));
    }

    /**
     * A hold's result together with whether it replayed an earlier one.
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
            // First-writer-wins on UNIQUE (incarnation_id, idempotency_key): the loser lands here, and the
            // winner's row is visible only to a transaction opened after this rollback, which is why the hash
            // comparison happens in replayFor. Any other integrity violation is rethrown to the handler's 500
            // rather than answered by replayFor, which would claim a write that never happened.
            if (!violatesIdempotencyConstraint(race)) {
                throw race;
            }
            return proxy.replayFor(incarnationId, key, requestHash);
        }
    }

    /**
     * The transactional unit of {@link #hold}: locks the account, replays or creates, appends the ledger row.
     *
     * <p>This method, {@link #replayFor}, {@link #settleOnce}, {@link #releaseOnce} and
     * {@link #expireOneCandidate} are public only because a transactional method has to be entered through
     * the Spring proxy - reached as a plain {@code this.method(...)} call it would run with no transaction at
     * all, which is why every caller goes through {@code self.getObject()}. Their callers stay
     * non-transactional because each has work to do outside the transaction that writes.</p>
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

        // Any row this owner-scoped query returns belongs to an earlier incarnation, the current one having
        // been answered above, so the key is reuse and never a replay whatever its hash: the retained hold
        // reserved funds in an account life that no longer exists. UNIQUE (incarnation_id, idempotency_key)
        // cannot see it, and the check is race-free only here, under the cash_account row lock a retail DELETE
        // must also take before it can change the incarnation.
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

        // The order reference is stored exactly as it arrived, which is what canonicalRequestHash hashed, so a
        // verbatim retry hashes back to the same digest and is recognized as the replay it is.
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

    // Asked two ways because neither alone is dependable: Hibernate's extractor puts the name on a
    // ConstraintViolationException when it can, and the fallback matches the name inside the message, which
    // PostgreSQL does not localize even where it localizes the surrounding prose. The whole cause chain is
    // walked, since Spring wraps the exceptions that carry the name, and folded case because PostgreSQL does.
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

    // The newest retained row is the one the rejection names, and reservationId breaks a created_at tie so the
    // ApiError payload is identical on every call and every pod, which row order alone would not guarantee.
    private static CashReservation mostRecentlyCreated(List<CashReservation> retained) {
        return retained.stream()
                .max(Comparator.comparing(CashReservation::createdAt)
                        .thenComparing(CashReservation::reservationId))
                // Unreachable: the only caller has already established the list is non-empty.
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
                // No row under this key means the owner was re-created between the read and the write, which a
                // retryable 409 states honestly where a 500 would blame the caller for a repeatable race.
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
        // One unlocked read establishes that the reservation exists, its state, and whose account row a
        // mutation would have to lock first.
        CashReservation stored = requireReservation(reservationId);
        // Parsed before anything is locked or written, so a request refused as INVALID_AMOUNT leaves no state
        // change behind it.
        Money settleAmount = requireSettleAmount(request == null ? null : request.amount(), reservationId);

        // A terminal reservation is answered without the account row: its rows are retained after a retail
        // DELETE (AAP 0.11.1), so it legitimately outlives the account it names, and its outcome moves no
        // money. Locking the account first would turn such a call on a deleted owner into a 409 about the
        // account instead, so the lock is taken only for the HELD case that actually moves funds.
        if (!stored.isHeld()) {
            ReservationStateMachine.settleFromTerminal(stored);
            return ReservationResponse.from(stored);
        }

        // An overdue hold is expired by the request that touched it, and a settle from EXPIRED is refused - but
        // CashAccountException is unchecked, so raising that 409 inside the transaction would roll the expiry
        // back with it and lose the EXPIRY ledger row every transition owes the audit trail (AAP 0.7.4).
        // settleOnce commits whichever outcome it reached and reports which; the refusal follows the commit.
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
        // have made the row terminal in between, and judging it as it stands here is what keeps exactly one
        // terminal transition and one ledger row per hold.
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
        // where a release would, so the committed EXPIRED outcome is the answer rather than a 409.
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
        // ledger row per hold: a sweeper or a settle that won the race leaves nothing for this call to do.
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
    // The interval this expression names is bound and proven to be a positive duration by the constructor, which
    // the container runs first, so the placeholder's resolved text can never be an expression by the time Spring
    // reads it here.
    @Scheduled(fixedRateString = "${" + EXPIRY_SWEEP_INTERVAL_PROPERTY + ":PT60S}")
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
                // the batch; the next pass retries it.
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
        // The key claims "this is the same call"; the hash decides whether the claim is true. A key presented
        // with a different payload is reuse (422), not a replay, because answering it with the stored
        // reservation would silently discard a hold the caller genuinely asked for.
        if (!requestHash.equals(stored.requestHash())) {
            throw CashAccountException.forReservation(CashAccountErrorCode.IDEMPOTENCY_KEY_REUSED,
                    stored.reservationId());
        }
        // originalHold, not from: a replay owes the caller the answer its original call received (AAP 0.6.2,
        // 0.7.3), so the row's live state must not reach a client that is merely retrying the create.
        return new HoldOutcome(ReservationResponse.originalHold(stored), true);
    }

    // The one place a lapsed hold is expired, shared by the settle and release paths and by the sweep; an
    // empty Optional means nothing was overdue and the caller performs the transition it was asked for. The
    // ledger source is SYSTEM even when a caller's request noticed it, because the event is the TTL elapsing,
    // which leaves a lazily expired hold indistinguishable from one the scheduled sweep reached first.
    private Optional<ReservationResponse> expireLapsedHold(CashAccount account, CashReservation reservation,
            OffsetDateTime now) {

        ReservationStateMachine.Effect expiry = ReservationStateMachine.expire(account, reservation, now);
        if (expiry.idempotentNoOp()) {
            return Optional.empty();
        }
        return Optional.of(persist(account, reservation, expiry, LedgerEntry.Source.SYSTEM));
    }

    // Every balance effect written here was decided and applied by domain/ReservationStateMachine - a hold
    // moves available to reserved, a settle takes the settled part out and returns the remainder, a release or
    // expiry returns the whole hold - so this class persists the rows it named and never restates the rule.
    private ReservationResponse persist(CashAccount account, CashReservation reservation,
            ReservationStateMachine.Effect effect, LedgerEntry.Source source) {
        CashReservation persisted = reservations.save(reservation);
        accounts.save(account);
        appendLedger(account, persisted, effect, source);
        return ReservationResponse.from(persisted);
    }

    private void appendLedger(CashAccount account, CashReservation reservation,
            ReservationStateMachine.Effect effect, LedgerEntry.Source source) {
        // One row per effect, inside this transaction, so a partial settlement's SETTLEMENT and RELEASE rows
        // and the balance change commit together and are queryable the instant they do. Each row's after-state
        // comes from its own effect and is never recomputed here: which balances a leg leaves behind is a
        // balance effect, and the state machine is the only authority on those.
        for (ReservationStateMachine.LedgerEffect ledgerEffect : effect.ledgerEffects()) {
            ledger.append(account, reservation, ledgerEffect.eventType(), ledgerEffect.amount(),
                    ledgerEffect.availableAfter(), ledgerEffect.reservedAfter(), source);
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

    // The owner's cash_account row is the first lock on every path that moves money - request and sweep alike
    // - and only then is the reservation row read FOR UPDATE, which is what keeps the lock graph cycle-free.
    // Pessimistic row locking is the estate's strategy
    // [backend/portfolio/src/main/resources/META-INF/persistence.xml:L13-L14], used here on locking queries
    // only, so plain reads stay lock-free.
    private CashAccount lockAccountFor(String owner, UUID reservationId) {
        return accounts.findByOwnerForUpdate(owner)
                // Only the HELD paths reach this, and a retail DELETE is refused while any hold is HELD, so an
                // absent row means a hold whose funds have nowhere to return to - most likely a row changed
                // outside this service - and INVALID_TRANSITION is the truthful answer.
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

    // Compared before Money is constructed, because Money.of answers anything above the NUMERIC(9,2) ceiling
    // with AMOUNT_OUT_OF_RANGE, which the closed hold error set does not contain (AAP 0.6.2); no balance can
    // exceed that ceiling either, so such a hold is more than the account can cover. The authoritative
    // sufficiency check stays in Money.minus under the account lock, shared with the retail debit.
    private static Money requireHoldAmount(BigDecimal raw, String owner) {
        if (raw == null) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        // Scaled the way Money scales, so the value judged is the value that would be stored: a hold of 0.004
        // reserves nothing once stored and is refused as the zero it becomes.
        BigDecimal normalized = raw.setScale(Money.SCALE, Money.ROUNDING);
        // Money permits zero because a retail credit or debit of zero is legal; a hold of zero is not, and a
        // negative amount is the same 400, which is why one comparison covers both.
        if (normalized.signum() <= 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INVALID_AMOUNT, owner);
        }
        if (normalized.compareTo(Money.MAX_VALUE) > 0) {
            throw CashAccountException.forOwner(CashAccountErrorCode.INSUFFICIENT_FUNDS, owner,
                    "The hold exceeds the largest balance an account can hold.");
        }
        return Money.of(normalized);
    }

    // The settle counterpart of the same rule: an amount above the ceiling necessarily exceeds what was held,
    // the contract's INVALID_AMOUNT, while AMOUNT_OUT_OF_RANGE is outside the closed settle set (AAP 0.6.2).
    // Against the row's own held amount it is domain/ReservationStateMachine that compares, and a null amount
    // passes through as the full settlement. The sign is judged raw: -0.001 truncates DOWN to a legal zero.
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
        // The expression is the one AAP 0.7.3 fixes, component for component: the order reference is hashed
        // exactly as the caller sent it, no case folding or trimming; 10 / 10.0 / 10.00 fix to one scale and
        // hash alike; and an omitted expiry contributes a literal so a replay that omits it too matches the
        // time-dependent server default. Any other difference is two requests under one key - 422, not replay.
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

    // Binder, never a @Value placeholder: a placeholder's RESOLVED TEXT is then handed to Spring's expression
    // resolver, so a duration written as #{...} would execute while this service was being created. Binder resolves
    // ${...} and converts, evaluating nothing, so such a value fails the conversion instead of running. Reading it
    // from the Environment rather than injecting the typed properties object keeps this package free of config,
    // which wires everything and is depended on by nothing (AAP 0.8.2). A non-configurable Environment exposes no
    // property sources, so it yields the documented default exactly as an unset key does.
    private static Duration durationFrom(Environment environment, String key, Duration fallback) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return fallback;
        }
        return Binder.get(environment).bind(key, Bindable.of(Duration.class)).orElse(fallback);
    }

    // Refused at start-up rather than absorbed: a zero or negative TTL would make every hold expire the instant it
    // was taken, and a zero or negative sweep interval is not a schedule at all - both are misconfigurations an
    // operator has to see before the first hold, which is also the posture config/CashAccountProperties takes to
    // the same two keys.
    private static Duration positiveDuration(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(key + " must be a positive ISO-8601 duration, but was " + value);
        }
        return value;
    }
}
