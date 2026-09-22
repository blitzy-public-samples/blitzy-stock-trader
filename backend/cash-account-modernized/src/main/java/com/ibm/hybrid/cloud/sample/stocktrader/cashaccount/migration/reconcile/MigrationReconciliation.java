package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** One recorded difference between the legacy export and the migrated state: the evidence a variance is. */
@Entity
@Table(name = "migration_reconciliation")
public class MigrationReconciliation {

    /**
     * The {@code legacy_value} of a {@link VarianceKind#BALANCE} row whose legacy side stated no balance,
     * named rather than left blank so the row cannot be read as a comparison that failed to complete.
     */
    public static final String ABSENT_IN_CAPTURE = "ABSENT_IN_CAPTURE";

    /** Width of {@code legacy_value} and {@code migrated_value} in the schema. */
    private static final int MAX_VALUE_LENGTH = 64;

    /** Scale of every monetary column here, and of the signed difference between two of them. */
    private static final int MONEY_SCALE = 2;

    // A finding has no natural identity - two BALANCE rows for one owner in one run are both legitimate - so
    // the column is BIGINT GENERATED ALWAYS AS IDENTITY, which rejects a supplied value: IDENTITY is the one
    // strategy that sends none, where SEQUENCE or AUTO would look for a generator that does not exist.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reconciliation_id", nullable = false, updatable = false)
    private Long reconciliationId;

    // A plain UUID and deliberately not a @ManyToOne: the repositories derive their finders from this
    // attribute rather than through an association path, nothing in the tooling navigates from a finding to
    // its run, and referential integrity is the schema's foreign key to migration_run(run_id) either way.
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    // Not always an account owner: a RATE_SOURCE row keys on the exported rate-table currency, so the value
    // is recorded as the caller's finding names it and is neither normalized nor validated as an owner here.
    @Column(name = "owner", nullable = false, length = 32)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "variance_kind", nullable = false, length = 24)
    private VarianceKind varianceKind;

    // Nullable because a kind may leave a side with nothing to state - a count row states no balance on
    // either side - and an empty string would be indistinguishable from an exported empty field, which is
    // itself a characterized condition.
    @Column(name = "legacy_value", length = MAX_VALUE_LENGTH)
    private String legacyValue;

    @Column(name = "migrated_value", length = MAX_VALUE_LENGTH)
    private String migratedValue;

    // Nullable, and never defaulted to zero: a MISSING_IN_TARGET or MISSING_IN_LEGACY finding has no balance
    // on the missing side, and writing 0.00 there would assert a balance the export never stated.
    @Column(name = "legacy_balance", precision = 9, scale = MONEY_SCALE)
    private BigDecimal legacyBalance;

    @Column(name = "migrated_balance", precision = 9, scale = MONEY_SCALE)
    private BigDecimal migratedBalance;

    // Ten digits where both operands are nine: a signed difference of two NUMERIC(9,2) balances needs one
    // integer digit more than either, so the column has headroom instead of overflowing on the very row
    // that reports the largest discrepancy.
    @Column(name = "variance", precision = 10, scale = MONEY_SCALE)
    private BigDecimal variance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationStatus status;

    // OffsetDateTime, never LocalDateTime or java.sql.Timestamp: the first maps to "timestamp without time
    // zone", which ddl-auto=validate rejects against TIMESTAMPTZ, and the last resolves its instant against
    // the JVM default zone. The factories always stamp it, so the row carries the moment the tooling
    // observed the difference rather than the moment it reached the database.
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    // No @Version: the row is written once by the command that produced it and its only later write is the
    // operator's reclassification at sign-off, a single-writer action, so an optimistic-lock column would
    // guard a race that cannot arise and would fail that reclassification on a stale read instead.

    protected MigrationReconciliation() {
    }

    private MigrationReconciliation(UUID runId,
                                    String owner,
                                    VarianceKind varianceKind,
                                    ReconciliationStatus status,
                                    String legacyValue,
                                    String migratedValue,
                                    BigDecimal legacyBalance,
                                    BigDecimal migratedBalance,
                                    BigDecimal variance) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.varianceKind = Objects.requireNonNull(varianceKind, "varianceKind");
        this.status = Objects.requireNonNull(status, "status");
        this.legacyValue = fit(legacyValue);
        this.migratedValue = fit(migratedValue);
        this.legacyBalance = legacyBalance;
        this.migratedBalance = migratedBalance;
        this.variance = variance;
        this.recordedAt = OffsetDateTime.now();
    }

    /**
     * Records one difference with every column supplied explicitly; the single construction path, which
     * {@link #balance} and {@link #text} delegate to.
     *
     * @param runId           the run this finding belongs to
     * @param owner           the key the finding is about, an account owner or a rate-table currency
     * @param varianceKind    which comparison produced it
     * @param status          its disposition
     * @param legacyValue     the legacy side as text, or a reason token, or {@code null}
     * @param migratedValue   the migrated side as text, or a reason token, or {@code null}
     * @param legacyBalance   the legacy balance, or {@code null} where that side stated none
     * @param migratedBalance the migrated balance, or {@code null} where that side stated none
     * @param variance        the signed difference, or {@code null} where either side is absent
     * @return the finding, stamped with the moment it was observed
     * @throws NullPointerException if {@code runId}, {@code owner}, {@code varianceKind} or
     *         {@code status} is {@code null}
     */
    public static MigrationReconciliation of(UUID runId,
                                             String owner,
                                             VarianceKind varianceKind,
                                             ReconciliationStatus status,
                                             String legacyValue,
                                             String migratedValue,
                                             BigDecimal legacyBalance,
                                             BigDecimal migratedBalance,
                                             BigDecimal variance) {
        return new MigrationReconciliation(runId, owner, varianceKind, status, legacyValue,
                migratedValue, legacyBalance, migratedBalance, variance);
    }

    /**
     * Records a {@link VarianceKind#BALANCE} difference, deriving both renderings and the signed variance
     * here so that no call site can get the sign or the scale wrong.
     *
     * @param runId           the run this finding belongs to
     * @param owner           the account owner compared
     * @param legacyBalance   the legacy balance, or {@code null} when the capture stated none, which
     *                        renders as {@link #ABSENT_IN_CAPTURE} and leaves the variance unknowable
     * @param migratedBalance the balance the target answered with
     * @param status          its disposition
     * @return the finding, stamped with the moment it was observed
     */
    public static MigrationReconciliation balance(UUID runId,
                                                  String owner,
                                                  BigDecimal legacyBalance,
                                                  BigDecimal migratedBalance,
                                                  ReconciliationStatus status) {
        return of(runId, owner, VarianceKind.BALANCE, status,
                legacyBalance == null ? ABSENT_IN_CAPTURE : render(legacyBalance), render(migratedBalance),
                legacyBalance, migratedBalance,
                signedVariance(legacyBalance, migratedBalance));
    }

    /**
     * Records a non-monetary finding - {@code STATE}, {@code CURRENCY}, {@code RATE_SOURCE},
     * {@code REJECTED_BY_TARGET} or {@code TRANSACTION_COUNT} - whose two sides are text.
     *
     * @param runId         the run this finding belongs to
     * @param owner         the key the finding is about
     * @param kind          which comparison produced it
     * @param status        its disposition
     * @param legacyValue   the legacy side: a currency code, a reason token or a count
     * @param migratedValue the migrated side, in the same terms
     * @return the finding, stamped with the moment it was observed
     */
    public static MigrationReconciliation text(UUID runId,
                                               String owner,
                                               VarianceKind kind,
                                               ReconciliationStatus status,
                                               String legacyValue,
                                               String migratedValue) {
        return of(runId, owner, kind, status, legacyValue, migratedValue, null, null, null);
    }

    // The sign convention, fixed in this one place so that no call site reimplements it: variance =
    // migrated - legacy. Null when either side is absent, because the difference from a balance that was
    // never stated is unknown rather than zero, and a 0.00 variance would read as agreement.
    private static BigDecimal signedVariance(BigDecimal legacyBalance, BigDecimal migratedBalance) {
        if (legacyBalance == null || migratedBalance == null) {
            return null;
        }
        BigDecimal difference = migratedBalance.subtract(legacyBalance);
        // The rescale engages only for a value wider than the NUMERIC(9,2) columns hold, and truncates
        // toward zero because the legacy COMPUTE carried no ROUNDED (CASH00.cbl:L222, L256), so the tooling
        // never reports a difference the legacy arithmetic would not have produced.
        return difference.scale() == MONEY_SCALE
                ? difference
                : difference.setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static String render(BigDecimal balance) {
        // toPlainString, never toString: an exponent form ("1.0E+7") reads badly as evidence and does not
        // match the plain-decimal shape the legacy exports and this module's wire format use.
        return balance == null ? null : balance.toPlainString();
    }

    // No value carried in practice approaches the column width, so this guard never fires; it exists so
    // that if one ever did, the row is still written rather than the insert failing and losing the very
    // difference the run was executed to record.
    private static String fit(String value) {
        return value == null || value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH);
    }

    /**
     * Reclassifies a reviewed finding, the runbook's sign-off path, and the only mutator on the type: the
     * run, owner, kind and both sides stay unwritable, so a sign-off changes what a finding means and
     * never what it says.
     *
     * @param newStatus the disposition the reviewer assigns, {@code ACCEPTED_EXCEPTION} for an authorized
     *                  difference or {@code MATCHED} once it is resolved
     * @throws NullPointerException if {@code newStatus} is {@code null}
     */
    public void reclassify(ReconciliationStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus");
    }

    public Long reconciliationId() {
        return reconciliationId;
    }

    public UUID runId() {
        return runId;
    }

    public String owner() {
        return owner;
    }

    public VarianceKind varianceKind() {
        return varianceKind;
    }

    public String legacyValue() {
        return legacyValue;
    }

    public String migratedValue() {
        return migratedValue;
    }

    public BigDecimal legacyBalance() {
        return legacyBalance;
    }

    public BigDecimal migratedBalance() {
        return migratedBalance;
    }

    public BigDecimal variance() {
        return variance;
    }

    public ReconciliationStatus status() {
        return status;
    }

    public OffsetDateTime recordedAt() {
        return recordedAt;
    }

    // Identity is the generated key alone, and only once the database has assigned it: two unpersisted
    // findings are equal only to themselves, which is right where a run may record two identical rows.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MigrationReconciliation that)) {
            return false;
        }
        return reconciliationId != null && reconciliationId.equals(that.reconciliationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reconciliationId);
    }

    // Excludes the value columns deliberately: they can hold an exported balance and this string reaches
    // logs.
    @Override
    public String toString() {
        return "MigrationReconciliation{owner=" + owner
                + ", varianceKind=" + varianceKind
                + ", status=" + status
                + ", variance=" + variance
                + '}';
    }
}
