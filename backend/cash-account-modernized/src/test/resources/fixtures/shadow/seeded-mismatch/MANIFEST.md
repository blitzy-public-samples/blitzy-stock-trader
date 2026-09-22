# Fixture manifest — `src/test/resources/fixtures/shadow/seeded-mismatch/`

This directory is the **seeded-mismatch** variant of the shadow-mode window: a captured transaction
stream and the legacy replies it produced, with three known divergences planted between them. Its
only purpose is to prove that `ShadowComparator` flags **every** seeded divergence and flags
**nothing else**. A run that reports two rows has a hole in it; a run that reports four has invented
one. Both are failures, so the row set below is the assertion and not a summary of it.

Authorising sections of the AAP: §0.2.1 puts fixture data in scope under this root; §0.3.1 builds the
dual-run comparator now and proves it **against fixtures only**; §0.6.1 places these two files in the
module tree and mandates one `MANIFEST.md` per fixture directory; §0.8.1 names the three seeds
(RYAN, RAUNAK, JOHN); §0.10.3 fixes the contents and the expected rows; §0.12.1 gives the delimited
conventions both files obey; §0.12.2 requires key joins rather than positional ones; §0.12.5 fixes
the rate source parity is judged on.

**Both files are synthetic and hand-authored from the characterized legacy arithmetic.** No legacy
request stream was captured, and the dual-run mechanism was never run against the live legacy system,
against DB2 for z/OS or against `SYSD.STOCK.HISTORY` — AAP §0.3.2 prohibits all three. Only the
*behaviour* the replies encode is legacy-derived, read from the read-only characterization source
`backend/cash-account-cobol/COBOL/CASH00.cbl` and the copybook `COBOL/DCLFRANK.cpy`, neither of which
is modified.

This sidecar exists because the delimited-export contract (AAP §0.12.1) reserves line 1 of every CSV
for the column header and defines **no comment syntax**, so neither fixture can carry its own
provenance (AAP §0.6.1, §0.10.1). Nothing loads this document at build or run time. It is the
authority for the two files in this directory and for nothing else: the parent
`fixtures/shadow/MANIFEST.md` owns `rollback-replay.csv` — its watermark, its deriving ledger range
and its `NEWCUST` rationale — and `fixtures/legacy-export/seeded-mismatch/MANIFEST.md` owns the
corpus-side seeds. Neither derivation is restated here; two copies would drift.

## 1. The fixtures

| File | Shape | What it encodes | §0.4 finding it carries | §0.10.3 seed | Expected tooling outcome |
|---|---|---|---|---|---|
| `transactions.csv` | `seq,owner,req,amount,currency` — **6** data rows | The replayed request stream, with the caller-supplied COMMAREA amount as the multiplicand | §0.4.5 request-code baseline — `EVALUATE WS-REQ` recognizes exactly `A/Q/U/X/C/D`, case-sensitively, and `END-EVALUATE` at `CASH00.cbl:L102` carries no `WHEN OTHER` (`L89-L102`). §0.4.1 — the multiplicand is the caller's `WS-BALANCE PIC 9(7)V99` (`L56`), moved into `BALANC-RATE` at `L221` (credit) and `L255` (debit); it is **not** `FRANKFURT1.AMOUNT`, which the rate `SELECT` fetches (`L215`, `L249`) and no `COMPUTE` or `MOVE` ever references | Carries the `RAUNAK` over-debit, and is deliberately **one row short** of the replies — the RYAN omitted-transaction seed | `migrated_record_count 6`; one `REJECTED_BY_TARGET` row. The file is well formed on its own: the shortfall is only observable against the replies |
| `legacy-responses.csv` | `seq,owner,retcode,balance` — **7** data rows | The reply each request produced: the sign-dropped status channel and the echoed balance | §0.4.5 status-code-as-return-channel — `MOVE SQLCODE TO WS-RETCODE` (`CASH00.cbl:L104`, and `L117` for the history copy) renders a numeric `SQLCODE` into the alphanumeric `WS-RETCODE PIC X(10)` (`L58`) and drops the sign. §0.4.1 arithmetic landing in the **unsigned** `WS-CALC PIC 9(7)V99` (`L17`), which is why `RAUNAK`'s reply is a positive `50.00` | All three: `JOHN` `1250.60` (balance), `RAUNAK` `50.00` (sign drop), and the extra `RYAN` reply at `seq 7` (count) | `legacy_record_count 7`; exactly the three rows of §4; `variance_count 2`; exit code **2** |

Both column sets are declared once, in `migration/LegacyExportFormat.SHADOW_TRANSACTION_COLUMNS` and
`SHADOW_LEGACY_RESPONSE_COLUMNS`, and the two file names in `SHADOW_TRANSACTIONS_FILE` /
`SHADOW_LEGACY_RESPONSES_FILE`. These fixtures **satisfy** those constants; they never redefine them.
A renamed column or file belongs in `LegacyExportFormat`, with the fixtures following.

## 2. Starting state the replay assumes

`ShadowComparatorIT` establishes this state before replaying the stream, from the matched legacy
export `fixtures/legacy-export/matched/cashaccounty.csv`:

| Owner | Balance | Currency |
|---|---|---|
| `JOHN` | `1000.00` | USD |
| `KARRI` | `12345.67` | USD |
| `RYAN` | `23456.78` | USD |
| `RAUNAK` | `100.00` | USD |
| `GREG` | `123456.78` | GBP |
| `ERIC` | `1234567.89` | EUR |

Rates come from `fixtures/legacy-export/matched/frankfurt1.csv` — `USD 1.00`, `EUR 0.92`, `GBP 0.79`
— with base currency `USD`. Only `JOHN`, `RAUNAK` and `RYAN` are touched by this stream; the other
three accounts exist so the starting state is shared verbatim with the sibling `matched/` variant and
one corpus serves both. Change a balance or a rate there and every expected value below moves
without either file in this directory being touched.

## 3. The arithmetic rule, and the sign convention

Every credit and debit reply in `legacy-responses.csv` is

```
truncate2( stored ± RATES × caller_amount )
```

computed with the **full-precision product** and **exactly one** final truncation,
`RoundingMode.DOWN`, applied to the signed sum. That is the legacy
`COMPUTE WS-CALC = BALANCE ± (RATES * BALANC-RATE)` (`CASH00.cbl:L221-L222` credit, `L255-L256`
debit) landing in `WS-CALC PIC 9(7)V99` (`L17`), which is **unsigned** and carries neither `ROUNDED`
nor `ON SIZE ERROR` — so truncation is the legacy semantic and a negative result loses its sign
rather than failing.

`RATES` is `DECIMAL(3, 2)` (`DCLFRANK.cpy:L12`) / `PIC S9(1)V9(2) USAGE COMP-3` (`L22`): two
decimals, ceiling `9.99`. Every transaction in this stream is `USD` against a `USD` account, so the
applicable rate is exactly `1.00` and is exactly representable at that precision — every conversion
here is rounding-free. That is deliberate: it isolates each seed from any rounding question, so a
failure in this directory can only mean the comparator's classification is wrong, never that its
arithmetic drifted a cent. The order-of-truncation case is proven in the sibling `matched/` variant
instead, where `ERIC`'s sub-cent product makes the two orders diverge.

The module-wide sign convention, declared in `MigrationReconciliation` and applied by
`ReconciliationService.record`, is **`variance = migrated − legacy`**. A negative variance therefore
means the target holds *less* than the legacy reply claimed.

## 4. The three expected rows — the contract of record

`shadow-compare` over this directory writes **exactly three** `migration_reconciliation` rows under
the window's `run_id`. This table is the contract `ShadowComparatorIT`'s seeded case asserts, row for
row:

| Owner | Kind | Status | Detail |
|---|---|---|---|
| `JOHN` | `BALANCE` | `VARIANCE` | legacy `1250.60` against target `1000.00 + 1.00 × 250.50 = 1250.50` → variance **−0.10** |
| `RAUNAK` | `REJECTED_BY_TARGET` | `ACCEPTED_EXCEPTION` | legacy `100.00 − 1.00 × 150.00` is raw `−50.00`, stored as its **absolute value** `50.00` because `WS-CALC PIC 9(7)V99` is unsigned (`CASH00.cbl:L17`, `L256`); the target refuses with `422 INSUFFICIENT_FUNDS`, an authorized deviation (§0.4.6) |
| `RYAN` | `TRANSACTION_COUNT` | `VARIANCE` | legacy **5** counted replies (`seq 3`-`7`) against target **4** replayed transactions (`seq 3`-`6`) |

Plus `legacy_record_count 7`, `migrated_record_count 6`, **`variance_count 2`** — the two `VARIANCE`
rows — and exit code **2**.

## 5. Why exactly three rows and not four

This is the one question a maintainer arrives here with, so it is answered in full.

- **`RYAN` contributes no `BALANCE` row, because every balance on that chain agrees.** Each step is
  re-derived below and each result equals its paired legacy reply exactly:

  | `seq` | Request | Derivation | Result | Paired legacy reply |
  |---|---|---|---|---|
  | 3 | `RYAN C 100.00 USD` | `23456.78 + 1.00 × 100.00`, raw `23556.7800` | `23556.78` | `23556.78` |
  | 4 | `RYAN D 56.78 USD` | `23556.78 − 1.00 × 56.78`, raw `23500.0000` | `23500.00` | `23500.00` |
  | 5 | `RYAN C 500.00 USD` | `23500.00 + 1.00 × 500.00`, raw `24000.0000` | `24000.00` | `24000.00` |
  | 6 | `RYAN U 24000.00 USD` | An **absolute overwrite**, not arithmetic and not rate-scaled: `MOVE WS-BALANCE TO BALANCE` (`CASH00.cbl:L172`) followed by the `UPDATE` at `L174-L179`, which sets `BALANCE=:BALANCE, CURRENCYC=:WS-CURRENCY` (`L176-L177`) | `24000.00` | `24000.00` |

  The `U` at `seq 6` deliberately writes the value the chain has already reached, so the `U` branch is
  exercised without perturbing the chain and `seq 6` still agrees on both sides. `RYAN`'s stored
  `23456.78` never appears in a reply after `seq 3`, and no rate is read on the `U` path at all.
  `RYAN` therefore contributes the `TRANSACTION_COUNT` row and nothing more.
- **`RAUNAK` yields one row, not two. A transaction the target rejects still counts as processed.**
  The target count is of transactions **attempted**, not accepted, so `RAUNAK`'s counts agree at
  **1 / 1** and no `TRANSACTION_COUNT` row is written for it. That is load-bearing rather than
  incidental: the refused line already carries its own `REJECTED_BY_TARGET` row, and counting it as a
  shortfall as well would report one divergence twice and turn this seed into two rows instead of the
  one the acceptance criteria fix.
- **`JOHN`'s counts agree at 1 / 1 too**, so its single divergence — the balance — is its single row.
- **An agreeing line gets no row at all, not even a `MATCHED` one.** `ReconciliationStatus.MATCHED`
  is the value an operator sets when reclassifying a reviewed row, never something the comparison
  writes. That is what keeps the assertion a fixed row set instead of one that scales with the
  window's size.
- **`ACCEPTED_EXCEPTION` does not raise `variance_count` and does not by itself force exit 2.** Only
  `VARIANCE` feeds either, so `RAUNAK`'s row is evidence without being a failure, and the count of
  **2** comes from `JOHN` and `RYAN` alone. The sibling `legacy-export` manifests record the same
  convention, and a change to it would silently move the exit code of every fixture in the module.

## 6. The counting rule the `TRANSACTION_COUNT` seed depends on

Stated explicitly, because it is the mechanism of the seed and the `ShadowComparatorIT` author needs
it unambiguous. Per owner:

- the **legacy count** is the successful replies — `retcode` *parses to zero*, tested once in
  `LegacyExportFormat.isSuccessRetcode` — whose request code is one of the counted codes
  `A/U/X/C/D`, and an **unpaired** successful reply counts toward its owner's legacy total, because
  it is evidence of a legacy state change the target never saw;
- the **target count** is the transactions replayed for that owner, **including** any the target
  rejected.

Hence `JOHN` 1 / 1 and `RAUNAK` 1 / 1 — no row for either — and `RYAN` 5 / 4. A target *shortfall*
cannot be explained away and is a `VARIANCE`; a target *excess* is expected and accepted, because
`EXEC CICS IGNORE CONDITION DUPREC` (`CASH00.cbl:L124`) discarded a second history record whose
29-byte key already existed and that key's only time component is a whole second, making legacy
counts a lower bound (§0.11.1). `RYAN` is a shortfall, so `VARIANCE` is the right status.

`Q` replies are excluded from the counts: the legacy history write follows `END-EVALUATE`
unconditionally (`CASH00.cbl:L102`, `L111-L131`), so reads were audited too, while a read changed no
state and has nothing on the target side to count against. **This stream contains no `Q` row**, so
the exclusion is inert here and is exercised by the sibling `matched/` variant instead.

**The comparator joins on `seq` together with the normalised owner, never on file order.** EBCDIC and
UTF-8 collate differently — digits collate after letters in EBCDIC and before them in ASCII — so two
exports of one window can arrive in different orders and an ordinal pairing would compare unrelated
lines (AAP §0.12.2). That join is what makes the seed expressible at all: the reply at `seq 7` has no
partner transaction, so it is counted for `RYAN` without being replayed, which is precisely the
omitted-transaction condition.

**The extra reply is the last `RYAN` row on purpose.** Inserting it mid-chain would renumber every
later reply, so each subsequent transaction would be joined to the answer to its predecessor and each
mismatched pair would add a `BALANCE` row — breaking the exact-three expectation while appearing to
test the same thing. Its balance `24100.00` is a plausible continuation of the chain
(`24000.00 + 100.00`) so that the row is realistic capture data rather than an obvious marker, and it
is never replayed, so that value reaches no assertion.

## 7. Decisions a reader would otherwise mistake for defects

- **No `RATE_SOURCE` row is expected.** With `tool.rate-source=legacy-table` — the default — the
  comparator judges parity against the staged `frankfurt1.csv` rates, so both sides are computed from
  identical inputs and a live-versus-legacy rate divergence cannot arise (AAP §0.12.5). A run with
  `tool.rate-source=live` would classify a rate-explained difference as `RATE_SOURCE` /
  `ACCEPTED_EXCEPTION`; that is a different configuration, not a defect in these files.
- **The 6-versus-7 row asymmetry is the seed, not an authoring error.** The two files are meant to
  disagree in their line counts. `requireOwnersAgree` validates only sequence numbers present on both
  sides, so an unpaired reply is legal input rather than a rejected capture.
- **`JOHN`'s request is identical to the one the sibling `matched/transactions.csv` carries, and only
  the reply differs.** The four request fields are the same tuple — `JOHN,C,250.50,USD` — at `seq 2`
  there and at `seq 1` here, this stream carrying no leading `Q` line; the replies are `1250.50`
  there against `1250.60` here. That is the cleanest way to confirm the seed: the request is the
  same, the correct answer is the same, and the captured reply is one dime out.
- **`RAUNAK`'s reply `50.00` carries no minus sign, and that is correct.** The legacy field physically
  cannot hold one — `WS-CALC` is `PIC 9(7)V99` with no `S` (`CASH00.cbl:L17`) — so the over-debit was
  stored and echoed as an absolute value. Writing `-50.00` would assert a legacy capability that did
  not exist and would make the reply unparseable as a legacy balance.
- **`retcode` is a nine-character string, not a number, and is uniform across all seven rows.**
  `MOVE SQLCODE TO WS-RETCODE` (`CASH00.cbl:L104`, and `L117` for the history copy) moves a numeric
  `SQLCODE` into the alphanumeric `WS-RETCODE PIC X(10)` (`L58`), which renders the absolute digits
  and drops the sign — `-803` and `+803` both arrive as `000000803`. Parsing the column to an integer
  would invent a sign and discard the leading zeros, so "parses to zero" is the only unambiguous
  success test. Every reply here is a success, which is deliberate: each seed must be a divergence
  between two *successful* sides, since a failed legacy reply is not comparable with a target result
  at all and would be skipped rather than flagged.
- **Two balances deliberately diverge from the estate stub they are named after.** The owners are the
  uppercase forms of the six broker stub names at
  `backend/broker/src/main/liberty/config/includes/none.xml:L58`, whose totals are `John 1234.56`,
  `Karri 12345.67`, `Ryan 23456.78`, `Raunak 98765.43`, `Greg 123456.78` and `Eric 1234567.89`. The
  corpus keeps `RYAN`'s `23456.78` verbatim but uses `JOHN 1000.00` and `RAUNAK 100.00`. Both
  divergences are load-bearing: `RAUNAK`'s small balance is what makes the `D 150.00` sign-drop seed
  possible at all — against the stub's `98765.43` the debit would simply succeed — and `JOHN`'s round
  `1000.00` makes the `−0.10` variance readable at a glance. Nothing here is to be "corrected" to
  match the stub.

## 8. Format contract, and the relationship to `matched/`

For whoever regenerates or extends these files (AAP §0.12.1, §0.7.1):

- UTF-8, LF line endings, no BOM, exactly one trailing newline; no comment lines and no blank lines —
  every line is a record a reader can parse.
- Line 1 is the header; readers bind columns by **header name**, never by ordinal, so column order is
  free but the spellings are fixed by `LegacyExportFormat`.
- Comma-delimited with RFC 4180 quoting available where a field contains a comma, quote or newline.
  Nothing in these two files needs it, and nothing here is quoted.
- A SQL NULL or a not-applicable field is an **empty unquoted field**; the literal text `NULL` never
  appears. Every row in this variant carries all of its columns, so no empty field occurs — the null
  convention is exercised by the sibling `matched/` variant's `Q` and `X` lines.
- Money is plain text matching `-?\d+\.\d{2}` — **always two decimals**, no thousands separator, no
  exponent, and no floating-point value anywhere on a balance, amount or rate path (AAP §0.7.1). Two
  decimals are load-bearing beyond tidiness: every amount in this stream carries exactly two, so no
  expected value depends on the open input-rounding question of AAP §0.11.2.

This variant reuses the sibling `matched/` directory's corpus, its rates, its header shapes and its
derivation conventions, and diverges only in the three seeded points. **Both `transactions.csv`
headers and both `legacy-responses.csv` headers are byte-identical between the two folders** — the
seeds live in the data, never in the shape, so a reader can diff a fixture against its twin and see
only the intended difference.

**No `# sha256` line is embedded in either CSV, and no checksum sidecar file exists.** AAP §0.12.1
keeps export checksums in the runbook evidence *beside* the file, so the CSVs stay free of comment
syntax. As a recorded byte fact rather than file content, `transactions.csv` is 153 bytes and
`legacy-responses.csv` is 206 bytes, and every value in them is reproduced in §4, §5 and §7 of this
document — a stronger check than a digest, because a reader can recompute it from the arithmetic in
§3. No generator script is committed (AAP §0.7.6); §3 and §5 are the reproduction record.

## 9. Consumers and cross-references

`migration/shadow/ShadowComparator` replays each transaction through `RetailCashAccountService`
**directly — never over HTTP** — and emits only `BALANCE`, `TRANSACTION_COUNT`,
`REJECTED_BY_TARGET` and `RATE_SOURCE` rows; the other two `VarianceKind` values, `STATE` and
`CURRENCY`, belong to reconcile mode and cannot appear in a shadow window.

The consumer of this directory is `migration/ShadowComparatorIT`'s seeded case: exactly the three
rows of §4, `variance_count 2`, and exit code **2** — `MigrationToolRunner` maps 0 clean, 2 variance,
1 error.

For the clean twin of this stream see `../matched/MANIFEST.md`; for `rollback-replay.csv` and the
rollback derivation see `../MANIFEST.md`; for the corpus-side seeds, the rate table and the legacy
history pair see `../../legacy-export/seeded-mismatch/MANIFEST.md` and
`../../legacy-export/matched/MANIFEST.md`. Those derivations are referenced, not duplicated. The full
behavioural baseline, with every open item, is the module's `docs/legacy-characterization.md`.
