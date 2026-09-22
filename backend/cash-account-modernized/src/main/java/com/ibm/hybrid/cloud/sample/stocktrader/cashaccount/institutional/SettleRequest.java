package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.institutional;

import java.math.BigDecimal;

/**
 * Optional request body for settling an institutional hold, unconstrained on purpose: a null amount is the
 * documented full settlement, {@code 0} is a legal settlement of nothing that releases the whole hold, and an
 * amount above the held amount is refused by domain/ReservationStateMachine against the row it locked.
 *
 * @param amount the portion of the hold to settle, or null for all of it
 */
public record SettleRequest(BigDecimal amount) {
}
