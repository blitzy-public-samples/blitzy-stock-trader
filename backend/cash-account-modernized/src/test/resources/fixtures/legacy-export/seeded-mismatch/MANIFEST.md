# Fixture manifest — `src/test/resources/fixtures/legacy-export/seeded-mismatch/`

This directory is the deliberately divergent counterpart of the sibling `matched/` corpus. Its five
files are shaped like the same export path AAP §0.12.1 defines — a DB2 `UNLOAD DELIMITED` of
`STOCKTRD.CASHACCOUNTY` and `STOCKTRD.FRANKFURT1` plus an IDCAMS `REPRO` of the history KSDS — but
known discrepancies are planted in them. Their single purpose is to prove that `ReconciliationService`
flags **every** seeded discrepancy **completely** and flags **nothing else**. A run that reports four
rows has a hole in it; a run that reports six has invented one. Both are failures.

**All data in this directory is synthetic.** It was invented to *resemble* a legacy export. No real
DB2 for z/OS table and no real VSAM KSDS was read, connected to, or written in order to produce it,
and none may be: AAP §0.3.2 prohibits contact with `STOCKTRD.CASHACCOUNTY`, `STOCKTRD.FRANKFURT1` and
`SYSD.STOCK.HISTORY`, and the migration tooling in this module is verified against fixtures only.
Only the *layouts* are legacy-derived, read from the read-only characterization sources
`backend/cash-account-cobol/COBOL/CASH00.cbl`, `DB2-DDL/DB2DDL.jcl`, `VSAM/DEFKSDS.jcl`,
`COBOL/DCLCASH.cpy` and `COBOL/DCLFRANK.cpy`, none of which is modified.

This sidecar exists because the fixtures cannot carry their own provenance: the §0.12.1 delimited
contract reserves line 1 of every CSV for the column header and defines **no comment syntax**, and a
binary fixture cannot carry a comment at all (AAP §0.6.1, §0.10.1). Nothing loads this file at build
or run time. **No checksum sidecar file exists either** — §0.12.1 keeps export checksums in the
runbook evidence rather than embedded beside the data. The binary's sha256 is recorded in §3 below as
a reproducibility fact, which is not the same thing as a checksum artifact.

## 1. The fixtures

| File | What it encodes | Finding / seed it carries | Expected tooling outcome |
|---|---|---|---|
| `cashaccounty.csv` | The legacy truth. Header `owner,balance,currencyc` per `DCLCASH.cpy:L17-L19` / `DB2DDL.jcl:L47-L49`; **7** data rows — the six `matched/` accounts plus `NULLBAL,,USD` | §0.4.5 nullable legacy columns: `balance` and `currencyc` carry no `NOT NULL` (`DB2DDL.jcl:L48-L49`) and the program declares no null indicators for them (`DCLCASH.cpy:L17-L19`), so a NULL is representable in a real export and unhandled by the program. §0.10.3 `NULLBAL` seed | `legacy_record_count 7`; `NULLBAL` → `STATE` / `NULL_IN_LEGACY`, account **not** loaded |
| `frankfurt1.csv` | The legacy rate table. Header `currnkey,cyrrnbase,amount,rates,loaddt`; **4** data rows — the three `matched/` rates (`USD 1.00`, `EUR 0.92`, `GBP 0.79`) plus `ZZZ,,,,2024-01-15` | §0.4.1: `CURRNBASE` and `AMOUNT` are fetched by the credit and debit `SELECT` (`CASH00.cbl:L215`, `L249`) yet referenced by **no** `COMPUTE` and no `MOVE` — only `RATES` reaches the arithmetic (`L222`, `L256`) — so both columns are inert and are staged, never used. §0.4.5 nullability; §0.10.3 `ZZZ` seed | `ZZZ` → `RATE_SOURCE` / `NULL_RATE`, rate row **not** staged; the empty `cyrrnbase` and `amount` on that row are staged as NULL with **no** variance |
| `target-state.csv` | The divergent already-migrated state — same account column shape as `cashaccounty.csv`; **5** data rows. `ReconciliationIT` loads it through `LegacyLoader` as if it were the result of an earlier faulty load, then reconciles `cashaccounty.csv` against it | §0.10.3 seeds: `KARRI` digit transposition, `ERIC` currency change, `GREG` absence | `migrated_record_count 5`; three of the five variance rows |
| `history.csv` | **Byte-identical to `matched/history.csv`** — eight records, header `name,event_date,event_time,request_code,balance,currency,retcode` | §0.12.1 record layout (`CASH00.cbl:L38-L45`). Caller casing is preserved because `MOVE WS-NAME TO WS-VR-NAME` applies no case folding (`L111`); the retcode is nine digits because `MOVE SQLCODE TO WS-VR-RETCODE` renders it into `X(10)` (`L117`); two rows are `Q` because the write is unconditional, following `END-EVALUATE` (`L102`) with no guard (`L111-L131`) | Staged into `legacy_history` under the run id; contributes **no** variance |
| `history.cp037.bin` | **Byte-identical to `matched/history.cp037.bin`** — the same eight records as fixed-length 100-byte IBM037 records | §0.12.1 / §0.12.2 encoding; `RECSZ(100 100)` (`DEFKSDS.jcl:L11`); the §0.11.2 record-length open item | Decodes identically to `history.csv` (`LoaderIT`); requires `tool.history-record-length=100` |

**Why the history pair is unchanged between the two variants.** Holding history byte-for-byte constant
isolates the five account and rate seeds: any variance the seeded run reports is then attributable to
the account export or the rate export and never to history. Seeding history as well would make a
failure ambiguous between two inputs, and history contributes no variance kind in reconcile mode
anyway (§2). The two files are therefore intentional duplicates of their `matched/` counterparts —
verifiable with `cmp`, and not a copy-paste slip to be "deduplicated".

## 2. Expected reconciliation outcome (AAP §0.10.3, seeded row)

`load` of `target-state.csv` as the migrated state, then `reconcile` against `cashaccounty.csv` and
`frankfurt1.csv` under a second run id of the same `batch_id`, yields **exactly five `VARIANCE` rows,
all under the `reconcile` `run_id`**. The sign convention is the module-wide one declared in
`MigrationReconciliation`: **`variance = migrated − legacy`**.

| Owner / key | Kind | Detail |
|---|---|---|
| `KARRI` | `BALANCE` | legacy `12345.67` / migrated `12345.76` / variance **+0.09** |
| `ERIC` | `CURRENCY` | `EUR` → `USD`. Balances are identical, so **no** `BALANCE` row — the currency and balance checks are independent and each writes at most one row |
| `GREG` | `STATE` | `MISSING_IN_TARGET` — present in the legacy export, absent from `target-state.csv`; recorded with the legacy balance and no migrated balance |
| `NULLBAL` | `STATE` | `NULL_IN_LEGACY`, written by `ReconciliationService.validateSource()` as it reads the legacy export |
| `ZZZ` | `RATE_SOURCE` | `NULL_RATE`, written by `validateSource()` |

Plus `legacy_record_count 7`, `migrated_record_count 5` and exit code **2**.

Why there is no sixth row:

- **`JOHN`, `RYAN` and `RAUNAK` agree and persist no row each.** A matching owner gets no row at all,
  not even a `MATCHED` one — `ReconciliationStatus.MATCHED` is the value an operator sets when
  reclassifying a reviewed row, not something the comparison writes. The assertion is therefore a row
  count and does not scale with fixture size.
- **`NULLBAL` yields one row, not two.** `validateSource()` rejected it, and the reason it was
  rejected is also the reason it was never loaded; the comparison loop skips an already-rejected owner
  so its absence from the target is not reported a second time as `MISSING_IN_TARGET`. One condition,
  one row.
- **`TRANSACTION_COUNT` never appears in reconcile mode.** After a bulk load the target holds one
  `MIGRATION_LOAD` ledger row per account and no per-transaction history, so there is nothing to count
  against. It is a shadow-window kind only (§0.10.3), exercised by `fixtures/shadow/seeded-mismatch/`.
- **Only `VARIANCE` status feeds `migration_run.variance_count` and the exit code.** An
  `ACCEPTED_EXCEPTION` row must never inflate either.

## 3. `history.cp037.bin` — byte facts and reproduction

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
| 30 | 9 | `balance` | `9(7)V99`, unsigned zoned decimal, implied scale 2 |
| 39 | 8 | `currency` | `X(8)`, blank-padded |
| 47 | 10 | `retcode` | `X(10)`, blank-padded |
| 57 | 43 | padding | none — `RECSZ(100 100)` slack, EBCDIC space `0x40` |

The layout is `WS-VSAM-RECORD` at `CASH00.cbl:L38-L45`; offsets 0-28 are the 29-byte primary key, its
redefinition at `CASH00.cbl:L47-L50`, matching `KEYS(29 0)` (`DEFKSDS.jcl:L14`).

**How it was produced, and how to reproduce it.** For each row of `history.csv` in file order, build
the 57-character layout string `name.ljust(15) + event_date + event_time + request_code +
zoned9(balance) + currency.ljust(8) + retcode.ljust(10)`, where `zoned9` is the balance with its
decimal point removed and left-padded with zeros to nine digits; assert the string is exactly 57
characters; `encode("cp037")`; append 43 bytes of `0x40`; concatenate the eight records with no
separator and no trailing newline. Balances are built from digit strings only — never `double` or
`float`, which AAP §0.7.1 prohibits on any money path. The zoned encodings this corpus uses:

| Balance | Zoned 9 digits |
|---|---|
| `1000.00` | `000100000` |
| `12345.67` | `001234567` |
| `23456.78` | `002345678` |
| `100.00` | `000010000` |
| `123456.78` | `012345678` |
| `1234567.89` | `123456789` |

**No generator script is committed.** AAP §0.2.1 enumerates the files this folder may hold and a
script is not among them, and §0.7.6 forbids artifacts beyond the enumerated set — the procedure above
is the reproduction record.

**Validation:** `wc -c history.cp037.bin` must print `800`; `sha256sum history.cp037.bin` must match
the digest above; on the first record `xxd -s 30 -l 9` spots the zoned balance
(`f0f0 f0f1 f0f0 f0f0 f0`), `xxd -s 47 -l 10` the retcode (nine `f0` then `40`) and `xxd -s 57 -l 43`
the padding (all `40`). If a check fails, **fix the binary, never the digest in this document.**

**Why the padded 100-byte variant.** `CASH00` writes a 57-byte record (`CASH00.cbl:L38-L45`) into a
cluster defined `RECSZ(100 100)` (`DEFKSDS.jcl:L11`). AAP §0.11.2 records that tension as an open item
settled only by the CICS FILE/FCT definition, which is not in this repository — so the decoder
tolerates both lengths and this fixture exercises the padded case. The record length is always
**declared** through `tool.history-record-length`, never inferred from the file.

## 4. Three decisions that look like defects and are not

**This folder's `frankfurt1.csv` header uses the DDL spelling `cyrrnbase`; `matched/` uses the
copybook spelling `currnbase`. The divergence is deliberate.** The shipped artifacts genuinely
disagree: `DB2DDL.jcl:L56` declares `cyrrnbase CHAR(5)` while `DCLFRANK.cpy:L10` declares
`CURRNBASE CHAR(5)` and the program's own `SELECT` names `CURRNBASE` (`CASH00.cbl:L215`), which means
the program as written would not precompile against that DDL. Carrying one spelling in each variant
exercises `LegacyExportFormat.resolveRateBaseColumn`'s documented acceptance of either at zero extra
cost. AAP §0.11.2 records the real DB2 catalog as the only thing that settles which is true, so
neither variant asserts an answer — **do not "fix" either header.**

**`NULLBAL` carries a valid currency (`USD`) on purpose.** Currency validation against the accepted
31-code set is account-scoped (§0.6.3, §0.7.2 — "account not loaded") and is applied independently of
the balance check, so one row can produce both findings. A NULL or out-of-set currency here would emit
a **second** row — `CURRENCY` / `INVALID_IN_LEGACY` on top of `STATE` / `NULL_IN_LEGACY` — and the
expected count would become six, not five. The row seeds exactly one condition: the null balance.

**`ZZZ` yields one row and not two.** A rate key is **never** currency-validated; only accounts are.
So `ZZZ`, although it is not an ISO code and is not in the accepted set, produces only the
`RATE_SOURCE` / `NULL_RATE` row for its empty `rates` field.

## 5. Encoding discipline

- **A null column is an empty *unquoted* field** — nothing at all between two commas, the DB2
  `UNLOAD DELIMITED` default of §0.12.1. `DelimitedExportReader` treats a *quoted* empty field as the
  empty string and the literal text `NULL` as an ordinary value, so either mistake silently yields a
  non-null value, the seeded variance disappears, and no error is raised anywhere.
- **Every money value carries exactly two decimals.** `LoaderIT` compares CSV-decoded against
  binary-decoded records and `BigDecimal.equals` is scale-sensitive: `new BigDecimal("1000.00")`
  equals `new BigDecimal("000100000").movePointLeft(2)`, but `1000.0` or `1000` would not.
- **`rates` is two decimals with a ceiling of 9.99 and is nullable** — `NUMERIC(3,2)`
  (`DB2DDL.jcl:L58`) / `PIC S9(1)V9(2) COMP-3` (`DCLFRANK.cpy:L12`, `L22`). That is why the sibling
  entity `LegacyRateTable.rates` stays nullable at precision 3, scale 2 rather than defaulting, and
  why the `ZZZ` seed is representable at all.

## 6. Cross-fixture consistency — changing a number here breaks siblings silently

The six account balances and the three rates are shared with the sibling `matched/` folder and,
through it, with `fixtures/shadow/**`, whose expected legacy responses are *computed* from them using
the §0.4.1 arithmetic: the full-precision product, the signed addition, then one final truncation
`RoundingMode.DOWN`. The coupling is invisible from inside this directory, so it is recorded here.

| Owner | Legacy computation | Result |
|---|---|---|
| `JOHN` | `1000.00 + 1.00 × 250.50` | `1250.50` |
| `GREG` | `123456.78 + 0.79 × 100.00` | `123535.78` |
| `ERIC` | `1234567.89 − 0.92 × 0.30` — `0.276` subtracted, then the single final truncation | `1234567.61` |
| `RAUNAK` | `100.00 − 1.00 × 150.00`, raw `−50.00` | legacy stored the absolute value `50.00`, the unsigned `WS-CALC PIC 9(7)V99` at `CASH00.cbl:L17`; the target returns `422` instead |

**Do not "tidy" any of these numbers.** Rounding `1234567.89` to something friendlier, or making
`0.92` a live ECB rate, silently invalidates the shadow fixtures' expected responses without failing
anything in this directory.

The owner names come from the estate stub JSON at
`backend/broker/src/main/liberty/config/includes/none.xml:L58`, whose totals are `John 1234.56`,
`Karri 12345.67`, `Ryan 23456.78`, `Raunak 98765.43`, `Greg 123456.78` and `Eric 1234567.89`. `KARRI`,
`RYAN`, `GREG` and `ERIC` reuse those verbatim; `JOHN 1000.00` and `RAUNAK 100.00` deliberately
diverge, because the two round numbers make the shadow credit and the sign-drop arithmetic above exact
and hand-checkable.
