# Fixture manifest — `src/test/resources/fixtures/legacy-export/matched/`

This directory is the **matched** variant of the legacy export corpus: internally consistent by
construction, so every tooling run over it is expected to finish with **zero variance**. Its four
files are shaped like the output of a DB2 `UNLOAD DELIMITED` of `STOCKTRD.CASHACCOUNTY` and
`STOCKTRD.FRANKFURT1` plus an IDCAMS `REPRO` of the history KSDS — the export path AAP §0.12.1
defines — so that the loader, the reconciler and the decoder can be proven without mainframe access.

**All data here is synthetic and invented.** No byte originates from the real DB2 for z/OS tables
`STOCKTRD.CASHACCOUNTY` or `STOCKTRD.FRANKFURT1`, or from the VSAM data set `SYSD.STOCK.HISTORY`.
Nothing was read from a live system to produce it, and nothing may be: AAP §0.3.2 prohibits
connecting to, reading from or writing to any of them. Only the *layouts* are legacy-derived, read
from these read-only characterization sources and modified nowhere —
`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl`, `backend/cash-account-cobol/VSAM/DEFKSDS.jcl`,
`backend/cash-account-cobol/COBOL/CASH00.cbl`, `backend/cash-account-cobol/COBOL/DCLCASH.cpy` and
`backend/cash-account-cobol/COBOL/DCLFRANK.cpy`.

This sidecar exists because the fixtures cannot carry their own provenance: the delimited-export
contract reserves line 1 of every CSV for the column header and defines **no comment syntax**, and a
binary fixture cannot carry a comment at all (AAP §0.6.1, §0.10.1). It is the only place in this
directory where prose appears, and nothing loads it at build or run time. For the full behavioral
baseline, read the module's `docs/legacy-characterization.md`; this document restates only what a
reader needs in order to trust these four files.

## 1. The fixtures

| File | What it encodes | §0.4 finding it carries | Expected tooling outcome |
|---|---|---|---|
| `cashaccounty.csv` | Six-account export, header `owner,balance,currencyc` per `DCLCASH.cpy:L8-L12` / `DB2DDL.jcl:L47-L49` | Uppercase storage: the legacy insert stores `UPPER(:CUST-NAME-TEXT)` (`CASH00.cbl:L155`), so a real account export holds **no** lowercase owner — every row here is uppercase. Fixed-point ceiling `NUMERIC(9,2)` / `PIC 9(7)V99` (`DB2DDL.jcl:L48`, `CASH00.cbl:L17`), which `ERIC 1234567.89` exercises at full seven-digit width | Six `cash_account` rows, zero variance |
| `frankfurt1.csv` | Three rate rows, header `currnkey,currnbase,amount,rates,loaddt` per `DCLFRANK.cpy:L10` / `DB2DDL.jcl:L56-L59`: `USD 1.00`, `EUR 0.92`, `GBP 0.79`, all `loaddt 2024-01-15` | §0.4.1: `AMOUNT` and `CURRNBASE` are fetched by the credit and debit `SELECT` (`CASH00.cbl:L215`, `L249`) but referenced by **no** `COMPUTE` and no `MOVE` — the caller's COMMAREA amount is the multiplicand, so both columns are inert here and are staged only. Rate ceiling 9.99 from `NUMERIC(3,2)` (`DB2DDL.jcl:L58`) / `PIC S9(1)V9(2) COMP-3` (`DCLFRANK.cpy:L12`, `L22`) | Three `legacy_rate_table` rows under the run id, zero variance |
| `history.csv` | Eight-record delimited conversion of the KSDS, header `name,event_date,event_time,request_code,balance,currency,retcode` | The history write is **unconditional** — it follows `END-EVALUATE` (`CASH00.cbl:L102`) with no guard (`L111-L131`), so reads are audited too, which is why two of the eight rows are `Q`. Raw caller casing: `MOVE WS-NAME TO WS-VR-NAME` applies no case folding (`CASH00.cbl:L111`). Sign-dropped status channel: `MOVE SQLCODE TO WS-VR-RETCODE` renders the code into `X(10)` (`CASH00.cbl:L117`, field at `L45`), so success is the nine digits `000000000` | Eight `legacy_history` rows under the run id, zero variance |
| `history.cp037.bin` | The same eight rows as fixed-length IBM037 records — the binary twin of `history.csv` | §0.11.2 open item: `CASH00` writes a 57-byte record (`CASH00.cbl:L38-L45`) into a cluster defined `RECSZ(100 100)` (`DEFKSDS.jcl:L11`). The CICS FILE definition that would settle which length the data set actually holds is not in the repository, so this fixture deliberately uses the **padded 100-byte** shape and the decoder takes a *declared* length (`tool.history-record-length`) rather than inferring one | Decodes identically to `history.csv` |

## 2. `history.cp037.bin` — byte facts

Eight records of **100 bytes** each (57 data bytes + 43 bytes of EBCDIC space `0x40`), **exactly 800
bytes**, no trailing newline.

```
sha256  bb400ee499d6e7d611427782afc58497cb7bb3b0c182c0497adbfc8c6c552c97
```

| Offset | Length | Field | Legacy picture |
|---|---|---|---|
| 0 | 15 | `name` | `X(15)`, caller casing, blank-padded |
| 15 | 8 | `event_date` | `X(08)`, `YYYYMMDD` |
| 23 | 6 | `event_time` | `X(06)`, `HHMMSS` |
| 29 | 1 | `request_code` | `X(1)` |
| 30 | 9 | `balance` | `9(7)V99`, unsigned zoned decimal, implied point |
| 39 | 8 | `currency` | `X(8)`, blank-padded |
| 47 | 10 | `retcode` | `X(10)`, blank-padded |
| 57 | 43 | padding | none — `RECSZ(100 100)` slack, EBCDIC space |

Offsets 0-28 are the 29-byte primary key, matching `KEYS(29 0)` (`DEFKSDS.jcl:L14`); the layout is
`WS-VSAM-RECORD` at `CASH00.cbl:L38-L45` and its key redefinition at `L47-L50`.

**How it was produced, and how to reproduce it.** For each row of `history.csv` in file order, build
the 57-character string `name.ljust(15) + event_date + event_time + request_code + zoned9(balance) +
currency.ljust(8) + "000000000".ljust(10)`, where `zoned9` is the balance with its decimal point
removed, left-padded with zeros to nine digits; assert the string is exactly 57 characters; encode it
`cp037`; append 43 bytes of `0x40`; concatenate the eight records with no separator and no trailing
newline. The zoned encodings this corpus uses: `1000.00`→`000100000`, `12345.67`→`001234567`,
`23456.78`→`002345678`, `100.00`→`000010000`, `123456.78`→`012345678`, `1234567.89`→`123456789`.

**No generator script is committed.** AAP §0.2.1 enumerates the files this folder may hold and a
script is not among them, and §0.7.6 forbids artifacts beyond the enumerated set — the procedure
above is the reproduction record. The checksum likewise lives here and in the runbook evidence, not
in a committed sidecar and not in an embedded comment line, which the §0.12.1 CSV contract has no
syntax for.

**Validation:** `wc -c history.cp037.bin` must print `800`; `sha256sum history.cp037.bin` must match
the digest above; `xxd -s 30 -l 9`, `xxd -s 47 -l 10` and `xxd -s 57 -l 43` on the first record spot
the zoned balance (`f0f0f0f1f0f0f0f0f0`), the retcode (nine `f0` then `40`) and the padding (all
`40`). If a check fails, **fix the binary, never the digest in this document.**

## 3. Four things a reader will otherwise get wrong

**The header here says `currnbase`; the sibling `seeded-mismatch/frankfurt1.csv` says `cyrrnbase`.
That divergence is deliberate.** The copybook and the program's own `SELECT` use `CURRNBASE`
(`DCLFRANK.cpy:L10`; `CASH00.cbl:L215`, `L249`) while the shipped DDL declares `cyrrnbase`
(`DB2DDL.jcl:L56`) — as written, the program would not precompile against that DDL. Carrying one
spelling in each variant exercises `LegacyExportFormat.resolveRateBaseColumn`'s documented acceptance
of either at zero extra cost. AAP §0.11.2 records the real DB2 catalog as the only thing that settles
which spelling is true; **neither variant asserts an answer**, so do not "correct" either header.

**The two `Q` rows are excluded from transaction counts.** Because the write is unconditional
(`CASH00.cbl:L111-L131`, after `END-EVALUATE` at `L102`), the legacy file records reads as well as
state changes. Counts therefore filter `request_code IN ('A','U','X','C','D')` with a zero return
code — `LegacyExportFormat.COUNTED_REQUEST_CODES` and `isSuccessRetcode` — leaving six of the eight
rows countable. Per AAP §0.4.6 the target ledger records state changes only; the `Q` rows are staged
in `legacy_history` for reference and are not a gap.

**Both `John` and `JOHN` must survive the load.** `MOVE WS-NAME TO WS-VR-NAME` applies no case
folding (`CASH00.cbl:L111`), so `John`+stamp and `JOHN`+stamp are two distinct, valid 29-byte KSDS
keys — which is exactly what rows 1 and 2 encode. `legacy_history`'s primary key is the raw key
`(run_id, name, event_date, event_time)` with a separately derived uppercased `owner_key` for joins,
and `LoaderIT` asserts both rows persist. **Never deduplicate or case-fold them.**

**`JOHN` and `RAUNAK` deliberately diverge from the estate stub.** The owner names come from the stub
JSON at `backend/broker/src/main/liberty/config/includes/none.xml:L58`, whose totals are
`John 1234.56`, `Karri 12345.67`, `Ryan 23456.78`, `Raunak 98765.43`, `Greg 123456.78` and
`Eric 1234567.89`.
KARRI, RYAN, GREG and ERIC match that stub exactly; `JOHN 1000.00` and `RAUNAK 100.00` were chosen so
the sibling shadow fixtures land on clean, hand-checkable numbers (§5 below).

## 4. Expected tooling outcomes (AAP §0.10.3, matched row)

- `load` then `reconcile` under one shared `batch_id` → `migration_run.variance_count = 0`, **zero
  `VARIANCE` rows**, exit code **0**.
- `LoaderIT` sees **6** `cash_account` rows and **6** `MIGRATION_LOAD` `ledger_entry` rows carrying
  the `run_id`; `legacy_history` and `legacy_rate_table` populated under that `run_id`;
  `history.cp037.bin` decoding identically to `history.csv`; and **both** the `John` and `JOHN`
  history keys surviving.
- **No `migration_reconciliation` row is written for a matching owner.** `ReconciliationStatus` fixes
  that `MATCHED` is not persisted per agreeing owner, which is why this variant yields *zero* rows
  rather than six `MATCHED` ones — the assertion is a row count, not a status filter.

## 5. Cross-fixture consistency — changing anything here breaks siblings silently

The `fixtures/shadow/**` expected responses are computed from **these** balances and **these** rates,
with the legacy arithmetic of §0.4.1: the full-precision product, the signed addition, then one final
truncation `RoundingMode.DOWN`.

| Owner | Legacy computation | Result |
|---|---|---|
| `JOHN` | `1000.00 + 1.00 × 250.50` | `1250.50` |
| `GREG` | `123456.78 + 0.79 × 100.00` | `123535.78` |
| `ERIC` | `1234567.89 − 0.92 × 0.30` (raw `1234567.6140`) | `1234567.61` |
| `RAUNAK` | `100.00 − 1.00 × 150.00` (raw `−50.00`) | legacy stored the absolute value `50.00` — unsigned `WS-CALC PIC 9(7)V99` (`CASH00.cbl:L17`) — where the target returns `422 INSUFFICIENT_FUNDS` (the seeded shadow case) |

`seeded-mismatch/` reuses these six account rows, these three rate rows and this byte-identical
history pair, altering only what it seeds. Editing a balance, a rate or a history row here therefore
moves the expected values of `ShadowComparatorIT` and `ReconciliationIT` without touching either
test — a silent break. Direct consumers of this directory: `LoaderIT`, `ReconciliationIT` and
`VsamHistoryRecordDecoderTest`.

**Two decimals everywhere, and that is load-bearing.** `LoaderIT` compares the `history.csv` and
`history.cp037.bin` records for equality, and `BigDecimal.equals` is scale-sensitive: `1000.00`
(scale 2) equals `new BigDecimal("000100000").movePointLeft(2)`, whereas `1000.0` or `1000` would
not. Every monetary value in these files carries exactly two decimals for that reason, and every
amount in the parity corpus does too, so no expected value depends on the open input-rounding
question of AAP §0.11.2.
