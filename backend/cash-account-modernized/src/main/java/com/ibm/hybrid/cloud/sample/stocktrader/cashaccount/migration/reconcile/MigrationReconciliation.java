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

// This row is the evidence, which is the whole reason the type exists: a reconciliation or dual-run
// difference is never allowed to live only in a log line, so every finding is persisted with its kind,
// both sides and a disposition, and the operational runbook's bulk-migration and shadow-run sign-offs
// are granted against the set of these rows for a run rather than against a console transcript. It is
// also the answer to the legacy status channel this module replaces: MOVE SQLCODE TO WS-RETCODE
// (backend/cash-account-cobol/COBOL/CASH00.cbl:L104) rendered one code into an alphanumeric field
// without its sign, so a caller learned neither which condition fired nor what the two sides held.
//
// Only the table name is declared. The (run_id, status) index is idx_migration_reconciliation_run_status
// in schema/cash-account-schema.sql and the foreign key to migration_run is fk_migration_reconciliation_run
// there; ddl-auto=validate creates neither, so repeating them in @Table would add a second place to keep
// in step while enforcing nothing.
/** One recorded difference between the legacy export and the migrated state: the evidence a variance is. */
@Entity
@Table(name = "migration_reconciliation")
public class MigrationReconciliation {

    /** Width of {@code legacy_value} and {@code migrated_value} in the schema. */
    private static final int MAX_VALUE_LENGTH = 64;

    /** Scale of every monetary column here, and of the signed difference between two of them. */
    private static final int MONEY_SCALE = 2;

    // Database-generated, unlike migration_run's assigned run_id: a finding has no natural identity - two
    // BALANCE rows for one owner in one run are both legitimate - so the column is BIGINT GENERATED ALWAYS
    // AS IDENTITY and the INSERT must not supply a value, which is exactly what GenerationType.IDENTITY
    // does. SEQUENCE or AUTO would look for a sequence generator that does not exist and, worse, would send
    // a value the column rejects outright.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reconciliation_id", nullable = false, updatable = false)
    private Long reconciliationId;

    // A plain UUID and deliberately not a @ManyToOne to MigrationRun: MigrationReconciliationRepository
    // derives findByRunIdOrderByReconciliationIdAsc(UUID), findByRunIdAndStatus(UUID, ReconciliationStatus)
    // and countByRunIdAndStatus(UUID, ReconciliationStatus) from this attribute, and an association would
    // force every one of them to findByRun_RunId(...) instead. Referential integrity is not lost by that
    // choice - the foreign key to migration_run(run_id) is enforced by the schema - and nothing in the
    // tooling navigates from a finding to its run, so an association would buy a lazy proxy and a second
    // query per row for no caller.
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    // VARCHAR(32) carries the legacy CHAR(32) key width exactly (DB2DDL.jcl:L47 and DCLCASH.cpy:L9). Not
    // always an account owner: a RATE_SOURCE row keys on the exported rate-table currency (the 'ZZZ' null-rate
    // finding, for one), so nothing here normalizes or validates the value as an owner - the caller records
    // the key its finding is about, already uppercased where it is an owner.
    @Column(name = "owner", nullable = false, length = 32)
    private String owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "variance_kind", nullable = false, length = 24)
    private VarianceKind varianceKind;

    // The text renderings of the two sides, or the reason token of a non-monetary kind (MISSING_IN_TARGET,
    // NULL_IN_LEGACY, INVALID_IN_LEGACY, NULL_RATE, INSUFFICIENT_FUNDS...). Both nullable because a finding
    // that one side is absent has nothing to render for it, and recording an absence as an empty string
    // would be indistinguishable from an exported empty field - which is itself a characterized condition.
    @Column(name = "legacy_value", length = MAX_VALUE_LENGTH)
    private String legacyValue;

    @Column(name = "migrated_value", length = MAX_VALUE_LENGTH)
    private String migratedValue;

    // precision and scale are declared rather than left to Hibernate's defaults on every monetary attribute:
    // the default scale would be validated against NUMERIC(9,2) at start-up and drift, and a money path in
    // this module is BigDecimal at scale 2 throughout - the legacy PIC 9(7)V99 / DECIMAL(9,2) precision
    // (CASH00.cbl:L17, DB2DDL.jcl:L48) reproduced exactly, with no float anywhere near it.
    //
    // Nullable, and never defaulted to zero: a MISSING_IN_TARGET or MISSING_IN_LEGACY finding has no balance
    // on the missing side, and writing 0.00 there would assert a balance the export never stated.
    @Column(name = "legacy_balance", precision = 9, scale = MONEY_SCALE)
    private BigDecimal legacyBalance;

    @Column(name = "migrated_balance", precision = 9, scale = MONEY_SCALE)
    private BigDecimal migratedBalance;

    // Ten digits where both operands are nine: a signed difference of two NUMERIC(9,2) values needs one
    // integer digit more than either of them, so the column has the headroom instead of overflowing on the
    // very row that reports the largest discrepancy.
    @Column(name = "variance", precision = 10, scale = MONEY_SCALE)
    private BigDecimal variance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReconciliationStatus status;

    // OffsetDateTime for TIMESTAMPTZ, never Instant, LocalDateTime or Timestamp: the first two lose the
    // offset the column stores, and a finding's timestamp is read back by an operator in the runbook's
    // evidence, where a zone-less reading of a cross-region run is unusable. The schema's DEFAULT now() is
    // there for a hand-written INSERT; the factories always stamp it, so the recorded time is the moment the
    // tooling observed the difference rather than the moment the row reached the database.
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    // No @Version. The row is written once by the command that produced it, and its only later write is the
    // operator's reclassification during sign-off - a deliberate, single-writer manual action, never two
    // concurrent tool invocations touching one finding - so an optimistic-lock column would guard a race
    // that cannot arise and would fail the reclassification on a stale read instead.

    protected MigrationReconciliation() {
        // Required by JPA; every application-created instance comes from of(...), balance(...) or text(...).
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
     * Records one difference with every column supplied explicitly, stamping {@code recordedAt}; the
     * single construction path, which {@link #balance} and {@link #text} delegate to.
     *
     * <p>{@code runId}, {@code owner}, {@code varianceKind} and {@code status} are required and are
     * rejected with {@link NullPointerException} naming the argument; the value and balance columns are
     * nullable because a finding about an absent side has nothing to record there. This is tooling with no
     * HTTP surface, so a missing required argument raises no service exception and maps to no status code:
     * it is a programming error in the caller, not a condition an operator can act on.</p>
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
     * Records a {@link VarianceKind#BALANCE} difference: both balances, their plain-decimal renderings and
     * the signed variance, computed here so no call site can get the sign or the scale wrong.
     */
    public static MigrationReconciliation balance(UUID runId,
                                                  String owner,
                                                  BigDecimal legacyBalance,
                                                  BigDecimal migratedBalance,
                                                  ReconciliationStatus status) {
        return of(runId, owner, VarianceKind.BALANCE, status,
                render(legacyBalance), render(migratedBalance),
                legacyBalance, migratedBalance,
                signedVariance(legacyBalance, migratedBalance));
    }

    /**
     * Records a non-monetary finding - {@code STATE}, {@code CURRENCY}, {@code RATE_SOURCE},
     * {@code REJECTED_BY_TARGET} or {@code TRANSACTION_COUNT} - whose two sides are text: a currency code,
     * a reason token or a count.
     */
    public static MigrationReconciliation text(UUID runId,
                                               String owner,
                                               VarianceKind kind,
                                               ReconciliationStatus status,
                                               String legacyValue,
                                               String migratedValue) {
        return of(runId, owner, kind, status, legacyValue, migratedValue, null, null, null);
    }

    // The sign convention, fixed in this one place: variance = migrated - legacy. ReconciliationService and
    // shadow/ShadowComparator both depend on it and their integration tests assert the signed value, so it
    // is stated here rather than reimplemented per call site. It matches the expectations the plan names: a
    // KARRI row of legacy 12345.67 against migrated 12345.76 is +0.09, and the shadow seed of legacy 1250.60
    // against target 1250.50 is -0.10. Null when either side is absent, because the difference from a
    // balance that was never stated is not zero - it is unknown, and a 0.00 variance on a MISSING_IN_TARGET
    // row would read as agreement.
    private static BigDecimal signedVariance(BigDecimal legacyBalance, BigDecimal migratedBalance) {
        if (legacyBalance == null || migratedBalance == null) {
            return null;
        }
        BigDecimal difference = migratedBalance.subtract(legacyBalance);
        // BigDecimal throughout and never double: a binary float cannot hold a two-decimal balance exactly,
        // and a penny of drift here is a reconciliation break invented by the tool that reports it. The
        // rescale engages only if a caller hands in a wider value than the NUMERIC(9,2) columns hold, and
        // it truncates toward zero because that is the rounding the legacy COMPUTE applied - CASH00.cbl:L222
        // and L256 carry no ROUNDED - so the tooling never reports a difference the legacy would not have.
        return difference.scale() == MONEY_SCALE
                ? difference
                : difference.setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private static String render(BigDecimal balance) {
        // toPlainString, never toString: a value normalized to an exponent ("1.0E+7") is unreadable as
        // evidence and would not match the plain-decimal shape the legacy exports and this module's own
        // wire format use.
        return balance == null ? null : balance.toPlainString();
    }

    // legacy_value and migrated_value are VARCHAR(64). Every value they carry in practice - a plain decimal,
    // a three-letter currency code, a reason token, a count - is far shorter, so this guard never fires. It
    // exists so that if one ever did, the row is still written and the finding survives, rather than the
    // insert failing and losing the very difference the run was executed to record.
    private static String fit(String value) {
        return value == null || value.length() <= MAX_VALUE_LENGTH
                ? value
                : value.substring(0, MAX_VALUE_LENGTH);
    }

    /**
     * Reclassifies a reviewed finding, the runbook's sign-off path: the data owner accepts an outstanding
     * {@code VARIANCE} as an {@code ACCEPTED_EXCEPTION} with a written reason, or marks it {@code MATCHED}
     * once it is resolved.
     *
     * <p>The only mutator on the type. The run, owner, kind and both sides of the difference are the
     * evidence itself and stay unwritable after construction, so a sign-off can change what a finding
     * <em>means</em> and never what it <em>says</em>.</p>
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
    // findings are equal only to themselves, which is correct here because a run may legitimately record
    // two rows carrying identical values.
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

    // Carries what identifies a finding in a failing assertion - which owner, which kind, which disposition
    // and by how much - and deliberately nothing else: the value columns can hold an exported balance, and
    // this string reaches logs.
    @Override
    public String toString() {
        return "MigrationReconciliation{owner=" + owner
                + ", varianceKind=" + varianceKind
                + ", status=" + status
                + ", variance=" + variance
                + '}';
    }
}
