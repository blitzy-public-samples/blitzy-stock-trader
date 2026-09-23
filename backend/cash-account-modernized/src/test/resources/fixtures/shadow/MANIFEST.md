# Fixture manifest — `src/test/resources/fixtures/shadow/`

**All data in this directory and in its subdirectories is synthetic and hand-authored from the
characterized legacy behaviour. No real legacy traffic was captured, and the dual-run mechanism was
never run against the live legacy system.** That is a standing prohibition (AAP §0.3.2), not a
caveat about this particular fixture: the comparator and the rollback derivation are proven against
these files only, and nothing here was read from DB2 for z/OS, from `SYSD.STOCK.HISTORY`, or from a
production request stream.

This manifest exists because the delimited-export contract (AAP §0.12.1) reserves line 1 of every
CSV for the column header and defines **no comment syntax** — a fixture CSV physically cannot carry
its own provenance, so each fixture directory carries a sidecar instead (AAP §0.6.1, §0.10.1).
`shadow/` directly holds one fixture file, `rollback-replay.csv`, and this document is the authority
for it. The two stream subdirectories carry their own manifests and own their own derivations.

## 1. Directory inventory

| File | What it encodes | §0.4 finding / §0.10.3 seed | Expected tooling outcome |
|---|---|---|---|
| `rollback-replay.csv` | The runbook Step 3 post-cutover rollback derivation (AAP §0.3.3): the absolute-state replay file derived from the closed ledger range above the cutover watermark, laid out per the §0.12.1 replay-file contract | §0.4.1 credit/debit arithmetic — full-precision product, one final truncation `RoundingMode.DOWN` (`CASH00.cbl:L221-L222` credit, `L255-L256` debit, unsigned `WS-CALC PIC 9(7)V99` at `L17`); §0.4.6 row "credit/debit arithmetic — PRESERVED". Carries **no** §0.10.3 variance seed: it is a derivation fixture, not a comparison stream | `migration/RollbackReplayFileTest` derives a **byte-identical** file from the in-memory ledger range recorded in §3 below. Text equality is the whole assertion — this fixture produces no `migration_reconciliation` rows and no exit code |
| `matched/` | Shadow transaction stream and legacy responses that agree | See that directory's own `MANIFEST.md` | `shadow-compare` → zero `VARIANCE` rows, exit code 0 |
| `seeded-mismatch/` | The same stream with deliberate divergences seeded | See that directory's own `MANIFEST.md` | `shadow-compare` → exactly three `VARIANCE`/`ACCEPTED_EXCEPTION` rows, exit code 2 |

Each subdirectory's `MANIFEST.md` is the authority for the files inside it, including every seed and
every expected row. Those derivations are deliberately **not** restated here; if this document and a
subdirectory manifest ever appear to disagree about a stream file, the subdirectory manifest wins.

## 2. `rollback-replay.csv` — the recorded contract and contents

Header, exactly as the file carries it:

```
seq,owner,op,balance,currency,first_entry_id,last_entry_id,incarnation_id
```

This column set is declared once, in `migration/LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS`. The
fixture **satisfies** that constant; it does not redefine it. Changing the order or the spelling of a
column belongs in `LegacyExportFormat`, and the fixture then follows.

The seven expected data lines, verbatim, so the file can be checked without opening it:

```
1,JOHN,U,1250.50,USD,101,102,11111111-1111-4111-8111-111111111111
2,KARRI,U,12000.00,USD,103,103,22222222-2222-4222-8222-222222222222
3,GREG,U,123535.78,GBP,104,104,33333333-3333-4333-8333-333333333333
4,NEWCUST,A,500.00,USD,105,106,44444444-4444-4444-8444-444444444444
5,RAUNAK,X,0.00,USD,107,108,55555555-5555-4555-8555-555555555555
6,RYAN,U,750.00,USD,109,110,77777777-7777-4777-8777-777777777777
7,ERIC,U,1234367.89,EUR,111,114,88888888-8888-4888-8888-888888888888
```

UTF-8, LF line endings, no BOM, comma-delimited, no quoting required (no field contains a comma,
quote or newline), one trailing newline.

**Ordering.** One line per owner touched after the watermark, ordered by **ascending
`last_entry_id`**; `seq` simply numbers that order from 1. The seven lines therefore carry
`last_entry_id` 102, 103, 104, 106, 108, 110, 114 — strictly increasing.

**Field derivation**, per owner, from that owner's ledger rows in the range:

- `op` — `X` when the owner's **last** ledger row is `ACCOUNT_DELETED`, tested first; otherwise `A`
  when the owner is absent from that cutover's final legacy export and `U` when it is present. These
  are the legacy request codes the mainframe team replays through the existing transaction.
- `balance`, `currency` — the **absolute** end state, taken from that last row's `available_after`
  and `currency`. Never a sum, never a delta.
- `first_entry_id`, `last_entry_id` — the closed ledger range the line summarizes, inclusive at both
  ends. Equal when the owner has exactly one row in the range.
- `incarnation_id` — the incarnation of that **last** row, so the line names the account life its
  balance describes and can never be applied to an earlier life of the same owner name.

**One line per owner, whatever the owner's history in the range.** The grouping key is the owner
alone. An owner deleted and created again above the watermark has rows under two `incarnation_id`s
and still hands back exactly one final state — `RYAN` below is that case, and the reason it matters
is in §4.

## 3. The deriving ledger range — record of reference

`RollbackReplayFileTest` is a Surefire `*Test` with **no database**: it builds this range in memory,
so every value below has to be a deterministic literal. This section is that record of reference.

**Watermark: `W = 100`.** The derivation covers the closed range `entry_id > W`, that is entry ids
101 through 114.

| entry_id | owner | event_type | amount | available_after | reserved_after | currency | incarnation |
|---|---|---|---|---|---|---|---|
| 101 | JOHN | CREDIT | 150.50 | 1150.50 | 0.00 | USD | `1111…` |
| 102 | JOHN | CREDIT | 100.00 | 1250.50 | 0.00 | USD | `1111…` |
| 103 | KARRI | DEBIT | 345.67 | 12000.00 | 0.00 | USD | `2222…` |
| 104 | GREG | CREDIT | 100.00 | 123535.78 | 0.00 | GBP | `3333…` |
| 105 | NEWCUST | ACCOUNT_CREATED | 400.00 | 400.00 | 0.00 | USD | `4444…` |
| 106 | NEWCUST | CREDIT | 100.00 | 500.00 | 0.00 | USD | `4444…` |
| 107 | RAUNAK | CREDIT | 50.00 | 150.00 | 0.00 | USD | `5555…` |
| 108 | RAUNAK | ACCOUNT_DELETED | 150.00 | 0.00 | 0.00 | USD | `5555…` |
| 109 | RYAN | ACCOUNT_DELETED | 23456.78 | 0.00 | 0.00 | USD | `6666…` |
| 110 | RYAN | ACCOUNT_CREATED | 750.00 | 750.00 | 0.00 | USD | `7777…` |
| 111 | ERIC | HOLD | 500.00 | 1234067.89 | 500.00 | EUR | `8888…` |
| 112 | ERIC | RELEASE | 500.00 | 1234567.89 | 0.00 | EUR | `8888…` |
| 113 | ERIC | HOLD | 200.00 | 1234367.89 | 200.00 | EUR | `8888…` |
| 114 | ERIC | SETTLEMENT | 200.00 | 1234367.89 | 0.00 | EUR | `8888…` |

**The reservation rule is read off each owner's *last* row, never off the whole range.** AAP §0.3.3
makes "release every `HELD` reservation" a hard precondition of the rollback, because the replay file
carries only an available balance and legacy has no reservation concept — a held amount would simply
vanish from the hand-back. That is a claim about the owner's **end state**: a non-zero
`reserved_after` on the owner's last row makes the derivation **fail**, naming the owner and the
entry, rather than emit a line that understates its money. Entries 111 and 113 carry `500.00` and
`200.00` reserved on purpose and do **not** fail the derivation, because entry 112 released the first
hold and entry 114 settled the second, leaving `ERIC` with nothing held. The ledger is append-only,
so those two rows exist forever; a rule applied to every row in the range would deny rollback to
every owner that has ever held funds, however long ago they were released. The operator sees the same
end-state condition as `reservedBalance` in `GET /cash-account/institutional/accounts/{owner}` before
starting, and the runbook's derivation aborts on it before any file is written.

**Balance arithmetic that produced the range.** Starting balances are the six-account corpus in
`fixtures/legacy-export/matched/cashaccounty.csv`; rates are from
`fixtures/legacy-export/matched/frankfurt1.csv` (USD 1.00, EUR 0.92, GBP 0.79). Every step keeps the
`rate × amount` product at full precision and truncates **once**, on the final result, with
`RoundingMode.DOWN` — the legacy `COMPUTE WS-CALC = BALANCE ± (RATES * BALANC-RATE)` semantics
(`CASH00.cbl:L221-L222`, `L255-L256`) and two-decimal fixed point throughout, no floating point
anywhere (AAP §0.7.1):

- **JOHN** — `1000.00 + 1.00 × 150.50 = 1150.50` (entry 101), then `1150.50 + 1.00 × 100.00 = 1250.50` (entry 102).
- **KARRI** — `12345.67 − 1.00 × 345.67 = 12000.00` (entry 103).
- **GREG** — `123456.78 + 0.79 × 100.00 = 123535.78` (entry 104), the only line where a rate other than 1.00 applies.
- **NEWCUST** — created at `400.00` (entry 105), then `400.00 + 1.00 × 100.00 = 500.00` (entry 106).
- **RAUNAK** — `100.00 + 1.00 × 50.00 = 150.00` (entry 107), then `ACCOUNT_DELETED` takes the account to `0.00` (entry 108).
- **RYAN** — the corpus account holding `23456.78` is deleted, taking it to `0.00` (entry 109), then a
  new account life is created at `750.00` (entry 110). No rate is involved in either event: both are
  absolute-set events, and the second carries a **new** `incarnation_id` exactly as `CashAccount.open`
  stamps one on every create.
- **ERIC** — `1234567.89` held twice with no retail movement at all: `HOLD 500.00` moves it to
  available `1234067.89` / reserved `500.00` (entry 111), `RELEASE` returns it to available
  `1234567.89` (entry 112), `HOLD 200.00` moves it to available `1234367.89` / reserved `200.00`
  (entry 113), and a **full** `SETTLEMENT` of `200.00` clears the reservation without returning the
  money, leaving available `1234367.89` (entry 114) — the §0.6.3 rule that a full settle releases
  nothing back to available. The EUR rate never enters: the institutional path requires the hold
  currency to equal the account currency and never converts (§0.7.2).

Each owner's last-row `available_after` is exactly the `balance` the replay file carries for that
owner: JOHN 1250.50, KARRI 12000.00, GREG 123535.78, NEWCUST 500.00, RAUNAK 0.00, RYAN 750.00,
ERIC 1234367.89.

## 4. Decisions a reader would otherwise mistake for defects

- **`NEWCUST` is deliberately not one of the six corpus owners.** The corpus is JOHN, KARRI, RYAN,
  RAUNAK, GREG, ERIC — the uppercase forms of the broker stub names at
  `backend/broker/src/main/liberty/config/includes/none.xml:L58`. `op=A` is only correct for an owner
  **absent from the final legacy export**, so exercising the `A` case requires an owner outside that
  set. Renaming `NEWCUST` to a corpus owner would silently invalidate the `A` case while leaving the
  test green-looking.
- **JOHN deliberately spans two entries (101-102).** That is the multi-row range collapse: one output
  line with `first_entry_id` 101, `last_entry_id` 102, and `balance` `1250.50` taken from the *last*
  row. It is not the sum of the two `amount` values (`250.50`) and not the first row's balance
  (`1150.50`). KARRI and GREG are the single-row contrast, where `first_entry_id` equals
  `last_entry_id`.
- **RYAN spans two account incarnations (109-110), and still yields one line carrying the second.**
  This is the case a derivation keyed on `(owner, incarnation_id)` gets wrong: it would emit `X` for
  the deleted life and `U` for the recreated one, and the mainframe replay of that pair deletes
  legacy's RYAN row and then finds nothing to update — legacy ends with no RYAN at all while the
  target has one holding `750.00`. The single line is `U` because RYAN **is** in the final legacy
  export (`fixtures/legacy-export/matched/cashaccounty.csv`), so an absolute overwrite reproduces the
  end state, and it carries `7777…` because the balance belongs to the recreated life. Renaming RYAN
  to an owner absent from the corpus would silently turn this into an `A` case and stop exercising the
  collapse across incarnations.
- **ERIC's four entries never touch the retail path, and its two holds do not block the hand-back.**
  Its `available_after` moves only because funds were reserved, released and settled, so it is the
  case that distinguishes "no funds held **now**" from "no funds ever held in the range" — the
  distinction §3 states. `balance` is `1234367.89`, not the original `1234567.89`: the settled
  `200.00` left the account, which is exactly what a hand-back must carry.
- **The `amount` column carries three different conventions, and none of them affects the expected
  file** (AAP §0.6.3). For `CREDIT`/`DEBIT` it is the non-negative caller amount — entry 104's
  `100.00` is the caller amount while `available_after` `123535.78` already has the legacy GBP rate
  applied. For the absolute-set event `ACCOUNT_CREATED` it is the resulting available balance. For
  `ACCOUNT_DELETED` it is the balance removed. The derivation reads only `event_type`,
  `available_after` and `currency` from the owner's last row, so no `amount` convention can change
  the output.
- **No `C`/`D` row, no rate and no per-transaction detail is ever replayed.** Per AAP §0.3.3,
  replaying **absolute state** through legacy `A`/`U`/`X` reproduces the target's end state exactly
  and is independent of exchange rates. That independence is precisely why the rollback is safe
  without re-deriving FX, and why a well-meaning "improvement" that emitted credits and debits would
  make the hand-back rate-sensitive and wrong.
- **No `# sha256` line is embedded in the CSV.** AAP §0.12.1 keeps the checksum in the runbook
  evidence *beside* the file, so the CSV stays free of comment syntax and every line remains a record
  the reader can parse. The checksum of a real replay file is captured as Step 3 rollback evidence in
  `docs/operational-runbook.md`, not inside the file.
- **`RAUNAK`'s `balance` is `0.00`, not its pre-deletion `150.00`.** The derivation copies
  `available_after` from the `ACCOUNT_DELETED` row, which is `0.00`. Legacy `X` ignores the balance
  field entirely, but the §0.12.1 contract still requires the column to be present and well formed,
  so `0.00` is written rather than an empty field.

## 5. Consumers

`rollback-replay.csv` is consumed by
`src/test/java/com/ibm/hybrid/cloud/sample/stocktrader/cashaccount/migration/RollbackReplayFileTest.java`
— a Surefire `*Test` with **no database and no Testcontainers**. It builds the §3 ledger range in
memory, runs the derivation, and asserts the produced text equals this file. That is why the entry
ids are small literals (101-114 above a watermark of 100) and the `incarnation_id`s are fixed
canonical version-4 UUID literals rather than generated values: both round-trip through
`UUID.toString()` unchanged, which is what makes a text comparison stable across runs and machines.

The stream fixtures under `matched/` and `seeded-mismatch/` are consumed by `ShadowComparatorIT`
instead, not by this test. `ShadowComparator` replays each transaction through
`RetailCashAccountService` directly — never over HTTP — and joins legacy responses to target results
on `seq` together with the normalized owner key, never on file order: EBCDIC and UTF-8 collate
differently (AAP §0.12.2), so ordinal position is not a usable join key across an exported stream.
