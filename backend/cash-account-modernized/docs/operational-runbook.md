# Operational Runbook — Cash Account Migration and Cutover

This document is the operational hand-off for replacing the CICS/COBOL cash-account program with the
service in this module. It is a deliverable of the build; **its steps are not performed by the
build.** Nothing described below has been executed, and nothing in this repository has touched the
mainframe, the legacy data, or any live deployment value.

The five steps are executed by the roles named in each step — the platform operator, the mainframe
operator, the cash-account data owner, the product owner, and risk/compliance — under their own change
control. An operator who was not present for the build should be able to work each step from this
document alone; where a fact lives in a sibling document, the step links it rather than paraphrasing
it, because a paraphrase is the copy that goes stale.

## Scope and how to read this document

- **Five steps.** Step 0 is a prerequisite that is signed off before any other step begins. Steps 1-4
  are the operational sequence: bulk migration rehearsal and reconciliation, shadow-mode dual-run,
  controlled cutover, decommission.
- **Five elements per step**, each under its own heading: **Preconditions**, **Actions**, **Evidence to
  capture**, **Sign-off required**, **Rollback criterion**. A step with an unmet precondition or an
  unrecorded sign-off has not started, whatever else has been done.
- **No step is optional and no step is reorderable.** Step 3 hands live traffic to a service whose
  correctness is established by Steps 1 and 2; Step 4 destroys the only thing Step 3's rollback can
  hand state back to.
- A **gate** is a go/no-go decision with a stated pass condition. A gate that cannot be evaluated has
  failed — an absent measurement is not a pass.

### Roles

| Role | Holds |
| --- | --- |
| Platform operator | The change window; the Helm/operator values and the StockTrader CR; broker and portfolio restart authority; the on-call rollback decision |
| Mainframe operator | CICS transaction enable/disable; DB2 utility and IDCAMS execution; the `FREE`/`DROP`/`DELETE` retirement actions; the replay of a rollback file through the existing transaction |
| Cash-account data owner | Acceptance of every reconciliation variance; the catalog baseline; the final load and pre-routing validation |
| Product owner | Acceptance of the dual-run result and of the post-cutover state |
| Risk / compliance | Joint authorization of decommission, with the data owner and the platform owner |
| Platform owner | The release's relational store (the PostgreSQL move), `vault.enabled`, and joint decommission authorization |

## Standing prohibitions

These hold **regardless of what access the executing environment happens to have.** They constrain the
build that produced this module, and they are recorded here because this document is where an operator
looks for the boundary between what was delivered and what must still be done under change control.

1. **Do not connect to, read from, or write to the real DB2 for z/OS or the VSAM KSDS.** The migration
   tooling is verified against the synthetic fixtures under
   [`../src/test/resources/fixtures/`](../src/test/resources/fixtures/) only. The mainframe data is
   production state owned by the requesting organization; reading it is Step 1, under read-only
   credentials the requesting organization issues.
2. **Do not run the dual-run mechanism against the live legacy system.** The comparator is proven
   against synthetic transaction streams. A live shadow run needs request/response capture
   infrastructure, sign-off and a rollback plan that this document specifies but the build does not
   own — that is Step 2.
3. **Do not change any deployment configuration value that would route traffic to the new service.**
   `cashAccount.enabled`, `cashAccount.url`, `database.kind`, the StockTrader CR and broker's
   `CASH_ACCOUNT_URL` stay exactly as they are. That cutover needs only a values change is a design
   property verified by inspection of the existing chart, never by flipping a value.
4. **Take no decommissioning or retention action.** No CICS, DB2 or VSAM asset is retired, exported for
   retention, or deleted. This repository contains no data-retention policy, and **defaulting one is
   prohibited** — see Step 4.

Prohibition 3 is also why this document never instructs anyone to edit a chart **template**. The
existing `infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml` already
probes, exposes and injects everything the service needs. If a step here appears to require a template
change, that is a stop-and-flag condition: stop, and report the exact template attribute believed
missing.

## Conventions

- **Placeholders** are angle-bracketed and must be substituted before a command is run. A command that
  still contains an angle bracket has not been completed; where one is passed through a quoted heredoc
  it reaches the tool untouched and fails loudly there, rather than expanding to nothing in the shell.

  | Placeholder | Substitute with |
  | --- | --- |
  | `<release>`, `<namespace>`, `<name>` | The Helm release, its namespace, and the StockTrader CR name |
  | `<registry>`, `<version>` | The image registry path and the version tag being promoted |
  | `<host>`, `<port>`, `<database>`, `<id>`, `<password>` | PostgreSQL connection details, from the `database.*` values |
  | `<uuid>` | A fresh batch id, one per runbook step |
  | `<W>` | The ledger watermark recorded in Step 3(d) |
  | `<dir>`, `<window-dir>` | The directory holding that run's export or shadow files |
  | `<OWNER>`, `<reservationId>`, `<token>` | An account owner, a reservation id, and a bearer token for a caller with the `StockTrader` role |
  | `<broker-host>` | The broker service host, for the one post-cutover read through broker |
  | `<hlq>`, `<sequential-dataset>`, `<wlm-env>`, `<db2-ssid>` | z/OS high-level qualifier, target data set, WLM environment and DB2 subsystem — site values supplied by the mainframe team |
  | `<region-ccsid>`, `<region-zone>`, `<57-or-100>` | The CICS region's code page and time zone, and the history record length, all obtained as Step 1 preconditions |

- **The tooling jar** is `target/cash-account-modernized-1.0.0-SNAPSHOT.jar` in a build tree and
  `/deployments/app.jar` inside the container image. Both are the same artifact: the service and the
  migration CLI are one jar, selected by `--spring.profiles.active=tool`.
- **Tool exit codes**: `0` clean (no variance rows), `2` variance (the run completed and recorded
  variances), `1` error (the run failed). A gate reads the exit code **and** the recorded rows; the
  rows are what gets signed off, because a log line cannot be signed off.
- **Tool database access.** The `tool` profile needs the same `JDBC_KIND`, `JDBC_HOST`, `JDBC_PORT`,
  `JDBC_DB`, `JDBC_ID`, `JDBC_PASSWORD` environment as a normal run — see the configuration map in
  [`../README.md`](../README.md).
- **Evidence register.** Every item listed under *Evidence to capture* is attached to the change record
  for its step, named `step<N>-<item>`, before that step's sign-off is requested. Evidence captured
  after a sign-off is not evidence for it.
- **Schema qualification.** Steps 1 and 2 qualify every relation with `cash_account_rehearsal.`,
  because the rehearsal schema is deliberately not on the default search path — an unqualified query
  there would silently read production. Steps 3 and 4 run against the default search path, which by
  then is where the live tables are. Either form may be replaced by
  `SET search_path TO cash_account_rehearsal;` once per session, but not by relying on a default.

### Step index

| Step | Name | Executed by | Signed off by |
| --- | --- | --- | --- |
| 0 | Prerequisites | Platform operator | Platform owner; data owner |
| 1 | Bulk migration rehearsal and reconciliation | Mainframe operator; platform operator | Cash-account data owner |
| 2 | Shadow-mode dual-run | Platform operator | Product owner; risk |
| 3 | Controlled cutover | Platform operator; mainframe operator | Platform operator and data owner at gates (b)-(c); product owner after (g) |
| 4 | Decommission | Mainframe operator | Data owner, risk/compliance and platform owner jointly |

---

## Step 0 — Prerequisites

Signed off before any part of Steps 1-4 begins. Each item here is something a later step silently
depends on; discovering it missing mid-cutover means discovering it during a change window with
traffic on the line.

### Preconditions

- **The release's relational store is PostgreSQL 12 or later**, with `database.kind: postgres` and the
  `database.*` values pointing at it.

  This is a **separate change, completed and signed off on its own — never inside the cash cutover.**
  `database.kind` is chart-global: the same values reach `backend/portfolio`, which reads the identical
  `JDBC_HOST` / `JDBC_PORT` / `JDBC_DB` / `JDBC_ID` / `JDBC_PASSWORD` / `JDBC_SSL` variables
  (`backend/portfolio/src/main/liberty/config/includes/postgres.xml:L1-L13`). Moving the store inside
  the cash window would put portfolio's datasource in the blast radius of a cash rollback, and
  portfolio is out of scope for this migration.

  The chart default is still `db2`
  (`infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L74-L81`), so this is a real change
  somebody must have already made. The estate's provisioned floor is PostgreSQL **12**
  (`infra/stocktrader-setup/azure/modules/postgres/main.tf:L23` sets `version = "12"`), which is the
  compatibility floor this module's schema is written and tested against; a later major version is
  fine, an earlier one is not.

- **`vault.enabled: false` for the release.** The chart default is already `false`
  (`infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L220-L221`) and it must stay there.
  When it is true the template injects Liberty-specific container arguments —
  `/opt/ol/helpers/runtime/docker-server.sh …`
  (`infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L66-L71`) — which a
  Spring Boot image cannot execute, so the pod would never start. The template is not edited to
  accommodate this service; a Vault-compatible argument shape for a non-Liberty workload is an open
  item for the chart's owners.

- **The service image is built, scanned and pushed, and its digest is recorded.** See
  [Image build, scan and push](../README.md#image-build-scan-and-push) — that is the only place the
  path is documented, and it is not restated here so the two cannot drift.

- **A database identity with `CREATE SCHEMA`** for the rehearsal schema Step 1 needs, and the
  connection details for the store above.

- **A Prometheus scrape of the service's `/metrics` endpoint is in place**, because Step 3's error-rate
  rollback criterion is evaluated from it. The chart already annotates the pod for scraping with no
  path (`…/templates/cash-account.yaml:L51-L54`), which is why the service exposes `/metrics` as well
  as `/actuator/prometheus`.

### Actions

1. **Record the image digest** produced by the README build path. The digest, not the tag, is what
   Step 3(e) puts into `cashAccount.image.*`:

   ```bash
   docker inspect --format '{{index .RepoDigests 0}}' <registry>/cash-account:<version>
   ```

   A tag can be re-pointed after the fact and a digest cannot, which is what makes "the image we
   validated" and "the image the pod runs" the same statement. This command only answers after the
   push: a locally built image carries no `RepoDigests` entry.

2. **Capture the live values / CR snapshot.** This repository holds chart *defaults*, not the live
   release's state, so the defaults are not a rollback target. Every later instruction to "restore"
   means restoring **this** snapshot verbatim:

   ```bash
   helm get values <release> -n <namespace> --all -o yaml > step0-values-snapshot.yaml
   # or, where the StockTrader operator owns the release:
   kubectl get stocktrader <name> -n <namespace> -o yaml > step0-cr-snapshot.yaml
   ```

   Capture whichever one governs the release, and both if both exist.

3. **Run the pre-cutover catalog query** against the selected database:

   ```sql
   SELECT table_name, column_name, data_type
     FROM information_schema.columns
    WHERE table_name IN ('cashaccount','cash_account')
    ORDER BY 1,2;
   ```

   The output must show **no `cash_account` at all** and, if `cashaccount` is present, its columns
   exactly as the estate created them.

   Those two names are **different tables**, and the distinction is the whole point of the query. The
   estate's Azure init template creates a never-built Java-flavour `cashaccount(owner VARCHAR(32),
   balance DOUBLE PRECISION, currency VARCHAR(8))` with an `allowed_currencies` CHECK over 31 ISO codes
   (`infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L3-L9`). This module's
   table is `cash_account` — underscore, `NUMERIC(9,2)`, available and reserved balances — and it
   neither reads nor alters `cashaccount`. Baselining the catalog now is how anyone can later show that
   nothing in this migration touched a table it does not own: the same query is re-run after Steps 1
   and 3, and the `cashaccount` rows must come back **byte-identical**.

4. **Run the memory-fit check** and observe readiness inside the chart's envelope:

   ```bash
   docker run --rm --memory=2g --cpus=1 \
     -e JDBC_KIND=postgres -e JDBC_HOST=<host> -e JDBC_PORT=<port> -e JDBC_DB=<database> \
     -e JDBC_ID=<id> -e JDBC_PASSWORD=<password> -e AUTH_TYPE=basic \
     -p 8080:8080 <registry>/cash-account:<version>
   ```

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/actuator/health/readiness
   ```

   `--memory=2g --cpus=1` mirrors the chart's limits
   (`…/templates/cash-account.yaml:L224-L232`). The check is **manual because no in-repo test can
   assert it**: the base image's `run-java.sh` derives the heap from the container memory limit, so the
   answer depends on the runtime limit rather than on anything a unit or integration test observes.

### Evidence to capture

| Item | What it is |
| --- | --- |
| `step0-values-snapshot.yaml` / `step0-cr-snapshot.yaml` | The live values or CR, verbatim. The rollback target for Step 3 |
| `step0-image-digest.txt` | The pushed image digest from `docker inspect` |
| `step0-catalog-baseline.txt` | The catalog query output: no `cash_account`, `cashaccount` as created |
| `step0-memory-fit.txt` | The `docker run --memory=2g --cpus=1` observation and the readiness status code |
| `step0-store.txt` | The effective `database.kind` and the server version reported by `SELECT version();` |

### Sign-off required

- **Platform owner** — the PostgreSQL move (`database.kind: postgres`, version ≥ 12, `database.*`
  pointing at it) and `vault.enabled: false`, each as a change completed in its own right.
- **Cash-account data owner** — the catalog-query baseline, because they are the party who must later
  be able to say that `cashaccount` was never touched.

### Rollback criterion

There is nothing to roll back here — Step 0 changes no state that this migration owns. Its failure mode
is a **block**: any unmet prerequisite stops every step below from beginning. In particular:

- `database.kind` still `db2`, or a server older than PostgreSQL 12;
- no recorded image digest, or a digest that does not match the artifact that passed `./mvnw -B clean verify`;
- a catalog query showing a pre-existing `cash_account` — which means something already applied this
  schema, and the "final load into an empty production schema" gate in Step 3(b) cannot be evaluated;
- `vault.enabled` true;
- no `CREATE SCHEMA` identity, which makes Step 1's isolation impossible.

Resolve the item and re-capture the affected evidence. Do not proceed with a noted exception: each of
these is load-bearing for a later gate, and a step whose gate cannot be evaluated has failed.

---

## Step 1 — Bulk migration rehearsal and reconciliation

A rehearsal against a real export, in a disposable schema, with nothing routed to the new service. Its
purpose is to find out what the real data does to the loader and the reconciler while a mistake still
costs only a `DROP SCHEMA`.

### Preconditions

- **Step 0 signed off.**
- **Read-only credentials** to DB2 for z/OS (`STOCKTRD.CASHACCOUNTY`, `STOCKTRD.FRANKFURT1`) and to the
  KSDS `SYSD.STOCK.HISTORY`. Read-only is not caution for its own sake: the legacy system is still the
  system of record until Step 3, and a write path from this exercise into it has no legitimate use.
- **The CICS FILE definition for `HISTORY`** (`RECORDFORMAT`, `RECORDSIZE` from the FCT or CSD). It
  settles the record-length open item recorded in
  [`legacy-characterization.md` §9.5](legacy-characterization.md): the program writes **57** bytes into
  a cluster defined `RECSZ(100 100)` (`backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L11`), and only the
  file definition says which length the data set actually holds. Its answer is
  `tool.history-record-length`, which is **mandatory for binary history input** and never inferred.
- **The region's CCSID and time zone**, which set `tool.legacy-charset` (assumed `IBM037`) and
  `tool.legacy-timezone` (assumed `UTC`). Both are open items in
  [`legacy-characterization.md` §9.6](legacy-characterization.md) and both are properties rather than
  constants precisely so the answer arrives as configuration. A wrong code page corrupts every decoded
  owner name; a wrong zone shifts every history timestamp by a fixed offset and makes window boundaries
  in Step 2 meaningless.
- **A disposable rehearsal schema**, created by the DDL-owning role:

  ```sql
  CREATE SCHEMA cash_account_rehearsal;
  ```

  The tooling is pointed at it with `--spring.datasource.hikari.schema=cash_account_rehearsal`. The
  service ships **no** default for that property for exactly this reason: production tables stay
  untouched until Step 3, and a rehearsal that shared them would make its own reset impossible.

- **No traffic routed to the new service.** `cashAccount.enabled` stays `false` and broker's
  `CASH_ACCOUNT_URL` stays as the snapshot records it.

- **`Status: ACCEPTED`** in [`legacy-characterization.md`](legacy-characterization.md) §10. The tooling
  copies that value into `migration_run.characterization_status`, and this step's sign-off requires
  `ACCEPTED`. A `DRAFT` characterization may be exercised against fixtures freely but can never be
  accepted against a real export, because a reconciliation result is only as trustworthy as the
  baseline it is judged against.

### Actions

The two z/OS extracts below are **templates for the mainframe team to complete** — job cards, data set
names, utility IDs, the WLM environment and the DB2 subsystem are site values. They are written against
the artifacts in this repository and must be reconciled with the deployed catalog before submission.

1. **Unload the DB2 tables to delimited files.** One step per table, each with its own `SYSREC`, so
   that neither output depends on concatenation order:

   ```jcl
   //UNLDCASH JOB (ACCT#),'UNLOAD CASHACCT',NOTIFY=&SYSUID,CLASS=A,
   // MSGCLASS=H,MSGLEVEL=(1,1),SCHENV=<wlm-env>
   //UNLOAD   EXEC PGM=DSNUTILB,REGION=0M,PARM='<db2-ssid>,UNLDCASH'
   //SYSPRINT DD  SYSOUT=*
   //SYSPUNCH DD  DUMMY
   //SYSREC   DD  DSN=<hlq>.CASHACCT.CSV,DISP=(NEW,CATLG,DELETE),
   //             SPACE=(CYL,(50,50),RLSE),UNIT=SYSDA
   //SYSIN    DD  *
     UNLOAD TABLESPACE STOCKTRD.TSSTAPP
       FROM TABLE STOCKTRD.CASHACCOUNTY
       FORMAT DELIMITED COLDEL ',' CHARDEL '"' DECPT '.'
       HEADER NONE
   /*
   ```

   The second step is identical with `FROM TABLE STOCKTRD.FRANKFURT1` and
   `DSN=<hlq>.FRANKFRT.CSV`. Both tables live in table space `STOCKTRD.TSSTAPP`
   (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L62`).

   Where the `UNLOAD` utility is not licensed, `DSNTIAUL` is the alternative, run under `IKJEFT01` with
   `RUN PROGRAM(DSNTIAUL) PLAN(DSNTIAUL) PARMS('SQL')` and a `SYSIN` of
   `SELECT * FROM STOCKTRD.CASHACCOUNTY;`. `DSNTIAUL` writes a **fixed-format** record, so the
   delimited conversion becomes a separate `SORT`/`OUTREC` step — one more place for a column to shift,
   which is why the `UNLOAD` template is the primary path.

   `FORMAT DELIMITED` emits **no column-name line**, so the header the readers require is prepended
   during the transfer (step 3 below). The reader contract the files must satisfy — it is enforced, not
   advisory:

   | Aspect | Requirement |
   | --- | --- |
   | Encoding | UTF-8, LF line endings; a leading BOM is ignored |
   | Header | First line names the columns: `owner,balance,currencyc` for `cashaccounty.csv`; `currnkey,currnbase,amount,rates,loaddt` for `frankfurt1.csv`, where `cyrrnbase` is accepted as an alias for the second column |
   | Delimiting | Comma, RFC 4180 quoting: a field containing a comma, quote or newline is double-quoted with embedded quotes doubled. Trailing `CHAR` padding may be present and is right-trimmed |
   | NULL | An **empty unquoted field**. The literal text `NULL` is *not* recognized — it is indistinguishable from an owner or currency legitimately spelled that way |
   | Decimals | Plain text matching `-?\d+\.\d{2}`; no thousands separators, no exponent |
   | Dates | `loaddt` as `YYYY-MM-DD` |

   The `cyrrnbase` alias exists because the shipped DDL spells that column `cyrrnbase`
   (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L56`) while the copybook and the program's own
   `SELECT` spell it `CURRNBASE`; which spelling the deployed catalog carries is an open item
   ([`legacy-characterization.md` §9.7](legacy-characterization.md)), so the reader accepts both rather
   than guessing.

2. **Copy the KSDS to a sequential data set** with IDCAMS, and transfer it **in binary so the EBCDIC is
   preserved**:

   ```jcl
   //REPROHST JOB (ACCT#),'REPRO HISTORY',NOTIFY=&SYSUID,CLASS=A,
   // MSGCLASS=H,MSGLEVEL=(1,1)
   //STEPNAME EXEC PGM=IDCAMS
   //SYSPRINT DD   SYSOUT=*
   //SYSIN    DD   *
       REPRO INDATASET(SYSD.STOCK.HISTORY)                 -
             OUTDATASET(<sequential-dataset>)
   /*
   ```

   The cluster name is `SYSD.STOCK.HISTORY`
   (`backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L9`). The binary copy lands as `history.cp037.bin`;
   a delimited conversion may be supplied instead, or as well, as `history.csv` with header
   `name,event_date,event_time,request_code,balance,currency,retcode`, `event_date` as `YYYYMMDD` and
   `event_time` as `HHMMSS`.

   For binary input `tool.history-record-length` is **mandatory** — `57` (what the program writes) or
   `100` (the cluster's `RECSZ`) — the file length must be an exact multiple of it, and any other value
   or a non-multiple length is rejected with an explicit error. That refusal is deliberate: a record
   length guessed from a file divides cleanly often enough to look right and shifts every field by a
   few bytes when it is wrong.

3. **Transfer, convert and checksum.** Copy each data set into a USS file first, then move it to the
   landing host:

   ```bash
   # On z/OS UNIX. -B suppresses code-page translation; use it for the binary history only.
   cp -B "//'<hlq>.HISTORY.SEQ'" history.cp037.bin
   cp    "//'<hlq>.CASHACCT.CSV'" cashaccounty.ebcdic
   cp    "//'<hlq>.FRANKFRT.CSV'" frankfurt1.ebcdic
   ```

   ```bash
   # On the landing host: convert the text exports and prepend the header line the readers require.
   iconv -f <region-ccsid> -t UTF-8 cashaccounty.ebcdic | tr -d '\r' > cashaccounty.body
   { echo 'owner,balance,currencyc'; cat cashaccounty.body; } > cashaccounty.csv
   iconv -f <region-ccsid> -t UTF-8 frankfurt1.ebcdic | tr -d '\r' > frankfurt1.body
   { echo 'currnkey,currnbase,amount,rates,loaddt'; cat frankfurt1.body; } > frankfurt1.csv
   ```

   `<region-ccsid>` is the CCSID obtained as a precondition (for example `IBM-037`); it is not assumed
   here for the same reason the tool does not assume it.

   ```bash
   # Record checksums over exactly the files the tool will read.
   sha256sum cashaccounty.csv frankfurt1.csv history.cp037.bin history.csv \
     | tee step1-export.sha256
   sha256sum -c step1-export.sha256
   ```

   Compute a checksum at the source as well, with the mainframe team's chosen utility, and compare.
   The checksum is what distinguishes "the reconciliation found a variance" from "the transfer lost or
   translated a byte", and those two findings have opposite remedies.

4. **Load, then reconcile**, under one shared batch id:

   ```bash
   BATCH_ID=<uuid>
   export JDBC_KIND=postgres JDBC_HOST=<host> JDBC_PORT=<port> JDBC_DB=<database> \
          JDBC_ID=<id> JDBC_PASSWORD=<password>

   java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
        --spring.profiles.active=tool \
        --spring.datasource.hikari.schema=cash_account_rehearsal \
        --tool.command=load \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID" \
        --tool.history-record-length=<57-or-100> \
        --tool.legacy-charset=<region-ccsid> \
        --tool.legacy-timezone=<region-zone>
   echo "load exit=$?"

   java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
        --spring.profiles.active=tool \
        --spring.datasource.hikari.schema=cash_account_rehearsal \
        --tool.command=reconcile \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID"
   echo "reconcile exit=$?"
   ```

   Inside the container image the jar is `/deployments/app.jar`; the arguments are identical.

   **The identifier model.** Every invocation is its own `migration_run` row with its own `run_id`; the
   `load` and the `reconcile` that judges it share the `--tool.batch-id`. A `load` runs in **one**
   database transaction — either the whole export is applied and the run is recorded `CLEAN`/`VARIANCE`,
   or nothing is applied and the run is `FAILED` — so a retry after a failure is simply a new `run_id`
   under the same `batch_id`, with no half-loaded state to clean up first. A partial unique index on
   `ledger_entry` additionally guarantees that a completed run can never write a second
   `MIGRATION_LOAD` row for an owner, so a retry cannot double-count an account.

5. **Re-run the Step 0 catalog query** and diff it against `step0-catalog-baseline.txt`. The
   `cashaccount` rows must be byte-identical; `cash_account` now appears, in the rehearsal schema only.

### Evidence to capture

The run and variance rows, queried from the rehearsal schema:

```sql
SELECT run_id, batch_id, mode, status, characterization_status,
       legacy_record_count, migrated_record_count, variance_count,
       source_path, started_at, finished_at
  FROM cash_account_rehearsal.migration_run
 WHERE batch_id = '<uuid>'
 ORDER BY started_at;
```

```sql
SELECT r.mode, v.owner, v.variance_kind, v.status,
       v.legacy_value, v.migrated_value,
       v.legacy_balance, v.migrated_balance, v.variance, v.recorded_at
  FROM cash_account_rehearsal.migration_reconciliation v
  JOIN cash_account_rehearsal.migration_run r ON r.run_id = v.run_id
 WHERE r.batch_id = '<uuid>'
   AND v.status <> 'MATCHED'
 ORDER BY v.owner, v.variance_kind;
```

| Item | What it is |
| --- | --- |
| `step1-migration-run.txt` | The `migration_run` rows for the batch, including `characterization_status` |
| `step1-variances.txt` | Every `migration_reconciliation` row with status other than `MATCHED`, with the data owner's written disposition beside each |
| `step1-exit-codes.txt` | The `load` and `reconcile` exit codes |
| `step1-export.sha256` | Checksums of every transferred export, and the source-side comparison |
| `step1-catalog-after.txt` | The re-run catalog query and its diff against the Step 0 baseline |
| `step1-tool-settings.txt` | The `tool.history-record-length`, `tool.legacy-charset` and `tool.legacy-timezone` used, and the file definition / region configuration they came from |

`characterization_status` must read `ACCEPTED` on every row of the batch. It is copied from the
`Status` field of [`legacy-characterization.md`](legacy-characterization.md) at run time, so a `DRAFT`
baseline is visible in the evidence rather than discoverable only by asking.

### Sign-off required

**The cash-account data owner** accepts the batch. Acceptance means every row in `step1-variances.txt`
is either resolved — the cause found and the run repeated clean — or reclassified
`ACCEPTED_EXCEPTION` **with a written reason** recorded beside it. An unexplained `VARIANCE` row is not
acceptable at any count: the point of the rehearsal is that each one is cheap to investigate now and
expensive to investigate in Step 3.

### Rollback criterion

Roll back on **any unresolved `VARIANCE` row**, or **any checksum mismatch** on the transferred
exports.

The reset drops relations rather than deleting rows, which is what makes it compatible with ledger
immutability — `ledger_entry` refuses `UPDATE` and `DELETE` by trigger, so "clear the rehearsal data"
cannot mean deleting ledger rows:

```sql
DROP SCHEMA cash_account_rehearsal CASCADE;
CREATE SCHEMA cash_account_rehearsal;
```

Then restart the tool: the schema script re-applies on start-up and the schema is empty and current
again. Re-transfer the exports if the trigger was a checksum mismatch, and begin the step again with a
new batch id.

---

## Step 2 — Shadow-mode dual-run

The legacy system keeps serving every request and stays the system of record. Captured request/response
pairs are replayed into the rehearsal schema and compared. Nothing about this step is visible to a
caller, which is exactly its value: it measures agreement on real traffic patterns at zero blast radius.

### Preconditions

- **Step 1 signed off.** Comparing responses is only meaningful once the starting balances are known to
  match.
- **A capture of legacy request/response pairs** for an agreed window, in the two-file shape the
  comparator reads. The column shapes are fixed by the tooling and shown here rather than duplicated
  from the fixtures — see [`../src/test/resources/fixtures/shadow/`](../src/test/resources/fixtures/shadow/)
  for worked examples:

  | File | Header |
  | --- | --- |
  | `transactions.csv` | `seq,owner,req,amount,currency` |
  | `legacy-responses.csv` | `seq,owner,retcode,balance` |

  Both follow the delimited conventions of Step 1: UTF-8, LF, a header line, comma-delimited with
  RFC 4180 quoting, NULL as an empty unquoted field, decimals as plain two-place text. `req` is the
  legacy single-character request code (`A`, `Q`, `U`, `X`, `C`, `D`); `retcode` is the ten-character
  legacy return field as the caller received it.

  The comparator joins the two files on `seq` together with the normalized owner key, never on file
  order. EBCDIC and UTF-8 collate differently, so ordinal position is not a usable join key across an
  exported stream.

- **`tool.rate-source=legacy-table`** for the parity gate. This is the default, and the reason is that
  **parity must be judged on identical inputs**: expected values are computed from the staged legacy
  rate rows with the legacy arithmetic, so a disagreement means a behavioural difference rather than a
  rate that moved between the capture and the replay. With `tool.rate-source=live` the live lookup is
  used instead and a difference the rate difference fully explains is recorded as a `RATE_SOURCE`
  variance with status `ACCEPTED_EXCEPTION` — informative, but not a parity measurement.
- **The legacy system remains the system of record, and broker still points at it.**
  `cashAccount.enabled` stays `false`; no caller reaches the new service in this step.
- **The comparator runs against the rehearsal schema**, reached the same way as in Step 1
  (`--spring.datasource.hikari.schema=cash_account_rehearsal`), reloaded from the latest accepted
  export so the replay starts from the balances Step 1 signed off.
- **An agreed window definition and an agreed number of consecutive clean windows**, recorded before
  the first window runs. Deciding "how many is enough" after seeing the results is not a gate.

### Actions

One invocation per window, each with its own batch id:

```bash
java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=tool \
     --spring.datasource.hikari.schema=cash_account_rehearsal \
     --tool.command=shadow-compare \
     --tool.input=<window-dir> \
     --tool.batch-id=<uuid> \
     --tool.rate-source=legacy-table
echo "shadow-compare exit=$?"
```

`<window-dir>` holds that window's `transactions.csv` and `legacy-responses.csv`. The comparator
replays each transaction through the service layer directly and writes a `migration_reconciliation`
row for every disagreement, summarized by the window's `migration_run`.

Windows are run **in capture order**, and a window is reviewed before the next one runs. A defect found
in window *n* invalidates the clean windows after it, so running ahead of the review only creates work
to discard.

### Evidence to capture

```sql
SELECT r.run_id, r.batch_id, r.source_path, r.status,
       r.legacy_record_count, r.migrated_record_count, r.variance_count,
       r.started_at, r.finished_at
  FROM cash_account_rehearsal.migration_run r
 WHERE r.mode = 'SHADOW'
 ORDER BY r.started_at;
```

```sql
SELECT v.owner, v.variance_kind, v.status,
       v.legacy_value, v.migrated_value,
       v.legacy_balance, v.migrated_balance, v.variance
  FROM cash_account_rehearsal.migration_reconciliation v
  JOIN cash_account_rehearsal.migration_run r ON r.run_id = v.run_id
 WHERE r.batch_id = '<uuid>'
 ORDER BY v.variance_kind, v.owner;
```

| Item | What it is |
| --- | --- |
| `step2-windows.txt` | One `migration_run` row per window, in capture order, with `variance_count` and exit code |
| `step2-review.txt` | Every `RATE_SOURCE` and `REJECTED_BY_TARGET` row, each either accepted with a written reason or traced to a defect with its defect reference |
| `step2-window-definition.txt` | The agreed window boundaries and the agreed number of consecutive clean windows, recorded before the first window ran |

The pass condition is **zero `VARIANCE` rows across the agreed number of consecutive windows.**
`RATE_SOURCE` and `REJECTED_BY_TARGET` rows are not automatically failures and are not automatically
passes either: each one is reviewed individually. A `REJECTED_BY_TARGET` row is the expected shape of a
deliberate behavioural improvement — the legacy program stored the absolute value of a negative result
where this service answers `422 INSUFFICIENT_FUNDS` — and confirming that is what the review is for.

### Sign-off required

**Product owner and risk**, jointly. Product owner for the behavioural result; risk because the
accepted exceptions recorded here are the documented differences between the two systems' answers, and
those differences outlive this step.

### Rollback criterion

Roll back on **any `VARIANCE` row not explained within that window's review**, or on **any target defect
found by the review**, however small the variance.

Action: stop the comparator and perform the Step 1 reset —

```sql
DROP SCHEMA cash_account_rehearsal CASCADE;
CREATE SCHEMA cash_account_rehearsal;
```

— then reload from the latest accepted export and restart the window sequence from the first window.
The count of consecutive clean windows restarts at zero: a defect fixed mid-sequence changes the
behaviour the earlier windows measured, so those measurements no longer describe the system under test.

---

## Step 3 — Controlled cutover

The only step that changes what a caller reaches. It runs inside a change window, as seven gated
actions in a fixed order, and it is the last point at which rollback is cheap.

### Preconditions

- **Steps 0, 1 and 2 signed off**, with their evidence attached to the change record.
- **The platform operator holds the change window** and the authority to restart broker and portfolio.
- **The mainframe team holds the CICS transaction-disable authority** and has the replay procedure of
  the rollback regime below in hand *before* the window opens. A rollback that first has to negotiate
  who may replay a file is not a rollback.
- **The production schema (the default search path) is empty of `cash_account` rows.** The rehearsal
  schema is not the production schema, and the final load must be into a known-empty target so that
  gate (b) measures the export rather than the sum of the export and an earlier attempt:

  ```sql
  SELECT count(*) AS cash_account_rows FROM cash_account;
  ```

- **No `HELD` reservation anywhere**, and therefore no reserved balance. This is a precondition of the
  *rollback*, and it is checked here because the rollback may have to start at any moment after gate
  (e): the replay file carries only an available balance, and legacy has no reservation concept, so a
  held amount would simply vanish from a hand-back. See the reservation precondition under
  [Rollback criterion](#rollback-criterion-3).
- **A scheduled reconcile is in place** for the post-cutover window, with its own batch ids, because
  one of the rollback criteria is a variance in a *scheduled* reconcile rather than in an ad-hoc one.

### Actions

Seven gated actions, in order. Each gate is a go/no-go decision: on a no-go, stop and apply the
rollback regime that matches where you are — the two regimes differ, and which one applies is decided
by whether gate (e) has been applied.

1. **(a) Freeze legacy writes.** A mainframe-operator action: disable the CICS transaction that fronts
   the program, so no write can enter the legacy tables after the export that gate (b) loads.

   *Gate:* the transaction is confirmed disabled and the mainframe operator has recorded the time.
   Without the freeze the final export is a moving target and gate (b) can never be clean.

2. **(b) Final load into the production schema.** Take a **fresh** legacy export exactly as in Step 1
   (unload, REPRO, transfer, checksum), then load and reconcile under a **new** batch id, with no
   `--spring.datasource.hikari.schema` override so the production search path is used:

   ```bash
   BATCH_ID=<uuid>
   java -jar /deployments/app.jar \
        --spring.profiles.active=tool \
        --tool.command=load \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID" \
        --tool.history-record-length=<57-or-100> \
        --tool.legacy-charset=<region-ccsid> \
        --tool.legacy-timezone=<region-zone>
   echo "load exit=$?"

   java -jar /deployments/app.jar \
        --spring.profiles.active=tool \
        --tool.command=reconcile \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID"
   echo "reconcile exit=$?"
   ```

   *Gate:* `variance_count = 0` on the reconcile run, **zero** `migration_reconciliation` rows with
   status `VARIANCE`, and the Step 0 catalog query re-run with the `cashaccount` rows byte-identical to
   `step0-catalog-baseline.txt`.

3. **(c) Validate before routing.** For every owner in the final legacy export, read the institutional
   account view and compare its total with the exported balance. No caller is routed yet, so the target
   is **static** — which is what makes an exhaustive comparison both possible and conclusive here, and
   impossible ten minutes later. Reach the service directly rather than through broker, because broker
   still points at the legacy system:

   ```bash
   kubectl port-forward svc/<release>-cash-account-service 8080:8080 -n <namespace> &
   curl -s -H "Authorization: Bearer <token>" \
        http://localhost:8080/cash-account/institutional/accounts/<OWNER>
   ```

   The response carries `availableBalance`, `reservedBalance` and `totalBalance`.

   *Gate:* for every owner, `totalBalance` equals the exported balance and `reservedBalance` is `0.00`;
   the owner set matches the export exactly, with no extra and no missing owner.

4. **(d) Record the ledger watermark.**

   ```sql
   SELECT COALESCE(MAX(entry_id), 0) AS w FROM ledger_entry;
   ```

   Store the value as `W` **with the Step 0 snapshot**, in the change record. `W` is the boundary
   between "state the migration put here" and "state a caller put here", and every later judgement —
   the scheduled reconcile's adjudication and the rollback's replay range — is expressed relative to
   it. It has to be read before gate (e): once callers are writing, `MAX(entry_id)` keeps moving and no
   longer marks the migration boundary, and nothing else in the database records where that boundary was.

   *Gate:* `W` is recorded in the change record, not only in a terminal.

5. **(e) Apply the cutover value set.** This is the action that routes traffic. Apply it against the
   Step 0 snapshot, not against the chart defaults:

   | Value | Set to | Why |
   | --- | --- | --- |
   | `cashAccount.enabled` | `true` | Deploys the service and sets `CASH_ACCOUNT_ENABLED` for broker and portfolio (`…/templates/broker.yaml:L103-L104`; `…/templates/cash-account.yaml:L15`) |
   | `cashAccount.image.repository` / `cashAccount.image.tag` | The registry path and the **digest** recorded in Step 0 | The digest is the only reference that cannot be re-pointed after validation |
   | `cashAccount.url` | `http://{{ .Release.Name }}-cash-account-service:8080/cash-account` — the chart default — **if the snapshot differs** | This is the path the controllers are mapped at; broker reads it as `CASH_ACCOUNT_URL` (`…/templates/broker.yaml:L97-L102`) |
   | `cashAccount.exchangeRateUrl` | Unchanged | Reaches the service as `CURRENCY_API_URL` (`…/templates/cash-account.yaml:L156-L160`) |
   | `database.*` | **Not changed by this cutover** | The release is already on PostgreSQL as a Step 0 prerequisite, signed off separately, because these values are shared with portfolio |
   | `vault.enabled` | Remains `false` | The enabled branch injects Liberty container arguments a Spring Boot image cannot execute |

   No chart **template** is edited, at this gate or any other.

   *Gate:* the applied values diff against the snapshot contains **only** the rows above, and the new
   pod passes its startup, readiness and liveness probes (`/actuator/startup`,
   `/actuator/health/readiness`, `/actuator/health/liveness` on port 8080 —
   `…/templates/cash-account.yaml:L204-L222`).

6. **(f) Roll broker and portfolio** so they pick up `CASH_ACCOUNT_ENABLED` and `CASH_ACCOUNT_URL`.
   Both read them from the environment at start-up, so an applied value that nothing restarted has
   changed nothing.

   *Gate:* both deployments are fully rolled and healthy, and the new pods' environment shows the
   intended values.

7. **(g) Confirm routing, then observe the first scheduled reconcile.** One read through broker for an
   owner whose balance is known from the final export:

   ```bash
   curl -s -H "Authorization: Bearer <token>" http://<broker-host>:9080/broker/<OWNER>
   ```

   The `cashAccountBalance` and `cashAccountCurrency` fields in broker's response are populated from
   this service's `balance` and `currency`, so a correct value proves the whole path.

   Then let the first scheduled reconcile run and adjudicate its output against `W`. The reconcile
   compares the frozen legacy export with the target as it is **now**; callers are writing, so a raw
   comparison would flag every legitimate post-routing write as a variance. `W` is the adjudication
   rule: a variance row is genuine only if the owner has no ledger activity above the watermark.

   ```sql
   SELECT v.owner, v.variance_kind, v.status,
          v.legacy_balance, v.migrated_balance, v.variance,
          count(l.entry_id) AS post_watermark_entries
     FROM migration_reconciliation v
     JOIN migration_run r ON r.run_id = v.run_id
     LEFT JOIN ledger_entry l ON l.owner = v.owner AND l.entry_id > <W>
    WHERE r.batch_id = '<uuid>'
      AND v.status = 'VARIANCE'
    GROUP BY v.owner, v.variance_kind, v.status,
             v.legacy_balance, v.migrated_balance, v.variance
    ORDER BY v.owner;
   ```

   *Gate:* every row returned has `post_watermark_entries > 0` — that is, every variance is explained
   by a caller write above the watermark. A row with `post_watermark_entries = 0` is a genuine variance
   and a rollback criterion.

### Evidence to capture

| Item | What it is |
| --- | --- |
| `step3-freeze.txt` | The time the CICS transaction was disabled and by whom |
| `step3-export.sha256` | Checksums of the fresh final export |
| `step3-migration-run.txt` | The `migration_run` and `migration_reconciliation` rows for gate (b)'s batch, and both exit codes |
| `step3-catalog-after.txt` | The catalog query re-run at gate (b), diffed against the Step 0 baseline |
| `step3-validation.txt` | Gate (c): the per-owner comparison of `totalBalance` against the exported balance, with the owner-set comparison |
| `step3-watermark.txt` | `W`, stored with the Step 0 snapshot |
| `step3-values-diff.txt` | The applied values diffed against `step0-values-snapshot.yaml` / `step0-cr-snapshot.yaml` |
| `step3-routing.txt` | The broker read from gate (g) and the adjudicated scheduled-reconcile output |

### Sign-off required

- **Platform operator and cash-account data owner** at gates **(b)** and **(c)** — the data gates. Both
  sign before gate (e) is applied.
- **Product owner** after gate **(g)**, on the confirmed routing and the adjudicated reconcile.

### Rollback criterion

**Two regimes, and they are not variations of each other.** Which one applies is decided by one
question: has gate (e) been applied?

#### Before (e) — nothing has changed for callers

Unfreeze legacy writes and **apply nothing**. No caller ever reached the new service, the legacy tables
are exactly as the freeze left them, and the loaded target data is inert. Leave the loaded rows in
place or clear the schema at leisure; neither choice affects a caller.

#### After (e) — a state hand-back, not a repoint

The target has accepted writes, so those writes exist nowhere else. Restoring routing without handing
the state back would silently discard them. In order:

1. **Release every `HELD` reservation, and confirm none remain.** Do this **before** the restore
   begins. The replay file carries one absolute available balance per owner and legacy has no
   reservation concept, so a held amount would vanish from the hand-back; settlements must already be
   reflected in `available_after`.

   ```sql
   SELECT reservation_id, owner, amount, state, expires_at
     FROM cash_reservation
    WHERE state = 'HELD';

   SELECT owner, reserved_balance FROM cash_account WHERE reserved_balance <> 0;
   ```

   ```bash
   kubectl port-forward svc/<release>-cash-account-service 8080:8080 -n <namespace> &
   curl -s -X POST -H "Authorization: Bearer <token>" \
     http://localhost:8080/cash-account/institutional/reservations/<reservationId>/release
   ```

   Both queries must return no rows before step 2 below. The operator sees the same condition as
   `reservedBalance` in `GET /cash-account/institutional/accounts/{owner}`. Release is idempotent by
   state, so a reservation released twice answers `200` with its current state rather than failing —
   which is what makes "release everything, then re-run the queries" a safe loop.

2. **Restore the snapshot values and roll broker and portfolio.** Restore
   `step0-values-snapshot.yaml` (or the CR snapshot) **verbatim**, then roll both callers so they read
   the restored environment.

   **The trap, stated plainly: `cashAccount.enabled: false` alone does not restore legacy routing.**
   It stops the new service from being deployed and sets broker's `CASH_ACCOUNT_ENABLED` to `false`,
   which disables the cash path rather than pointing it back at the legacy system. What restores legacy
   routing is the snapshot's own `CASH_ACCOUNT_URL` and enablement — which is the entire reason Step 0
   captures the live snapshot instead of trusting the repository's defaults.

3. **Confirm quiescence.** No new `ledger_entry` row for 60 seconds:

   ```sql
   SELECT (SELECT count(*) FROM ledger_entry
            WHERE recorded_at > now() - interval '60 seconds') AS recent_entries,
          (SELECT COALESCE(max(entry_id), 0) FROM ledger_entry)  AS high_water;
   ```

   Run it twice, 60 seconds apart: `recent_entries` must be `0` both times and `high_water` must be the
   same value in both. The two columns are deliberately scoped differently — the count is windowed and
   the high-water mark is table-wide — because a windowed maximum would read `0` the moment the window
   is empty and would look like quiescence regardless. Deriving a replay file from a range that is
   still growing produces a file that is already wrong when it is replayed.

4. **Export the closed range above the watermark and checksum it.**

   ```bash
   psql -h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 -q \
        > step3-ledger-above-watermark.csv <<'SQL'
   COPY (SELECT entry_id, owner, incarnation_id, event_type, amount, currency,
                available_after, reserved_after, reservation_id, order_reference,
                source, recorded_at
           FROM ledger_entry
          WHERE entry_id > <W>
          ORDER BY entry_id)
     TO STDOUT WITH (FORMAT csv, HEADER true);
   SQL
   ```

   ```bash
   sha256sum step3-ledger-above-watermark.csv | tee -a step3-rollback.sha256
   ```

   `TO STDOUT` rather than a server-side `COPY … TO '<path>'`: the latter needs file-write privileges
   in the database and would land the file on the database host, whereas the operator needs it — and
   its checksum — where they are working. Substitute `<W>` with the recorded watermark before running;
   the quoted heredoc passes it through untouched, so an unsubstituted placeholder fails loudly instead
   of expanding to nothing.

   This export is the audit record of everything the target accepted while it was live. It is evidence
   in its own right, independent of the replay file derived from it.

5. **Derive the absolute-state replay file.** The contract is fixed and is asserted in this repository
   by `RollbackReplayFileTest` against
   [`../src/test/resources/fixtures/shadow/rollback-replay.csv`](../src/test/resources/fixtures/shadow/rollback-replay.csv);
   the column list is declared once, in
   `migration/LegacyExportFormat.ROLLBACK_REPLAY_COLUMNS`.

   Header, exactly:

   ```text
   seq,owner,op,balance,currency,first_entry_id,last_entry_id,incarnation_id
   ```

   | Field | Derivation |
   | --- | --- |
   | `seq` | Numbers the output from 1, in the emitted order |
   | `owner` | The owner the line hands back. One line per owner touched after the watermark |
   | `op` | `X` when that owner's **last** ledger row in the range is `ACCOUNT_DELETED`; `A` when the owner did not exist in the final legacy export; otherwise `U` |
   | `balance` | The owner's **absolute** end state — `available_after` from that last row. Never a sum, never a delta |
   | `currency` | `currency` from that same last row |
   | `first_entry_id` / `last_entry_id` | The closed ledger range the line summarizes, inclusive at both ends; equal when the owner has one row in the range |
   | `incarnation_id` | Ties the line to one account life, so a line can never be applied to a different incarnation of the same owner name |

   Ordering is by **ascending `last_entry_id`**, and `seq` simply numbers that order. Every line's
   `reserved_after` must be `0.00`; **a non-zero `reserved_after` makes the derivation fail** rather
   than emit a line for that owner — which is what step 1 above protects against. The file follows the
   delimited conventions of the export format (UTF-8, LF, header line, comma-delimited, two-place
   decimals). **No checksum line is embedded**: the checksum is recorded beside the file in the
   evidence, so the CSV stays free of comment syntax and every line remains a parseable record.

   **Why absolute state, and never `C`/`D`.** Replaying the end state through the legacy `A`/`U`/`X`
   codes reproduces the target's state exactly and is **independent of exchange rates**. A credit/debit
   replay would re-apply the legacy `RATES` join to each amount and land on a different balance whenever
   a rate differed from the one the target used — so it would be a rate-sensitive reconstruction of a
   number that is already known exactly. No rate, and no per-transaction detail, is replayed.

   The query below implements the contract against the production schema. Check its output against the
   table above before handing it to the mainframe team: it is a convenience, and the contract is the
   authority.

   ```bash
   psql -h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 -q \
        > step3-rollback-replay.csv <<'SQL'
   COPY (WITH ranged AS (
            SELECT owner, incarnation_id, entry_id, event_type,
                   available_after, reserved_after, currency
              FROM ledger_entry
             WHERE entry_id > <W>
         ),
         bounds AS (
            SELECT owner, incarnation_id,
                   min(entry_id) AS first_entry_id,
                   max(entry_id) AS last_entry_id,
                   max(reserved_after) AS max_reserved_after
              FROM ranged
             GROUP BY owner, incarnation_id
         )
         SELECT row_number() OVER (ORDER BY b.last_entry_id) AS seq,
                b.owner AS owner,
                CASE WHEN last_row.event_type = 'ACCOUNT_DELETED' THEN 'X'
                     WHEN NOT EXISTS (SELECT 1 FROM ledger_entry m
                                       WHERE m.owner = b.owner
                                         AND m.entry_id <= <W>
                                         AND m.event_type = 'MIGRATION_LOAD') THEN 'A'
                     ELSE 'U' END AS op,
                last_row.available_after::text AS balance,
                last_row.currency AS currency,
                b.first_entry_id AS first_entry_id,
                b.last_entry_id AS last_entry_id,
                b.incarnation_id AS incarnation_id
           FROM bounds b
           JOIN ledger_entry last_row ON last_row.entry_id = b.last_entry_id
          WHERE b.max_reserved_after = 0
          ORDER BY b.last_entry_id)
     TO STDOUT WITH (FORMAT csv, HEADER true);
   SQL
   ```

   Two notes on that query. The `A` case tests for the absence of a `MIGRATION_LOAD` row at or below
   `W`: gate (b) writes exactly one such row per loaded owner, so its absence *is* "the owner did not
   exist in the final legacy export", read from the ledger instead of from a file. And the
   `WHERE b.max_reserved_after = 0` filter is the guard, not an optimization — if it drops a line, the
   derivation has **failed** for that owner and the run must stop, because a silently missing owner is
   an owner whose money is not handed back. Verify the count:

   ```sql
   SELECT count(DISTINCT (owner, incarnation_id)) AS owners_in_range
     FROM ledger_entry WHERE entry_id > <W>;
   ```

   It must equal the number of data lines in `step3-rollback-replay.csv`. Checksum the file and record
   the checksum beside it:

   ```bash
   sha256sum step3-rollback-replay.csv | tee -a step3-rollback.sha256
   ```

6. **The mainframe team replays the file** through the existing transaction, one legacy request per
   line, using each line's `op`, `balance` and `currency`. The replay is a mainframe-operator action
   under their own procedure, prepared before the window opened.

7. **Reconcile a fresh legacy export against the target** as of the export watermark.

   *Gate:* zero `VARIANCE` rows. This is the proof that the hand-back landed: the legacy system now
   holds the state the target accepted.

8. **Unfreeze legacy writes.** Only after step 7's gate passes. Unfreezing first would let new legacy
   writes interleave with the replay and make the reconcile unreadable.

#### Rollback criteria, each executable

- **Any `VARIANCE` row in a scheduled reconcile** that the watermark adjudication of gate (g) does not
  explain (`post_watermark_entries = 0`).
- **Any failed gate** in (a)-(g).
- **The error-rate rule**, evaluated from the service's Prometheus scrape (`/metrics`):

  ```promql
  sum(rate(http_server_requests_seconds_count{uri=~"/cash-account/.*",status=~"5.."}[5m]))
    / sum(rate(http_server_requests_seconds_count{uri=~"/cash-account/.*"}[5m])) > 0.01
  ```

  Sustained for 10 minutes, the decision resting with the **on-call platform operator**. The rule is
  written on server errors only: `4xx` responses are the fail-closed error model working as designed —
  a `404` for an unknown owner or a `422` for insufficient funds is a correct answer, not a fault — so
  including them would trigger a rollback on correct behaviour.

---

## Step 4 — Decommission

Retirement of the legacy assets. This is the only irreversible step in the sequence, and its
preconditions are hard for that reason.

### Preconditions

- **The data-retention requirement, answered in writing by the requesting organization. There is no
  default, and defaulting one is prohibited.**

  The basis for refusing to default it: **this repository contains no data-retention policy, no
  audit-control matrix and no compliance artifact.** A retention period cannot be derived from anything
  in the codebase — it is an organizational and often regulatory determination about financial records,
  and a period invented here would look like an answer while carrying no authority. The answer must
  cover, at minimum:

  | Question | Why it must be answered before anything is retired |
  | --- | --- |
  | Which artifacts are retained — the DB2 tables, the KSDS, `ledger_entry`, the reservation rows, the reconciliation rows | Determines the scope of the immutable export below |
  | For how long | After the first retirement action the exports are the only copy; the period decides how long that copy must survive |
  | On what medium, in what location, under whose control | An export nobody can locate is not retention |
  | Who may read it, and how access is recorded | Financial records usually carry access constraints of their own |
  | When and how it is disposed of, and who authorizes disposal | Retention without a disposal rule becomes indefinite by accident |

  Until that answer exists in writing, **this step does not begin.** Waiting costs nothing: this step is
  reached only after a cutover that is already complete and stable, with every earlier step's evidence
  attached to its own change record, and nothing decays while the answer is obtained.

- **An immutable export of the final legacy state**, stored as the retention answer requires:
  `UNLOAD` of `STOCKTRD.CASHACCOUNTY` and `STOCKTRD.FRANKFURT1` and `REPRO` of
  `SYSD.STOCK.HISTORY`, exactly as in Step 1, with checksums recorded.

- **An export of the target's audit state**, covering the ledger and the rows that reference it:

  ```bash
  PG="-h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 -q"

  psql $PG -c "COPY (SELECT * FROM ledger_entry ORDER BY entry_id) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-ledger-entry.csv
  psql $PG -c "COPY (SELECT * FROM cash_reservation ORDER BY created_at, reservation_id) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-cash-reservation.csv
  psql $PG -c "COPY (SELECT * FROM cash_account ORDER BY owner) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-cash-account.csv
  ```

  ```bash
  sha256sum step4-ledger-entry.csv step4-cash-reservation.csv step4-cash-account.csv \
    | tee step4-target-export.sha256
  ```

- **The agreed post-cutover rollback window has elapsed** with clean scheduled reconciles throughout.
  The window is what makes Step 3's hand-back available; retiring the legacy assets ends it, so the
  window must be over rather than merely uneventful so far.

### Actions

Performed by the **mainframe operator**, in this order, each only after the previous is confirmed
complete. The order follows the dependency chain — a plan references packages, packages reference
tables — so that nothing is left bound to something that no longer exists.

1. **Retire the CICS transaction** that fronts the program, and its program definition. The freeze in
   Step 3(a) disabled it; this removes it. `DB2BIND.jcl` records that the package was bound
   `ENABLE(BATCH,CICS)` (`backend/cash-account-cobol/DB2-DDL/DB2BIND.jcl:L26`), which is why the CICS
   definition is the first thing to go: while it exists, a transaction can still be enabled by mistake.

2. **Free the DB2 plan, then the package.** The plan's `PKLIST` names the package collection
   (`backend/cash-account-cobol/DB2-DDL/DB2BIND.jcl:L31`), so freeing the plan first never leaves a
   bound plan pointing at a missing package:

   ```jcl
   //FREEPKG  JOB (ACCT#),'FREE STOCK TRADER',NOTIFY=&SYSUID,CLASS=A,
   // MSGCLASS=H,MSGLEVEL=(1,1),SCHENV=<wlm-env>
   //FREE     EXEC PGM=IKJEFT01,DYNAMNBR=20
   //STEPLIB  DD  DSN=SYS1.DSND00A.SDSNLOAD,DISP=SHR
   //SYSPRINT DD  SYSOUT=*
   //SYSTSPRT DD  SYSOUT=*
   //SYSTSIN  DD  *
   DSN SYSTEM(<db2-ssid>)
   FREE PLAN (STOCKPL)
   FREE PACKAGE (STOCKTRD.ACCT01.(*))
   END
   /*
   ```

   `STOCKPL` is the plan (`…/DB2BIND.jcl:L30`); the package is `ACCT01` in collection `STOCKTRD`
   (`…/DB2BIND.jcl:L16` and `L19`). Confirm the scope against the real catalog before submitting: the
   plan's `PKLIST` is `NULLID.*, *.STOCKTRD.*`, which is broader than this program, and only the
   catalog says what else is bound into that collection.

3. **Drop the DB2 tables**, after the export above is stored and its checksum verified:

   ```sql
   DROP TABLE STOCKTRD.CASHACCOUNTY;
   DROP TABLE STOCKTRD.FRANKFURT1;
   ```

   Both are declared at `backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L62`, in table space
   `STOCKTRD.TSSTAPP`. The table space itself is **not** dropped here: whether anything else occupies
   it is a catalog question, not a question this migration can answer.

4. **Delete the KSDS:**

   ```jcl
   //DELKSDS  JOB (ACCT#),'DELETE HISTORY',NOTIFY=&SYSUID,CLASS=A,
   // MSGCLASS=H,MSGLEVEL=(1,1)
   //STEPNAME EXEC PGM=IDCAMS
   //SYSPRINT DD   SYSOUT=*
   //SYSIN    DD   *
       DELETE SYSD.STOCK.HISTORY CLUSTER
   /*
   ```

   The cluster is defined at `backend/cash-account-cobol/VSAM/DEFKSDS.jcl:L9`, with its data and index
   components at `L15-L16`; deleting the cluster removes both.

The JCL and DDL files cited in this step — `backend/cash-account-cobol/DB2-DDL/DB2BIND.jcl`,
`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl` and `backend/cash-account-cobol/VSAM/DEFKSDS.jcl` —
are **read-only characterization sources.** They are cited to identify what is being retired and are
never modified, here or anywhere else in this migration. Nothing under
`backend/cash-account-cobol/` is edited.

### Evidence to capture

| Item | What it is |
| --- | --- |
| `step4-retention-answer.*` | The written retention requirement from the requesting organization, as received |
| `step4-legacy-export.sha256` | Checksums of the final `UNLOAD` and `REPRO` exports |
| `step4-target-export.sha256` | Checksums of the `ledger_entry`, `cash_reservation` and `cash_account` exports |
| `step4-retention-location.txt` | Where each retained artifact is stored, under whose control, and its expiry date per the answer above |
| `step4-retirements.txt` | Per-asset retirement confirmation, in execution order with timestamps: CICS transaction, plan, package, each table, the cluster |
| `step4-reconciles.txt` | The clean scheduled reconciles across the elapsed rollback window |

### Sign-off required

**The cash-account data owner, risk/compliance and the platform owner, jointly**, recorded **before the
first retirement action.** Joint and prior, not sequential and not after: each party is signing that
the retention answer is satisfied from their own angle, and after the first retirement action there is
nothing left to withhold consent from.

### Rollback criterion

Rollback is possible **only until the first retirement action.** Before it, rollback is Step 3's
post-(e) procedure, unchanged and still available.

**After the first retirement action, decommission is irreversible.** The immutable exports are then the
**sole** recovery source: the CICS transaction, the plan, the package, the tables and the KSDS no longer
exist to hand state back to, and no sequence of steps in this document recreates them. That is exactly
why the written retention answer and the elapsed rollback window are hard preconditions rather than
recommendations — they are the only two things standing between an orderly retirement and an
unrecoverable one.

---

## Pending sign-offs

The acceptance criteria below can be closed **only by this hand-off**. They are recorded here as
pending, each against the step that closes it.

| Criterion | Closed by | Status |
| --- | --- | --- |
| Legacy balances migrated with zero variance against real DB2 for z/OS and VSAM data | Step 1, on the cash-account data owner's acceptance of every variance row | **Pending** |
| Dual-run clean for the agreed number of consecutive windows | Step 2, on the product owner's and risk's joint sign-off | **Pending** |
| Cutover completed and routing through broker confirmed | Step 3, on the platform operator's and data owner's gate sign-offs and the product owner's post-(g) sign-off | **Pending** |
| Legacy assets retired under an answered retention requirement | Step 4, on the joint data-owner / risk / platform-owner sign-off recorded before the first retirement action | **Pending** |

**Nothing in this repository claims any of them as achieved.** No connection has been made to DB2 for
z/OS or to `SYSD.STOCK.HISTORY`; no dual-run has been performed against the live legacy system; no
deployment value has been changed and broker still points where it always pointed; no asset has been
retired and no retention action has been taken.

The mechanisms these criteria exercise — the export readers, the loader, the reconciler, the shadow
comparator and the rollback derivation — are **proven against fixtures only**, under
[`../src/test/resources/fixtures/`](../src/test/resources/fixtures/). Matched fixtures reconcile with
zero variance and seeded fixtures flag exactly the seeded rows; that is evidence about the tooling, and
it is not evidence about any real data set.

## Referenced documents and fixtures

| Reference | What it carries |
| --- | --- |
| [`../README.md`](../README.md) | The build → scan → push → digest path, the environment↔property map, the tooling command reference, the chart values consumed, and the open items |
| [`../README.md#image-build-scan-and-push`](../README.md#image-build-scan-and-push) | The exact image path Step 0 requires, including the `docker inspect` digest capture |
| [`legacy-characterization.md`](legacy-characterization.md) | The legacy behaviour, cited to `file:line`. §10 **Acceptance** carries the `Status` field that Step 1's `characterization_status` gate reads; §9.5 is the record-length open item; §9.6 the code page and time zone; §9.7 the `cyrrnbase` / `CURRNBASE` spelling |
| [`../src/test/resources/fixtures/legacy-export/`](../src/test/resources/fixtures/legacy-export/) | The export shapes Steps 1 and 3 must produce — `cashaccounty.csv`, `frankfurt1.csv`, `history.csv`, `history.cp037.bin` — in matched and seeded-mismatch variants, each with a `MANIFEST.md` |
| [`../src/test/resources/fixtures/shadow/`](../src/test/resources/fixtures/shadow/) | The Step 2 stream shapes (`transactions.csv`, `legacy-responses.csv`) and `rollback-replay.csv`, the worked example of the Step 3 replay-file contract |

Chart values and templates referenced throughout — `values.yaml`, `templates/cash-account.yaml`,
`templates/broker.yaml` under `infra/stocktrader-operator/helm-charts/stocktrader/` — are **read and
cited, never edited by this migration.** The cutover is a values change applied by the platform
operator against the Step 0 snapshot; a chart **template** change is a stop-and-flag condition, not a
runbook step.
