# Fixture manifest — `src/test/resources/fixtures/shadow/matched/`

This directory is the **matched** variant of the shadow-mode window: a transaction stream and the
legacy replies it produces, constructed to agree exactly. Replaying `transactions.csv` through the
new service reproduces every balance in `legacy-responses.csv` to the cent, so `ShadowComparatorIT`'s
matched case asserts **zero `VARIANCE` rows and exit code 0** (AAP §0.10.3). There is **no seeded
mismatch here** — every §0.10.3 seed lives in the sibling `seeded-mismatch/` directory.

**Both files are synthetic and hand-authored from the characterized legacy arithmetic.** No legacy
request stream was captured, and the dual-run mechanism was never run against the live legacy system,
against DB2 for z/OS or against `SYSD.STOCK.HISTORY`; AAP §0.3.2 prohibits all three. Only the
*behaviour* is legacy-derived, read from the read-only characterization source
`backend/cash-account-cobol/COBOL/CASH00.cbl`.

This sidecar exists because the delimited-export contract (AAP §0.12.1) reserves line 1 of every CSV
for the column header and defines **no comment syntax**, so neither fixture can carry its own
provenance (AAP §0.6.1, §0.10.1). It is the authority for the two files in this directory and for
nothing else: the parent `fixtures/shadow/MANIFEST.md` owns `rollback-replay.csv`, and
`fixtures/legacy-export/matched/MANIFEST.md` owns the account corpus, the rate table and the history
pair. Neither is restated here.

## 1. The fixtures

| File | Shape | What it encodes | §0.4 finding it carries | §0.10.3 seed | Expected tooling outcome |
|---|---|---|---|---|---|
| `transactions.csv` | `seq,owner,req,amount,currency` | Ten replayed requests covering all six legacy request codes, with the caller-supplied COMMAREA amount as the multiplicand | §0.4.5 request-code baseline — `EVALUATE WS-REQ` recognizes exactly `A/Q/U/X/C/D`, case-sensitively (`CASH00.cbl:L89-L102`). §0.4.1 — the amount is the caller's `WS-BALANCE PIC 9(7)V99` (`CASH00.cbl:L56`) moved into the host variable at `L75`, **not** `FRANKFURT1.AMOUNT`, which the credit and debit `SELECT` fetches (`L215`, `L249`) and no `COMPUTE` or `MOVE` ever references | none (clean variant) | Every line replays successfully; no `REJECTED_BY_TARGET` row |
| `legacy-responses.csv` | `seq,owner,retcode,balance` | The reply each request produced: the sign-dropped status channel and the echoed balance | §0.4.1 credit/debit arithmetic — full-precision product, one final truncation `RoundingMode.DOWN` (`CASH00.cbl:L221-L222` credit, `L255-L256` debit; unsigned `WS-CALC PIC 9(7)V99` at `L17`). §0.4.5 status-code-as-return-channel — `MOVE SQLCODE TO WS-RETCODE` renders the code into `X(10)` and drops the sign (`CASH00.cbl:L104`) | none (clean variant) | `shadow-compare` → `migration_run.variance_count = 0`, zero `VARIANCE` rows, exit code 0 |

Both column sets are declared once, in `migration/LegacyExportFormat.SHADOW_TRANSACTION_COLUMNS` and
`SHADOW_LEGACY_RESPONSE_COLUMNS`, and the file names in `SHADOW_TRANSACTIONS_FILE` /
`SHADOW_LEGACY_RESPONSES_FILE`. These fixtures **satisfy** those constants; they never redefine them.
A renamed column or file belongs in `LegacyExportFormat`, with the fixtures following.

## 2. Starting state the replay assumes

`ShadowComparatorIT` establishes this state before replay, from the matched legacy export:

| Owner | Balance | Currency |
|---|---|---|
| `JOHN` | `1000.00` | USD |
| `KARRI` | `12345.67` | USD |
| `RYAN` | `23456.78` | USD |
| `RAUNAK` | `100.00` | USD |
| `GREG` | `123456.78` | GBP |
| `ERIC` | `1234567.89` | EUR |

Source: `fixtures/legacy-export/matched/cashaccounty.csv`. Rates come from
`fixtures/legacy-export/matched/frankfurt1.csv` — `USD 1.00`, `EUR 0.92`, `GBP 0.79` — with base
currency `USD`. Change a balance or a rate there and every expected value below moves without either
file in this directory being touched; that coupling is recorded in the sibling manifest's
cross-fixture section.

## 3. The arithmetic rule, stated once

Every credit and debit reply is

```
truncate2( stored ± RATES × caller_amount )
```

computed with the **full-precision product** and **exactly one** final truncation,
`RoundingMode.DOWN`, applied to the signed sum. That is the legacy
`COMPUTE WS-CALC = BALANCE ± (RATES * BALANC-RATE)` (`CASH00.cbl:L221-L222` credit,
`L255-L256` debit) landing in `WS-CALC PIC 9(7)V99` (`CASH00.cbl:L17`), which is unsigned and carries
neither `ROUNDED` nor `ON SIZE ERROR` — so truncation, not rounding, is the legacy semantic. `RATES`
is keyed on the **stored account** currency, not on the captured request currency
(`MOVE CURRENCYC TO WS-CURRENCY-KEY`, `CASH00.cbl:L213` credit, `L247` debit).

## 4. Per-`seq` derivation

Both files verbatim, so they can be checked without opening them. `transactions.csv`:

```
seq,owner,req,amount,currency
1,JOHN,Q,,
2,JOHN,C,250.50,USD
3,KARRI,D,345.67,USD
4,GREG,C,100.00,GBP
5,ERIC,D,0.30,EUR
6,RYAN,U,20000.00,USD
7,RAUNAK,C,0.00,USD
8,RAUNAK,X,,
9,RAUNAK,A,500.00,USD
10,RAUNAK,Q,,
```

`legacy-responses.csv`:

```
seq,owner,retcode,balance
1,JOHN,000000000,1000.00
2,JOHN,000000000,1250.50
3,KARRI,000000000,12000.00
4,GREG,000000000,123535.78
5,ERIC,000000000,1234567.61
6,RYAN,000000000,20000.00
7,RAUNAK,000000000,100.00
8,RAUNAK,000000000,100.00
9,RAUNAK,000000000,500.00
10,RAUNAK,000000000,500.00
```

| `seq` | Request | Reply `balance` | Derivation |
|---|---|---|---|
| 1 | `JOHN Q` | `1000.00` | The stored balance, read unchanged: `CASH-ACCT-READ` moves it into the reply field on `SQLCODE 0` (`CASH00.cbl:L136-L150`, balance at `L145`). Establishes the baseline that `seq 2` then credits |
| 2 | `JOHN C 250.50 USD` | `1250.50` | Rate `1.00`; product `250.5000`; raw `1250.5000`; one final truncation → `1250.50` |
| 3 | `KARRI D 345.67 USD` | `12000.00` | Rate `1.00`; product `345.6700`; raw `12000.0000` → `12000.00`. The amount is chosen to land on a round balance, so a reader can check the subtraction at a glance |
| 4 | `GREG C 100.00 GBP` | `123535.78` | Rate `0.79` (GBP); product `79.0000`; raw `123535.7800` → `123535.78`. The only cross-rate credit |
| 5 | `ERIC D 0.30 EUR` | `1234567.61` | Rate `0.92` (EUR); product `0.2760`; raw `1234567.6140`; one final truncation → `1234567.61`. §5 below explains why this row exists |
| 6 | `RYAN U 20000.00 USD` | `20000.00` | An **absolute overwrite**, not a delta and not rate-scaled: the update moves the caller's amount straight into the host variable (`CASH00.cbl:L172`) and writes both columns, `SET BALANCE=:BALANCE, CURRENCYC=:WS-CURRENCY` (`L176-L177`). RYAN's stored `23456.78` therefore never appears in the reply, and no rate is read on this path |
| 7 | `RAUNAK C 0.00 USD` | `100.00` | Zero-value credit: product `0.0000`, raw `100.0000`, balance untouched, `SQLCODE 0`. Legal and preserved deliberately (§0.4.5) — the target accepts it, changes no balance, and still writes **one zero-amount ledger row**, because dropping it would make target transaction counts disagree with the legacy history for no behavioural reason |
| 8 | `RAUNAK X` | `100.00` | The **pre-delete** balance. `CASH-ACCT-DELETE` fills the host variable with `SELECT … INTO` (`CASH00.cbl:L187-L192`) and only then deletes the row (`L195-L198`); the reply is echoed once, after dispatch, from that same host variable (`L105`). A deleted account echoing a balance is the legacy fact, not a fixture error |
| 9 | `RAUNAK A 500.00 USD` | `500.00` | The **caller-supplied** amount. The insert binds `:BALANCE` (`CASH00.cbl:L155`), which was set from the COMMAREA at `L75`, and the single echo point (`L105`) returns that host variable — so a create replies with exactly what it was given |
| 10 | `RAUNAK Q` | `500.00` | Reads the recreated account back, confirming `seq 9` landed rather than trusting its echo |

Code coverage: `Q` (1, 10), `C` (2, 4, 7), `D` (3, 5), `U` (6), `X` (8), `A` (9) — all six branches of
`EVALUATE WS-REQ` (`CASH00.cbl:L89-L102`), each exercised at least once.

## 5. Why the truncation is single and final

`seq 5` is in this stream for one reason: it is the only row where the order of truncation changes the
answer. Truncating the **product** first gives `trunc2(0.2760) = 0.27` and therefore
`1234567.89 − 0.27 = 1234567.62`, one cent above the correct `1234567.61`, which comes from
truncating the **result** (`raw 1234567.6140`). The legacy `COMPUTE` truncates once, when the
expression lands in `WS-CALC` (`CASH00.cbl:L17`, `L255-L256`), so `1234567.61` is the parity value and
`1234567.62` is the defect this row catches.

`ERIC`'s amount is deliberately tiny. The product has to be sub-cent for the two orders to diverge,
while the balance has to stay inside `9(7)V99` / `NUMERIC(9,2)` at its full seven integer digits — an
amount large enough to move the integer part would overflow the ceiling instead of exercising the
rounding rule. The other three arithmetic rows have exact two-decimal products (`250.5000`,
`345.6700`, `79.0000`), so they are insensitive to the order and cannot mask a regression here.

## 6. Decisions a reader would otherwise mistake for defects

- **`seq 8` (`X`) before `seq 9` (`A`) is load-bearing.** `RAUNAK` is already in the starting corpus at
  `100.00`, so without the delete the create would collide: legacy would reply `-803`, rendered
  `000000803` (`CASH00.cbl:L104`), and the target would refuse with `409 ACCOUNT_ALREADY_EXISTS`,
  producing a `REJECTED_BY_TARGET` row and destroying the zero-variance guarantee. The four `RAUNAK`
  lines are a chain — credit, delete, create, read — and each reply depends on the one before it.
  Reordering them, or replaying them in isolation, changes the expected replies of `seq 8`, `seq 9`
  and `seq 10`.
- **`retcode` is nine zero characters and stays a String.** `MOVE SQLCODE TO WS-RETCODE`
  (`CASH00.cbl:L104`, and `L117` for the history copy) moves the numeric `SQLCODE` into the
  alphanumeric `WS-RETCODE PIC X(10)` (`CASH00.cbl:L58`), which renders the absolute digits and
  **drops the sign** — `-803` and `+803` both arrive as `000000803`. Parsing the field to an integer
  would invent a sign and discard the
  leading zeros, so "parses to zero" is the only unambiguous success test, implemented once in
  `LegacyExportFormat.isSuccessRetcode`. The nine zeros match the sibling
  `legacy-export/matched/history.csv` for the same reason.
- **`amount` and `currency` are empty on the `Q` and `X` rows because they are absent, not zero.** The
  §0.12.1 NULL convention is an **empty unquoted field**: `""` is the empty string and the literal text
  `NULL` is a value, and neither appears anywhere in these files. `DelimitedExportReader` applies that
  test to unquoted fields only, via `LegacyExportFormat.isNull`, and `ShadowTransaction` carries both
  as `null` for those lines. Writing `0.00` instead would assert an amount the legacy request never
  carried.
- **The `currency` column binds on `A` and `U` and is inert on `C` and `D`.** The insert and the update
  write it (`CASH00.cbl:L155`, `L177`), whereas credit and debit read the rate on the **stored**
  account currency (`L213`, `L247`) and never on the captured one. Every `C`/`D` line here carries the
  same code the account already holds, and the two binding lines carry the owner's existing `USD` —
  `seq 6` for `RYAN`, `seq 9` for `RAUNAK` — so no line changes a currency and the distinction cannot
  silently move an expected value.
- **No `RATE_SOURCE` row is expected.** With `tool.rate-source=legacy-table` — the default — the
  comparator computes expected values from the staged `legacy-export/matched/frankfurt1.csv` rates, so
  parity is judged on identical inputs and a live-versus-legacy rate divergence cannot arise in this
  variant (AAP §0.12.5). A run with `tool.rate-source=live` would classify the rate difference as
  `RATE_SOURCE`; that is a different configuration, not a defect in these files.
- **Two balances deliberately differ from the estate stub they are named after.** The owners are the
  uppercase forms of the six broker stub names at
  `backend/broker/src/main/liberty/config/includes/none.xml:L58`, where the totals are `John 1234.56`
  and `Raunak 98765.43`, while these fixtures start `JOHN` at `1000.00` and `RAUNAK` at `100.00`. That
  divergence is intentional and is recorded by `fixtures/legacy-export/matched/MANIFEST.md`; the clean
  starting values are exactly what makes `seq 2`, `seq 7` and the `RAUNAK` chain checkable by hand.
  Nothing here is to be "corrected" to match the stub.
- **The comparator joins by key, never by file order.** Rows are matched on `seq` together with the
  normalized owner, because EBCDIC and UTF-8 collate differently and an exported stream's ordinal
  position is not a usable key (AAP §0.12.2). The two files are nevertheless written in the same order
  for human legibility — which means a wrong `owner` on a `seq` is a real defect that the shared
  ordering will not hide.
- **`RAUNAK`'s insufficient-funds case is not here.** The legacy unsigned `WS-CALC` (`CASH00.cbl:L17`)
  stored an over-debit as its absolute value where the target returns `422 INSUFFICIENT_FUNDS`; that is
  a deliberate divergence and belongs to `seeded-mismatch/`, whose manifest owns it. A matched stream
  by definition contains no request whose two sides disagree.

## 7. Format contract

For whoever regenerates or extends these files (AAP §0.12.1, §0.7.1):

- UTF-8, LF line endings, no BOM, exactly one trailing newline; no comment lines and no blank lines —
  every line is a record a reader can parse.
- Line 1 is the header; readers bind columns by **header name**, never by ordinal, so column order is
  free but the spellings are fixed by `LegacyExportFormat`.
- Comma-delimited with RFC 4180 quoting available where a field contains a comma, quote or newline.
  Nothing in these files needs it, and nothing here is quoted.
- An empty unquoted field is NULL; the literal `NULL` is never written.
- Money is plain text matching `-?\d+\.\d{2}` — **always two decimals**, no thousands separator, no
  exponent, and no floating-point value anywhere on a balance, amount or rate path. The two decimals
  are load-bearing: `BigDecimal.equals` is scale-sensitive, so `1000.0` would not compare equal to
  `1000.00`. Every amount in this stream carries exactly two decimals, so no expected value depends on
  the open input-rounding question of AAP §0.11.2.
- `retcode` stays a nine-digit string, for the sign-dropping reason in §6.

**No checksum is embedded, and no checksum sidecar exists.** AAP §0.12.1 keeps the CSVs free of
comment syntax and records export checksums in the runbook evidence *beside* the file, so a `# sha256`
line would be unparseable content in a contract that has no place for it. These two files are ten
lines each and are reproduced verbatim in §4, which is a stronger check than a digest a reader cannot
recompute from the document — and a digest quoted here would go stale the moment a value legitimately
changed. The derivation in §3 and §4 is the reproduction record; no generator script is committed for
this variant (AAP §0.7.6).

## 8. Consumers

`migration/ShadowComparatorIT`'s matched case, through `migration/shadow/ShadowComparator`, which
replays each transaction through `RetailCashAccountService` **directly — never over HTTP** — and
writes `migration_reconciliation` rows plus a `migration_run` summary. For this directory the
assertion is the absence of rows: zero `VARIANCE`, `variance_count = 0`, exit code 0.

For the divergent stream and its three expected rows, see `../seeded-mismatch/MANIFEST.md`. For
`rollback-replay.csv` and the rollback derivation, see `../MANIFEST.md`. For the starting corpus, the
rate table and the legacy history pair, see `../../legacy-export/matched/MANIFEST.md`. The full
behavioural baseline, with every open item, is the module's `docs/legacy-characterization.md`.
