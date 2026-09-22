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

package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.domain;

/** The four states a cash reservation may occupy. */
public enum ReservationState {

    // These constant names are a database contract: they are the literals of
    // CHECK (state IN ('HELD','SETTLED','RELEASED','EXPIRED')) on cash_reservation.state
    // in schema/cash-account-schema.sql, which CashReservation persists as text. Renaming one
    // breaks the constraint on the next insert, so the schema file governs the spelling.
    // This enum is deliberately behaviour-free: ReservationStateMachine is the single authority
    // on which transitions are legal, idempotent or rejected, and a helper here would split it.
    HELD,
    SETTLED,
    RELEASED,
    EXPIRED
}
