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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEntry;
import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain.LedgerEventType;

/*
 * WHY THIS TYPE EXISTS. The legacy audit trail was WS-VSAM-RECORD, 57 fixed bytes of NAME X(15), DATE X(8),
 * TIME X(6), REQ X(1), BALANCE 9(7)V99, CURRENCY X(8) and RETCODE X(10)
 * [backend/cash-account-cobol/COBOL/CASH00.cbl:L38-L45], written to the HISTORY KSDS after every dispatch
 * [CASH00.cbl:L111-L131] - and never read back by any program in the repository, the WRITE being its only
 * application access. This record is the half of the replacement that makes the trail readable, and it is the
 * only way a ledger_entry row may reach a client: the JPA entity is never serialized (AAP 0.6.2), so the
 * mapping below is the whole serialization boundary of the audit query rather than one of several.
 *
 * WHY IT IS NOT A FIELD-FOR-FIELD PORT. The X(1) request code and the X(10) SQLCODE return channel are both
 * gone: the event is named by eventType and its outcome by the HTTP status (AAP 0.4.3, 0.12.3), which is what
 * removes the legacy ambiguity where a dropped sign made -803 and +803 the same ten characters. The
 * caller-cased, 15-character name becomes the normalized 32-character owner (AAP 0.4.2), and identity is the
 * generated entry_id rather than the one-second-resolution key whose collisions the legacy writer discarded
 * under IGNORE CONDITION DUPREC [CASH00.cbl:L123-L124].
 *
 * WHY incarnationId AND runId ARE ABSENT although both are real ledger_entry columns: incarnation_id is the
 * internal account-lifetime and idempotency scope, run_id is migration-tool bookkeeping, and the payload AAP
 * 0.6.2 fixes contains neither. Publishing either would put internal state on a public contract.
 *
 * WHY NOTHING HERE FLOWS INWARD. There is no toEntity, no builder and no copier: the ledger is append-only,
 * guarded in Java by an entity with no setters and in the database by the ledger_entry_immutable trigger, and
 * an inbound path on the outbound type is an invitation to try to write through it.
 */
/** The wire form of one immutable ledger row: the audit query's only serialized representation. */
// Null-bearing deliberately, and this annotation is what makes it so. application.yml:L84 sets
// spring.jackson.default-property-inclusion: non_null service-wide, which is correct for error/ApiError - an error
// body is read by a human and there the mere presence of "owner" carries the meaning. A ledger response is read by
// a machine: the query returns an array, and a consumer reading a field across rows (the runbook's evidence tooling
// among them) needs every element to carry the identical key set, so reservationId and orderReference have to
// render as null on the retail and migration events instead of vanishing from those elements only. ALWAYS overrides
// the global default for this type alone; NON_NULL here would be the exact defect this prevents.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LedgerEntryResponse(Long entryId, String owner, LedgerEventType eventType, BigDecimal amount,
        String currency, BigDecimal availableAfter, BigDecimal reservedAfter, UUID reservationId,
        String orderReference, LedgerEntry.Source source, OffsetDateTime recordedAt) {

    /*
     * A null entry is a wiring error in the caller and never a caller-reachable condition, so it fails here
     * rather than becoming a row of nulls - an all-null element in an audit array is indistinguishable from a
     * real event whose fields happened to be empty, which is the one reading an auditor must never be offered.
     *
     * amount, availableAfter and reservedAfter arrive as Money and are unwrapped to BigDecimal: the entity keeps
     * its monetary accessors in the module's money type so a caller cannot compare a row against a balance with a
     * scale-sensitive equals, while the wire needs the plain decimal that WRITE_BIGDECIMAL_AS_PLAIN renders.
     */
    public static LedgerEntryResponse from(LedgerEntry entry) {
        Objects.requireNonNull(entry, "entry is required");
        return new LedgerEntryResponse(
                entry.entryId(),
                entry.owner(),
                entry.eventType(),
                scaled(entry.amount().amount()),
                entry.currency(),
                scaled(entry.availableAfter().amount()),
                scaled(entry.reservedAfter().amount()),
                entry.reservationId(),
                entry.orderReference(),
                entry.source(),
                entry.recordedAt());
    }

    /*
     * Iteration order is itself the contract, which is why this maps and does nothing else. The repository hands
     * back rows ordered recordedAt DESC, entryId DESC - the stable tie-break AAP 0.6.2 requires, backed by the
     * index (owner, recorded_at DESC, entry_id DESC) - so a sort, reverse, filter or distinct here would quietly
     * substitute a different ordering for the one the caller's "since" bound and limit were computed against.
     *
     * Zero-amount rows are the filter that looks most defensible and would be the worst: a legacy zero credit or
     * debit updated the row, returned SQLCODE 0 and wrote a history record, so the replacement keeps one
     * zero-amount ledger row for transaction-count parity (AAP 0.4.5) and dropping it would break the very
     * reconciliation that parity exists for. An empty list maps to an empty list - an owner with no rows and no
     * account answers with an empty array, never a 404 (AAP 0.6.2).
     */
    public static List<LedgerEntryResponse> fromAll(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries is required");
        return entries.stream().map(LedgerEntryResponse::from).toList();
    }

    /*
     * A normalization, never a rounding decision: every monetary column is NUMERIC(9,2) and every value reaching
     * this class has passed through Money, which normalizes on construction, so this discards nothing today. It is
     * what keeps the rendered JSON two-decimal if a value ever arrives straight from the driver at another scale,
     * and it uses the module's single rounding mode - RoundingMode.DOWN, the legacy COMPUTE without ROUNDED
     * [CASH00.cbl:L222]. The literal scale is used in place of Money.SCALE on purpose: this package depends on two
     * domain types (AAP 0.8.2) and widening that for a constant would buy nothing.
     */
    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN);
    }
}
