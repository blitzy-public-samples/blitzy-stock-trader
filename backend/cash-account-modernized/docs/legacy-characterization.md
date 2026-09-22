# Legacy Behavior Characterization — CICS/COBOL Cash Account (`CASH00`)

This is the behavioral baseline for the program the `cash-account-modernized` service replaces. It
exists because the replacement's reconciliation logic has to reproduce legacy arithmetic exactly, and
that arithmetic cannot be guessed: it is a property of specific COBOL picture clauses and a specific
`COMPUTE` statement. Every claim below **about this program** is derived from a direct read of the
legacy source and carries an inline `file:line` citation so a reviewer can check the claim rather
than trust it. A second, smaller category is separated from it rather than mixed into it: what a Db2
or CICS return code *means* is product behavior that no file in this repository states, so the three
`SQLCODE` rows of section 7.1 and open item 9.9 that rest on it are labelled product semantics and
cited to their IBM documentation instead of to a source line. Nothing else in the document is of that
kind, and no claim about what `CASH00` did rests on it.

## 0. How to read this document

Citation shorthand: unqualified `Lnnn` references are lines of `COBOL/CASH00.cbl`; every other
citation names its file. All legacy paths are relative to `backend/cash-account-cobol/`, which is a
read-only characterization source — it is opened and cited here and modified nowhere.

Nothing in this document rests on prior documentation of this program, on a generated summary, or on
the modernization plan; the IBM product documentation cited in section 7.1 and open item 9.9 is the
one external source, and it is cited for what a return code means, never for anything this program
does. Where the source cannot settle a question, section 9 records it as an open item with what
would settle it; no plausible value is substituted for a source-derived one. Nothing here
reports evidence from a live system: no DB2 for z/OS table, no VSAM data set and no CICS region was
read to write it, and the migration, dual-run, cutover and decommission steps it refers to are
described in [`operational-runbook.md`](operational-runbook.md) as handoffs, not as work performed.

The six files that constitute the source of truth, with the size each was read at:

| Source file | Lines | What it settles |
|---|---|---|
| `COBOL/CASH00.cbl` | 269 | Working storage, COMMAREA, dispatch, the six paragraphs, the arithmetic, the history write |
| `COBOL/DCLCASH.cpy` | 22 | `STOCKTRD.CASHACCOUNTY` declaration and its host variables |
| `COBOL/DCLFRANK.cpy` | 26 | `STOCKTRD.FRANKFURT1` declaration and its host variables |
| `DB2-DDL/DB2DDL.jcl` | 103 | Database, tablespace, table and index DDL, CCSID, grants |
| `DB2-DDL/DB2BIND.jcl` | 46 | BIND PACKAGE/PLAN — CICS enablement and isolation level |
| `VSAM/DEFKSDS.jcl` | 16 + an unterminated `/*` delimiter | IDCAMS definition of the history KSDS |

Because `CASH00.cbl` ends at line 269, a citation past `L269` is a factual error; none appears here.

Three line references in circulation elsewhere are off by a line or two and are corrected here, each
checked against the file: the credit/debit `IF SQLCODE = 0` guard is at **L212** (credit) and
**L246** (debit), the line before the credit guard being blank; the history record's owner-name move
is at **L111**, three lines above the move of the request code; and the `CASH-ACCT-DELETE` paragraph
label is at **L185**, two lines above the `EXEC SQL` inside it. This document cites only the verified
values.

Consumers of this document. `LegacyCharacterization.java` takes one constant per subsection of
section 1 and cites that subsection by name in its Javadoc; `LegacyBalanceCalculator` implements
section 1's formula; `ReconciliationService` and `ShadowComparator` classify variances by the rules in
sections 4, 5 and 8; the fixtures under
[`../src/test/resources/fixtures/legacy-export/matched/`](../src/test/resources/fixtures/legacy-export/matched/)
and `../src/test/resources/fixtures/shadow/` encode the expected values section 1 derives. Build and
configuration context is in the [module README](../README.md). The subsection headings in section 1
are load-bearing, and checked rather than merely declared so: each constant's Javadoc quotes its
heading verbatim, `CharacterizationDocPresentTest` asserts those heading lines as well as section 1's
arithmetic citations, and the heading assertion sits inside the very guard the reconciliation
integration tests call from their `@BeforeAll` methods. Renaming or merging a heading therefore fails
the build instead of silently orphaning every reference to it.

## 1. What `amount` resolves to in the credit/debit computation

This is the question that decides whether migrated balances can ever match legacy balances, because
the credit/debit paragraphs fetch two different numbers that could plausibly be the multiplicand —
the caller's amount and the rate table's own `AMOUNT` column — and use only one of them.

### 1.1 The multiplicand is the caller's COMMAREA amount, not `FRANKFURT1.AMOUNT`

The caller's amount arrives in the COMMAREA field `WS-BALANCE PIC 9(7)V99` (L56) and is copied into
the DB2 host variable `BALANCE` before dispatch (L75). That host variable does not survive the
paragraph: `CASH-ACCT-CREDIT` selects the account row `INTO :DCLCASHACCOUNTY` (L205-L209), which
overwrites host `BALANCE` with the *stored* balance. The caller's amount is therefore re-read from the
COMMAREA field itself at L221 — `MOVE WS-BALANCE TO BALANC-RATE` — immediately before
**L222**, `COMPUTE WS-CALC = BALANCE + (RATES * BALANC-RATE)`. `CASH-ACCT-DEBIT` mirrors this exactly
at **L255** and **L256**, with subtraction.

The rate row is selected `INTO :DCLFRANKFURT1` by a statement whose column list is
`CURRNKEY,CURRNBASE,AMOUNT,RATES,LOADDT` (L215-L218 for credit, L249-L252 for debit). Of those five
columns, only `RATES` is ever referenced again. **`FRANKFURT1.AMOUNT` is fetched into the host
variable `AMOUNT` (`COBOL/DCLFRANK.cpy:L21`) and never referenced by any arithmetic statement or any
`MOVE` in the program** — its only two appearances in the entire source are the two `SELECT` column
lists at L215 and L249. The same is true of `CURRNBASE` and `LOADDT`.

Definitive answer: **the caller-supplied COMMAREA amount is the multiplicand.** The consequence that
matters downstream is that legacy balances encode real trade amounts scaled by a stored per-currency
rate — not a stored per-currency constant. A reconciler that treated the rate table's `AMOUNT` as the
multiplicand would reproduce a number the legacy system never computed, and would do so consistently
enough to look plausible.

### 1.2 The legacy formula

```text
new_balance = truncate_to_2dp( stored_balance ± RATES × caller_amount )
```

`stored_balance` is the value the account `SELECT` placed in host `BALANCE` (L205-L209 credit,
L240-L245 debit); `RATES` is the rate row's column (`COBOL/DCLFRANK.cpy:L12`); `caller_amount` is the
COMMAREA amount re-read at L221/L255. The sign is `+` for `C` (L222) and `−` for `D` (L256). The five
subsections below give the fixed-point parameters of that one line, each of which is a separate
constant in `LegacyCharacterization`.

### 1.3 Constant: decimal scale 2

Every money field in the path has exactly two decimal places and none has more: the COMMAREA amount
is `9(7)V99` (L56), the arithmetic target `WS-CALC` is `9(7)V99` (L17), the host balance is
`S9(7)V9(2) COMP-3` (`COBOL/DCLCASH.cpy:L18`), the stored column is `NUMERIC(9,2)`
(`DB2-DDL/DB2DDL.jcl:L48`) and the history record's balance is `9(7)V99` (L43). Two decimals is
therefore not a convention to be chosen in the replacement but a width to be matched; the target's
`NUMERIC(9,2)` columns and scale-2 `BigDecimal` exist for this reason.

The one field that is wider is the multiplicand working field `BALANC-rate PIC 9(8)V99 value zeros`
(L26) — eight integer digits against the COMMAREA amount's seven — so the move at L221/L255 cannot
truncate the amount. All loss happens at the `COMPUTE`, which is what 1.4 to 1.6 describe.

### 1.4 Constant: rounding is `RoundingMode.DOWN`

`WS-CALC` is declared `pic 9(7)V99` (L17) and neither `COMPUTE` carries a `ROUNDED` phrase (L222,
L256). Storing a higher-precision intermediate result into a two-decimal field without `ROUNDED`
truncates the excess digits rather than rounding them, so the legacy result is the exact value
truncated toward zero at two decimals — `RoundingMode.DOWN` in the replacement. Because `RATES` has
two decimals and the amount has two, the product carries up to four, so this truncation is reached on
ordinary input rather than in an edge case: it is the normal path, not an exception.

### 1.5 Constant: unsigned result — where the sign is dropped

`WS-CALC` (L17) is **unsigned**, while the host variable it is moved into is **signed**
`PIC S9(7)V9(2) USAGE COMP-3` (`COBOL/DCLCASH.cpy:L18`). The sign is lost at the moment the `COMPUTE`
stores its result into `WS-CALC` (L222/L256); what travels onward through `MOVE WS-CALC TO BALANCE`
(L225 credit, L259 debit) into the `UPDATE` (L227-L231, L260-L264) is the absolute value. A debit
larger than the balance therefore did not fail — it committed a positive balance equal to the
magnitude of the overdraft.

Neither of the two outward channels could have carried a sign even if one had survived: the COMMAREA
balance is unsigned `9(7)V99` (L56) and the history record's balance is unsigned `9(7)V99` (L43). No
caller could ever have observed a negative legacy balance, which is why "the legacy system had no
overdrafts" is a statement about the picture clauses and not about the business rules.

The replacement rejects this input with `422 INSUFFICIENT_FUNDS` and writes nothing. The legacy
behavior is reproduced only inside `LegacyBalanceCalculator`, so that reconciliation can recognize a
historical absolute-value balance for what it is instead of reporting it as an unexplained variance.

### 1.6 Constant: modulus 10^7 — high-order digit loss on overflow

The same store that drops the sign also drops high-order digits. `WS-CALC` holds seven integer digits
(L17) and the `COMPUTE` carries no `ON SIZE ERROR` phrase (L222, L256), so a result of 10,000,000.00
or more is stored as that result modulo 10^7 with no diagnostic: the return channel still reports the
`UPDATE`'s `SQLCODE 0` (L104). The stored column would have accepted nothing wider either —
`NUMERIC(9,2)` (`DB2-DDL/DB2DDL.jcl:L48`) is exactly nine digits with two after the point.

The replacement rejects the operation with `422 AMOUNT_OUT_OF_RANGE`. The modulus is reproduced in
`LegacyBalanceCalculator` for the same reason as the sign drop: a legacy balance that wrapped is a
legitimate historical value that reconciliation must be able to explain.

### 1.7 Constant: rate key length 5

The rate lookup key is the *account's* currency truncated to five characters: `MOVE CURRENCYC TO
WS-CURRENCY-KEY` (L213 credit, L247 debit) moves a `CHAR(8)` column (`COBOL/DCLCASH.cpy:L11`) into
`WS-CURRENCY-KEY PIC X(5)` (L19), and that field is the host variable in the `WHERE CURRNKEY =`
predicate (L217-L218, L251-L252) against a `CHAR(5)` key column
(`DB2-DDL/DB2DDL.jcl:L55`). Five characters is the width the legacy join actually compared, so a
migration reader that keyed the staged rate table on anything else would not reproduce the legacy
lookup for currency values longer than five characters.

`RATES` is `DECIMAL(3,2)` (`COBOL/DCLFRANK.cpy:L12`; `DB2-DDL/DB2DDL.jcl:L58`) and its host variable
is `PIC S9(1)V9(2) USAGE COMP-3` (`COBOL/DCLFRANK.cpy:L22`) — one integer digit, two decimals, so a
ceiling of 9.99 and a granularity of 0.01. This bounds what the legacy table could express at all: a
rate of 10 or more cannot be stored, and a rate below 0.005 stores as 0.00, which turns every credit
and debit in that currency into a no-op. Currencies whose unit sits far from the base unit — JPY and
INR among them — fall outside the representable band in one direction or the other. The base currency
of a row is not checked either: `CURRNBASE` is fetched and never read (1.1), so the arithmetic
implicitly assumes every row's base matches the currency the caller's amount is denominated in. The
replacement's live rate lookup has none of these limits, which is the single remaining
source of legitimate divergence between legacy and target arithmetic and is why reconciliation
classifies it separately as `RATE_SOURCE` rather than as a balance variance.

### 1.8 One truncation, not two

The whole expression is truncated once, after the signed addition, because the `COMPUTE` at L222/L256
is a single statement with one store into `WS-CALC`; the product `RATES * BALANC-RATE` is an
intermediate value that COBOL carries at full precision. Truncating the product first is not
equivalent, and the difference is a real penny rather than a theoretical one. With
`stored = 100.00`, `rate = 0.03` and `amount = 0.30`:

| Order of operations | Computation | Result |
|---|---|---|
| Truncate the final result (legacy, L256) | `truncate2(100.00 − 0.009)` | `99.99` |
| Truncate the product first | `100.00 − 0.00` | `100.00` |

This one example is why `Money.applyRate` keeps the product at full `BigDecimal` precision, performs
the signed addition, and scales once at the end; `MoneyTest` carries exactly these numbers. Any
implementation that scales the product as it is computed will disagree with the legacy system on
small debits, and will do so in the direction that quietly favors the account holder.

### 1.9 Target decision: an incoming amount with more than two decimals

The COMMAREA field the program receives holds exactly two decimals (L56), so by the time any value
reached `CASH00` it had already been fitted to that width by whatever sat in front of it. Whether that
layer truncated or rounded is not recorded anywhere in this module, so it is not a legacy fact and is
not presented as one (open item 9.4). The replacement scales an incoming `amount` `DOWN` to two
decimals, chosen for consistency with the COBOL truncation established in 1.4 — a target decision.
Every parity fixture uses two-decimal amounts, so no expected value in the test suite depends on this
choice.

## 2. Owner casing

Owner identity in the legacy program is case-insensitive in both directions but implemented
inconsistently, and the replacement has to preserve the identity semantics without inheriting the
inconsistency.

Matching is case-insensitive everywhere, using two different idioms. Reads and deletes fold to lower
case: `LOWER(Owner) = LOWER(:CUST-NAME-TEXT)` at L141 (read), L169 (update's initial select), L191 and
L197 (delete's select and the `DELETE` itself), L209 (credit's select) and L244 (debit's select).
Updates fold to upper case: `UPPER(Owner) = UPPER(:CUST-NAME-TEXT)` at L178 (update), L230 (credit's
update) and L263 (debit's update). Storage is unconditionally upper case, because the insert stores
`UPPER(:CUST-NAME-TEXT)` (L155). Every stored owner is therefore upper case and every lookup that
ever worked was case-insensitive — which is what makes normalization in the replacement safe rather
than merely convenient.

The *returned* owner is where the inconsistency sits, and it is an artifact of COMMAREA plumbing
rather than a decision. `Q` returns the database column and so returns upper case (L144, `MOVE OWNER
TO WS-NAME`); `A` returns the caller's own text and so echoes the caller's casing (L158, `MOVE
CUST-NAME-TEXT TO WS-NAME`); `U`, `X`, `C` and `D` never assign `WS-NAME` at all, so the value copied
back to the caller (L108) is whatever the caller sent. Three different casings for the same account,
depending on the verb.

Owner length was silently lossy. The COMMAREA name field is `WS-NAME PIC X(15)` (L55), while
`CUST-NAME-TEXT` is `PIC X(32)` (L36) and the stored column is `CHAR(32)`
(`DB2-DDL/DB2DDL.jcl:L47`). A name longer than fifteen characters was truncated at the COMMAREA
boundary before it ever reached the wider field, so two distinct owners sharing a fifteen-character
prefix were the same account and nothing reported it.

Decision — **normalize**: store the owner upper case, match case-insensitively by uppercasing the path
variable, and always return the stored upper-case form. Accept owners up to 32 characters, the width
the column always had, and reject anything longer with `400 INVALID_OWNER`. Normalization preserves
every lookup that worked before, because legacy identity is already case-insensitive; the response
casing can be made consistent at no cost because the retail caller maps only the balance and currency
fields out of the response and never reads the owner back; and replacing silent truncation with an
explicit rejection removes the collision the 15-character limit created. `OwnerNormalizer` is the
single place this holds.

## 3. Unrecognized request codes

`EVALUATE WS-REQ` (L89-L102) has branches for `A` (L90-L91), `Q` (L92-L93), `U` (L94-L95), `X`
(L96-L97), `C` (L98-L99) and `D` (L100-L101), and **no `WHEN OTHER`**. The comparisons are
single-character literal comparisons, so they are case-sensitive: a lower-case `a` is not the add
code, it is an unrecognized code.

What an unrecognized code did is worth stating precisely, because all three of its effects are
plausible-looking failures rather than visible ones:

- No SQL statement executes, so `SQLCODE` still holds whatever the SQLCA contained when the task
  started. This program never sets it, and `MOVE SQLCODE TO WS-RETCODE` (L104) copies it out
  regardless, so the return field reports a status that no statement produced (open item 9.1).
- The COMMAREA is echoed unchanged. Host `BALANCE` and `CURRENCYC` were primed from the caller's own
  input before dispatch (L75, L77) and are copied straight back out after it (L105-L106, written to
  the caller at L108), so the caller receives **its own amount as the account balance**. A caller that
  trusted the response would record a balance the system never held.
- A history record is still written, carrying the unknown code, because the write follows the
  `EVALUATE` unconditionally with no guard between them (L111-L131).

The replacement **fails closed**, and this is an intentional improvement with a stated deviation
rather than a parity gap: an unmapped path returns `404 UNSUPPORTED_PATH`, an unmapped method on a
known path returns `405 UNSUPPORTED_METHOD`, both in the standard error payload, and the security
filter chain's `anyRequest().denyAll()` refuses anything outside the service's declared path space. No
reconciliation rule reproduces the fall-through and no test asserts it, because reproducing a
success-looking response to an unintelligible request would be reproducing a defect.

## 4. The write-only audit file

The history KSDS is the only audit trail the legacy program has, and nothing in the repository reads
it. The sole application-level access is `EXEC CICS WRITE FILE ('HISTORY')` (L126-L131); the data set
is otherwise named only by its IDCAMS definition (`VSAM/DEFKSDS.jcl:L9-L16`). There is consequently no
extraction program, no report and no read path to adapt — the export the migration needs has to be
built new, which is why the module ships its own decoder and reader rather than a wrapper around an
existing job.

### 4.1 Record layout and key

From `WS-VSAM-RECORD` (L38-L45), offsets computed by accumulating the declared widths:

| Offset | Length | Field | Picture | Declared | Content |
|---|---|---|---|---|---|
| 0 | 15 | `WS-VR-NAME` | `X(15)` | L39 | Owner as received in the COMMAREA — caller's casing, truncated to 15 |
| 15 | 8 | `WS-VR-DATE` | `X(08)` | L40 | `YYYYMMDD` from `FORMATTIME` (L82-L85), region local time |
| 23 | 6 | `WS-VR-TIME` | `X(06)` | L41 | `HHMMSS` from the same call |
| 29 | 1 | `WS-VR-REQ` | `X(1)` | L42 | Request code, including unrecognized ones |
| 30 | 9 | `WS-VR-BALANCE` | `9(7)V99` | L43 | Unsigned zoned decimal, implied point after seven digits |
| 39 | 8 | `WS-VR-CURRENCY` | `X(8)` | L44 | Currency as returned in the COMMAREA |
| 47 | 10 | `WS-VR-RETCODE` | `X(10)` | L45 | `SQLCODE` as unsigned digits (L117; see section 7) |

Total 57 bytes. The key `WS-VSAM-KEY` (L47-L50) is name, date and time — `X(15)`, `X(08)`, `X(06)` at
L48, L49 and L50 — 29 bytes at offset 0, matching the cluster's `KEYS(29 0)`
(`VSAM/DEFKSDS.jcl:L14`). The cluster is `INDEXED` (`VSAM/DEFKSDS.jcl:L10`) with
`SHAREOPTIONS(2 3)` (`VSAM/DEFKSDS.jcl:L13`) and is declared `RECSZ(100 100)`
(`VSAM/DEFKSDS.jcl:L11`) — fixed 100-byte records against a 57-byte write, a tension the source cannot
resolve (open item 9.5) and the reason `VsamHistoryRecordDecoder` accepts both lengths.

### 4.2 Counting rules the tooling depends on

Two properties of the write make legacy history a lower bound rather than a census, and both change
how a count mismatch must be classified.

`EXEC CICS IGNORE CONDITION DUPREC` (L124) precedes the write, so a second record whose 29-byte key
already exists is discarded and the program continues as if it had been written. Since the key's only
time component is a whole second (L41, L50), two requests for the same owner within the same second
leave one record. A target transaction count *greater* than the legacy count is therefore expected and
is recorded as an accepted exception; only a target count *lower* than legacy is a variance, because
that direction cannot be explained by the dropped duplicate. `IGNORE CONDITION NOTOPEN` (L123) is the
same shape of silence for an unopened file: the audit write can fail without affecting the response.

The record is written for every request, including `Q` and unrecognized codes, because the write
follows the dispatch unconditionally (L111-L131) and a read changes nothing. Transaction counts
derived from an export must therefore filter to `request_code IN ('A','U','X','C','D')` with a
zero return code; counting every row would compare state changes on the target against reads on the
legacy side.

### 4.3 The export path that has to be built

Handed off to [`operational-runbook.md`](operational-runbook.md), not executed here: IDCAMS `REPRO` of
the KSDS to a sequential data set, transferred either in binary so the EBCDIC bytes are preserved or
converted to delimited text. In this module `VsamHistoryRecordDecoder` decodes the fixed layout of 4.1
and `DelimitedExportReader` reads the delimited conversion, with `LegacyExportFormat` defining both
accepted shapes so that the same loader run handles either. `LoaderIT` asserts the two shapes decode
to identical rows, which is the only way to know a conversion step on the mainframe side did not
change the data.

## 5. The acceptance baseline

The conditions the replacement's error model and tests are written against. Each row is a legacy fact
with its citation and the constraint it places on the target; the status codes named here are derived
condition by condition in section 7.

| Baseline element | Legacy fact (cited) | What it constrains in the replacement |
|---|---|---|
| Request codes | `A` add, `Q` read, `U` update, `X` delete, `C` credit, `D` debit (L89-L102) | Six retail endpoints, one per code (section 7.2) |
| Account table | `STOCKTRD.cashaccounty(owner CHAR(32) NOT NULL PRIMARY KEY, balance NUMERIC(9,2), currencyc CHAR(8))`, `CCSID EBCDIC` (`DB2-DDL/DB2DDL.jcl:L46-L52`), unique clustered index on `owner` (`DB2-DDL/DB2DDL.jcl:L97-L99`) | Column widths and `NUMERIC(9,2)` carried into the new schema; owner is the primary key there too |
| Rate table | `STOCKTRD.frankfurt1(currnkey CHAR(5) NOT NULL PRIMARY KEY, cyrrnbase CHAR(5), amount NUMERIC(9,2), rates NUMERIC(3,2), loaddt DATE NOT NULL)`, `CCSID EBCDIC` (`DB2-DDL/DB2DDL.jcl:L54-L62`), unique clustered index on `currnkey` (`DB2-DDL/DB2DDL.jcl:L101-L103`) | Staged verbatim for reconciliation; never a target-state dependency (section 6) |
| Column-name mismatch | The DDL spells the second rate column `cyrrnbase` (`DB2-DDL/DB2DDL.jcl:L56`) while the copybook declares `CURRNBASE` (`COBOL/DCLFRANK.cpy:L10`, host variable `COBOL/DCLFRANK.cpy:L20`) and the program's `SELECT` names `CURRNBASE` (L215, L249) | As written the program would not precompile against this DDL, so one of the two artifacts does not describe the real catalog (open item 9.7); the export readers accept either spelling as a header |
| VSAM record layout | 57-byte record, 29-byte key (L38-L50) against `RECSZ(100 100)` and `KEYS(29 0)` (`VSAM/DEFKSDS.jcl:L11`, `VSAM/DEFKSDS.jcl:L14`) | `VsamHistoryRecordDecoder` layout and its 57/100-byte tolerance; the `legacy_history` primary key |
| Currency-conversion mechanics | Rate looked up on the first five characters of the **account's** currency (L213-L219, L247-L253); `CURRNBASE`, `AMOUNT` and `LOADDT` fetched and ignored | `LegacyRateTableSource` keys on five characters; `LegacyBalanceCalculator` uses `RATES` only (section 1.1, 1.7) |
| Status as the only channel | `MOVE SQLCODE TO WS-RETCODE` (L104) into an `X(10)` field (L58) that was initialized to spaces (L78) | One explicit HTTP status and error code per condition (section 7.1) |
| Isolation | Both the package and the plan bind `ISO(CS)` (`DB2-DDL/DB2BIND.jcl:L17`, `DB2-DDL/DB2BIND.jcl:L33`) with `REL(DEALLOCATE)` (`DB2-DDL/DB2BIND.jcl:L27`) | Cursor stability holds no read lock between the guarded `SELECT` and the `UPDATE` in credit/debit (L212-L231, L246-L264), so a concurrent writer's balance change was simply overwritten. The replacement takes a `PESSIMISTIC_WRITE` lock on the account row first and maps a conflict to `409 CONCURRENT_MODIFICATION` rather than losing the update |
| Privileges | `GRANT DBADM ON DATABASE`, `GRANT ALL PRIVILEGES ON TABLE ... TO PUBLIC` (`DB2-DDL/DB2DDL.jcl:L78-L81`) | The legacy store had no privilege separation at all, so the target's DB-level ledger immutability trigger is a strict improvement even under a single database identity |
| Update is an absolute overwrite | `U` re-moves the caller's amount into the host variable after the existence check and writes balance and currency (L171-L179, `MOVE WS-BALANCE TO BALANCE` at L172) | Retail `PUT` overwrites the available balance and currency rather than adjusting them |
| Zero-value credit/debit | `C`/`D` with amount 0 computes `stored ± 0` (L222, L256), performs the `UPDATE`, reports `SQLCODE 0` and writes a history record | `200` with the balance unchanged and one ledger row of amount 0, preserved so transaction counts still reconcile |
| Account not found | The `SELECT INTO` returns `SQLCODE 100`; `C` and `D` are guarded by `IF SQLCODE = 0` at L212 and L246, so no `UPDATE` runs; the COMMAREA still echoes the caller's amount (L105-L106) | `404 ACCOUNT_NOT_FOUND` with the error payload and no echo of caller input |
| Duplicate create | The `INSERT` (L152-L156) violates the `owner` primary key (`DB2-DDL/DB2DDL.jcl:L50`) and returns `-803`, taking the `ELSE` branch at L160-L161 | `409 ACCOUNT_ALREADY_EXISTS` |
| Missing rate row | The rate `SELECT` (L214-L219) returns `SQLCODE 100`, the following `UPDATE` (L227-L231) resets `SQLCODE` to 0, and `RATES` holds whatever its uninitialized host variable held (`COBOL/DCLFRANK.cpy:L22`) | `503 EXCHANGE_RATE_UNAVAILABLE`, balance unchanged, no ledger row; the legacy outcome itself is undefined (open item 9.2) |
| Duplicate history key | `IGNORE CONDITION DUPREC` (L124) drops a second record for the same owner within one second | Legacy history counts are a lower bound (section 4.2) |
| Zero-value settlement | No legacy analogue — the single-balance model has no reservations | Legal in the replacement: `HELD → SETTLED` with a `SETTLEMENT` entry of 0 and a `RELEASE` of the full hold |

### 5.1 Nullable columns and the absence of null indicators

This is the one part of the baseline where the legacy behavior differs per paragraph, and where a
loader that "filled in" a missing value would silently invent data.

Five of the eight columns across the two tables are nullable: `balance` and `currencyc` carry no
`NOT NULL` (`DB2-DDL/DB2DDL.jcl:L48-L49`), and neither do `cyrrnbase`, `amount` and `rates`
(`DB2-DDL/DB2DDL.jcl:L56-L58`). Only `owner` (`DB2-DDL/DB2DDL.jcl:L47`), `currnkey`
(`DB2-DDL/DB2DDL.jcl:L55`) and `loaddt` (`DB2-DDL/DB2DDL.jcl:L59`) are `NOT NULL`. The program
declares **no null indicator variables** anywhere: the host groups are three and five plain fields
(`COBOL/DCLCASH.cpy:L17-L19`; `COBOL/DCLFRANK.cpy:L19-L23`) with no `INDICATOR` clause and no
indicator array in working storage. Selecting a null into a host variable that has no indicator is the
`SQLCODE -305` case, so a nullable column that is actually null does not return a value — it returns an
error, and each paragraph then behaves according to where that error lands:

- `Q` (L136-L150) — the `SELECT INTO` fails, the `IF SQLCODE = 0` branch at L143 is skipped, the
  `ELSE` message is set (L149) and the return field carries `000000305` (L104, rendered unsigned per
  section 7.1) while the COMMAREA echoes the caller's own input (L105-L106).
- `U` (L164-L183) and `X` (L185-L202) — the initial `SELECT` fails the same way, so the guard at L171
  or L194 is false and the `UPDATE` (L174-L179) or `DELETE` (L195-L198) never runs. The account is
  unreachable through these verbs for as long as the null is present.
- `C` and `D` with a null in an account column — the guarded account `SELECT` (L205-L210, L240-L245)
  fails, the guard at L212 or L246 is false, and no arithmetic and no update occur.
- `C` and `D` with a null in **any** column of the rate row — this is the case worth spelling out.
  All five rate columns are fetched by one statement (L215-L218, L249-L252), so a null in
  `cyrrnbase`, `amount` or `rates` alike raises `-305` at that statement. Execution then continues
  straight into the `COMPUTE` (L222, L256) and the `UPDATE` (L227-L231, L260-L264), because nothing
  re-checks `SQLCODE` between them, and the `UPDATE`'s own `SQLCODE 0` overwrites the failure before
  it is ever reported (L104). The row commits with an undefined `RATES` and the caller is told it
  succeeded.

Reconciliation classifies each of these rather than repairing them, so that the decision about a null
belongs to the data owner and not to the loader:

| Export condition | Classification | Load action |
|---|---|---|
| Null `balance` or `currencyc` | `STATE` variance, `NULL_IN_LEGACY` | Account not loaded |
| Null `rates` | `RATE_SOURCE` variance, `NULL_RATE` | Rate row not staged; any `C`/`D` replay for that currency is then `REJECTED_BY_TARGET` |
| Null `cyrrnbase` or `amount` | No variance | Staged as null — the program never reads either column (section 1.1), so a null there changed no balance |

The target's own columns are `NOT NULL`, which is why the distinction has to be made at the boundary:
the seeded fixture set carries a `NULLBAL` account row with an empty balance and a `ZZZ` rate row with
an empty rate so that both variance paths are exercised before any real export is touched.

## 6. Disposition of every legacy behavior

Every behavior established above, labeled with what the replacement does about it. "Deliberately
changed" means an authorized improvement with the legacy behavior recorded; "not replicated" means the
behavior is a property of the legacy platform that the target has no equivalent for. A row whose
disposition rests on what the calling estate does rather than on the six sources of section 0 is
marked an estate-seam fact and cites that caller's own file and line, so the table never presents a
broker behavior as something read from the legacy program.

| Legacy behavior | Disposition | Reason / replacement |
|---|---|---|
| Six request codes and their SQL semantics (L89-L102) | PRESERVED | One retail endpoint per code, wire-identical (section 7.2) |
| Response carrying owner, balance and currency, and the `amount` input | PRESERVED | The caller's client interface is unchanged, so the wire shape is fixed |
| `truncate2(stored ± RATES × amount)` with one truncation of the final result (L222, L256) | PRESERVED | `Money.applyRate` keeps the full-precision product, adds, then scales once with `RoundingMode.DOWN` (sections 1.4, 1.8) |
| Case-insensitive owner identity with upper-case storage (L141, L155, L178) | PRESERVED | `OwnerNormalizer` (section 2) |
| Balance range 0.00 – 9,999,999.99 (L17; `DB2-DDL/DB2DDL.jcl:L48`) | PRESERVED | `NUMERIC(9,2)` columns and `Money` bounds; widening is an open item (9.8) |
| Default currency when the caller omits one | PRESERVED (estate-seam fact) | The caller already defaults to `USD` — `DEFAULT_CURRENCY` (`backend/broker/src/main/java/com/ibm/hybrid/cloud/sample/stocktrader/broker/BrokerService.java:L83`), applied on the create path at `BrokerService.java:L359`, which is broker's behavior rather than a fact of the six sources; the service defaults again rather than storing a blank `CHAR(8)` (L57) |
| A state change is always recorded (L111-L131) | PRESERVED as the ledger | One `ledger_entry` per transition, in the same transaction as the balance change |
| An amount with more than two decimals | DELIBERATELY CHANGED (decision recorded) | Scaled `DOWN` to 2; the legacy input path is not in the repository (sections 1.9, 9.4) |
| Nullable `CHAR(8)` currency, rate keyed on its first five characters (L213, L217-L218) | DELIBERATELY CHANGED | The API accepts only trimmed, upper-case codes from the accepted set; imports trim padding and classify nulls and out-of-set values as variances (section 5.1) |
| Owner echoed in three different casings (L144, L158, L108) | DELIBERATELY CHANGED | Always the stored upper-case owner (section 2) |
| Owner silently truncated to 15 characters (L55) | DELIBERATELY CHANGED | Up to 32 accepted, longer rejected with `400 INVALID_OWNER` |
| Negative result stored as its absolute value (L17, L222/L256, L225/L259) | DELIBERATELY CHANGED | `422 INSUFFICIENT_FUNDS` and no write (section 1.5) |
| High-order digits dropped on overflow (L17, no `ON SIZE ERROR` at L222/L256) | DELIBERATELY CHANGED | `422 AMOUNT_OUT_OF_RANGE` (section 1.6) |
| Unrecognized request code falls through as success (L89-L102, L104-L108) | DELIBERATELY CHANGED | Fail closed: `404 UNSUPPORTED_PATH` / `405 UNSUPPORTED_METHOD` (section 3) |
| Missing rate row yields success with undefined arithmetic (L214-L231) | DELIBERATELY CHANGED | `503 EXCHANGE_RATE_UNAVAILABLE`, balance unchanged |
| `SQLCODE` sign dropped and only the last statement reported (L104) | DELIBERATELY CHANGED | Explicit HTTP status plus a stable error code per condition (section 7.1) |
| Lost update between the guarded `SELECT` and the `UPDATE` under `ISO(CS)` (`DB2-DDL/DB2BIND.jcl:L17`, `DB2-DDL/DB2BIND.jcl:L33`) | DELIBERATELY CHANGED | Account row locked `PESSIMISTIC_WRITE` first; a conflict becomes `409 CONCURRENT_MODIFICATION` |
| Duplicate audit record silently dropped (L124) | DELIBERATELY CHANGED | The ledger's identity is a generated key, so nothing is dropped (section 4.2) |
| Audit write failure ignored (`IGNORE CONDITION NOTOPEN`, L123) | DELIBERATELY CHANGED | The ledger append shares the balance change's transaction, so an audit failure rolls the change back |
| History written for reads (`Q`) and for unrecognized codes (L111-L131) | NOT REPLICATED | The ledger records state changes only; a read changes nothing. Legacy `Q` rows are staged in `legacy_history` for reference and excluded from transaction counts (section 4.2). Adding a read event type later would be additive |
| `STOCKTRD.FRANKFURT1` as an in-database rate table (`DB2-DDL/DB2DDL.jcl:L54-L62`) | NOT REPLICATED | A migration-source artifact only; target state uses a live rate lookup, and the staged table backs reconciliation arithmetic |
| `CURRNBASE`, `AMOUNT` and `LOADDT` columns (L215, L249; `COBOL/DCLFRANK.cpy:L10-L13`) | NOT REPLICATED | Fetched and never read by the program (section 1.1); staged for reconciliation only |
| VSAM `HISTORY` KSDS as the audit store (`VSAM/DEFKSDS.jcl:L9-L16`) | NOT REPLICATED | Exported and staged in `legacy_history`; the target's audit store is `ledger_entry` |
| CICS `ASKTIME`/`FORMATTIME` region-local stamps (L80-L85) | NOT REPLICATED | The ledger stores `TIMESTAMPTZ`; legacy stamps are decoded with a configurable zone for reconciliation (open item 9.6) |
| `CCSID EBCDIC` storage (`DB2-DDL/DB2DDL.jcl:L22`, `DB2-DDL/DB2DDL.jcl:L29`, `DB2-DDL/DB2DDL.jcl:L51`, `DB2-DDL/DB2DDL.jcl:L61`) | NOT REPLICATED | UTF-8 PostgreSQL; the decoder handles the legacy code page on import (section 8) |
| `DISPLAY` of the request fields to the region log (L70-L73, L86-L87) | NOT REPLICATED | Structured application logging; request fields are not written to a console stream |
| `BALANCE-GRP` with `BALANCE-CH` and its `REDEFINES` (L28-L30), `WS-KEY-LGTH` (L24), `WS-DATA-LGTH` (L25) | NOT REPLICATED — genuinely unused | None of these five names appears anywhere in the procedure division; the `WRITE` derives both lengths with `LENGTH OF` instead (L128, L130), so the two length fields never receive a value. Nothing depends on them |
| `WS-MSG` (L20) | NOT REPLICATED — write-only, not unused | It is assigned twelve times (L147, L149, L159, L161, L180, L182, L199, L201, L233, L235, L266, L268) and never read: no path moves it into the COMMAREA or the history record, so every outcome message the program composes is discarded. The distinction from the row above matters — the legacy program *had* a per-condition human-readable diagnostic for all twelve outcomes and simply threw it away, which is precisely what the `message` field of the replacement's error payload restores |
| `WS-ASKTIME` (L21), `WS-DATE` (L22), `WS-TIME` (L23) | PRESERVED in effect | Consumed only to stamp the audit record (L112-L113, L120-L121); the ledger's timestamp serves the same purpose |

## 7. Status-code derivation and the dispatch table

Every status that replaces a characterized legacy outcome is derived here rather than chosen: 7.1
takes each condition the program could reach and states what its one return field reported, which is
what a status and a stable error code have to replace; 7.2 maps the six request codes onto the
endpoints that carry them. The replacement's target-only conditions — the reservation, idempotency
and authentication codes a single-balance program with no reservations and no token had no analogue
for — derive from nothing here and belong to its own closed error model, listed in the
[module README](../README.md).

### 7.1 Why the return channel was lossy, condition by condition

The program has exactly one status channel: `WS-RETCODE PIC X(10)` (L58), initialized to spaces (L78)
and filled once, after dispatch, by `MOVE SQLCODE TO WS-RETCODE` (L104). Two properties of that single
statement account for every ambiguity a caller faced.

It is a numeric-to-alphanumeric move. `SQLCODE` is a signed binary field in the SQLCA included at
L7-L9; the receiving field is alphanumeric. The move renders the absolute integer digits and drops the
sign, so `-803` and `+803` both arrive as `000000803` and the caller cannot distinguish an error class
from a warning class. The same value is written to the audit record by the same kind of move (L117
into `WS-VR-RETCODE PIC X(10)`, L45), so the history file preserves the ambiguity rather than
resolving it.

It reports the last statement's code. `SQLCODE` is whatever the most recently executed SQL statement
left behind, and in `CASH-ACCT-CREDIT` and `CASH-ACCT-DEBIT` that is the `UPDATE` (L227-L231,
L260-L264) — not the rate `SELECT` that preceded it (L214-L219, L248-L253). An earlier failure in the
same paragraph is therefore overwritten before it is ever reported; section 5.1 walks the case where
this masks a `-305`.

| Legacy path | Last `SQLCODE` seen | Legacy outcome | Replacement |
|---|---|---|---|
| `Q` found | 0 | Row returned (L143-L147) | `200` with the body |
| `Q` not found | 100 | Return code 100; the COMMAREA still holds the caller's amount and echoes it as the balance (L105-L106) | `404 ACCOUNT_NOT_FOUND`, no echo |
| `A` inserted | 0 | Row inserted upper case, owner echoed in the caller's casing (L155, L158) | `200` with the stored owner |
| `A` duplicate | -803 | Return code `000000803` against the `owner` primary key (`DB2-DDL/DB2DDL.jcl:L50`) | `409 ACCOUNT_ALREADY_EXISTS` |
| `A`/`U` invalid data (`-302`, `-407`, `-413`) | negative | Digits only, sign dropped | `400 INVALID_OWNER` / `INVALID_AMOUNT` / `INVALID_CURRENCY`, or `422 AMOUNT_OUT_OF_RANGE` — all validated before any SQL runs |
| `U` found / not found | 0 / 100 | Balance and currency overwritten (L172-L179) / nothing written | `200` / `404`; `409 RESERVATIONS_OUTSTANDING` when funds are held |
| `X` found / not found | 0 / 100 | Row deleted (L195-L198) / nothing deleted | `200` with the deleted account / `404`; `409 RESERVATIONS_OUTSTANDING` when funds are held |
| `C`/`D` account not found | 100, caught by the guard at L212 / L246 | No arithmetic, no update; caller's amount echoed | `404 ACCOUNT_NOT_FOUND` |
| `C`/`D` rate row missing | rate `SELECT` 100, then `UPDATE` 0 | **Return code 0** with `RATES` uninitialized (`COBOL/DCLFRANK.cpy:L22`) — undefined arithmetic committed under a success status | `503 EXCHANGE_RATE_UNAVAILABLE` with `Retry-After`, balance unchanged, no ledger row |
| `C`/`D` computed | 0 | Truncated, unsigned result stored (L222/L256, L225/L259): absolute value on a negative result, high-order digits dropped on overflow | `200`, or `422 INSUFFICIENT_FUNDS` / `422 AMOUNT_OUT_OF_RANGE` |
| `C`/`D` null in the rate row | `-305` at the rate `SELECT`, then `UPDATE` 0 | Success reported; the update commits with an undefined `RATES` (section 5.1) | `503 EXCHANGE_RATE_UNAVAILABLE`; the export's null rate is a `RATE_SOURCE` variance |
| Deadlock or timeout, unit of work already rolled back (`-911`) | negative | Digits only; Db2 had rolled the unit of work back before the program regained control, so whatever the paragraph had written so far was already undone (SQLSTATE 40001) | `503 DATASTORE_UNAVAILABLE` with `Retry-After` |
| Deadlock or timeout, unit of work left open (`-913`) | negative | Digits only; the statement failed and nothing was rolled back — the commit-or-rollback decision stayed with the application, and whether a CICS caller saw this or `-911` is decided by the region's `DROLLBACK` attachment attribute, which this repository does not contain (SQLSTATE 57033; open item 9.9) | `503 DATASTORE_UNAVAILABLE` with `Retry-After` |
| Resource unavailable (`-904`) | negative | Digits only; a required resource was unavailable rather than contended, so no rollback is implied and this is not a lock-contention outcome at all (SQLSTATE 57011) | `503 DATASTORE_UNAVAILABLE` with `Retry-After` |
| Unrecognized request code | untouched — no statement ran in this task | Success-looking code, COMMAREA echoed, history row written (section 3) | `404 UNSUPPORTED_PATH` / `405 UNSUPPORTED_METHOD` |
| Any other negative code | negative | Digits only | `500 INTERNAL` |

Those three rows state what the codes mean — Db2 for z/OS product semantics, SQLSTATE 40001, 57033
and 57011, from the IBM Db2 for z/OS documentation, *Codes* → SQL codes `-904`, `-911` and `-913`,
and labelled as such per section 0. The six sources settle something narrower and more useful: where
such a code could arrive, and whether the caller ever saw it. The program declares no `WHENEVER` and
tests `SQLCODE = 0` in six places (L143, L157, L171, L194, L212, L246), and each of those guards
only its own paragraph's **first** SQL statement, so the two cases are not alike:

- On that guarded first statement, any of the three codes takes the `ELSE` branch, whose `WS-MSG`
  is discarded unread (section 6), and `MOVE SQLCODE TO WS-RETCODE` (L104) hands the code back as
  unsigned digits.
- On any **later** statement in the paragraph nothing is checked. The success branch had already
  been entered, so the paragraph still composes its success message — `'ACCOUNT UPDATED'` (L180),
  `'ACCOUNT CREDITED'` (L233) — and whether the failing code reaches the caller at all depends on
  position: `U`'s `UPDATE` (L174-L179) and `X`'s `DELETE` (L195-L198) are the last statements their
  paragraphs execute, so their errors survive to L104 beside that success message, while `C`/`D`'s
  rate `SELECT` (L214-L219, L248-L253) is followed by an `UPDATE` (L227-L231, L260-L264) whose own
  `SQLCODE` overwrites it — the same masking section 5.1 walks for `-305`.

The program therefore distinguished none of the three, and the return field could not tell a
rolled-back unit of work from one still open, nor either from the success message composed beside
it. The replacement splits them by cause instead: a lock conflict raised by the target's own row
locks is `409 CONCURRENT_MODIFICATION` with `Retry-After: 1`, and an unreachable or timed-out
datastore is `503 DATASTORE_UNAVAILABLE` with `Retry-After: 5` — so no caller has to infer a
rollback from a digit string.

The replacement has no analogue of the "last statement wins" masking: each characterized failure
raises before its transaction commits, and one exception handler maps it to exactly one status and one
stable code, so a caller reading a success can rely on it.

### 7.2 Request code to paragraph to endpoint

| Code | Paragraph | Label | New endpoint |
|---|---|---|---|
| `A` | `CASH-ACCT-ADD` | L151 | `POST /cash-account/{owner}` |
| `Q` | `CASH-ACCT-READ` | L136 | `GET /cash-account/{owner}` |
| `U` | `CASH-ACCT-UPDATE` | L164 | `PUT /cash-account/{owner}` |
| `X` | `CASH-ACCT-DELETE` | L185 | `DELETE /cash-account/{owner}` |
| `C` | `CASH-ACCT-CREDIT` | L204 | `PUT /cash-account/{owner}/credit?amount=` |
| `D` | `CASH-ACCT-DEBIT` | L238 | `PUT /cash-account/{owner}/debit?amount=` |
| anything else | falls through (L89-L102) | — | Fails closed (section 3) |

The replacement needs no explicit catch-all because the HTTP verb and path *are* the request code, and
routing supplies the branch the `EVALUATE` lacked: an unmapped path is a 404 and an unmapped method on
a known path is a 405, both rendered through the same error payload as every other failure so the
response shape does not change with the failure mode. `FailClosedIT` asserts both.

## 8. Character encoding and code page

The legacy data is EBCDIC and the target is UTF-8, so every byte of an export passes through a
conversion that can silently change ordering and padding. The rules below are what keep that
conversion from being mistaken for a data difference.

The database, the tablespace and both tables are created `CCSID EBCDIC`
(`DB2-DDL/DB2DDL.jcl:L22`, `DB2-DDL/DB2DDL.jcl:L29`, `DB2-DDL/DB2DDL.jcl:L51`,
`DB2-DDL/DB2DDL.jcl:L61`), and CICS working storage is EBCDIC by construction.
The region's exact CCSID is not recorded anywhere in this module (open item 9.6), which is why the
decoder takes it from the `tool.legacy-charset` property — default `IBM037` — instead of hard-coding
one: a wrong code page assumption is recoverable by configuration, but not by a rebuild in the middle
of a migration window.

Numeric decoding. `WS-VR-BALANCE` is unsigned zoned decimal `9(7)V99` (L43): nine digit positions with
no sign nibble and an implied decimal point, so after code-page conversion the field is nine digit
characters. The correct read is to take those digits as text into a `BigDecimal` and shift the point
two places left — never through `double`. Any float on a money path reintroduces representation error
into values that COBOL held exactly, which shows up as penny-level differences spread across a
reconciliation run and is indistinguishable from a real defect.

Padding. `CHAR` columns and `X(n)` fields are blank-padded — `0x40` in EBCDIC, `0x20` after conversion
— so `owner CHAR(32)` (`DB2-DDL/DB2DDL.jcl:L47`), `currencyc CHAR(8)`
(`DB2-DDL/DB2DDL.jcl:L49`), `WS-VR-NAME X(15)` (L39) and `WS-VR-CURRENCY X(8)` (L44) all arrive padded
and are right-trimmed on load. A delimited export produced on the mainframe carries the same padding
inside its fields, so the delimited reader trims identically rather than assuming the export step did.

Collation. EBCDIC and ASCII order characters differently — digits sort after letters in EBCDIC and
before them in ASCII — so any comparison that depends on sort order or on record position changes
meaning when the data is converted. Reconciliation therefore joins legacy and migrated rows on the
normalized owner key and never on sort order or ordinal position.

Owner casing in the two exports differs, and the difference is structural rather than incidental. The
account table contains no lower-case owners, because the insert stores `UPPER(:CUST-NAME-TEXT)` (L155).
The history file does: `WS-VR-NAME` receives the caller's name in the caller's own casing (L111), and
the 29-byte key includes that name (L119, L47-L50), so `John` and `JOHN` with the same timestamp are
two distinct, valid KSDS keys. Uppercasing on import would therefore collide two records that legacy
kept apart, which is why `legacy_history` keys on the raw decoded name and carries a separate
uppercased `owner_key` for joins.

## 9. Open items the source cannot settle

Each of these is a question the replacement had to answer and the legacy source cannot. They are
recorded with what would settle them rather than with a plausible value, because a guess written here
would be indistinguishable from a finding once this document is cited by code.

### 9.1 The status returned for an unrecognized request code

No SQL statement runs on the fall-through path (L89-L102), and this program never initializes
`SQLCODE`, so the value `MOVE SQLCODE TO WS-RETCODE` (L104) copies out is whatever the SQLCA held when
the CICS task began. The return field is therefore recorded here as **success-looking** and no exact
value is asserted. What would settle it: the region's task-initialization behavior, observed against a
real CICS system. Nothing in the replacement depends on the answer, because the fall-through is not
reproduced (section 3).

### 9.2 The behavior when the rate table has no row for a currency

The arithmetic then reads an uninitialized `RATES` host variable
(`COBOL/DCLFRANK.cpy:L22`), whose content at that point is a property of the region rather than of the
program. This is recorded as **undefined arithmetic with a success code** — the shape is certain from
L214-L231, the resulting number is not. What would settle it: the region's working-storage
initialization settings, or an observed execution against the real system; neither is available to a
deliverable that connects to no live system. The fixture set always supplies a rate row so that no
expected value depends on this, and the replacement returns `503` rather than computing anything.

### 9.3 Whether anything rejected owners longer than 15 characters before the COMMAREA

Truncation to `X(15)` (L55) is certain; whether a caller ever reached it with a longer name, or was
rejected earlier, is not recorded in this module. What would settle it: the interface definition of the
layer in front of CICS. The replacement's 32-character limit with an explicit rejection is a
deliberate change either way (section 2), so the answer changes no behavior — only whether the change
is observable to an existing caller.

### 9.4 The rounding applied to an amount with more than two decimals

The COMMAREA field holds exactly two decimals (L56), so any additional precision was discarded before
the program saw it — but by something outside this module. This is not settleable here, and the basis
for saying so is concrete: the legacy module contains exactly nine files — the six sources listed in
section 0 plus `README.md`, `LICENSE` and `architecture-diagram.png` — and no z/OS Connect API or
service archive artifact is among them, even though the legacy module's own README describes z/OS
Connect exposure (`README.md:L2`). What would settle it: that API or service-archive definition. The
replacement truncates (section 1.9) for consistency with COBOL's default, recorded as a target
decision rather than a legacy fact.

### 9.5 The VSAM record-length tension

`CASH00` writes 57 bytes — the accumulated width of `WS-VSAM-RECORD` (L38-L45), passed as `LENGTH OF
WS-VSAM-RECORD` (L128) — into a cluster defined `RECSZ(100 100)` (`VSAM/DEFKSDS.jcl:L11`), which is
fixed-length. Only `NOTOPEN` and `DUPREC` are ignored (L123-L124), so a `LENGERR` from a fixed-format
file definition would not be suppressed and the transaction would abend; a variable-format definition
would store 57-byte records instead. The two artifacts in the repository are consistent with each
other only if the CICS file definition reconciles them, and that definition is not here. What would
settle it: the FCT or CSD `FILE` attributes (`RECORDFORMAT`, `RECORDSIZE`), or a real `REPRO` or
`PRINT` sample of the data set. Until then `VsamHistoryRecordDecoder` accepts both 57-byte and
100-byte records, treating bytes 57-99 as padding, and the record length is declared to the tool
rather than inferred from the file.

### 9.6 The region's code page and time zone

Decoding the binary history requires a code page, and interpreting its `YYYYMMDD`/`HHMMSS` stamps
(L40-L41, produced by `FORMATTIME` at L82-L85 in region local time) requires a zone. Neither is
recorded in this module — `CCSID EBCDIC` (`DB2-DDL/DB2DDL.jcl:L22`) names an encoding family, not a
specific code page, and no artifact here names a zone at all. What would settle it: the region
configuration, from the mainframe team. Both are `tool.*` properties rather than constants, so the
answer is applied as configuration when it arrives.

### 9.7 The `cyrrnbase` / `CURRNBASE` spelling mismatch

The DDL declares `cyrrnbase` (`DB2-DDL/DB2DDL.jcl:L56`) while the copybook declares `CURRNBASE`
(`COBOL/DCLFRANK.cpy:L10`) and the program's `SELECT` names `CURRNBASE` (L215, L249). As written the
program would not precompile against this DDL, so at least one of the two artifacts does not describe
the deployed catalog — and this repository cannot say which. What would settle it: the column name in
the real DB2 catalog. The export reader accepts either spelling as a header, so a delimited export
loads correctly under either answer; nothing else depends on it, because the column is never read
(section 1.1).

### 9.8 The `NUMERIC(9,2)` ceiling

The legacy ceiling of 9,999,999.99 is preserved by requirement (section 6), and preserving it is the
right default for parity. Whether it suits the institutional volumes the new reservation surface is
intended to serve is a business question this repository does not answer. What would settle it: a
stated ceiling from the requesting organization. Widening it later is one DDL change plus the bounds
in `Money`, and does not affect parity for values inside the current range.

### 9.9 Whether a deadlock or timeout reached the caller as `-911` or as `-913`

Both codes are reachable and they are different outcomes: `-911` is returned after the unit of work
has already been rolled back (SQLSTATE 40001), `-913` reports the same deadlock or timeout with the
unit of work still open and the commit-or-rollback decision still the application's (SQLSTATE 57033)
— Db2 for z/OS product semantics, from the IBM Db2 for z/OS documentation, *Codes* → SQL codes
`-911` and `-913`, and labelled as product semantics per section 0. Which of the two an application
running under CICS sees is set by the `DROLLBACK` attribute of the Db2 attachment's
`DB2CONN`/`DB2ENTRY` definition: `YES`, the default, makes the CICS Db2 attachment facility issue a
syncpoint rollback and return `-911`, while `NO` initiates no rollback and returns `-913` — IBM CICS
Transaction Server for z/OS documentation, *DB2CONN* and *DB2ENTRY* resource definitions, the
`DROLLBACK` attribute. No such
definition is in this repository — the legacy module holds exactly nine files (the six sources of
section 0 plus `README.md`, `LICENSE` and `architecture-diagram.png`) and none of them declares a
`DB2CONN`, a `DB2ENTRY` or an RCT entry. The package binds `ENABLE(BATCH,CICS)`
(`DB2-DDL/DB2BIND.jcl:L26`), so the same package reached through a batch connection has its unit of
work rolled back by Db2 itself while under CICS the attachment attribute decides — which is why this
is a region-configuration fact and not a program fact. What would settle it: the `DROLLBACK`
attribute of the region's own `DB2CONN`/`DB2ENTRY` definitions, read from those definitions — or, in
a region predating resource definition online, the equivalent `ROLBE` parameter of its RCT entry.
Meanwhile nothing in the replacement depends on the answer:
it runs one transaction per request that either commits whole or rolls back whole, so no caller is
ever left asking whether its work survived.

## 10. Acceptance

This section is the machine-readable gate on everything above. The migration tooling copies the
`Status` value into `migration_run.characterization_status`, a `VARCHAR(8)` column whose only legal
values are `DRAFT` and `ACCEPTED`, and the first runbook step's sign-off requires `ACCEPTED`. A
`DRAFT` characterization can therefore be exercised against the synthetic fixtures freely but can
never be accepted against a real export — which is the intended safeguard, because the reconciliation
results are only as trustworthy as the baseline they are judged against.

The authoring agent sets `DRAFT`. Only the requesting organization's designated reviewer — the
cash-account data owner — may change it to `ACCEPTED`, after checking the citations above against the
source, and must record their name, role and the date in the two fields that follow. Keep these three
lines exactly as they are: single lines, this spelling, no surrounding markup, so a simple read can
parse them.

Status: DRAFT

Accepted by: <named reviewer, role>

Date:

