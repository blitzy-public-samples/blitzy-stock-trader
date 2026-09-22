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

/** Closed vocabulary of the state changes recorded in the append-only ledger. */
public enum LedgerEventType {

    /*
     * Why a closed enum rather than the legacy's free-form code: CASH00 recorded every request in a
     * single X(1) field of a write-only VSAM record (CASH00.cbl:L38-L45) whose writer ignored DUPREC
     * (CASH00.cbl:L123-L131), so same-second events were silently lost and nothing read the file back.
     * This ledger is lossless and queryable, which only holds if the vocabulary is fixed and typed.
     *
     * Why no READ constant: the legacy write followed the dispatch unconditionally, so it also logged
     * reads ('Q') and unrecognized codes (CASH00.cbl:L111-L131). That is deliberately not replicated -
     * the ledger records state changes only, and legacy 'Q' records are staged in legacy_history by the
     * migration tooling instead. A READ event would be a purely additive later decision.
     *
     * Why direction is not a member here: ledger_entry.amount is NUMERIC(9,2) under
     * CHECK (amount >= 0), so it is always a non-negative magnitude and the constant alone carries the
     * direction. Any signed delta a consumer needs is derived from consecutive available_after /
     * reserved_after values, never stored, so the magnitude and the balances cannot drift apart. A
     * sign() or direction() helper would encode that knowledge a second time, next to the services that
     * already know which event they are writing. The mapping, which is not evident from the names:
     *
     *   CREDIT                                     inflow to available
     *   DEBIT                                      outflow from available
     *   HOLD                                       inflow to reserved, the matching outflow from
     *                                              available being visible in available_after
     *   RELEASE, EXPIRY                            inflow to available, reserved falling by the same
     *   SETTLEMENT                                 outflow - the settled portion leaves reserved for good
     *   ACCOUNT_CREATED, ACCOUNT_UPDATED,          absolute-set events: amount is the resulting
     *   MIGRATION_LOAD                             available balance, not a delta
     *   ACCOUNT_DELETED                            amount is the balance removed
     *
     * Why MIGRATION_LOAD may never be renamed: the partial index
     * UNIQUE (run_id, owner) WHERE event_type = 'MIGRATION_LOAD' in
     * src/main/resources/schema/cash-account-schema.sql matches this name as a SQL string literal, and
     * it is the guarantee that a retried migration load cannot write a second load row for an owner.
     * The coupling is invisible from Java and survives only while the persisted text equals the constant
     * name, so the column is mapped with @Enumerated(EnumType.STRING) - declared on LedgerEntry, not
     * here. That schema file is the authority for every name below; entities are validated against it at
     * start-up (spring.jpa.hibernate.ddl-auto=validate).
     */
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_DELETED,
    CREDIT,
    DEBIT,
    HOLD,
    SETTLEMENT,
    RELEASE,
    EXPIRY,
    MIGRATION_LOAD
}
