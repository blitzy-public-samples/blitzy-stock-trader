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

/**
 * The wire form of one immutable ledger row: the audit query's only serialized representation.
 *
 * @param entryId        the row's identity and its stable tie-break within one timestamp
 * @param owner          the stored uppercase owner
 * @param eventType      the transition recorded, which alone carries the direction
 * @param amount         the non-negative magnitude, or the resulting available balance on an absolute-set event
 * @param currency       the account currency at the time of the event
 * @param availableAfter the available balance the transaction committed
 * @param reservedAfter  the reserved balance the transaction committed
 * @param reservationId  the reservation the event concerns, null on retail and migration events
 * @param orderReference the order the reservation backs, null on retail and migration events
 * @param source         which surface produced the event
 * @param recordedAt     when the event committed
 */
// Deliberately null-bearing: this overrides the service-wide spring.jackson.default-property-inclusion:
// non_null of application.yml for this type alone, because the query returns an array and a consumer reading
// one field across rows needs every element to carry the identical key set - so reservationId and
// orderReference render as null on retail and migration events rather than vanishing from those elements only.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LedgerEntryResponse(Long entryId, String owner, LedgerEventType eventType, BigDecimal amount,
        String currency, BigDecimal availableAfter, BigDecimal reservedAfter, UUID reservationId,
        String orderReference, LedgerEntry.Source source, OffsetDateTime recordedAt) {

    /*
     * A null entry fails here rather than becoming a row of nulls: an all-null element in an audit array is
     * indistinguishable from a real event whose fields happened to be empty. The monetary components are
     * unwrapped from Money so the wire carries the plain decimal WRITE_BIGDECIMAL_AS_PLAIN renders.
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
     * This maps and does nothing else because iteration order is itself the contract: the repository's rows
     * already carry the stable tie-break AAP 0.6.2 requires, so a sort, filter or distinct here would
     * substitute a different ordering for the one the caller's "since" bound and limit were computed against.
     * Filtering zero-amount rows would break the very reconciliation their parity exists for (AAP 0.4.5).
     */
    public static List<LedgerEntryResponse> fromAll(List<LedgerEntry> entries) {
        Objects.requireNonNull(entries, "entries is required");
        return entries.stream().map(LedgerEntryResponse::from).toList();
    }

    /*
     * A normalization, never a rounding decision: every value reaching this class has already passed through
     * Money, so this discards nothing and only keeps the rendered JSON two-decimal should a value arrive
     * straight from the driver at another scale. DOWN is the module's single rounding mode because neither
     * legacy COMPUTE carried ROUNDED [backend/cash-account-cobol/COBOL/CASH00.cbl:L222].
     */
    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN);
    }
}
