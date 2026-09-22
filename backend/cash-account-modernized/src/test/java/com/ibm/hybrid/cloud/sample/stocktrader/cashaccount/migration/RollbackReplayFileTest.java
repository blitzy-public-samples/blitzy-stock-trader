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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.DelimitedExportReader;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.export.LegacyCashAccountRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/*
 * Why this test exists, and why it is the only place the derivation lives.
 *
 * Runbook Step 3 (AAP 0.3.3) has one hard part: once the cutover has let the target accept writes,
 * rollback is not a repoint but a state hand-back. The ledger rows above the cutover watermark W have
 * to become a file the mainframe team can replay through the *existing* CICS transaction, and the only
 * codes that transaction understands are the six of EVALUATE WS-REQ (CASH00.cbl:L89-L102). This test
 * proves that derivation - and nothing else about the rollback: the procedure itself is documented and
 * handed off, never executed here (AAP 0.3.1-0.3.2), so the input is the synthetic ledger range that
 * fixtures/shadow/MANIFEST.md records and the output is compared with a committed golden file. Nothing
 * in this class reaches a database, DB2 for z/OS or SYSD.STOCK.HISTORY, which is also why it is a
 * Surefire *Test with no Spring context and no Testcontainers.
 *
 * Why absolute state rather than the credits and debits that produced it: legacy C and D recompute the
 * balance as BALANCE +/- (RATES * BALANC-RATE) (CASH00.cbl:L221-L222 credit, L255-L256 debit), so a
 * replay built from them would reproduce the target's end state only if the rate rows in
 * STOCKTRD.FRANKFURT1 still held the values that were in force during the window. A/U/X carry the
 * balance as an absolute value and consult no rate at all, so replaying end state is exactly
 * rate-independent - which is the property that makes the hand-back safe. An "improvement" that emitted
 * C/D lines would quietly make it rate-sensitive and wrong, so the op field is asserted below.
 *
 * Why the derivation is a private method of this test and not a main-code class: AAP 0.6.1 declares no
 * deriver under src/main - Step 3 assigns the derivation to the operator - and domain/LedgerEntry could
 * not supply the input even if one existed, because its entry_id is IDENTITY-generated and every column
 * is mapped @Column(updatable = false) with no setters, so rows bearing assigned ids 101-114 cannot be
 * constructed. The row model below is therefore test-local, while the column contract
 * (LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS, MONEY_SCALE, DECIMAL_TEXT, isNull) and the event
 * vocabulary (LedgerEventType) are the real ones, so no literal of the file format is written twice.
 */

/** Asserts the Step 3 rollback replay file derived from a post-watermark ledger range matches its fixture. */
class RollbackReplayFileTest {

    /** The golden file this derivation must reproduce byte for byte, as a test-classpath resource. */
    private static final String GOLDEN_FILE_RESOURCE = "fixtures/shadow/rollback-replay.csv";

    /** The final legacy export whose owner set decides between op A and op U. */
    private static final String LEGACY_EXPORT_RESOURCE = "fixtures/legacy-export/matched/cashaccounty.csv";

    /** Legacy request codes the replay file may carry: add, absolute update, delete. */
    private static final Set<String> REPLAYABLE_OPS = Set.of("A", "U", "X");

    private static final String ADD_OP = "A";

    private static final String UPDATE_OP = "U";

    private static final String DELETE_OP = "X";

    private static final BigDecimal NO_RESERVED_FUNDS = new BigDecimal("0.00");

    // The eight incarnation ids of the range, as canonical version-4 UUID literals. They are literals
    // rather than UUID.randomUUID() values because the assertion is text equality with a committed
    // file: a generated id would differ on every run, and these round-trip through UUID.toString()
    // unchanged (fixtures/shadow/MANIFEST.md section 5).
    private static final UUID JOHN_INCARNATION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final UUID KARRI_INCARNATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final UUID GREG_INCARNATION = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static final UUID NEWCUST_INCARNATION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final UUID RAUNAK_INCARNATION = UUID.fromString("55555555-5555-4555-8555-555555555555");

    // RYAN is deleted and created again inside the range, so its rows carry two incarnation ids. Both
    // are needed: the derivation must emit one line for the owner and carry the *second* id, because a
    // line applied to the incarnation that no longer exists would hand the money back to a dead account
    // life. Two lines - X for the old life, U for the new - would replay as "delete, then update what is
    // no longer there" and leave legacy with no row at all.
    private static final UUID RYAN_FIRST_INCARNATION = UUID.fromString("66666666-6666-4666-8666-666666666666");

    private static final UUID RYAN_SECOND_INCARNATION = UUID.fromString("77777777-7777-4777-8777-777777777777");

    private static final UUID ERIC_INCARNATION = UUID.fromString("88888888-8888-4888-8888-888888888888");

    /**
     * One row of the ledger range being rolled back.
     *
     * <p>Every monetary field is a {@link BigDecimal} built from text, and no binary floating-point type
     * appears on a money path anywhere in this module (AAP 0.7.1): such a type reintroduces
     * representation error into values COBOL held exactly in {@code PIC 9(7)V99} (CASH00.cbl:L17), which
     * a replay file handed to the mainframe team cannot afford at any digit.
     */
    private record LedgerRow(
            long entryId,
            String owner,
            LedgerEventType eventType,
            BigDecimal amount,
            String currency,
            BigDecimal availableAfter,
            BigDecimal reservedAfter,
            UUID incarnationId) {
    }

    /** One derived output line, before it is ordered and numbered. */
    private record ReplayLine(
            String owner,
            String op,
            BigDecimal balance,
            String currency,
            long firstEntryId,
            long lastEntryId,
            UUID incarnationId) {
    }

    @Test
    void rollbackReplayFileDerivedFromALedgerRangeMatchesItsFixture() {
        Set<String> legacyOwners = finalLegacyExportOwners();
        List<LedgerRow> range = postWatermarkLedgerRange();

        String derived = deriveReplayFile(range, legacyOwners);

        // Line endings are normalized on the expected side only. The derivation always emits LF, so a
        // checkout with core.autocrlf=true that rewrote the committed fixture to CRLF would otherwise
        // fail this test for a reason that has nothing to do with the derivation being wrong.
        String golden = readGoldenFile().replace("\r\n", "\n");
        assertThat(derived)
                .as("the derived rollback replay file must equal %s byte for byte", GOLDEN_FILE_RESOURCE)
                .isEqualTo(golden);

        List<String> lines = derived.lines().toList();
        assertThat(lines.get(0))
                .as("line 1 must be the header declared once in LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS")
                .isEqualTo(String.join(",", LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS));

        List<String> dataLines = lines.subList(1, lines.size());
        assertThat(dataLines)
                .as("one line per owner touched after the watermark: JOHN, KARRI, GREG, NEWCUST, RAUNAK,"
                        + " RYAN (deleted and created again) and ERIC (funds held, then released and settled)")
                .hasSize(7);

        long previousLastEntryId = 0L;
        for (int position = 0; position < dataLines.size(); position++) {
            String line = dataLines.get(position);
            List<String> fields = List.of(line.split(",", -1));
            assertThat(fields)
                    .as("data line %d must carry one field per declared column: %s", position + 1, line)
                    .hasSize(LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS.size());

            assertThat(Long.parseLong(fields.get(0)))
                    .as("seq numbers the emitted order from 1, so line %d carries seq %d",
                            position + 1, position + 1)
                    .isEqualTo(position + 1L);

            // The op field is the whole reason the hand-back is rate-independent: A/U/X apply an
            // absolute state and consult no exchange rate, while C/D would recompute the balance from
            // RATES (CASH00.cbl:L221-L222, L255-L256) and so depend on rate rows that may have moved.
            assertThat(fields.get(2))
                    .as("op must be a replayable absolute-state code, never the rate-dependent C or D")
                    .isIn(REPLAYABLE_OPS);

            long lastEntryId = Long.parseLong(fields.get(6));
            assertThat(lastEntryId)
                    .as("lines are ordered by ascending last_entry_id, so line %d must exceed line %d",
                            position + 1, position)
                    .isGreaterThan(previousLastEntryId);
            previousLastEntryId = lastEntryId;

            // The replay file's checksum is captured as Step 3 evidence beside the file, never inside
            // it, because the delimited contract (AAP 0.12.1) defines no comment syntax at all - a
            // '#' line would make the file unparsable by the very readers that consume its siblings.
            assertThat(line)
                    .as("no derived line may be a comment: the contract reserves every line for a record")
                    .doesNotStartWith("#");
        }

        // One line per owner is the contract (AAP 0.12.1), and RYAN is the case that can break it: its
        // rows span two account incarnations, so a derivation keyed on (owner, incarnation) emits two
        // lines - X for the deleted life, U for the recreated one. Replayed in that order legacy deletes
        // its row and then finds nothing to update, ending with no RYAN at all while the target has one.
        // The single line therefore carries the *final* incarnation, the account life the balance
        // belongs to, so a hand-back can never be applied to a life that no longer exists.
        assertThat(dataLines.stream().map(line -> line.split(",", -1)[1]).toList())
                .as("the owner column must be unique: an owner deleted and created again above the"
                        + " watermark still hands back exactly one final state")
                .doesNotHaveDuplicates();

        List<String> ryan = fieldsFor(dataLines, "RYAN");
        assertThat(ryan.get(2))
                .as("RYAN's last row in the range is ACCOUNT_CREATED, and RYAN is in the final legacy"
                        + " export, so the absolute overwrite U reproduces its end state")
                .isEqualTo(UPDATE_OP);
        assertThat(ryan.get(7))
                .as("RYAN's line must carry the incarnation its balance belongs to - the recreated life,"
                        + " never the deleted one")
                .isEqualTo(RYAN_SECOND_INCARNATION.toString());
        assertThat(List.of(ryan.get(5), ryan.get(6)))
                .as("the summarized range spans both incarnations' rows, 109 through 110")
                .containsExactly("109", "110");

        // AAP 0.3.3 requires no HELD reservation when the hand-back begins, and that is a statement
        // about the owner's *end* state: ERIC held funds twice inside the range and released the first
        // hold and settled the second, so nothing is held at its last row. The immutable ledger keeps
        // the two rows that recorded the holds forever, so a rule read over the whole range rather than
        // over the end state would make rollback unavailable to every owner that ever had a hold.
        List<String> eric = fieldsFor(dataLines, "ERIC");
        assertThat(eric.get(3))
                .as("ERIC's balance is its last row's available_after, after the release and the"
                        + " settlement: 1234567.89 - 200.00 settled")
                .isEqualTo("1234367.89");

        List<LedgerRow> rangeWithFundsHeldThenReleased =
                withReservedAfter(range, 101L, new BigDecimal("75.00"));
        assertThat(deriveReplayFile(rangeWithFundsHeldThenReleased, legacyOwners))
                .as("funds held at an intermediate row and gone by the owner's last row must not block"
                        + " the hand-back: JOHN's end state at entry 102 holds nothing")
                .isEqualTo(golden);

        // The converse, and the whole reason the guard exists: funds still held on the owner's *last*
        // row are funds the replay file cannot express, because it carries one available balance per
        // owner and legacy has no reservation concept. That must stop the derivation - naming the owner
        // and the entry - rather than emit a line that understates the owner's money.
        List<LedgerRow> rangeWithFundsStillHeld = withReservedAfter(range, 114L, new BigDecimal("25.00"));
        assertThatThrownBy(() -> deriveReplayFile(rangeWithFundsStillHeld, legacyOwners))
                .as("a non-zero reserved_after on an owner's last row must fail the derivation, naming"
                        + " the owner and entry id")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERIC")
                .hasMessageContaining("114");
    }

    /** Splits the one emitted line of an owner into its fields, failing when the owner is absent. */
    private static List<String> fieldsFor(List<String> dataLines, String owner) {
        for (String line : dataLines) {
            List<String> fields = List.of(line.split(",", -1));
            if (fields.get(1).equals(owner)) {
                return fields;
            }
        }
        return fail(("no replay line was emitted for owner %s, and a dropped owner is an owner whose money"
                + " is never handed back").formatted(owner));
    }

    /**
     * Derives the Step 3 rollback replay file from a closed ledger range, per the AAP 0.12.1 contract.
     *
     * @param range        every ledger row with {@code entry_id > W}, the watermark recorded at cutover
     * @param legacyOwners owners present in the final legacy export, which decides op A against op U
     * @return the complete file text: a header line, then one LF-terminated line per owner touched
     */
    private static String deriveReplayFile(List<LedgerRow> range, Set<String> legacyOwners) {
        if (range == null || range.isEmpty()) {
            throw new IllegalStateException("A rollback replay file cannot be derived from an empty ledger"
                    + " range; the closed range above the cutover watermark is the derivation's only input");
        }

        // Grouped by owner alone, never by (owner, incarnation_id): the contract is one line per owner
        // touched (AAP 0.12.1), and an owner deleted and created again inside the range has rows under
        // two incarnations. Keyed on the pair it would emit two lines for that owner - X for the deleted
        // life and U for the recreated one - which replay as "delete the legacy row, then update what is
        // no longer there" and leave legacy holding nothing for an owner the target still has.
        Map<String, List<LedgerRow>> rowsByOwner = new LinkedHashMap<>();
        for (LedgerRow row : range) {
            rowsByOwner.computeIfAbsent(row.owner(), owner -> new ArrayList<>()).add(row);
        }

        List<ReplayLine> replayLines = new ArrayList<>(rowsByOwner.size());
        for (Map.Entry<String, List<LedgerRow>> group : rowsByOwner.entrySet()) {
            replayLines.add(deriveLine(group.getKey(), group.getValue(), legacyOwners));
        }

        // Ascending last_entry_id is the emitted order, and seq simply numbers it: replaying an owner's
        // end state out of ledger order would hand back a state the target never held.
        replayLines.sort(Comparator.comparingLong(ReplayLine::lastEntryId));

        StringBuilder file = new StringBuilder();
        file.append(String.join(",", LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS)).append('\n');
        long seq = 1L;
        for (ReplayLine line : replayLines) {
            file.append(seq++).append(',')
                    .append(line.owner()).append(',')
                    .append(line.op()).append(',')
                    .append(renderBalance(line)).append(',')
                    .append(line.currency()).append(',')
                    .append(line.firstEntryId()).append(',')
                    .append(line.lastEntryId()).append(',')
                    .append(line.incarnationId())
                    .append('\n');
        }
        return file.toString();
    }

    /** Collapses one owner's rows in the range into the single line that hands its end state back. */
    private static ReplayLine deriveLine(String owner, List<LedgerRow> rows, Set<String> legacyOwners) {
        long firstEntryId = Long.MAX_VALUE;
        long lastEntryId = Long.MIN_VALUE;
        LedgerRow lastRow = null;
        for (LedgerRow row : rows) {
            firstEntryId = Math.min(firstEntryId, row.entryId());
            if (row.entryId() > lastEntryId) {
                lastEntryId = row.entryId();
                lastRow = row;
            }
        }

        // The rollback precondition is read off the owner's last row, not off the range: AAP 0.3.3
        // requires every HELD reservation released before the hand-back, which is a claim about the end
        // state. Rows that recorded an earlier hold stay in the ledger forever - it is append-only - so
        // a rule applied to every row would deny rollback to any owner that ever held funds, however
        // long ago they were released or settled. Funds still held on the last row are different: the
        // file carries one available balance per owner and legacy has no reservation concept, so they
        // would vanish from the hand-back, and that stops the derivation instead of understating money.
        if (lastRow.reservedAfter().signum() != 0) {
            throw new IllegalStateException(("owner %s still has %s reserved at ledger entry %d, its last"
                            + " row in the range, so the rollback precondition was violated: every HELD"
                            + " reservation must be released before the replay file is derived, because the"
                            + " file carries only an available balance and legacy has no reservation concept")
                    .formatted(owner, lastRow.reservedAfter().toPlainString(), lastRow.entryId()));
        }

        // The last row by entry_id supplies the absolute end state - never a sum of the amounts and
        // never an intermediate balance - which is what collapses a multi-row range into one line. It
        // also supplies the incarnation the line carries, because an owner deleted and created again
        // inside the range ends the range in its newest account life and that is the life the balance
        // describes; a line stamped with the deleted incarnation would hand the money back to it.
        UUID incarnationId = lastRow.incarnationId();
        String op = resolveOp(owner, lastRow, legacyOwners);
        String currency = LegacyExportFormat.trimPadding(lastRow.currency());
        if (LegacyExportFormat.isNull(owner) || LegacyExportFormat.isNull(currency)) {
            throw new IllegalStateException(("ledger entry %d carries a blank owner or currency (owner '%s',"
                            + " currency '%s'), so no replayable line can be derived for it")
                    .formatted(lastRow.entryId(), owner, lastRow.currency()));
        }
        return new ReplayLine(owner, op, lastRow.availableAfter(), currency,
                firstEntryId, lastEntryId, incarnationId);
    }

    /**
     * Chooses the legacy request code that reproduces an owner's end state.
     *
     * <p>Three cases, because the legacy transaction distinguishes them: the account may have to be
     * removed, created, or overwritten. {@code X} when the target's last event deleted the account;
     * {@code A} when the owner is absent from the final legacy export, so legacy has no row to update
     * and an {@code U} would find nothing; {@code U} otherwise, overwriting the balance and currency
     * legacy still holds.
     */
    private static String resolveOp(String owner, LedgerRow lastRow, Set<String> legacyOwners) {
        if (lastRow.eventType() == LedgerEventType.ACCOUNT_DELETED) {
            return DELETE_OP;
        }
        return legacyOwners.contains(owner) ? UPDATE_OP : ADD_OP;
    }

    /** Renders a balance in the plain two-decimal text the delimited contract accepts. */
    private static String renderBalance(ReplayLine line) {
        // RoundingMode.DOWN for consistency with the characterized legacy truncation (CASH00.cbl:L17,
        // L222: unsigned WS-CALC, no ROUNDED, no ON SIZE ERROR); ledger_entry money is NUMERIC(9,2), so
        // the scale change is exact here and the mode only fixes what would happen to a wider value.
        String text = line.balance().setScale(LegacyExportFormat.MONEY_SCALE, RoundingMode.DOWN)
                .toPlainString();
        // toPlainString rather than toString, and then verified: BigDecimal.toString emits scientific
        // notation for some scales, and the replay file's reader rejects an exponent (DECIMAL_TEXT is
        // anchored). Checking here means a malformed balance fails the derivation rather than reaching
        // the mainframe team as a line their replay cannot parse.
        if (!LegacyExportFormat.DECIMAL_TEXT.matcher(text).matches()) {
            throw new IllegalStateException(("balance '%s' of owner %s is not in the plain two-decimal export"
                            + " shape the replay contract requires")
                    .formatted(text, line.owner()));
        }
        return text;
    }

    /**
     * The ledger range above the cutover watermark, exactly as fixtures/shadow/MANIFEST.md records it.
     *
     * <p>Watermark {@code W = 100}, so the closed range is entry ids 101-114. JOHN, NEWCUST and RAUNAK
     * each contribute two rows on purpose: that is what exercises the "last row wins" rule and the
     * collapse of a closed multi-row range into one line, which a range of single-row owners could not.
     * RYAN spans two account incarnations (deleted at 109, created again at 110) and ERIC holds funds
     * twice (released at 112, settled at 114): those two owners are the cases a rollback derivation gets
     * wrong by grouping on the incarnation or by reading the reservation rule over the whole range.
     */
    private static List<LedgerRow> postWatermarkLedgerRange() {
        return List.of(
                ledgerRow(101L, "JOHN", LedgerEventType.CREDIT, "150.50", "USD", "1150.50", JOHN_INCARNATION),
                ledgerRow(102L, "JOHN", LedgerEventType.CREDIT, "100.00", "USD", "1250.50", JOHN_INCARNATION),
                ledgerRow(103L, "KARRI", LedgerEventType.DEBIT, "345.67", "USD", "12000.00", KARRI_INCARNATION),
                ledgerRow(104L, "GREG", LedgerEventType.CREDIT, "100.00", "GBP", "123535.78", GREG_INCARNATION),
                ledgerRow(105L, "NEWCUST", LedgerEventType.ACCOUNT_CREATED, "400.00", "USD", "400.00",
                        NEWCUST_INCARNATION),
                ledgerRow(106L, "NEWCUST", LedgerEventType.CREDIT, "100.00", "USD", "500.00",
                        NEWCUST_INCARNATION),
                ledgerRow(107L, "RAUNAK", LedgerEventType.CREDIT, "50.00", "USD", "150.00", RAUNAK_INCARNATION),
                ledgerRow(108L, "RAUNAK", LedgerEventType.ACCOUNT_DELETED, "150.00", "USD", "0.00",
                        RAUNAK_INCARNATION),
                ledgerRow(109L, "RYAN", LedgerEventType.ACCOUNT_DELETED, "23456.78", "USD", "0.00",
                        RYAN_FIRST_INCARNATION),
                ledgerRow(110L, "RYAN", LedgerEventType.ACCOUNT_CREATED, "750.00", "USD", "750.00",
                        RYAN_SECOND_INCARNATION),
                heldLedgerRow(111L, "ERIC", LedgerEventType.HOLD, "500.00", "EUR", "1234067.89", "500.00",
                        ERIC_INCARNATION),
                heldLedgerRow(112L, "ERIC", LedgerEventType.RELEASE, "500.00", "EUR", "1234567.89", "0.00",
                        ERIC_INCARNATION),
                heldLedgerRow(113L, "ERIC", LedgerEventType.HOLD, "200.00", "EUR", "1234367.89", "200.00",
                        ERIC_INCARNATION),
                heldLedgerRow(114L, "ERIC", LedgerEventType.SETTLEMENT, "200.00", "EUR", "1234367.89", "0.00",
                        ERIC_INCARNATION));
    }

    private static LedgerRow ledgerRow(long entryId, String owner, LedgerEventType eventType, String amount,
            String currency, String availableAfter, UUID incarnationId) {
        return new LedgerRow(entryId, owner, eventType, new BigDecimal(amount), currency,
                new BigDecimal(availableAfter), NO_RESERVED_FUNDS, incarnationId);
    }

    /** A row of an owner whose institutional activity moved money between available and reserved. */
    private static LedgerRow heldLedgerRow(long entryId, String owner, LedgerEventType eventType,
            String amount, String currency, String availableAfter, String reservedAfter,
            UUID incarnationId) {
        return new LedgerRow(entryId, owner, eventType, new BigDecimal(amount), currency,
                new BigDecimal(availableAfter), new BigDecimal(reservedAfter), incarnationId);
    }

    /** Returns the range with one row's reserved balance replaced, to exercise the rollback precondition. */
    private static List<LedgerRow> withReservedAfter(List<LedgerRow> range, long entryId,
            BigDecimal reservedAfter) {
        List<LedgerRow> altered = new ArrayList<>(range.size());
        for (LedgerRow row : range) {
            altered.add(row.entryId() == entryId
                    ? new LedgerRow(row.entryId(), row.owner(), row.eventType(), row.amount(), row.currency(),
                            row.availableAfter(), reservedAfter, row.incarnationId())
                    : row);
        }
        return List.copyOf(altered);
    }

    /**
     * Owners of the final legacy export, read through the production export reader.
     *
     * <p>Read rather than written down, so the A-versus-U decision is driven by the same corpus the
     * loader and reconciler see. NEWCUST is deliberately outside it, which is what makes op {@code A}
     * reachable at all; hardcoding the set here would let a later fixture change diverge unnoticed.
     */
    private static Set<String> finalLegacyExportOwners() {
        List<LegacyCashAccountRecord> accounts =
                new DelimitedExportReader().readCashAccounts(fixture(LEGACY_EXPORT_RESOURCE));
        Set<String> owners = new LinkedHashSet<>();
        for (LegacyCashAccountRecord account : accounts) {
            // Uppercased because legacy identity is case-insensitive with uppercase storage
            // (CASH00.cbl:L141, L155, L178) and the ledger's owner column holds the normalized form.
            owners.add(LegacyExportFormat.trimPadding(account.owner()).toUpperCase(Locale.ROOT));
        }
        assertThat(owners)
                .as("%s must supply the six-owner corpus the A/U decision is made against",
                        LEGACY_EXPORT_RESOURCE)
                .isNotEmpty();
        return Set.copyOf(owners);
    }

    private static String readGoldenFile() {
        Path golden = fixture(GOLDEN_FILE_RESOURCE);
        try {
            return Files.readString(golden, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("the golden replay file at %s could not be read as UTF-8"
                    .formatted(golden.toAbsolutePath()), e);
        }
    }

    /**
     * Resolves a test-classpath fixture to a filesystem path.
     *
     * <p>Fails rather than skips when a fixture is missing: a derivation with nothing to compare against
     * proves nothing, and a skipped test reports as success.
     */
    private static Path fixture(String resource) {
        URL location = RollbackReplayFileTest.class.getClassLoader().getResource(resource);
        if (location == null) {
            return fail(("the fixture %s is not on the test classpath; it is required by this test and is"
                    + " expected under src/test/resources").formatted(resource));
        }
        try {
            return Path.of(location.toURI());
        } catch (URISyntaxException | FileSystemNotFoundException | IllegalArgumentException e) {
            return fail("the fixture %s resolved to %s, which is not a readable filesystem path"
                    .formatted(resource, location), e);
        }
    }
}
