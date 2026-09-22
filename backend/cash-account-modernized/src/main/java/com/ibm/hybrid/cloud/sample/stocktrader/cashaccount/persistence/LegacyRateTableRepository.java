package com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ibm.hybrid.cloud.sample.stocktrader.cashaccount.migration.reconcile.LegacyRateTable;

/** Data access to the run-scoped staging of exported {@code STOCKTRD.FRANKFURT1} rate rows. */
public interface LegacyRateTableRepository extends JpaRepository<LegacyRateTable, LegacyRateTable.Key> {

    // Migration tooling only - the live service resolves rates over HTTP - and the key arrives already
    // truncated to five characters by the caller, reproducing the legacy lookup that moved the account's
    // CURRENCYC X(8) into WS-CURRENCY-KEY X(5) before selecting the rate row
    // (backend/cash-account-cobol/COBOL/CASH00.cbl:L213-L218 for credit, L247-L252 for debit). Trimming,
    // padding or case folding here would resolve a key the legacy program itself would have missed, so this
    // repository deliberately normalizes nothing.
    //
    // An empty result must stay empty: it makes any C/D replay for that currency REJECTED_BY_TARGET, whereas
    // the legacy program read its uninitialized RATES host variable
    // (backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L18-L23) and committed a wrong balance under a success
    // code (CASH00.cbl:L214-L231). Substituting any rate for an absent row would reintroduce that defect.
    Optional<LegacyRateTable> findByRunIdAndCurrnkey(UUID runId, String currnkey);

    // One run's whole staged table, for a caller that would otherwise issue a statement per key. Reading it
    // whole is cheaper because the legacy catalog holds one row per CURRNKEY CHAR(5) (DB2DDL.jcl:L54-L62)
    // against an accepted set of 31 codes (AAP 0.7.2). Run-scoped, like every read here, because two runs of
    // one batch stage their own copies under their own run_id.
    List<LegacyRateTable> findByRunId(UUID runId);
}
