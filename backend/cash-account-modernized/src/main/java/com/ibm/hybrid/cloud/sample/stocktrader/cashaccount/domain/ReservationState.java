package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

/** The four states a cash reservation may occupy. */
public enum ReservationState {

    // These names are a database contract: they are the literals of ck_cash_reservation_state on
    // cash_reservation.state in cash-account-schema.sql, which CashReservation persists as text, so
    // renaming one breaks the constraint on the next insert.
    HELD,
    SETTLED,
    RELEASED,
    EXPIRED
}
