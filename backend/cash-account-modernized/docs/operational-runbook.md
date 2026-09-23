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
| Platform operator | The change window; the Helm/operator values and the StockTrader CR; broker and portfolio restart authority; the on-call rollback decision; custody of the sealed values/CR snapshot and every other release-configuration artifact of the restricted class |
| Mainframe operator | CICS transaction enable/disable; DB2 utility and IDCAMS execution; the `FREE`/`DROP`/`DELETE` retirement actions; the replay of a rollback file through the existing transaction |
| Cash-account data owner | Acceptance of every reconciliation variance; the catalog baseline; the final load and pre-routing validation; custody of every owner-level artifact of the restricted class, and the party who reads them |
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
  | `<registry>`, `<version>`, `<digest>` | The image registry path, the version tag being promoted, and the image digest Step 0 records |
  | `<host>`, `<port>`, `<database>`, `<id>` | PostgreSQL connection details, from the `database.*` values. The **password is never a placeholder** — it is supplied as [Operator command safety](#operator-command-safety) describes |
  | `<uuid>` | A fresh batch id, one per runbook step |
  | `<W>` | The ledger watermark recorded in Step 3(d) |
  | `<dir>`, `<window-dir>` | The directory holding that run's export or shadow files |
  | `<export-pvc>`, `<job-name>` | The read-only volume claim that carries the frozen final export the scheduled reconcile of Step 3 reads, and the name of one of that schedule's jobs |
  | `<n>` | The sequence number of a repeated evidence file — `step3-reconcile-1.txt`, then `-2`, and so on |
  | `<restricted-dir>`, `<evidence-store-locator>`, `<custodian>` | The 0700 local staging directory restricted-class evidence is produced in, the store locator a pointer line names, and the custodian role from [Roles](#roles) — all three fixed by the evidence-handling determination, see [Evidence handling and classification](#evidence-handling-and-classification) |
  | `<OWNER>`, `<reservationId>` | Named in prose only. In a command they arrive as **data** from a file, never substituted into it — see [Operator command safety](#operator-command-safety). A bearer token is likewise never substituted; it reaches `curl` from a protected config file |
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
- **Evidence register.** Every item listed under *Evidence to capture* is **registered** for its step —
  named `step<N>-<item>`, with the class its table's **Class** column gives it — before that step's
  sign-off is requested. Registering and attaching are not the same act: a change-record-class item is
  attached to the change record, while a restricted-class item is sealed into the restricted evidence
  store and only its pointer line is attached. Which is which, and why the distinction is not
  negotiable per item, is [Evidence handling and
  classification](#evidence-handling-and-classification). Evidence captured after a sign-off is not
  evidence for it.
- **Schema qualification.** Steps 1 and 2 qualify every relation with `cash_account_rehearsal.`,
  because the rehearsal schema is deliberately not on the default search path — an unqualified query
  there would silently read production. Steps 3 and 4 run against the default search path, which by
  then is where the live tables are. Either form may be replaced by
  `SET search_path TO cash_account_rehearsal;` once per session, but not by relying on a default.
- **Operator command safety.** Secrets and caller-supplied values are never typed into a command line.
  The two mechanisms are defined once, immediately below, and every step that needs one uses them.

### Operator command safety

A command line is neither private nor inert, which is why neither a secret nor an owner is ever
written into one.

- Its `argv` is readable by every process on the host for as long as the command runs (`ps -o args=`,
  and `/proc/<pid>/cmdline`, which is mode `-r--r--r--`), and an interactive shell writes the line
  verbatim into `HISTFILE`.
- A pasted value is *interpreted*, not passed. `JDBC_PASSWORD=p@ss w;rd$1` runs `rd` as a command,
  expands `$1` to nothing, and leaves the tool authenticating as `p@ss` — a failure that looks like a
  wrong password rather than a mangled one.
- An owner the SERVICE accepts is 1 to 32 characters of `A-Z 0-9 . _ -` (`OwnerNormalizer`), so it is
  shell-safe by construction. An owner in a LEGACY EXPORT is not: the DB2 column is `CHAR(32)` with no
  character restriction (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47`), so `/`, `;`, `&`, `?`,
  `#`, `%`, a quote and even an embedded newline can all appear in an export row, in a captured shadow
  line, and in the `owner` column of the reconciliation findings that refuse them. Every rule below
  therefore still applies to an owner read out of legacy data — which is most of the owners an
  operator handles in Steps 1 and 2.

**Secrets — database passwords and bearer tokens — come from a 0600 file.** Write it with the shell's
own `printf`, which is a builtin and therefore never becomes another process's `argv`:

```bash
umask 077                                       # every file created below is 0600
IFS= read -rsp 'JDBC_PASSWORD: ' CA_PW; echo
printf 'JDBC_KIND=postgres\nJDBC_HOST=<host>\nJDBC_PORT=<port>\nJDBC_DB=<database>\nJDBC_ID=<id>\nJDBC_PASSWORD=%s\n' "$CA_PW" > ca-db.env
unset CA_PW
```

```bash
# A container reads that file directly: docker parses KEY=VALUE literally, with no shell parsing and
# no expansion, so a password containing shell metacharacters arrives byte-for-byte.
docker run --env-file ./ca-db.env ...

# A shell loads the same file without interpreting any value. `set -a; . ca-db.env` would NOT do:
# sourcing is shell parsing, and the password above would execute rather than load.
while IFS='=' read -r k v; do [ -n "$k" ] && export "$k=$v"; done < ca-db.env
export PGPASSWORD="$JDBC_PASSWORD"              # psql reads it from the environment, not from argv
```

```bash
# A bearer token travels in a curl config file, so it reaches no argv and no shell history.
IFS= read -rsp 'Bearer token: ' CA_TOKEN; echo
printf 'header = "Authorization: Bearer %s"\n' "$CA_TOKEN" > ca-auth.conf
unset CA_TOKEN
curl -sS --config ./ca-auth.conf --url "<url>"
```

The token is minted **short-lived and for the change window only**, for a caller holding
`StockTrader`. Every step that creates one of these files destroys it before the step closes, and a
credential that was ever displayed on a terminal is rotated afterwards:

```bash
unset JDBC_PASSWORD PGPASSWORD
shred -u ca-db.env ca-auth.conf 2>/dev/null || rm -f ca-db.env ca-auth.conf
```

**No change-record evidence carries a secret.** What is attached to a change record is read by everyone
who can read the change, so a credential is recorded by where it came from — "the `database.password`
key of the release secret, read at 14:05 by the platform operator" — and never by its value. One
artifact in this document unavoidably embeds credentials, because a rollback has to restore them
byte-for-byte: Step 0's verbatim values/CR snapshot. It is the reason the restricted class exists, it
is sealed rather than attached, and what the change record gets in its place is a redacted structural
derivation and a pointer — see [Evidence handling and
classification](#evidence-handling-and-classification) and [Step 0 action 2](#actions).

The whole environment is never captured and filtered. A denylist (`env | grep -vE 'PASSWORD|TOKEN'`)
passes every secret whose name it did not anticipate — `AWS_SECRET_ACCESS_KEY`, `DOCKER_AUTH_CONFIG`,
a bearer token in `CURL_CONFIG` — and one unanticipated name is one leaked credential. Emit the named
non-secret fields instead, each one chosen deliberately:

```bash
for v in JDBC_KIND JDBC_HOST JDBC_PORT JDBC_DB JDBC_ID AUTH_TYPE JWT_ISSUER JWT_AUDIENCE; do
  printf '%s=%s\n' "$v" "${!v-<unset>}"
done > step1-tool-settings.env
```

`JDBC_ID` is a user name rather than a credential and is deliberately in the list; `JDBC_PASSWORD` is
deliberately not, and neither is anything else absent from it.

**Caller data — owners and reservation ids — is passed as data.** It is read from a file and quoted;
it is never substituted into a command string, and `eval` is never applied to it. Extract the owner
set with a real CSV reader (the export is RFC 4180, so a quoted field may itself contain a comma or a
newline) into a **NUL-delimited** list, because a newline-delimited list cannot carry an owner that
contains a newline:

```bash
umask 077
python3 - "<dir>/cashaccounty.csv" > <restricted-dir>/step3-owners.nul <<'PY'
import csv, sys
with open(sys.argv[1], newline='') as f:
    for row in csv.DictReader(f):
        owner = (row['owner'] or '').strip()
        if owner:
            sys.stdout.write(owner + '\0')
PY
```

```bash
# One path segment, RFC 3986. safe='' encodes '/' as well, so no owner can add a path element:
# A/../ADMIN becomes A%2F..%2FADMIN, and an owner containing a newline becomes LINE1%0ALINE2.
ca_urlencode() {
  python3 -c 'import sys, urllib.parse; sys.stdout.write(urllib.parse.quote(sys.argv[1], safe=""))' "$1"
}
```

Define `ca_urlencode` once in the session (or in a helper file the session sources); every loop below
assumes it. Each consuming loop then reads `while IFS= read -r -d '' owner`, encodes, and puts the
result inside a quoted URL.

That list is itself customer data — it is the set of every owner the legacy system holds — so it is
written under `umask 077` into `<restricted-dir>` and handled as [Evidence handling and
classification](#evidence-handling-and-classification) requires, as is every file a loop over it
produces.

### Evidence handling and classification

What makes an artifact evidence — it came out of the release, or out of the database — is also what
makes publishing it a disclosure. A change record is read by everyone who can read the change:
approvers, auditors, operators of other services, in most estates the whole platform group. Neither
source system has that readership. A value snapshot's secrets are readable in the release only by a
principal who can read the release Secret, which the chart renders from those very values
(`infra/stocktrader-operator/helm-charts/stocktrader/templates/credentials.yaml:L20-L47`); an owner's
balance is readable only through a privilege granted on `cash_account` and `ledger_entry`. Copying
either into the change record grants it to everyone who can read the change — and after the fact, a
disclosure through the evidence register is indistinguishable from a disclosure through the system
itself.

Every item in every *Evidence to capture* table therefore carries a **Class**, decided by what the item
contains and never by what is convenient to attach:

| Class | What it holds | Where it goes |
| --- | --- | --- |
| **Change record** | Counts, checksums, run and batch ids, gate verdicts, exit codes, timestamps, role names, redacted structure | Attached to the change record for its step, named `step<N>-<item>` |
| **Restricted** | Anything carrying a secret — a credential, a token, a key — or customer data: an owner identifier, a balance, a reservation id, an order reference, a ledger row | Sealed into the restricted evidence store and **never attached**; the change record carries a pointer line instead |

A restricted item is still evidence and is still read in full: the cash-account data owner adjudicates
variance rows one at a time, and the platform operator applies the sealed snapshot at gate (e). It is
read **in the store**, by a named role, with the read recorded. A derived file inherits the class of
what it came from — a percent-encoded owner is still an owner, and a diff of two value snapshots still
carries the values that differ — and no item changes class because a reviewer would find it easier to
have attached.

The pointer line is what the change record carries in a restricted item's place, one line per artifact:

```text
step3-validation.tsv  restricted  sha256=<hex>  store=<evidence-store-locator>  custodian=<custodian>  read-by=<named parties>
```

It exists so that a sealed artifact is provably present, unaltered and reachable without being copied:
the checksum ties the sealed bytes to what the step produced, the custodian is a role from the
[Roles](#roles) table, and `read-by` names the parties the determination admits. A store nobody can
name is not retention, and a custodian nobody holds is not access control.

The store is whatever the requesting organization already uses for restricted records, and it must
provide:

- **Encryption at rest**, because the reader this class has to be protected from is a reader of the
  storage rather than a reader of the change.
- **Access by named least-privilege roles taken from the [Roles](#roles) table** — owner-level artifacts
  to the cash-account data owner, release-configuration artifacts to the platform operator and platform
  owner — and to nobody by default.
- **A recorded read**: reader, artifact, time. Without one, an authorized read and an unauthorized copy
  leave the same trace, which is none.
- **Secret material in the organization's secret manager, not in a file store.** A file store's unit of
  access is the file, while a rotation's unit is the credential, and the question after an exposure is
  always "which credentials", never "which files".

Local working copies follow the same discipline as the credential files of [Operator command
safety](#operator-command-safety): created under `umask 077` so they are `0600` from their first byte,
checksummed, sealed, and destroyed before the step closes.

```bash
umask 077                                                   # 0600 at creation, not chmod'ed afterwards
mkdir -p <restricted-dir>                                   # 0700 under the same umask
sha256sum <restricted-dir>/<artifact> | tee -a step<N>-restricted.sha256
# Seal <artifact> into <evidence-store-locator> and confirm it is there, then remove the local copy:
shred -u <restricted-dir>/<artifact> 2>/dev/null || rm -f <restricted-dir>/<artifact>
```

A restricted artifact is never pasted into a ticket comment, a chat message or mail. Each of those
copies it into a system with its own readership and its own retention, and the evidence-handling
determination governs neither.

**Retention and disposal are answered by the requesting organization, in writing.** No period, duration
or default appears anywhere in this document, for the reason standing prohibition 4 gives: this
repository holds no retention policy, so a period written here would read as an answer while carrying
no authority. The written answer must cover the evidence of every step on the same questions as the
[Step 4 precondition](#preconditions-4) — which artifacts are retained, for how long, on what medium
and under whose control, who may read them and how access is recorded, and when and how they are
disposed of and who authorizes disposal — and that step's question table names the Steps 0-3 artifacts
explicitly so they cannot fall outside the answer. Until the answer exists, restricted evidence stays
in the store and nothing is disposed of; the `shred -u` above destroys a *local working copy* whose
sealed original remains, which is hygiene and not a disposal decision.

### Step index

| Step | Name | Executed by | Signed off by |
| --- | --- | --- | --- |
| 0 | Prerequisites | Platform operator | Platform owner; data owner; requesting organization with risk (framework support, deviation row D1) |
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

- **A restricted evidence store, a secret-manager location, and the requesting organization's written
  evidence-handling determination — all three in place before the first artifact of any step is
  captured.** What each must provide is [Evidence handling and
  classification](#evidence-handling-and-classification); the determination is what fixes
  `<restricted-dir>`, `<evidence-store-locator>` and `<custodian>`, names the parties who may read each
  class, and names the access-controlled channel the Step 3 rollback file travels on.

  The ordering is the whole point. Action 2 below captures a file that embeds every credential in the
  release, and Step 1's first query returns owner identifiers and balances; a classification decided
  after the capture is a decision taken after the disclosure it existed to prevent, and no later
  re-filing recalls a value somebody has already read. An absent determination blocks Step 0 exactly as
  an absent `CREATE SCHEMA` identity does.

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
  path is documented, and it is not restated here so the two cannot drift. Three properties of that run
  are Step 0's business rather than the README's, because each one is a gate somebody can skip:

  - **The blocking image scan was evaluated on the exact artifact being promoted.** It fails on any
    CRITICAL or HIGH finding that has a fix available — the single question a promotion can act on,
    namely whether a patched package exists that this image did not take. Without it a stale base is
    invisible: the pin this module shipped before the current one lagged its own release stream by a
    year and carried 64 fixable HIGH findings, ten of them against the JDK, while every step of the
    build path completed cleanly around it.

    A non-zero exit blocks promotion unless the sign-off below carries that finding by name, the reason
    no available version removes it, and the compensating control. Read the two package layers
    separately to know which kind of failure it is: as the module stands the base-image layer passes and
    the shipped-jar layer does not, and the second is the framework-line decision only the requesting
    organization can close. Making the gate pass by suppressing a finding is never the answer — that is
    the state the previous base image was already in, discovered a year late.
  - **The base-image pin was re-checked at promotion rather than inherited from the last build.** A pin
    goes stale by sitting still, so the tag, its manifest-list digest and the OS and JDK package
    versions inside it are re-derived and recorded with the date — the commands are in the README
    section above. A digest that no longer matches its tag's stream head is not itself a failure; not
    knowing which it is means the gate was evaluated against an unknown.
  - **The informational scan's output is recorded in full even when nothing blocked.** Findings with no
    fix available do not gate and must not therefore go unrecorded: the current base carries eleven of
    them, and the record is what makes them re-read at the next promotion instead of forgotten.

- **A vulnerability feed for the dependency scan is provisioned on the promotion runner.** The NVD scan
  of the resolved dependency set cannot run without one: the plugin refuses to contact the NVD 2.0 API
  with no credential, and the JSON 1.1 feeds its older generation downloaded are retired and answer
  `403 Forbidden`. It is a provisioning question rather than a network one, and any **one** of an NVD
  API key, an internal NVD 2.0 API mirror, an internal datafeed mirror, or a pre-populated offline
  cache satisfies it — the four forms, and the property each is passed as, are in
  [Dependency scanning needs a vulnerability feed the pipeline must provision](../README.md#dependency-scanning-needs-a-vulnerability-feed-the-pipeline-must-provision).
  A key is a credential and reaches the runner the way every other secret here does: out of the secret
  manager into a `0600` settings file the run names with `-s`, never as a command argument. An
  environment variable is not the escape it looks like — the shell expands `-DnvdApiKey="$VAR"` before
  `exec`, so the key is in `argv` either way, which is the same trap
  [Operator command safety](#operator-command-safety) describes for every other credential in this
  document.

  **Its absence is not a silent pass.** With no feed there is no NVD report, and the sign-off below must
  say so in as many words, naming the two Trivy scans — the build-input scan and the image scan — as the
  coverage that stood in for it. They are not a formality: the build-input scan resolves the parent POM
  chain and so reports advisories against coordinates the parent *manages* but never ships, which no
  image scan can see, and the image scan is the only view of the base image's own OS packages. Both stay
  in the pipeline after a feed is provisioned. What is not acceptable is a Step 0 signed off as
  "scanned" by somebody who could not have run the scan.

- **A written framework-support determination for the line this service is built on, and with it the
  requesting organization's authorization of the nine dependency overrides.** Spring Boot 3.3.13 is
  past open-source end of life — as is every 3.x line — and 3.3 is the line the plan mandates. The
  module's nine per-artifact overrides close every advisory raised against the Spring, Spring Data,
  Jackson, Logback, Micrometer, Tomcat, pgJDBC and Nimbus artifacts it ships, the single CRITICAL one
  among them; what they cannot reach is the `spring-boot` and `spring-boot-autoconfigure` artifacts,
  whose version **is** the parent's. Three advisories therefore ship: CVE-2026-22733
  (authentication bypass under the actuator CloudFoundry endpoints, CVSS 8.1, fixed 3.5.12/4.0.4),
  CVE-2026-40973 (3.5.14/4.0.6) and CVE-2026-41001 (3.5.15/4.0.7). Each is unreachable in this
  deployment — `anyRequest().denyAll()` covers that whole path space, the CloudFoundry actuator
  auto-configuration never activates without `VCAP_APPLICATION`, and only `health`, `startup` and
  `prometheus` are exposed — and none is patchable while the line is frozen. Both halves of that
  statement are in
  [what the nine cannot reach](../README.md#what-the-nine-cannot-reach-and-the-controls-that-stand-in-their-place),
  and a control standing in for a patch is precisely what needs a decision rather than a note.

  The determination is one of three, in writing: **stay** on 3.3.13 with those controls and the
  residual risk accepted; **buy** commercial support for the line; or **authorize a later minor line**,
  in which case the module is rebuilt, re-gated with `./mvnw -B clean verify` and re-scanned before
  Step 1 begins, and the two deprecated-for-removal call sites the current overrides create
  (`config/SecurityConfig.java`'s `addObjectPostProcessor`, `error/ApiExceptionHandler.java`'s
  `MethodValidationResult.getAllValidationResults`) are rewritten as part of that move, not after it.

  It belongs in Step 0 for the same reason the image digest does: it is a decision about the artifact
  every step below deploys, taken against the scan evidence this step already captures. Taken later it
  would be taken with traffic on the line. Row **D1** of the README's
  [authorization record](../README.md#authorization-record) is collected here too, since Step 0 is
  already where the sign-offs are gathered — and D1 and this determination answer the same question
  from opposite ends: D1 authorizes the versions that moved, this determination decides what happens
  about the two artifacts that could not.

- **A database identity with `CREATE SCHEMA`** for the rehearsal schema Step 1 needs, and the
  connection details for the store above.

- **A decision, in writing, on which database-identity posture the release runs** — and it is a
  decision rather than a default, because the weaker of the two is what a chart deployment starts with.

  The chart renders **one** database identity into the pod (`database.id` / `database.password`,
  `…/templates/cash-account.yaml:L110-L119`). With `spring.sql.init.mode` at its `always` default the
  pod applies `schema/cash-account-schema.sql` itself, so that same identity **owns** `ledger_entry` —
  and in PostgreSQL `DROP`, `ALTER` and `ALTER TABLE … DISABLE TRIGGER` follow ownership rather than a
  grantable privilege. Measured on PostgreSQL 12.22 as that identity, `DROP TRIGGER
  ledger_entry_immutable ON ledger_entry` succeeds and a following `UPDATE ledger_entry SET amount = 0`
  rewrites an audit row. The append-only ledger is the audit record this whole migration is reconciled
  against, so the credential that serves requests being able to rewrite it is a posture somebody must
  choose knowingly. The two admissible choices:

  1. **Split identities (recommended).** The DDL-owning role applies the script once; the pod runs as a
     runtime role that owns nothing and holds only `SELECT`, `INSERT`, `UPDATE`, `DELETE`; and
     `SPRING_SQL_INIT_MODE=never` is set on the Deployment. The role's grants and the refusals it then
     receives are in [Hardened production posture](../README.md#schema-application) — that is the only
     place they are written, so the two cannot drift. **This needs a second secret key in the chart**
     (the runtime role's credentials), which is a chart-owner change: this module may not make it
     (AAP 0.3.4), so the change must be in place before this option can be selected.
  2. **Single identity, with the residual accepted in writing** by the cash-account data owner. The
     compensating control is real but partial: the script is idempotent, so the next start-up
     re-creates both guards and the exposure is a window rather than a permanent loss; and the
     application itself has no code path that can `UPDATE`, `DELETE` or `TRUNCATE` the ledger. What is
     accepted is that a leaked `JDBC_PASSWORD` — or any flaw reaching the datasource — can rewrite
     audit history between restarts.

  Either way the chosen posture, its authority and its date are recorded in `step0-db-identity.txt` and
  signed below. **An unrecorded posture blocks Step 1**, for the same reason the retention requirement
  may not be defaulted: the party who carries the risk has to be the party who accepted it.

- **A network-layer constraint on who may reach `/actuator` and `/metrics`, or the residual accepted in
  writing.** Both surfaces are `permitAll` by necessity — a kubelet and a Prometheus scraper present no
  credential, and the chart probes and scrapes them by path (`…/templates/cash-account.yaml:L51-L54`,
  `L204-L222`). The module bounds what they disclose (the start-up recorder is filtered to six
  lifecycle steps, and `env`, `beans`, `configprops`, `heapdump`, `threaddump` and the rest are not
  exposed at all — [Run locally](../README.md#run-locally)); what it cannot bound is who may ask. The
  residual is the Spring Boot version the startup endpoint's own descriptor carries, and the
  `uri`-templated request metrics. A NetworkPolicy restricting ingress to port 8080 to the node (for
  the kubelet) and the monitoring namespace (for the scrape) closes it without touching the chart's own
  templates:

  ```yaml
  # Applied by the platform owner alongside the release, not part of the chart.
  apiVersion: networking.k8s.io/v1
  kind: NetworkPolicy
  metadata:
    name: cash-account-actuator-ingress
    namespace: <namespace>
  spec:
    podSelector:
      matchLabels: { app.kubernetes.io/name: <release>-cash-account }
    policyTypes: [Ingress]
    ingress:
      # The retail and institutional surface, from the callers that use it.
      - from:
          - podSelector: { matchLabels: { app.kubernetes.io/name: <release>-broker } }
          - podSelector: { matchLabels: { app.kubernetes.io/name: <release>-portfolio } }
        ports: [{ port: 8080, protocol: TCP }]
      # The scrape. The kubelet's probes originate on the node and are not matched by a
      # namespaceSelector, so confirm against this cluster's CNI that node-sourced traffic is
      # permitted before applying this - a policy that blocks the probes fails every pod.
      - from:
          - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: <monitoring-namespace> } }
        ports: [{ port: 8080, protocol: TCP }]
  ```

  Verify the probes still pass after applying it (`kubectl -n <namespace> get pod … -o wide` showing
  Ready, and no `Startup probe failed` event), and record the outcome in
  `step0-actuator-reachability.txt`. If the cluster's CNI cannot express it, record that instead: the
  disclosure is rated LOW and does not block, but the decision is evidence either way.

- **A Prometheus scrape of the service's `/metrics` endpoint is in place**, because Step 3's error-rate
  rollback criterion is evaluated from it. The chart already annotates the pod for scraping with no
  path (`…/templates/cash-account.yaml:L51-L54`), which is why the service exposes `/metrics` as well
  as `/actuator/prometheus`.

### Actions

1. **Record the image digest** produced by the README build path, **and the two chart field values it
   splits into**, because the chart renders the image as `repository` and `tag` joined by a colon
   (`…/templates/cash-account.yaml:L65`) and a digest reference has to be split at its final colon to
   survive that:

   ```bash
   REF=$(docker inspect --format '{{index .RepoDigests 0}}' <registry>/cash-account:<version>)
   echo "reference:                    $REF"
   echo "cashAccount.image.repository: ${REF%:*}"   # <registry>/cash-account@sha256
   echo "cashAccount.image.tag:        ${REF##*:}"  # the 64-character hex digest
   ```

   Both values are recorded here so that gate 3(e) applies them rather than re-deriving them under
   change-window pressure; the full contract, including the invalid mapping to avoid, is in
   [`../README.md#carrying-the-digest-through-the-charts-fixed-repositorytag-rendering`](../README.md#carrying-the-digest-through-the-charts-fixed-repositorytag-rendering).
   A tag can be re-pointed after the fact and a digest cannot, which is what makes "the image we
   validated" and "the image the pod runs" the same statement. This command only answers after the
   push: a locally built image carries no `RepoDigests` entry.

2. **Capture the live values / CR snapshot, seal it, and attach only its structure.** This repository
   holds chart *defaults*, not the live release's state, so the defaults are not a rollback target.
   Every later instruction to "restore" means restoring **this** snapshot verbatim, which is why it is
   captured whole and not summarized.

   **That snapshot is the release's credential set.** `helm get values --all` returns the values the
   chart was installed with, and those values *are* the credentials: `database.password`
   (`infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:L79`), `oidc.clientSecret`
   (`…/values.yaml:L212`), `watson.passwordOrApiKey` (`…/values.yaml:L227`), `odm.password`
   (`…/values.yaml:L231`), and the mq, cloudant, openwhisk, redis, kafka, twitter, mongo and S3
   credentials beside them — the release Secret is rendered *from* exactly these keys
   (`…/templates/credentials.yaml:L20-L47`). On the operator path the same is true inline: the
   StockTrader CRD marks `spec.database.password` and both `spec.oidc.clientId` and
   `spec.oidc.clientSecret` `format: password`
   (`infra/stocktrader-operator/config/crd/bases/operators.ibm.com_stocktraders.yaml:L229-L231,
   L782-L789`), so `kubectl get stocktrader <name> -o yaml` prints them. The capture is therefore
   restricted-class from the moment it exists, and the change record gets a derived structural file
   instead — [Evidence handling and classification](#evidence-handling-and-classification).

   Capture whichever one governs the release, and both if both exist. Both output forms of the same
   object are taken **back to back**: the YAML is the restore artifact, and the JSON is what the
   redactor below reads with nothing but the standard library. Step 0 runs outside any change window,
   so nothing is applying values between the two commands; if a later capture disagrees with the sealed
   one, that is the finding gate 3(c) reports, not an artifact of this ordering.

   ```bash
   umask 077                                        # every capture below is 0600 from creation
   mkdir -p <restricted-dir>
   cd <restricted-dir>
   helm get values <release> -n <namespace> --all -o yaml > step0-values-snapshot.yaml
   helm get values <release> -n <namespace> --all -o json > step0-values-snapshot.json
   # or, where the StockTrader operator owns the release:
   kubectl get stocktrader <name> -n <namespace> -o yaml > step0-cr-snapshot.yaml
   kubectl get stocktrader <name> -n <namespace> -o json > step0-cr-snapshot.json
   ```

   ```bash
   # Checksum whatever was actually captured - either path may be absent - and refuse an empty set.
   set --
   for f in step0-values-snapshot.yaml step0-values-snapshot.json \
            step0-cr-snapshot.yaml step0-cr-snapshot.json; do
     if [ -f "$f" ]; then set -- "$@" "$f"; fi
   done
   if [ "$#" -eq 0 ]; then
     echo 'no snapshot was captured: capture whichever of the two governs the release' >&2
     exit 1
   fi
   sha256sum "$@" | tee ../step0-snapshot.sha256
   ```

   **The redactor is an allowlist**, for the same reason the environment capture above is: a denylist
   passes every secret whose name it did not anticipate, and this file's key set is the whole chart's.
   Every leaf becomes a presence marker and only deliberately named non-secret cutover keys keep their
   literal value. No `oidc.*` value is kept — the CRD marks even `clientId` `format: password`
   (`…/operators.ibm.com_stocktraders.yaml:L782-L785`), so it is treated as a secret here whatever it
   is elsewhere.

   The allowlist is a file rather than a constant in each script, because two copies of it drift — and
   a drifted allowlist either redacts a cutover key a gate has to read or prints one it must not:

   ```bash
   cat > ca-keep.txt <<'KEEP'
   # The cutover keys, and only them: each is a non-secret value a later gate has to compare, which is
   # why it is here. Anything absent from this file is redacted whether or not it looks like a secret.
   # Read by ca-structure.py and ca-compare.py; a `spec.` prefix is stripped before matching, so one
   # list serves the values shape and the CR shape.
   cashAccount.enabled
   cashAccount.url
   cashAccount.image.repository
   cashAccount.image.tag
   cashAccount.exchangeRateUrl
   database.kind
   database.ssl
   vault.enabled
   global.auth
   global.specifyCerts
   jwt.issuer
   jwt.audience
   KEEP
   ```

   ```bash
   cat > ca-structure.py <<'PY'
   """Derive a change-record-class structural snapshot from a values or CR capture.

   Input: the -o json form of the capture. Output: one sorted `key.path=value` line per leaf, where
   `value` is a presence marker unless the path is allowlisted in ca-keep.txt. Usage:

       python3 ca-structure.py step0-values-snapshot.json > step0-values-structure.txt
   """
   import json
   import sys


   def allowlist():
       """ca-keep.txt from the working directory, which is <restricted-dir> for every gate that runs
       these scripts."""
       with open('ca-keep.txt', encoding='utf-8') as handle:
           return {line.strip() for line in handle
                   if line.strip() and not line.startswith('#')}


   def unqualified(path):
       """The CR carries the same keys under `spec.`; strip it so one allowlist serves both shapes."""
       return path[5:] if path.startswith('spec.') else path


   def marker(value):
       """Presence only, and never a hash: `database.password` is short and low-entropy, so a
       published digest of it is an offline-guessable copy - a wordlist recovers the password without
       touching the release. A length would leak for the same reason."""
       if isinstance(value, list):
           return '<list:%d>' % len(value)
       if value is None or value == '' or value == {}:
           return '<empty>'
       return '<set>'


   def walk(node, path, keep, out):
       if isinstance(node, dict) and node:
           for key in node:
               walk(node[key], path + [str(key)], keep, out)
           return
       # A list's elements are not walked: a list of maps in a values file is as likely to hold a
       # credential as a scalar is, and its length is the only structural fact the change record needs.
       dotted = '.'.join(path)
       if unqualified(dotted) in keep and not isinstance(node, (dict, list)):
           # json.dumps, so `false` is never confused with the string "false".
           out.append('%s=%s' % (dotted, json.dumps(node)))
       else:
           out.append('%s=%s' % (dotted, marker(node)))


   def main():
       with open(sys.argv[1], encoding='utf-8') as handle:
           document = json.load(handle)
       lines = []
       walk(document, [], allowlist(), lines)
       # Sorted, so two captures of the same release diff to nothing and a real change is the diff.
       sys.stdout.write(''.join(line + '\n' for line in sorted(lines)))


   if __name__ == '__main__':
       main()
   PY

   # The `if` form, not `[ -f … ] && …`: either capture may be absent, and the short-circuit form
   # returns non-zero for a missing one, which would abort a session running under `set -e`.
   for src in step0-values-snapshot.json step0-cr-snapshot.json; do
     if [ -f "$src" ]; then
       python3 ca-structure.py "$src" > "../${src%-snapshot.json}-structure.txt"
     fi
   done
   ```

   The structural file answers what the change record legitimately needs: which keys this release sets,
   which it leaves empty, how long its lists are, and the literal value of each cutover key gate 3(e)
   will change. It cannot answer what any secret is, and it is written outside `<restricted-dir>`
   because it is the change-record item.

   **What it equally cannot answer is whether a redacted value changed** — a rotated `database.password`
   reads `<set>` before and `<set>` after — and gates 3(c) and 3(e) turn on exactly that question. So
   the comparison the gates make is a second script, run on the captures themselves inside the store,
   which compares every path at full value fidelity and emits **paths and verdicts only**:

   ```bash
   cat > ca-compare.py <<'PY'
   """Compare two captures of one source inside the restricted store; print paths, never a secret.

       python3 ca-compare.py <baseline.json> <current.json> [expected.tsv]

   Both arguments are the -o json form of the same source - values against values, CR against CR.
   Every path is compared at full value fidelity, so a change to a field the structural derivation
   redacts is still detected; the value itself is printed only for an allowlisted cutover path
   (ca-keep.txt), so the output is change-record class whatever changed.

   Without an expectations file every difference is a failure, which is gate (c)'s condition. With one
   - a TSV of `path<TAB>json-value`, the authorized change set - each difference is verdicted EXPECTED,
   UNAUTHORIZED_VALUE or UNAUTHORIZED_PATH and an authorized path that did not change is reported
   NOT_APPLIED, which is gate (e)'s condition. Exit 0 when nothing differs or every difference is
   EXPECTED, 1 otherwise, so a gate is a command status rather than a reading.
   """
   import json
   import sys


   def allowlist():
       with open('ca-keep.txt', encoding='utf-8') as handle:
           return {line.strip() for line in handle
                   if line.strip() and not line.startswith('#')}


   def unqualified(path):
       return path[5:] if path.startswith('spec.') else path


   def flatten(node, path, out):
       """Path -> canonical JSON text of the value. A list is compared whole rather than element by
       element: a reordered list is a change, and a list is never printed anyway."""
       if isinstance(node, dict) and node:
           for key in node:
               flatten(node[key], path + [str(key)], out)
           return
       out['.'.join(path)] = json.dumps(node, sort_keys=True)


   def load(path):
       with open(path, encoding='utf-8') as handle:
           flat = {}
           flatten(json.load(handle), [], flat)
           return flat


   def expectations(path):
       want = {}
       with open(path, encoding='utf-8') as handle:
           for line in handle:
               if not line.strip() or line.startswith('#'):
                   continue
               key, _, value = line.rstrip('\n').partition('\t')
               want[key.strip()] = value.strip()
       return want


   def main():
       keep = allowlist()
       base, current = load(sys.argv[1]), load(sys.argv[2])
       want = expectations(sys.argv[3]) if len(sys.argv) > 3 else {}

       differences = []
       for path in sorted(set(base) | set(current)):
           before, after = base.get(path), current.get(path)
           if before == after:
               continue
           kind = 'ADDED' if before is None else 'REMOVED' if after is None else 'CHANGED'
           differences.append((path, kind, after if unqualified(path) in keep else None))

       failures = 0
       print('changed=%d' % len(differences))
       for path, kind, shown in differences:
           if want:
               expected = want.get(unqualified(path))
               if expected is None:
                   verdict = 'UNAUTHORIZED_PATH'
               elif kind == 'REMOVED':
                   verdict = 'UNAUTHORIZED_VALUE'
               elif json.loads(expected) == json.loads(current[path]):
                   verdict = 'EXPECTED'
               else:
                   # The path is authorized and the value is not: a mistyped digest lands here.
                   verdict = 'UNAUTHORIZED_VALUE'
           else:
               verdict = kind
           if verdict != 'EXPECTED':
               failures += 1
           print('%s\t%s%s' % (path, verdict, '\t' + shown if shown is not None else ''))

       changed_paths = {unqualified(path) for path, _, _ in differences}
       for path in sorted(set(want) - changed_paths):
           # Not a failure by itself: cashAccount.url need not change when the snapshot already holds
           # the default. Which of these may legitimately read NOT_APPLIED is stated at gate (e).
           print('%s\tNOT_APPLIED' % path)

       return 1 if failures else 0


   if __name__ == '__main__':
       sys.exit(main())
   PY
   ```

   `ca-keep.txt`, `ca-structure.py` and `ca-compare.py` stay in `<restricted-dir>` beside the captures,
   and the gates run from that directory because both scripts read the allowlist from it. A derivation
   or a comparison produced by a differently worded copy would differ for reasons that have nothing to
   do with the release, so a later gate running on another host re-creates all three from this action
   rather than rewriting them.

   Seal the captures and destroy the local copies as the closing action of this step.
   `step0-snapshot.sha256` is written beside the structural file rather than inside
   `<restricted-dir>` because it is change-record class: a digest over a whole capture is not guessable
   the way a digest of one short password field is, and it is what the pointer line and every later
   comparison quote.

   ```bash
   # After the captures are sealed into <evidence-store-locator> and confirmed present there.
   # The sealed YAML is what Step 3's post-(e) restore reads and applies; the sealed JSON is what
   # gates 3(c) and 3(e) restore, compare against, and destroy again.
   shred -u step0-values-snapshot.yaml step0-values-snapshot.json \
             step0-cr-snapshot.yaml step0-cr-snapshot.json 2>/dev/null \
     || rm -f step0-values-snapshot.yaml step0-values-snapshot.json \
              step0-cr-snapshot.yaml step0-cr-snapshot.json
   ls step0-values-snapshot.* step0-cr-snapshot.* 2>/dev/null || true   # must print nothing
   cd -
   ```

   Then write the pointer line the change record carries in the snapshot's place, in the shape
   [Evidence handling and classification](#evidence-handling-and-classification) defines, taking each
   checksum from `step0-snapshot.sha256` and the custodian from the [Roles](#roles) table — the
   platform operator, who holds the values and the rollback decision.

3. **Run the pre-cutover catalog query** against the selected database, in a session with **no
   `search_path` override**, as the identity that owns the schema — so that what the query cannot see
   is genuinely absent rather than merely invisible:

   ```sql
   SELECT table_schema, table_name, column_name, data_type
     FROM information_schema.columns
    WHERE table_name IN ('cashaccount','cash_account')
    ORDER BY 1,2,3;
   ```

   `table_schema` is in the select list and the sort key because **a relation without its schema is
   not located.** Step 1 deliberately creates `cash_account` in `cash_account_rehearsal`, so from
   Step 1 onwards the unqualified output cannot distinguish "the rehearsal table exists, production is
   still clean" — the intended state — from "this schema was applied to production", which blocks
   Step 3(b). The same omission would hide a second `cashaccount` in another schema behind rows that
   look like one table.

   Run the companion query in the same session, and keep both outputs:

   ```sql
   SELECT n.nspname AS table_schema,
          c.relname AS table_name,
          n.nspname = ANY (current_schemas(false)) AS on_search_path
     FROM pg_catalog.pg_class c
     JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relname IN ('cashaccount','cash_account')
      AND c.relkind = 'r'
    ORDER BY 1,2;
   ```

   It exists because `information_schema` **filters by privilege**: a role without privileges on
   `cashaccount` gets an empty result for a table that is plainly there, and an empty result is
   exactly what the pass condition below wants to see for `cash_account`. The `pg_catalog` view
   answers for every role, and `on_search_path` names which schema is "production" for this
   session — the one Step 3 will use — without hard-coding `public`.

   **One further query, and only where a `cash_account` table already exists** — a database some
   earlier build of this service has already started against. The owner columns carry a `CHECK`
   constraint that the start-up script adds to an existing table `NOT VALID`, so that rows written
   before the constraint existed cannot stop a pod from starting. It is therefore possible for such a
   database to hold an owner the service would now refuse, and Step 0 is where that is found:

   ```sql
   SELECT conrelid::regclass AS table_name, conname, convalidated
     FROM pg_catalog.pg_constraint
    WHERE conname LIKE '%\_owner\_identifier'
    ORDER BY 1;

   SELECT 'cash_account' AS table_name, owner FROM cash_account
    WHERE owner !~ '^[A-Z0-9._-]{1,32}$'
   UNION ALL
   SELECT 'cash_reservation', owner FROM cash_reservation
    WHERE owner !~ '^[A-Z0-9._-]{1,32}$'
   UNION ALL
   SELECT 'ledger_entry', owner FROM ledger_entry
    WHERE owner !~ '^[A-Z0-9._-]{1,32}$'
    ORDER BY 1,2;
   ```

   Pass condition: the first query returns the three constraints, and the second returns **no rows**.
   With no rows outstanding, promote each constraint once — `ALTER TABLE <table> VALIDATE CONSTRAINT
   ck_<table>_owner_identifier;` — and keep the re-run of the first query, now reporting
   `convalidated = t`, as the evidence. If the second query does return rows, they are data to correct
   with the data owner **before** cutover, not rows to delete here: a `ledger_entry` row cannot be
   deleted at all (it is append-only), so an owner that reached the ledger is a finding for the data
   owner and the reason `VALIDATE` is a separate, deliberate step rather than part of start-up.

   The pass condition, in schema terms:

   | Gate | `cash_account` | `cashaccount` |
   | --- | --- | --- |
   | Step 0 baseline | **Absent from every schema** | If present: its columns exactly as the estate created them, in the schema that reports `on_search_path = t` |
   | Re-run after Step 1 | Present in `cash_account_rehearsal` **and nowhere else** — in particular not in the schema that reports `on_search_path = t` | Rows byte-identical to the baseline |
   | Re-run after Step 3(b) | Present in the schema that reports `on_search_path = t` (the final load's target), and in `cash_account_rehearsal` while the rehearsal schema still exists | Rows byte-identical to the baseline |

   Those two names are **different tables**, and the distinction is the whole point of the query. The
   estate's Azure init template creates a never-built Java-flavour `cashaccount(owner VARCHAR(32),
   balance DOUBLE PRECISION, currency VARCHAR(8))` with an `allowed_currencies` CHECK over 31 ISO codes
   (`infra/stocktrader-setup/azure/modules/postgres_init/init_schema.sql.tmpl:L3-L9`). This module's
   table is `cash_account` — underscore, `NUMERIC(9,2)`, available and reserved balances — and it
   neither reads nor alters `cashaccount`. Baselining the catalog now is how anyone can later show that
   nothing in this migration touched a table it does not own: both queries are re-run at each gate in
   the table above, and each re-run is diffed against this baseline.

4. **Run the memory-fit check against a disposable database**, and observe readiness inside the
   chart's envelope.

   **This check must never be pointed at the release's store — the one action 3 just baselined, or
   any other store this migration does not own.** Starting the image applies the schema: the service
   runs with `spring.sql.init.mode=always` and
   `spring.sql.init.schema-locations=classpath:schema/cash-account-schema.sql`
   (`../src/main/resources/application.yml`), so every start creates the seven tables, the
   `ledger_entry_reject()` function and the `ledger_entry_immutable` and `ledger_entry_immutable_truncate`
   triggers in whatever schema the
   connection resolves to. Against the selected store that would mutate it, void the `cashaccount`
   baseline captured moments earlier, and put a `cash_account` into production before Step 3(b) — the
   one thing Step 0 exists to rule out. Nor is "turn the initializer off" the alternative:
   `spring.jpa.hibernate.ddl-auto=validate` then fails start-up against a store without those tables,
   and readiness includes the `db` indicator, so a check with no reachable database can never answer
   `200`. The isolation *is* the mechanism.

   Create a throwaway PostgreSQL and a throwaway credential for it, both destroyed at the end of this
   action. `--env-file` and the builtin `printf` keep the credential out of every `argv`, per
   [Operator command safety](#operator-command-safety):

   ```bash
   umask 077
   { printf 'POSTGRES_USER=memfit\nPOSTGRES_DB=memfit\nPOSTGRES_PASSWORD='; openssl rand -hex 24; } > memfit-db.env
   { printf 'JDBC_KIND=postgres\nJDBC_HOST=ca-memfit-db\nJDBC_PORT=5432\nJDBC_DB=memfit\nJDBC_ID=memfit\nAUTH_TYPE=basic\nJDBC_PASSWORD='
     sed -n 's/^POSTGRES_PASSWORD=//p' memfit-db.env; } > memfit-app.env
   ```

   ```bash
   docker network create ca-memfit-net
   # A NAMED data volume, so the teardown below can remove exactly this cluster and nothing else:
   # postgres:12.22-alpine declares /var/lib/postgresql/data as a VOLUME, and an anonymous one would
   # have to be found among every dangling volume on a shared host.
   docker run -d --name ca-memfit-db --network ca-memfit-net \
     -v ca-memfit-data:/var/lib/postgresql/data \
     --env-file ./memfit-db.env postgres:12.22-alpine
   docker run -d --name ca-memfit-app --network ca-memfit-net --memory=2g --cpus=1 \
     --env-file ./memfit-app.env -p 8080:8080 <registry>/cash-account:<version>
   ```

   ```bash
   # Poll rather than assume: a detached container has started a process, not answered a request, and
   # this one applies the schema first. Anything other than 200 at the end is a failed check.
   code=000
   for i in $(seq 1 30); do
     code=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' \
                 http://localhost:8080/actuator/health/readiness) || code=000
     [ "$code" = 200 ] && break
     sleep 2
   done
   printf 'readiness=%s after %ss\n' "$code" "$((i * 2))"
   [ "$code" = 200 ] || echo 'MEMORY-FIT CHECK FAILED - do not sign off; capture the logs below'
   docker logs ca-memfit-app 2>&1 | grep -iE 'MaxRAM|Started CashAccountApplication'
   ```

   This action needs a host with Docker and the ability to pull `postgres:12.22-alpine` — it is the one
   Step 0 item that runs a container rather than a query. `postgres:12.22-alpine` is the estate's
   provisioned major version and the floor this module's
   schema is written against, so the throwaway instance exercises the same DDL the release will get.
   `--memory=2g --cpus=1` mirrors the chart's limits
   (`…/templates/cash-account.yaml:L224-L232`). The check is **manual because no in-repo test can
   assert it**: the base image's `run-java.sh` derives the heap from the container memory limit, so the
   answer depends on the runtime limit rather than on anything a unit or integration test observes.

   Tear the whole thing down — the point of a disposable instance is that nothing survives it. Run
   this **whether the check passed or failed**; a failed check leaves a database holding the schema and
   a file holding a credential, which is the worse of the two states to walk away from:

   ```bash
   # The volume is removed by name. `docker rm -f` never removes a named volume, and removing the
   # container alone would leave the whole cluster - schema included - on the host.
   docker rm -fv ca-memfit-app ca-memfit-db
   docker network rm ca-memfit-net
   docker volume rm ca-memfit-data
   shred -u memfit-db.env memfit-app.env 2>/dev/null || rm -f memfit-db.env memfit-app.env
   ```

   Confirm the teardown rather than assuming it: each of the three commands below must print nothing.

   ```bash
   docker ps -a --filter name=ca-memfit --format '{{.Names}}'
   docker volume ls -q --filter name=ca-memfit-data
   ls memfit-db.env memfit-app.env 2>/dev/null
   ```

   *Gate:* readiness answers `200` under the chart's limits, and the seven tables exist **only** in the
   throwaway instance. If this check was ever run against the selected store, treat
   `step0-catalog-baseline.txt` as void: re-run action 3, record what the start-up created, and refer
   the state to the cash-account data owner and the DDL-owning role before Step 1 begins. Step 0 is
   signed off on the claim that it changed no state this migration owns, and that claim has to be true.

5. **Record the database-identity posture, and — if it is the split one — provision and verify the
   runtime role.** This action changes no state in the selected store under either choice: option 2
   writes a file, and option 1's `CREATE ROLE`/`GRANT` touch the catalog's role and privilege
   metadata, never a table of this migration's. The schema itself is applied at Step 3(b), not here.

   **Option 2 (single identity).** Record the acceptance and stop. Nothing is provisioned:

   ```bash
   # Written by the cash-account data owner, not on their behalf.
   cat > step0-db-identity.txt <<'TXT'
   posture: single-identity (chart-supplied database.id performs DDL and DML)
   residual accepted: the request-handling identity owns ledger_entry and can DROP TRIGGER
                      ledger_entry_immutable, then UPDATE/DELETE/TRUNCATE audit rows
   compensating control: schema/cash-account-schema.sql is idempotent, so the next start-up
                      re-creates ledger_entry_immutable and ledger_entry_immutable_truncate;
                      no application code path can update, delete or truncate the ledger
   accepted by: <name, role>
   date: <YYYY-MM-DD>
   reference: <change record / risk acceptance / ticket>
   TXT
   ```

   **Option 1 (split identities).** The chart change carrying the second secret key must already be in
   place. Provision as the DDL-owning role, with the connection coordinates and the `PGPASSFILE`
   discipline of [Operator command safety](#operator-command-safety); the grants are the ones
   [Hardened production posture](../README.md#schema-application) prescribes, and `<schema>` is the
   schema the service's connection resolves to (the one action 3 reported as `on_search_path = t`):

   ```bash
   umask 077
   psql -w -v ON_ERROR_STOP=1 <<'SQL' | tee step0-db-identity-grants.txt
   \set QUIET on
   -- The runtime role's password is supplied out of band by the secret manager and set with
   -- \password or a separate ALTER ROLE, so it reaches neither this file nor the psql history.
   CREATE ROLE :"runtime_role" LOGIN;
   GRANT CONNECT ON DATABASE :"database" TO :"runtime_role";
   GRANT USAGE ON SCHEMA :"schema" TO :"runtime_role";
   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA :"schema" TO :"runtime_role";
   ALTER DEFAULT PRIVILEGES IN SCHEMA :"schema"
     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_role";
   SQL
   ```

   Then set `SPRING_SQL_INIT_MODE=never` on the cash-account Deployment and point `database.id` /
   `database.password` at the runtime role. **Order matters**: with that setting the pod applies no
   schema, and `spring.jpa.hibernate.ddl-auto=validate` fails start-up against a store that has none —
   which is why the switch is applied together with gate 3(b), the action that applies the schema to
   the production search path, and never before it.

   Verify the separation once the tables exist — after 3(b) — as the **runtime role**, and keep the
   output. Every statement must be refused and the row count must not move:

   ```bash
   psql -w -v ON_ERROR_STOP=0 <<'SQL' | tee step0-ledger-guard-privileges.txt
   \set VERBOSITY verbose
   SELECT count(*) AS ledger_rows_before FROM ledger_entry;
   DROP TRIGGER ledger_entry_immutable ON ledger_entry;              -- 42501 must be owner of relation
   ALTER TABLE ledger_entry DISABLE TRIGGER ledger_entry_immutable;  -- 42501 must be owner of table
   DROP FUNCTION ledger_entry_reject() CASCADE;                      -- 42501 must be owner of function
   TRUNCATE ledger_entry;                                            -- 42501 permission denied for table
   UPDATE ledger_entry SET amount = 0;                               -- P0001 append-only: UPDATE rejected
   DELETE FROM ledger_entry;                                         -- P0001 append-only: DELETE rejected
   SELECT count(*) AS ledger_rows_after FROM ledger_entry;
   SQL
   ```

   *Gate:* `step0-db-identity.txt` exists, names an authority and a date, and matches what the
   Deployment actually runs — the effective `SPRING_SQL_INIT_MODE` and `database.id`. Under option 1,
   all six statements above are refused with those two SQLSTATE classes and `ledger_rows_after` equals
   `ledger_rows_before`. A statement that **succeeds** is a provisioning error: the role owns something
   it should not, so revisit ownership before routing any traffic — `audit/LedgerImmutabilityIT`
   asserts the same six refusals against a container, so a divergence here is the deployment's, not
   the module's.

6. **Measure what the rate endpoint costs to reach from the cluster, and set the FX budget from that
   measurement.** This action changes no state anywhere: it reads a public endpoint from a pod and
   writes a file. It is here rather than at cutover because the value it decides is container
   environment on the Deployment gate 3(e) creates, so it has to be known before that gate applies.

   `cashaccount.fx.timeout` (`PT2S` as shipped) budgets the **whole exchange per attempt** — DNS, TCP
   connect, the TLS handshake, the request write, the response headers and the body — and
   `fx/FrankfurterExchangeRateClient` makes at most two attempts, so a caller can wait twice it.
   Connection set-up is the part that does not reliably fit: measured from a pod against the endpoint
   the chart ships, a cold TLS handshake alone took **3.6 s**, more than the whole budget, while a warm
   connection answered the same lookup in 0.05 s. The service already removes the commonest exposure by
   opening that connection once at start-up, outside every caller's budget and outside readiness
   ([`../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it`](../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it)),
   but a pooled connection the provider later closes is re-established inside a caller's budget, so the
   budget still has to cover this network's cost. Measure it where the service will run — a
   workstation's path to the provider is not the cluster's:

   ```bash
   # In a throwaway pod on the cluster, in the namespace the release runs in, against the value the
   # chart injects. time_appconnect is DNS + TCP + TLS; total is the whole exchange. Three runs, so
   # the cold cost and the warm cost are both on the record; the first is the one that decides.
   kubectl run cash-account-fx-probe -n <namespace> --rm -i --restart=Never \
     --image=<curl-image> --command -- sh -c '
       for i in 1 2 3; do
         curl -s -o /dev/null \
           -w "attempt=$i appconnect=%{time_appconnect}s total=%{time_total}s http=%{http_code}\n" \
           "<cashAccount.exchangeRateUrl>?from=USD&to=EUR"
       done' | tee step0-fx-budget.txt
   ```

   Then decide, and record the decision in the same file:

   ```bash
   cat >> step0-fx-budget.txt <<'TXT'
   endpoint: <cashAccount.exchangeRateUrl, as the live values/CR snapshot carries it>
   cold total: <seconds, from attempt=1 above>
   shipped budget: PT2S (cashaccount.fx.timeout)
   decision: <"unchanged — the cold total plus margin fits PT2S">
         or  <"CASHACCOUNT_FX_TIMEOUT=<ISO-8601 duration> applied to the cash-account Deployment">
   decided by: <name, role>
   date: <YYYY-MM-DD>
   TXT
   ```

   The rule the decision follows: the budget must exceed the **cold** total with margin — a value below
   it refuses the first conversion over every connection the pool has to re-establish — and for a public
   provider reached over the internet that is typically `PT5S`. It is bounded from above too: broker's
   REST client configures no timeout at all, so a caller's thread and a broker thread behind it are held
   for up to twice this value, which is why the margin is a margin and not a multiple. Applying it is a
   **container-environment** edit on the cash-account Deployment — `CASHACCOUNT_FX_TIMEOUT=PT5S`,
   Spring's relaxed binding for the same property — and touches **no chart template and no chart
   value**, so it is outside the value set gate 3(e) applies and outside the Step 0 snapshot; it is
   recorded here because nothing else in the release records it.

   *Gate:* `step0-fx-budget.txt` exists, carries three measurements and a decision with an authority and
   a date, and the effective `CASHACCOUNT_FX_TIMEOUT` on the Deployment matches what it records — read
   back from the running pod, not from the manifest that was applied. After gate 3(e) the pod's own log
   line is the confirmation that the warm-up ran on this network: `Exchange-rate connection opened in
   <n> ms`. A `WARN` naming the start-up warm-up budget in its place means the endpoint was unreachable
   from the pod at start-up — the service still runs and readiness is unaffected, but the reachability
   is what this action exists to establish, so treat it as a failed gate and resolve it before 3(g).

### Evidence to capture

| Item | Class | What it is |
| --- | --- | --- |
| `step0-values-structure.txt` / `step0-cr-structure.txt` | change record | The redacted structural derivation of the live values or CR: one sorted line per key path, a presence marker for every value except the named non-secret cutover keys. This is what stands in the change record for the snapshot |
| `step0-snapshot.sha256` | change record | The SHA-256 of each capture that was taken, and the value every later comparison is made against |
| `step0-snapshot-pointer.txt` | change record | The pointer line for the sealed snapshot: file name, class, checksum from `step0-snapshot.sha256`, store locator, custodian and named readers |
| `step0-values-snapshot.yaml` / `step0-cr-snapshot.yaml`, with their `-o json` counterparts | **restricted** | The live values or CR, verbatim — the rollback target Step 3's post-(e) restore applies byte-for-byte. It embeds `database.password`, `oidc.clientId`/`clientSecret` and every other credential the release carries, so it is sealed and never attached |
| `step0-image-digest.txt` | change record | The pushed image digest from `docker inspect`, and the `cashAccount.image.repository` / `.tag` values it splits into, which gate 3(e) applies verbatim |
| `step0-image-scan.txt` | change record | Both image scans of the promoted artifact: the informational pass in full — every finding, including the ones with no fix available — and the blocking gate's exit status, each naming the scanner version and its vulnerability-database timestamp, because a scan is only as current as the database it ran against |
| `step0-base-image.txt` | change record | The base image tag, its manifest-list digest, the OS and JDK package versions read out of the image, and the date the stream head was re-derived. This is what makes "the base was current when we promoted" a dated claim rather than an impression |
| `step0-dependency-scan.txt` | change record | The NVD dependency-scan report — or, where no feed was provisioned, the recorded failure verbatim together with the build-input scan that stood in for it, which is what the sign-off then refers to |
| `step0-catalog-baseline.txt` | change record | Both catalog queries' output, schema-qualified: `cash_account` absent from every schema, `cashaccount` as the estate created it, and which schema reports `on_search_path = t`. Catalog metadata only — relation, column and type names, no row of either table |
| `step0-memory-fit.txt` | change record | The readiness status code and the heap line under `--memory=2g --cpus=1`, **naming the throwaway instance it ran against** and recording that it was destroyed. A memory-fit record that names the release's store is a Step 0 failure, not evidence |
| `step0-fx-budget.txt` | change record | The three measurements of the rate endpoint's cost from a pod on the cluster — `time_appconnect` and `total` per attempt — together with the budget decision they produced, its authority and its date. This is the only record of the FX budget a release runs with, because the value is container environment rather than a chart value, and it is what a later "the first conversion after a restart was refused" question is answered from |
| `step0-store.txt` | change record | The effective `database.kind` and the server version reported by `SELECT version();` |
| `step0-framework-support.txt` | change record | The written framework-support determination — which of **stay / buy support / move line** was chosen, by whom, dated — together with the artifact inventory and image-scan output it was decided against (`./mvnw -B dependency:tree` and the Trivy image scan of the digest recorded above), so the decision and the evidence under it are one record. If the choice is *move line*, this file also carries the re-gate and re-scan of the rebuilt artifact |
| `step0-authorization-record.txt` | change record | The status of rows D1–D6 of the README's authorization record at the moment Step 0 is signed, each with its authorizer, date and reference — a `PENDING` row here is an open deviation entering the cutover, which is the fact this evidence item exists to make visible |
| `step0-db-identity.txt` | change record | The chosen database-identity posture, the authority who chose it and the date — and, for the single-identity option, the accepted residual verbatim. This file **is** the decision; its absence blocks Step 1 |
| `step0-db-identity-grants.txt` | change record | Option 1 only: the `CREATE ROLE` / `GRANT` transcript for the runtime role. Role and database names, no password — the secret manager supplies that out of band |
| `step0-ledger-guard-privileges.txt` | change record | Option 1 only: the six refused statements as the runtime role, with their SQLSTATEs, and the `ledger_rows_before` / `ledger_rows_after` counts that must match. Captured after gate 3(b), when the tables exist |
| `step0-actuator-reachability.txt` | change record | Whether a NetworkPolicy now constrains ingress to port 8080, the probe outcome after applying it, or the recorded decision to accept the LOW residual disclosure of `/actuator` and `/metrics` |

### Sign-off required

- **Platform owner** — the PostgreSQL move (`database.kind: postgres`, version ≥ 12, `database.*`
  pointing at it) and `vault.enabled: false`, each as a change completed in its own right.
- **Platform owner** — the promoted image's scan result and the base-image pin, as one sign-off over
  `step0-image-scan.txt`, `step0-base-image.txt` and `step0-dependency-scan.txt`. Where no
  vulnerability feed was provisioned, this sign-off states that the NVD scan did not run and names the
  two Trivy scans as the coverage accepted in its place; where a fixable CRITICAL or HIGH finding is
  being carried, it names the finding, why no newer base or dependency version removes it, and the
  compensating control — an outstanding decision for the requesting organization, never a suppression
  applied at the scanner.
- **Cash-account data owner** — the catalog-query baseline, because they are the party who must later
  be able to say that `cashaccount` was never touched.
- **Cash-account data owner** — the database-identity posture in `step0-db-identity.txt`. Under option
  1 they are signing that the request-handling role owns nothing and that the six refusals were
  observed; under option 2 they are signing the residual itself, which is that the ledger they will
  reconcile against can be rewritten by the service's own credential between restarts. Nobody else may
  sign this, and it may not be defaulted — the same rule the retention requirement carries.
- **Platform owner** — the actuator reachability decision in `step0-actuator-reachability.txt`, since
  the NetworkPolicy and the monitoring topology are theirs; a recorded acceptance of the LOW residual
  is a valid outcome, an unrecorded one is not.
- **Platform owner** — the FX budget decision in `step0-fx-budget.txt`, since the path between the
  cluster and the rate provider is theirs to measure and the value is container environment on the
  Deployment they own. Signing "unchanged" is a valid outcome and says the cold measurement fits the
  shipped `PT2S`; leaving the file absent is not, because the release then runs a budget nobody checked
  against this network.
- **Platform owner and cash-account data owner** — the evidence-handling determination, the restricted
  store and the secret-manager location, and that the snapshot reached the store rather than the change
  record: the platform owner for the release-configuration class they are custodian of, the data owner
  for the owner-level class every step below produces. This sign-off is what makes the classification a
  decision somebody made rather than a convention somebody followed.
- **Requesting organization, with risk** — the framework-support determination and the authorization of
  the nine dependency overrides (row D1). Nobody building, reviewing or operating this module may
  record either on the organization's behalf, for the same reason the retention requirement may not be
  defaulted: an acceptance nobody granted is worse than an exposure plainly marked outstanding.

### Rollback criterion

There is nothing to roll back here — Step 0 changes no state that this migration owns. Its failure mode
is a **block**: any unmet prerequisite stops every step below from beginning. In particular:

- `database.kind` still `db2`, or a server older than PostgreSQL 12;
- no recorded image digest, or a digest that does not match the artifact that passed `./mvnw -B clean verify`;
- a blocking image scan that was not run, exited non-zero, or was run against an artifact other than
  the digest being promoted — in which case what passed the gate and what the pod would run are two
  different images;
- no recorded base-image check, or a scan whose database timestamp predates the build: both mean the
  gate's verdict describes something other than what is being promoted;
- a dependency scan recorded as done when no feed was provisioned to do it with, which is the one
  failure mode of this prerequisite that leaves no trace in the evidence;
- a catalog query showing a pre-existing `cash_account` in any schema — which means something already
  applied this schema, and the "final load into an empty production schema" gate in Step 3(b) cannot
  be evaluated;
- a memory-fit check run against the release's store rather than the throwaway instance, which creates
  exactly that `cash_account` and voids the baseline;
- `vault.enabled` true;
- no `CREATE SCHEMA` identity, which makes Step 1's isolation impossible;
- **no recorded database-identity posture**, or a `step0-db-identity.txt` that contradicts what the
  Deployment runs — option 1 recorded while `SPRING_SQL_INIT_MODE` is unset, or the runtime role
  recorded while `database.id` still carries the owner. Step 3(c) validates owner balances and every
  scheduled reconcile judges the ledger, so who can rewrite it has to be settled before either runs;
- under option 1, any of the six guard statements **succeeding** as the runtime role, which means that
  role owns an object it should not;
- **no restricted evidence store, no secret-manager location, or no written evidence-handling
  determination** — which blocks every step below rather than only this one, because the first thing
  action 2 captures is a file holding the release's credentials and the first thing Step 1 queries is a
  set of owner balances. There is nowhere to put either until those three exist;
- **no written framework-support determination** — the artifact every step below deploys carries three
  advisories that no in-module change can patch, and the only thing standing between them and a
  financial write surface is this deployment's own configuration. Proceeding without the determination
  is accepting that risk without anybody having accepted it. A rebuilt artifact on a later line that
  has not been re-gated and re-scanned is the same block.

Resolve the item and re-capture the affected evidence. Do not proceed with a noted exception: each of
these is load-bearing for a later gate, and a step whose gate cannot be evaluated has failed.

**A raw values or CR snapshot attached to the change record is a Step 0 failure in its own right**, and
re-filing it afterwards does not undo it: the readers who had the change open have already had the
values. The remedy is the disclosure remedy, not a correction — remove the attachment, record what was
exposed and to which readership, and treat every credential the snapshot carried as **disclosed**:
`database.password`, `oidc.clientId` and `oidc.clientSecret`, `watson.passwordOrApiKey`,
`odm.password`, and the mq, cloudant, openwhisk, redis, kafka, twitter, mongo and S3 keys that the
release Secret is rendered from (`…/templates/credentials.yaml:L20-L47`). Each is rotated with its
owning team before the cutover proceeds, and `database.password` is rotated by the platform owner
because portfolio reads the same value — the same reason the PostgreSQL move is its own signed-off
change. Then re-capture per action 2, and re-sign Step 0.

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

   Where the `UNLOAD` utility is not licensed, `DSNTIAUL` is the alternative, and it is a complete
   second path rather than a hint: one job, both tables, and output that already satisfies the reader
   contract below. `DSNTIAUL` writes one `SYSRECnn` per `SELECT` in statement order, so both tables
   leave in a single step:

   ```jcl
   //UNLDTIAU JOB (ACCT#),'DSNTIAUL CASH',NOTIFY=&SYSUID,CLASS=A,
   // MSGCLASS=H,MSGLEVEL=(1,1)
   //TIAUL    EXEC PGM=IKJEFT01,DYNAMNBR=20,REGION=0M
   //STEPLIB  DD  DISP=SHR,DSN=<db2-hlq>.SDSNEXIT
   //         DD  DISP=SHR,DSN=<db2-hlq>.SDSNLOAD
   //SYSTSPRT DD  SYSOUT=*
   //SYSPRINT DD  SYSOUT=*
   //SYSUDUMP DD  SYSOUT=*
   //SYSPUNCH DD  DUMMY
   //SYSREC00 DD  DSN=<hlq>.CASHACCT.BODY,DISP=(NEW,CATLG,DELETE),
   //             SPACE=(CYL,(50,50),RLSE),UNIT=SYSDA,
   //             DCB=(RECFM=FB,LRECL=100,BLKSIZE=27900)
   //SYSREC01 DD  DSN=<hlq>.FRANKFRT.BODY,DISP=(NEW,CATLG,DELETE),
   //             SPACE=(CYL,(5,5),RLSE),UNIT=SYSDA,
   //             DCB=(RECFM=FB,LRECL=64,BLKSIZE=27904)
   //SYSTSIN  DD  *
     DSN SYSTEM(<db2-ssid>)
     RUN  PROGRAM(DSNTIAUL) PLAN(DSNTIAUL) PARMS('SQL') -
          LIB('<db2-hlq>.RUNLIB.LOAD')
     END
   //SYSIN    DD  *
     SELECT CAST(COALESCE(
         '"' CONCAT REPLACE(STRIP(OWNER,TRAILING),'"','""') CONCAT '"'
         CONCAT ',' CONCAT
         CASE WHEN BALANCE IS NULL THEN '' ELSE
              CASE WHEN BALANCE < 0 THEN '-' ELSE '' END CONCAT
              SUBSTR(DIGITS(BALANCE),1,7) CONCAT '.' CONCAT
              SUBSTR(DIGITS(BALANCE),8,2)
         END CONCAT ',' CONCAT
         CASE WHEN CURRENCYC IS NULL THEN '' ELSE
              '"' CONCAT REPLACE(STRIP(CURRENCYC,TRAILING),'"','""')
              CONCAT '"'
         END
       ,'') AS CHAR(100))
       FROM STOCKTRD.CASHACCOUNTY;
     SELECT CAST(COALESCE(
         '"' CONCAT REPLACE(STRIP(CURRNKEY,TRAILING),'"','""') CONCAT '"'
         CONCAT ',' CONCAT
         CASE WHEN CYRRNBASE IS NULL THEN '' ELSE
              '"' CONCAT REPLACE(STRIP(CYRRNBASE,TRAILING),'"','""')
              CONCAT '"'
         END CONCAT ',' CONCAT
         CASE WHEN AMOUNT IS NULL THEN '' ELSE
              CASE WHEN AMOUNT < 0 THEN '-' ELSE '' END CONCAT
              SUBSTR(DIGITS(AMOUNT),1,7) CONCAT '.' CONCAT
              SUBSTR(DIGITS(AMOUNT),8,2)
         END CONCAT ',' CONCAT
         CASE WHEN RATES IS NULL THEN '' ELSE
              CASE WHEN RATES < 0 THEN '-' ELSE '' END CONCAT
              SUBSTR(DIGITS(RATES),1,1) CONCAT '.' CONCAT
              SUBSTR(DIGITS(RATES),2,2)
         END CONCAT ',' CONCAT
         CHAR(LOADDT, ISO)
       ,'') AS CHAR(64))
       FROM STOCKTRD.FRANKFURT1;
   /*
   ```

   **Why the `SELECT` formats the row instead of a downstream `SORT`/`OUTREC` step.** A raw
   `SELECT *` unload hands back DB2's internal column images: `NUMERIC(9,2)` as five packed-decimal
   bytes, `NUMERIC(3,2)` as two, and — because `balance`, `currencyc`, `cyrrnbase`, `amount` and `rates`
   are all nullable (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L48-L49, L56-L58`) — a one-byte null
   indicator immediately **before** each of those columns, which is what the `NULLIF` clauses in the
   generated `SYSPUNCH` control statements test:

   | Table | Record layout `DSNTIAUL` writes for `SELECT *` | LRECL |
   | --- | --- | --- |
   | `STOCKTRD.CASHACCOUNTY` | `owner` 1-32; indicator 33; `balance` 34-38 (packed); indicator 39; `currencyc` 40-47 | 47 |
   | `STOCKTRD.FRANKFURT1` | `currnkey` 1-5; indicator 6; `cyrrnbase` 7-11; indicator 12; `amount` 13-17 (packed); indicator 18; `rates` 19-20 (packed); `loaddt` 21-30 | 30 |

   Converting that shape with `OUTREC` is possible but not merely a matter of `EDIT` masks: the reader
   requires **an empty field** for a NULL, so every nullable column needs its own `IFTHEN` branch on the
   indicator byte, and a squeeze-based idiom (`SQZ`) — the usual way to close up blanks into a delimited
   line — silently drops the empty field instead of keeping it, turning a three-column row into two. The
   `SELECT` above removes that entire class of error: `DIGITS` renders a fixed digit string, the
   `CASE`/`COALESCE` pair renders a NULL as the empty field the contract asks for, `CAST(… AS CHAR(n))`
   keeps the record fixed-length so no varying-length prefix appears, and `CHAR(LOADDT, ISO)` is already
   `YYYY-MM-DD`.

   Four details in those statements are load-bearing, and each maps to a rule the reader enforces rather
   than a matter of taste:

   - **Character fields are quoted and their embedded quotes doubled.** Nothing in the legacy DDL
     restricts what a `CHAR` column may hold (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L46-L62`),
     and the owner is whatever reached the COMMAREA, so a value such as `DOE,JOHN` is possible. Emitted
     bare it would split into four fields under a three-column header and the reader would reject the
     row by field count; emitted with a bare quote inside it, the reader rejects the row for an
     unquoted quote. `'"' CONCAT REPLACE(…,'"','""') CONCAT '"'` satisfies RFC 4180 for both cases, and
     a value carrying a newline survives too, because the reader reads across a newline inside a quoted
     field. That last case is the one to watch in the row-count evidence of step 3: an embedded newline
     splits the record in the USS copy, so `wc -l` exceeds the unload's row count while the data is
     intact — investigate a count difference before treating it as loss.
   - **A NULL is an unquoted empty field, so the quoting sits inside the `CASE`, never around it.** The
     reader separates the two at the parse level: an unquoted empty field reads as NULL, a quoted empty
     field reads as the empty string. For the two nullable character columns the source validation
     happens to treat an empty string exactly as a NULL, recording the same `STATE` /
     `NULL_IN_LEGACY` variance, so quoting one would not change that row. The rule is kept for every
     column anyway, because on a *numeric* column the difference is severe: a quoted empty is not a
     NULL but a value that fails the plain-decimal check, which aborts the whole single-transaction
     load and records **no** variance row at all — the operator loses the finding instead of reviewing
     it. One rule for every column is what keeps that from depending on which column went NULL.
   - **`DIGITS` drops the sign**, which is why the sign is rendered separately. The legacy program can
     never have stored a negative balance (`WS-CALC` is unsigned,
     `backend/cash-account-cobol/COBOL/CASH00.cbl:L17`), so a negative in the export is an anomaly that
     must survive into the file to be seen rather than be silently made positive.
   - **`DIGITS` zero-pads**, which is correct here because the reader accepts leading zeros but
     right-trims only: a leading blank is data to it, so a mask that blank-suppresses would produce
     fields it rejects.

   Worth stating once, because it looks like a contradiction of the service's own owner rule: the
   quoting below still has to handle an owner full of quotes and newlines even though no such owner
   can exist in the target. The export is legacy data from a `CHAR(32)` column that restricted nothing
   (`backend/cash-account-cobol/DB2-DDL/DB2DDL.jcl:L47`), and such a row must arrive **intact** to be
   recorded as a `STATE` / `INVALID_OWNER_IN_LEGACY` finding naming the owner it refused. A row mangled
   by an under-quoted unload is a finding an operator cannot act on; a row that fails the reader's
   parse aborts the whole single-transaction load and records nothing at all.

   The `CAST` widths are the worst case, not the typical one, because a `CAST` that is too narrow
   truncates silently: an owner of 32 characters that are all quotes doubles to 64 and quotes to 66,
   plus 11 for a signed balance, 18 for a similarly pathological currency and two commas — 97, hence
   `CHAR(100)` and `LRECL=100`. The rate row's worst case is 12 + 12 + 11 + 5 + 10 + four commas = 54,
   hence `CHAR(64)`. Trailing record padding after the final closing quote is harmless: the reader
   right-trims it.

   Before submission the mainframe team confirms three site facts against the deployed catalog, none of
   which this repository can settle: the second rate column's real spelling (`CYRRNBASE` here, see the
   alias note below), that `CURRENT DATE FORMAT`/`CHAR(date, ISO)` yields `YYYY-MM-DD`, and that
   `DSNTIAUL` is available at the site's `PLAN(DSNTIAUL)`. The `UNLOAD` template remains the primary
   path because it needs none of those confirmations.

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
   a delimited conversion may be supplied instead, or as well, becoming `history.csv` once step 3
   transcodes it and prepends the header
   `name,event_date,event_time,request_code,balance,currency,retcode`, with `event_date` as `YYYYMMDD`
   and `event_time` as `HHMMSS`. **Either shape satisfies this step**, and step 3's checksum guard
   requires at least one of them.

   For binary input `tool.history-record-length` is **mandatory** — `57` (what the program writes) or
   `100` (the cluster's `RECSZ`) — the file length must be an exact multiple of it, and any other value
   or a non-multiple length is rejected with an explicit error. That refusal is deliberate: a record
   length guessed from a file divides cleanly often enough to look right and shifts every field by a
   few bytes when it is wrong.

3. **Transfer, convert and checksum.** Copy each data set into a USS file first, then move it to the
   landing host. This is where the legacy system's customer data first sits in bulk on a
   general-purpose host, so the landing directory is created `0700` under `umask 077` and everything in
   it is held and destroyed as restricted material ([Evidence handling and
   classification](#evidence-handling-and-classification)); only the checksums below ever leave it:

   ```bash
   # On z/OS UNIX. -B suppresses code-page translation; use it for the binary history only.
   cp -B "//'<hlq>.HISTORY.SEQ'" history.cp037.bin     # only if the binary shape is delivered
   cp    "//'<hlq>.HISTORY.CSV'" history.ebcdic        # only if the text shape is delivered
   cp    "//'<hlq>.CASHACCT.CSV'" cashaccounty.ebcdic
   cp    "//'<hlq>.FRANKFRT.CSV'" frankfurt1.ebcdic
   ```

   **Tier 1 — transfer integrity, computed on both sides over the same bytes.** Hash the artifacts
   **before** any conversion, on z/OS UNIX with whatever digest utility the site holds (`openssl dgst
   -sha256`, the site's standard `csum`, or the digest the file-transfer tool itself reports), then again
   on the landing host, and compare. Hash the **USS files** the `cp` above produced, never the MVS data
   sets behind them: `cp` turns fixed-length records into newline-terminated lines, so a data set and
   its own USS copy are not byte-identical and comparing across that boundary reproduces exactly the
   mistake this tier exists to avoid. The USS copies, and only they, exist identically on both sides:

   ```bash
   # On the landing host, over the untouched transferred artifacts.
   set -- cashaccounty.ebcdic frankfurt1.ebcdic
   if [ -f history.cp037.bin ]; then set -- "$@" history.cp037.bin; fi
   if [ -f history.ebcdic ];    then set -- "$@" history.ebcdic;    fi
   if [ "$#" -lt 3 ]; then
     echo 'no history shape was transferred: deliver history.cp037.bin or history.ebcdic' >&2
     exit 1
   fi
   sha256sum "$@" | tee step1-transfer.sha256
   sha256sum -c step1-transfer.sha256
   ```

   The file list is built from what was actually delivered because the step above permits **either**
   history shape, and the guard rejects a transfer that carried neither. A fixed list would fail a
   perfectly valid binary-only or text-only rehearsal — and the failure would look like a transfer
   fault, which is the one thing this evidence exists to rule out.

   **Convert.** The text exports are EBCDIC and carry no column-name line, so each is transcoded and
   re-headered. Each conversion also gets a row-count check, because this is where a hash stops being
   able to help:

   ```bash
   # On the landing host. <region-ccsid> is the CCSID obtained as a precondition (for example IBM-037);
   # it is not assumed here for the same reason the tool does not assume it.
   umask 077                    # every file below is an owner set with its balances, in the clear
   iconv -f <region-ccsid> -t UTF-8 cashaccounty.ebcdic | tr -d '\r' > cashaccounty.body
   { echo 'owner,balance,currencyc'; cat cashaccounty.body; } > cashaccounty.csv
   iconv -f <region-ccsid> -t UTF-8 frankfurt1.ebcdic   | tr -d '\r' > frankfurt1.body
   { echo 'currnkey,currnbase,amount,rates,loaddt'; cat frankfurt1.body; } > frankfurt1.csv

   # The text history shape, when that is the one delivered.
   if [ -f history.ebcdic ]; then
     iconv -f <region-ccsid> -t UTF-8 history.ebcdic | tr -d '\r' > history.body
     { echo 'name,event_date,event_time,request_code,balance,currency,retcode'
       cat history.body; } > history.csv
   fi

   # Row counts: each body must hold exactly the rows the unload reported in its SYSPRINT/SYSTSPRT.
   wc -l cashaccounty.body frankfurt1.body history.body 2>/dev/null | tee step1-record-counts.txt
   ```

   **Tier 2 — tool-input provenance.** Hash the files the tool will actually open. These are the
   converted, re-headered artifacts, so they are byte-identical to **nothing** on z/OS and are never
   compared with a source-side value; their purpose is to bind a reconciliation result to one specific
   input, so that a later re-run can prove it read the same bytes:

   ```bash
   set -- cashaccounty.csv frankfurt1.csv
   if [ -f history.cp037.bin ]; then set -- "$@" history.cp037.bin; fi
   if [ -f history.csv ];       then set -- "$@" history.csv;       fi
   sha256sum "$@" | tee step1-tool-input.sha256
   sha256sum -c step1-tool-input.sha256
   ```

   **Do not compare the two tiers.** The DB2 text exports are transcoded from EBCDIC to UTF-8 and given
   a header line between tier 1 and tier 2, so a tier-1 hash can never equal its tier-2 counterpart, and
   an operator who expects it to will read a successful conversion as a corrupt transfer. What each tier
   answers is different and both answers are needed: tier 1 distinguishes "the reconciliation found a
   variance" from "the transfer lost or translated a byte" — two findings with opposite remedies — while
   tier 2 answers "which bytes produced this run". `history.cp037.bin` is the one artifact that appears
   in both lists, because it is transferred in binary and never converted; its two hashes must be equal,
   and a difference there is a transfer fault.

   The row counts are the conversion's own integrity check. A hash cannot bridge a code-page conversion,
   but a count can: `iconv` cannot add or lose a line, so a body whose line count differs from the row
   count the unload reported has lost records in the transfer or the conversion, and the step restarts
   rather than loads. `wc -l` is the record count here because the USS copy terminates every record,
   including the last, so no line goes uncounted. Record the unload's reported count — `UNLOAD` prints
   it in `SYSPRINT`, `DSNTIAUL` in `SYSTSPRT` — beside the `wc -l` output.

   When both history shapes are delivered the loader stages the **binary** one and ignores `history.csv`
   — it decodes `history.cp037.bin` whenever that file is present. Hashing both is still correct
   evidence, but only one of them is a load input.

4. **Load, then reconcile**, under one shared batch id:

   The tool reads its database connection from the environment. Load it from the 0600 `ca-db.env` of
   [Operator command safety](#operator-command-safety) — the password never appears on a command line,
   and the literal loader is what lets it contain shell metacharacters without being mangled:

   ```bash
   BATCH_ID=<uuid>
   while IFS='=' read -r k v; do [ -n "$k" ] && export "$k=$v"; done < ca-db.env

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

   Inside the container image the jar is `/deployments/app.jar`; the arguments are identical, and a
   container reads the same file directly with `--env-file ./ca-db.env`.

   When the step closes, clear the credential from the session and destroy the file
   (`unset JDBC_PASSWORD PGPASSWORD; shred -u ca-db.env`), as the convention requires.

   **The identifier model.** Every invocation is its own `migration_run` row with its own `run_id`; the
   `load` and the `reconcile` that judges it share the `--tool.batch-id`. A `load` runs in **one**
   database transaction — either the whole export is applied and the run is recorded `CLEAN`/`VARIANCE`,
   or nothing is applied and the run is `FAILED` — so a retry after a failure is simply a new `run_id`
   under the same `batch_id`, with no half-loaded state to clean up first.

   **What stops a retry double-counting an account, precisely.** Two facts, and the partial unique index
   is neither of them. `uq_ledger_entry_migration_load` on
   `ledger_entry (run_id, owner) WHERE event_type = 'MIGRATION_LOAD'` is scoped to **one** run: it
   guarantees one load event per owner per run, which is what makes a run's own rows countable, and a
   retry carries a **new** `run_id`, so the index never sees the two attempts as the same key. Across
   runs the safety comes from (1) the single transaction above — a failed run leaves no row for a retry
   to duplicate — and (2) an unchanged owner writing no event at all: the loader compares the exported
   balance and currency against the row it finds and returns without touching the account or the ledger
   when both already match, so re-loading an identical export adds nothing to either table. A re-load
   that *does* change a balance is meant to record that change, and it does — one event, under that
   run's id.

5. **Re-run both Step 0 catalog queries** — schema-qualified, in a session with no `search_path`
   override — and diff the output against `step0-catalog-baseline.txt`. The `cashaccount` rows must be
   byte-identical, and `cash_account` must now appear in `cash_account_rehearsal` and in **no other
   schema**: it must be absent from the schema that reports `on_search_path = t`, which is what proves
   the rehearsal stayed inside its own schema. This is the Step 1 row of the gate table in Step 0
   action 3.

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

**That second query returns owner-level rows** — an owner identifier, the legacy and migrated balances
and the difference between them — while the first returns only ids, counts and status. Its output is
therefore restricted-class evidence ([Evidence handling and
classification](#evidence-handling-and-classification)), written under `umask 077` into
`<restricted-dir>` and sealed, and the change record gets the roll-up below: the same counts the gate
is read from, with no owner and no balance among them.

```sql
SELECT v.variance_kind, v.status, count(*) AS row_count
  FROM cash_account_rehearsal.migration_reconciliation v
  JOIN cash_account_rehearsal.migration_run r ON r.run_id = v.run_id
 WHERE r.batch_id = '<uuid>'
   AND v.status <> 'MATCHED'
 GROUP BY v.variance_kind, v.status
 ORDER BY v.variance_kind, v.status;
```

| Item | Class | What it is |
| --- | --- | --- |
| `step1-migration-run.txt` | change record | The `migration_run` rows for the batch, including `characterization_status` |
| `step1-variances.txt` | **restricted** | Every `migration_reconciliation` row with status other than `MATCHED`, with the data owner's written disposition beside each. One owner identifier and two balances per row, which is why it is sealed and read in the store rather than attached |
| `step1-variance-summary.txt` | change record | The roll-up query's output — row counts by `variance_kind` × `status` — the SHA-256 of `step1-variances.txt` as it was read, and the data owner's acceptance statement naming that checksum |
| `step1-exit-codes.txt` | change record | The `load` and `reconcile` exit codes |
| `step1-transfer.sha256` | change record | Tier-1 checksums over the pre-conversion transferred artifacts, beside the source-side values they are compared with |
| `step1-tool-input.sha256` | change record | Tier-2 checksums over the files the tool opened — never compared with a source-side value, and the reference a re-run is proved against |
| `step1-record-counts.txt` | change record | The row count each unload reported, beside the `wc -l` of the converted body it produced |
| `step1-catalog-after.txt` | change record | Both catalog queries re-run, and their diff against the Step 0 baseline: `cash_account` in `cash_account_rehearsal` only, `cashaccount` unchanged. Catalog metadata only, no row of either table |
| `step1-tool-settings.txt` | change record | The `tool.history-record-length`, `tool.legacy-charset` and `tool.legacy-timezone` used, and the file definition / region configuration they came from — together with the `step1-tool-settings.env` emission of [Operator command safety](#operator-command-safety), whose field list is an allowlist of named non-credentials for exactly this reason |

The exports themselves — `cashaccounty.csv`, `frankfurt1.csv` and the history file — are the legacy
system's customer data in bulk. They are working input rather than evidence, only their checksums are
registered above, and they are held and destroyed exactly as restricted evidence is.

`characterization_status` must read `ACCEPTED` on every row of the batch. It is copied from the
`Status` field of [`legacy-characterization.md`](legacy-characterization.md) at run time, so a `DRAFT`
baseline is visible in the evidence rather than discoverable only by asking.

### Sign-off required

**The cash-account data owner** accepts the batch, reading `step1-variances.txt` **inside the
restricted store** — row by row, because acceptance is per row and every row carries an owner and two
balances. Acceptance means each row is either resolved — the cause found and the run repeated clean —
or reclassified `ACCEPTED_EXCEPTION` **with a written reason** recorded beside it in that store. What
reaches the change record is `step1-variance-summary.txt`: the counts by `variance_kind` and `status`,
the checksum of the artifact that was read, and the acceptance statement naming that checksum — so the
acceptance is tied to exactly the rows the data owner saw, and a later re-read can prove it was those
bytes. An unexplained `VARIANCE` row is not acceptable at any count: the point of the rehearsal is that
each one is cheap to investigate now and expensive to investigate in Step 3.

### Rollback criterion

Roll back on **any unresolved `VARIANCE` row**, on **any tier-1 checksum mismatch** between the
source-side and landing-host values, or on **any row count** that differs from the count its unload
reported. A tier-2 value has no counterpart to disagree with, so it is never itself a trigger; it is
the record of which bytes the run read.

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
- **Step 1's `load` is the run that prices this step's cross-currency replays.** Under
  `tool.rate-source=legacy-table` the expected values come from the staged `frankfurt1` rows, which
  belong to a `load` run — and a window carries its own batch id, so its batch holds no load of its
  own. The window therefore resolves the completed `load` of its batch id when there is one and
  otherwise **the most recent completed load in the schema**, which in the rehearsal schema is Step 1's.
  Nothing extra is passed for this; what it requires is that Step 1's load be the newest completed load
  in that schema when the window runs, and the window's log states which run it used (captured below).
  Passing Step 1's batch id instead of a fresh one pins it explicitly and is equally valid.
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

Capture the staging line the window logs — one of

```text
Legacy rate lookups resolve against staging run <run> of batch <batch>
Batch <batch> holds no completed load, so legacy rate lookups resolve against staging run <run> of batch <batch> - this schema's most recent completed load
```

— and confirm the named run is Step 1's load. It is what makes the window's expected values attributable:
a window whose replays were priced from a load nobody signed off measures parity against rates nobody
accepted. If the schema holds **no** completed load, each cross-currency line is recorded
`REJECTED_BY_TARGET / VARIANCE` with migrated value `EXCHANGE_RATE_UNAVAILABLE` and the window exits `2`
— an unpriced window fails rather than reporting agreement it never measured.

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

As in Step 1, the second query's rows are **per owner** and the first's are per window, so the review
listing is sealed and the change record carries the roll-up:

```sql
SELECT v.variance_kind, v.status, count(*) AS row_count
  FROM cash_account_rehearsal.migration_reconciliation v
  JOIN cash_account_rehearsal.migration_run r ON r.run_id = v.run_id
 WHERE r.batch_id = '<uuid>'
 GROUP BY v.variance_kind, v.status
 ORDER BY v.variance_kind, v.status;
```

| Item | Class | What it is |
| --- | --- | --- |
| `step2-windows.txt` | change record | One `migration_run` row per window, in capture order, with `variance_count`, exit code and the staging line naming the `load` run that priced the window |
| `step2-review.txt` | **restricted** | Every `RATE_SOURCE` and `REJECTED_BY_TARGET` row, each either accepted with a written reason or traced to a defect with its defect reference. Per-owner variance rows, read in the store by the reviewer |
| `step2-review-summary.txt` | change record | The roll-up query's counts by `variance_kind` × `status`, the SHA-256 of `step2-review.txt` as it was reviewed, and the review's verdict per kind — the acceptances and the defect references, without the owners they were found on |
| `step2-window-definition.txt` | change record | The agreed window boundaries and the agreed number of consecutive clean windows, recorded before the first window ran |

The captured `transactions.csv` / `legacy-responses.csv` streams are the same class as the Step 1
exports: real requests for real owners, working input rather than evidence, held and destroyed as
restricted material and never attached to a window's review.

The pass condition is **zero `VARIANCE` rows across the agreed number of consecutive windows.**
`RATE_SOURCE` and `REJECTED_BY_TARGET` rows are not automatically failures and are not automatically
passes either: each one is reviewed individually. A `REJECTED_BY_TARGET` row is the expected shape of a
deliberate behavioural improvement — the legacy program stored the absolute value of a negative result
where this service answers `422 INSUFFICIENT_FUNDS` — and confirming that is what the review is for.
Those rows carry status `ACCEPTED_EXCEPTION` and so do not count toward `variance_count`. One class of
refusal deliberately does: a line whose migrated value is `EXCHANGE_RATE_UNAVAILABLE` is recorded
`VARIANCE`, because the captured legacy reply succeeded with a computed balance and a target that cannot
price the same line is reporting its own configuration — an unresolved staging load, an unreachable
provider, a currency no staged row carries — rather than a characterized difference in behaviour. A
window in which every cross-currency transaction went unpriced must fail this gate, not pass it.

### Sign-off required

**Product owner and risk**, jointly, on the rows themselves: `step2-review.txt` is read in the
restricted store and the verdict is recorded in `step2-review-summary.txt` against that file's
checksum. Product owner for the behavioural result; risk because the accepted exceptions recorded here
are the documented differences between the two systems' answers, and those differences outlive this
step.

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

- **Steps 0, 1 and 2 signed off**, with their evidence registered: each change-record-class item
  attached and each restricted-class item sealed with its pointer in the change record.
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
- **The post-cutover scheduled reconcile exists, is owned, and is ready to run.** Gate (g) adjudicates
  its first run, a variance it records is a rollback criterion, and Step 4 does not begin until a window
  of its runs has passed. Nothing in this module schedules it — the only `@Scheduled` work in the
  service is the reservation expiry sweep — so it is an external mechanism, and it is specified here in
  full rather than assumed:

  | Attribute | Value |
  | --- | --- |
  | What it runs | The gate (b) `reconcile` invocation: `--spring.profiles.active=tool --tool.command=reconcile`, no schema override, from the image **digest** Step 0 recorded. Never `load`: a scheduled load would write into the live tables |
  | Its input | The **frozen** final export of gate (b), copied whole — every file it contains plus `step3-export.sha256` beside them — onto a read-only volume the job can reach. `reconcile` opens only `cashaccounty.csv` and `frankfurt1.csv`, which is why `--tool.history-record-length` is neither passed nor needed; the history files travel with them so the checksum file verifies against a complete directory |
  | Its baseline | The data owner's **`ACCEPTED`** characterization document, mounted and named with `-Dcashaccount.characterization-doc=<path>`. The jar carries a copy of the document as a classpath resource, which is the revision the image was built from and the value a run without the flag would record — so the flag is not a workaround for an image that carries nothing, it is what makes the recorded value the **signed** revision when the signature came after the build. Resolution order: the flag, then `docs/legacy-characterization.md` under the working directory, then the packaged copy, then `DRAFT`. A `DRAFT` value is what Step 1's rule — a `DRAFT` baseline is never accepted against a real export — forbids for these runs, since they read the real frozen export |
  | Input verification | `sha256sum -c` on both mounts before each run, so an input or a baseline that was replaced, truncated or partially copied fails the run instead of producing a clean one |
  | Input refresh | **None, by design.** Legacy writes are frozen at gate (a), so there is nothing new to export; and a refreshed export would be a different baseline, against which the watermark `W` recorded at gate (d) would mean nothing |
  | Cadence | Hourly for the first six hours after gate (f), then every six hours until the rollback window closes. Another cadence is permitted and must be recorded in the change record **before** gate (e); one slower than the interval at which the rollback decision is revisited is not, because a criterion that is only evaluated after the window has closed is not a criterion |
  | Batch id | A fresh one per run, generated by the run and echoed into its log, so each run's `migration_run` and `migration_reconciliation` rows are attributable to it |
  | Runs owned by | The on-call platform operator, who holds the schedule, its evidence, and the rollback decision |
  | Output adjudicated by | The cash-account data owner, per run, with the three checks of gate (g) — (g1) export against the state at `W`, (g2) every reported difference against the ledger, (g3) the run itself |
  | Evidence | Per run: `step3-run-<n>.txt` (the completed-run check), `step3-asof-w-<n>.txt` ((g1), which must be empty) and `step3-reconcile-<n>.txt` ((g2) with every row's verdict) — the latter two written into `<restricted-dir>`, because a row of either names an owner and its balances — plus `step3-reconcile-summary-<n>.txt`, the roll-up that carries the run into the change record with the job's log line, its batch id and the tool's numeric exit code |
  | A missed run | A gap in the evidence, not a clean run. The window counts as clean only if the cadence held: "no variance was reported" by a job that never ran is not a measurement |
  | Retired | When the rollback window closes, before Step 4's first retirement action, with the removal recorded |

  Publish the accepted baseline once, before the schedule is created, from the revision the data owner
  signed off:

  ```bash
  cd backend/cash-account-modernized/docs
  grep -n '^Status:' legacy-characterization.md            # must read ACCEPTED
  sha256sum legacy-characterization.md | tee step3-characterization.sha256 \
    > legacy-characterization.sha256
  kubectl -n <namespace> create configmap cash-account-characterization \
    --from-file=legacy-characterization.md --from-file=legacy-characterization.sha256
  ```

  The checksum travels in the same configMap as the document so the job can refuse a mismatched pair,
  and `step3-characterization.sha256` records with the change evidence which revision the schedule ran
  against.

  Realize the schedule as an operator-applied CronJob in the release namespace —

  ```yaml
  apiVersion: batch/v1
  kind: CronJob
  metadata:
    name: cash-account-scheduled-reconcile
    namespace: <namespace>
  spec:
    schedule: "0 * * * *"          # the recorded cadence; "0 */6 * * *" after the first six hours
    concurrencyPolicy: Forbid      # two reconciles of one frozen baseline race for nothing
    startingDeadlineSeconds: 300
    successfulJobsHistoryLimit: 24
    failedJobsHistoryLimit: 24
    jobTemplate:
      metadata:
        labels: { app.kubernetes.io/name: cash-account-scheduled-reconcile }
      spec:
        backoffLimit: 0            # a failed run is adjudicated, never silently retried
        template:
          spec:
            restartPolicy: Never
            containers:
              - name: reconcile
                image: <registry>/cash-account@sha256:<digest>
                command: ["/bin/sh", "-c"]
                args:
                  - |
                    set -e
                    ( cd /export          && sha256sum -c step3-export.sha256 )
                    ( cd /characterization && sha256sum -c legacy-characterization.sha256 )
                    BATCH_ID="$(cat /proc/sys/kernel/random/uuid)"
                    echo "scheduled reconcile batch_id=$BATCH_ID"
                    set +e
                    java -Dcashaccount.characterization-doc=/characterization/legacy-characterization.md \
                         -jar /deployments/app.jar \
                         --spring.profiles.active=tool \
                         --tool.command=reconcile \
                         --tool.input=/export \
                         --tool.batch-id="$BATCH_ID"
                    rc=$?
                    echo "scheduled reconcile batch_id=$BATCH_ID exit=$rc"
                    # 0 clean and 2 variances-recorded are both COMPLETED runs and the job succeeds;
                    # anything else - 1, or a JVM that never started - fails the job.
                    [ "$rc" = 0 ] || [ "$rc" = 2 ]
                env:
                  - name: JDBC_KIND
                    valueFrom: { configMapKeyRef: { name: <release>-config, key: database.kind } }
                  - name: JDBC_HOST
                    valueFrom: { configMapKeyRef: { name: <release>-config, key: database.host } }
                  - name: JDBC_PORT
                    valueFrom: { configMapKeyRef: { name: <release>-config, key: database.port } }
                  - name: JDBC_DB
                    valueFrom: { configMapKeyRef: { name: <release>-config, key: database.db } }
                  - name: JDBC_SSL
                    valueFrom: { configMapKeyRef: { name: <release>-config, key: database.ssl } }
                  - name: JDBC_ID
                    valueFrom: { secretKeyRef: { name: <release>-credentials, key: database.id } }
                  - name: JDBC_PASSWORD
                    valueFrom: { secretKeyRef: { name: <release>-credentials, key: database.password } }
                volumeMounts:
                  - { name: export, mountPath: /export, readOnly: true }
                  - { name: characterization, mountPath: /characterization, readOnly: true }
            volumes:
              - name: export
                persistentVolumeClaim: { claimName: <export-pvc>, readOnly: true }
              - name: characterization
                configMap: { name: cash-account-characterization }
  ```

  — or as the same command under the operator's existing scheduler on a host with database access,
  where in-cluster jobs are not permitted. Both write the same rows and produce the same evidence, and
  which one is used is recorded alongside the cadence.

  Each run's batch id and **numeric** exit code are captured, because a gate reads the rows *and* the
  code, and Kubernetes reduces both `1` and `2` to the same failed Job. The wrapper therefore echoes the
  code and then classifies it: `0` (clean) and `2` (variances recorded — the normal outcome once traffic
  is flowing) are completed runs and the job succeeds; anything else fails the job, which is what makes
  a failed Job mean "this run produced nothing to adjudicate" rather than "this run found something".
  `backoffLimit: 0` keeps a failed run from being silently retried under a second batch id. Read both
  per run:

  ```bash
  kubectl -n <namespace> get jobs --selector app.kubernetes.io/name=cash-account-scheduled-reconcile \
    -o custom-columns=JOB:.metadata.name,SUCCEEDED:.status.succeeded,FAILED:.status.failed
  kubectl -n <namespace> logs job/<job-name> | grep 'scheduled reconcile batch_id='
  ```

  A missing `exit=` line is itself a finding: the container died before the tool returned. Gate (g3)
  then catches the same condition from the database side, which is the check that does not depend on a
  log surviving.

  Two properties of that manifest are deliberate. It **is not a chart object and is not added to the
  chart**: no scheduled-job template exists for this service, and Prohibition 3's stop-and-flag rule
  covers chart *templates* — an operator-applied CronJob changes no template, no image of the running
  service and no routing value. And it holds **no credential of its own**: it reads the same configMap
  and secret keys the service's own pod reads
  (`infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L84-L119`), so
  nothing here widens who can reach the database.

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
   java -Dcashaccount.characterization-doc=/characterization/legacy-characterization.md \
        -jar /deployments/app.jar \
        --spring.profiles.active=tool \
        --tool.command=load \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID" \
        --tool.history-record-length=<57-or-100> \
        --tool.legacy-charset=<region-ccsid> \
        --tool.legacy-timezone=<region-zone>
   echo "load exit=$?"

   java -Dcashaccount.characterization-doc=/characterization/legacy-characterization.md \
        -jar /deployments/app.jar \
        --spring.profiles.active=tool \
        --tool.command=reconcile \
        --tool.input=<dir> \
        --tool.batch-id="$BATCH_ID"
   echo "reconcile exit=$?"
   ```

   `-Dcashaccount.characterization-doc` names the data owner's **signed** copy — the
   `cash-account-characterization` configMap published under
   [Preconditions](#preconditions-3), mounted at `/characterization` — and it is passed here for the same
   reason the scheduled reconcile passes it: these runs read the real frozen export, and
   `characterization_status` is read by (g3) and by Step 4's criteria. The jar carries a copy of the
   document as a classpath resource, so a container run without the flag records the revision the
   **image** was built from rather than degrading to `DRAFT`; the flag is what makes the recorded value
   the signed revision when the signature came after the build. Resolution order is: this flag, then
   `docs/legacy-characterization.md` under the working directory, then the packaged copy, then `DRAFT`.

   *Gate:* `variance_count = 0` on the reconcile run, **zero** `migration_reconciliation` rows with
   status `VARIANCE`, and both Step 0 catalog queries re-run against the Step 3(b) row of their gate
   table: the `cashaccount` rows byte-identical to `step0-catalog-baseline.txt`, and `cash_account`
   present in the schema that reports `on_search_path = t` — this load's target — and in no schema
   besides that one and `cash_account_rehearsal`.

3. **(c) Validate before routing.** For every owner in the final legacy export, read the institutional
   account view and compare its total with the exported balance. No caller is routed yet, so the target
   is **static** — which is what makes an exhaustive comparison both possible and conclusive here, and
   impossible ten minutes later. Reach the service directly rather than through broker, because broker
   still points at the legacy system.

   **There is no Service to reach yet, and creating one is not available at this gate.** A single flag,
   `cashAccount.enabled`, wraps the *whole* of `templates/cash-account.yaml`: the guard opens at `L15`
   and closes on the file's last line, after the Service, so while the flag is `false` neither the
   Deployment nor the `<release>-cash-account-service` exists, and `kubectl port-forward svc/…` answers
   `services "<release>-cash-account-service" not found`. Turning the flag on to create it would **be**
   gate (e): the same apply sets `CASH_ACCOUNT_ENABLED` for broker and portfolio, routing callers to a
   target this gate has not yet validated. Editing the template to split the two is a
   [stop-and-flag](../README.md#stop-and-flag) condition, not a runbook step.

   So validate through a **throwaway validation pod** the operator creates directly. It is not a chart
   object — no template is added or edited and **no chart value changes**, so the Step 0 snapshot still
   describes the release exactly — and it reads its configuration from the release's own ConfigMap and
   Secret, so no credential is retyped into a shell:

   ```bash
   kubectl apply -n <namespace> -f - <<'YAML'
   apiVersion: v1
   kind: Pod
   metadata:
     name: cash-account-cutover-validation
     labels:
       # Deliberately NOT app: cash-account. That is the Service selector the chart uses, so a pod
       # carrying it would start taking caller traffic the moment gate (e) creates the Service.
       app: cash-account-cutover-validation
   spec:
     restartPolicy: Never
     containers:
       - name: cash-account
         # The digest reference recorded in step0-image-digest.txt: the image gate (e) will deploy,
         # so what is validated here and what serves callers later are the same bytes.
         image: <registry>/cash-account@sha256:<hex>
         env:
           - name: AUTH_TYPE
             valueFrom: { configMapKeyRef: { name: <release>-config, key: auth.type } }
           - name: JDBC_KIND
             valueFrom: { configMapKeyRef: { name: <release>-config, key: database.kind } }
           - name: JDBC_HOST
             valueFrom: { configMapKeyRef: { name: <release>-config, key: database.host } }
           - name: JDBC_PORT
             valueFrom: { configMapKeyRef: { name: <release>-config, key: database.port } }
           - name: JDBC_DB
             valueFrom: { configMapKeyRef: { name: <release>-config, key: database.db } }
           - name: JDBC_SSL
             valueFrom: { configMapKeyRef: { name: <release>-config, key: database.ssl } }
           - name: JWT_ISSUER
             valueFrom: { configMapKeyRef: { name: <release>-config, key: jwt.issuer } }
           - name: JWT_AUDIENCE
             valueFrom: { configMapKeyRef: { name: <release>-config, key: jwt.audience } }
           # Required whenever the release runs AUTH_TYPE=oidc: that mode has no other verification
           # key source, so the service refuses to start on a blank value rather than accept
           # unverified tokens — the pod would never reach Ready and this gate would be unusable.
           # Optional, exactly as the chart marks it, so a basic/ldap release is unaffected.
           - name: OIDC_JWKS_URL
             valueFrom:
               configMapKeyRef: { name: <release>-config, key: oidc.jwksUrl, optional: true }
           # Carried so the validation workload is configured like the Deployment gate (e) creates,
           # rather than falling back to the built-in default endpoint. It must be an https URL
           # naming a host, with no user-info credentials and no fragment: the client validates it
           # in its constructor, so a plaintext endpoint fails start-up and this pod never reaches
           # Ready, exactly as with a blank OIDC_JWKS_URL above. The refusal reads
           # `cashaccount.fx.url (CURRENCY_API_URL) must use the https scheme, not http`.
           #
           # The FX budget is NOT carried here and does not need to be: this pod only reads account
           # views, which convert nothing and reach no rate provider. Where Step 0 action 6 decided a
           # CASHACCOUNT_FX_TIMEOUT for this network, it belongs on the Deployment gate (e) creates,
           # not on this pod.
           - name: CURRENCY_API_URL
             valueFrom:
               configMapKeyRef: { name: <release>-config, key: cashAccount.exchangeRateUrl }
           - name: JDBC_ID
             valueFrom: { secretKeyRef: { name: <release>-credentials, key: database.id } }
           - name: JDBC_PASSWORD
             valueFrom: { secretKeyRef: { name: <release>-credentials, key: database.password } }
           # Gate (b) already applied the schema to the production search path. A pod that exists to
           # read does not need DDL rights over the tables it is reading.
           - name: SPRING_SQL_INIT_MODE
             value: "never"
         ports:
           - containerPort: 8080
         readinessProbe:
           httpGet: { path: /actuator/health/readiness, port: 8080 }
           periodSeconds: 15
   YAML
   kubectl wait -n <namespace> --for=condition=Ready \
     pod/cash-account-cutover-validation --timeout=180s
   ```

   The ConfigMap and Secret names are the chart's defaults (`global.configMapName` and
   `global.secretName` render `<release>-config` and `<release>-credentials`). Whether this release
   overrides them is visible in `step0-values-structure.txt` as a set marker on those keys, and the
   override names themselves are read from the cluster (`kubectl -n <namespace> get configmap,secret
   -o name`) rather than from the snapshot: the structural file records that a key is set, not what it
   is set to, and a name is cheaper to read from the release than to justify a restricted read for. Add
   `cert_defaultTrustStore` from ConfigMap key
   `ssl.certs` if the release sets `global.specifyCerts`, and an `imagePullSecrets` entry naming
   `global.pullSecretName` if it sets `global.pullSecret` — the same two conditions the chart applies to
   the real Deployment. The readiness probe is the chart's own path, which is what makes
   `kubectl wait --for=condition=Ready` mean "the application answers" rather than "the container
   started".

   **The variable list above is every deployment input the application reads, and it is complete on
   purpose.** A validation pod missing one of them is not a smaller version of the real workload but a
   differently configured one, and the difference shows up as a gate that cannot pass: on an
   `AUTH_TYPE=oidc` release an absent `OIDC_JWKS_URL` fails start-up outright, so the pod never becomes
   Ready and the comparison never runs. `SPRING_SQL_INIT_MODE` is the single deliberate difference from
   the deployed Deployment. `TRACE_SPEC`, `REDIS_URL`, `KAFKA_*` and `CQRS_ENABLED` are absent because
   this service does not consume them at all.

   Extract the owner set from the final export's `cashaccounty.csv` into
   `<restricted-dir>/step3-owners.nul` with the CSV reader in [Operator command
   safety](#operator-command-safety), and take the token from the `curl` config file defined there. An
   owner may legitimately contain `/`, `;`, `&`, `?`, `#` or `%`, so an owner pasted into a URL is an
   owner that can change the request being made:

   ```bash
   kubectl port-forward -n <namespace> pod/cash-account-cutover-validation 8080:8080 &
   umask 077                            # the comparison is owner-level from its first line
   : > <restricted-dir>/step3-validation.tsv
   while IFS= read -r -d '' owner; do
     seg=$(ca_urlencode "$owner")
     body=$(curl -sS --config ./ca-auth.conf \
                 --url "http://localhost:8080/cash-account/institutional/accounts/$seg" \
                 -w '\n%{http_code}') || body=$'\n000'
     status=${body##*$'\n'}
     json=$(printf '%s' "${body%$'\n'*}" | tr -d '\n\t')
     printf '%s\t%s\t%s\n' "$seg" "$status" "$json" >> <restricted-dir>/step3-validation.tsv
   done < <restricted-dir>/step3-owners.nul
   ```

   The loop records a line per owner instead of stopping at the first failure, so a single `404`
   leaves a complete comparison to sign off rather than a truncated one. Each response carries
   `availableBalance`, `reservedBalance` and `totalBalance`. The owner is written in its encoded form
   and the body is stripped of tabs and newlines, so one owner is exactly one record — a raw owner or a
   reformatted body could otherwise split a line and silently drop an owner from the comparison.

   **The encoding is not a redaction.** `ca_urlencode` exists so an owner cannot alter a request, and
   `urllib.parse.unquote` reverses it exactly; a file of encoded owners each beside its balance is a
   file of owners and balances. So this file is restricted-class, and the change record gets counts:

   ```bash
   python3 - "<dir>/cashaccounty.csv" "<restricted-dir>/step3-validation.tsv" \
     > step3-validation-summary.txt <<'PY'
   """Roll gate (c)'s per-owner comparison up into the four numbers the gate is read from.

   Emits counts and one checksum. No owner and no balance appears in the output, by construction:
   which owners mismatched is read from step3-validation.tsv inside the restricted store.
   """
   import csv
   import decimal
   import hashlib
   import json
   import sys
   import urllib.parse

   # The export is the authority for both the owner set and the expected balance.
   export = {}
   with open(sys.argv[1], newline='', encoding='utf-8') as handle:
       for row in csv.DictReader(handle):
           owner = (row['owner'] or '').strip().upper()
           if owner:
               export[owner] = (row['balance'] or '').strip()

   compared = equal = mismatched = 0
   with open(sys.argv[2], newline='', encoding='utf-8') as handle:
       for line in handle:
           if not line.strip():
               continue
           seg, status, body = line.rstrip('\n').split('\t', 2)
           owner = urllib.parse.unquote(seg).upper()
           compared += 1
           try:
               document = json.loads(body)
               ok = (status == '200'
                     and owner in export
                     and decimal.Decimal(str(document['totalBalance']))
                         == decimal.Decimal(export[owner])
                     and decimal.Decimal(str(document['reservedBalance'])) == 0)
           except (ValueError, KeyError, TypeError, decimal.InvalidOperation):
               # A non-JSON body, a missing field or an unparseable figure is a mismatch, never a pass.
               ok = False
           equal += 1 if ok else 0
           mismatched += 0 if ok else 1

   print('owners_in_export=%d' % len(export))
   print('owners_compared=%d' % compared)
   print('owners_equal=%d' % equal)
   print('owners_mismatched=%d' % mismatched)
   with open(sys.argv[2], 'rb') as handle:
       print('sha256_step3_validation_tsv=%s' % hashlib.sha256(handle.read()).hexdigest())
   PY
   ```

   `owners_compared` must equal `owners_in_export` — a smaller count means the drive list was
   truncated, not that the target is clean — and `owners_mismatched` must be `0`. A non-zero count
   fails the gate; **which** owners it names is read in the store by the data owner, who signs the gate
   off on the rows themselves.

   Delete the pod as the closing action of this gate, before (e) is applied — a validation pod left
   running would still be holding a database connection and answering requests after the real
   Deployment appears, with nothing in the release describing it:

   ```bash
   kubectl delete -n <namespace> pod/cash-account-cutover-validation --wait=true
   kubectl get pod -n <namespace> cash-account-cutover-validation   # must answer NotFound
   ```

   *Gate:* for every owner, `totalBalance` equals the exported balance and `reservedBalance` is `0.00`;
   the owner set matches the export exactly, with no extra and no missing owner;
   `step3-validation-summary.txt` reads `owners_mismatched=0`; the validation pod is gone; and the
   release's live values still match the sealed Step 0 snapshot, proving the validation changed nothing
   a caller can reach.

   That check is a **full path-and-value comparison against the sealed snapshot, performed inside the
   restricted store** — the change record receives the changed paths and the verdict, never the values.
   Comparing only the redacted structure would not do: a rotated credential reads `<set>` on both sides,
   so a structural comparison passes a release whose secrets changed. Comparing raw YAML in the change
   record would not do either — that diff carries exactly the values that differ, which is the Step 0
   disclosure by another route.

   The comparison runs against whichever source Step 0 sealed, values or CR or both, because the loop
   is driven by the baselines that exist rather than by one hard-coded command. Restoring them is an
   authorized, recorded read by their custodian:

   ```bash
   umask 077
   cd <restricted-dir>
   # Restore the sealed step0-*-snapshot.{yaml,json} baselines here from <evidence-store-locator> -
   # a recorded read by the platform operator - then prove they are the sealed bytes before comparing.
   sha256sum -c ../step0-snapshot.sha256

   : > ../step3-values-unchanged.txt
   for src in values cr; do
     if [ ! -f "step0-${src}-snapshot.json" ]; then continue; fi
     case "$src" in
       values) helm get values <release> -n <namespace> --all -o yaml > "recheck-${src}.yaml"
               helm get values <release> -n <namespace> --all -o json > "recheck-${src}.json" ;;
       cr)     kubectl get stocktrader <name> -n <namespace> -o yaml  > "recheck-${src}.yaml"
               kubectl get stocktrader <name> -n <namespace> -o json  > "recheck-${src}.json" ;;
     esac
     comparison=clean
     # Braces, not a subshell, so the assignment inside survives the redirection.
     {
       printf 'source=%s\n' "$src"
       if cmp -s "step0-${src}-snapshot.yaml" "recheck-${src}.yaml"; then
         printf 'bytes_match_sealed_snapshot=yes\n'
       else
         printf 'bytes_match_sealed_snapshot=no\n'
       fi
       # No expectations file, so any difference at any path - redacted or not - exits non-zero.
       if python3 ca-compare.py "step0-${src}-snapshot.json" "recheck-${src}.json"; then
         printf 'comparison=clean\n'
       else
         comparison=DIFFERS
         printf 'comparison=DIFFERS\n'
       fi
     } >> ../step3-values-unchanged.txt

     # A difference is examined after the window, so the capture that produced it is sealed BEFORE the
     # local copy goes - the one ordering mistake that would leave a finding with no artifact behind it.
     if [ "$comparison" = DIFFERS ]; then
       sha256sum "recheck-${src}.yaml" "recheck-${src}.json" | tee -a ../step3-recheck.sha256
       echo "seal recheck-${src}.{yaml,json} into <evidence-store-locator> before continuing"
     fi
     shred -u "recheck-${src}.yaml" "recheck-${src}.json" 2>/dev/null \
       || rm -f "recheck-${src}.yaml" "recheck-${src}.json"
   done

   shred -u step0-values-snapshot.yaml step0-values-snapshot.json \
             step0-cr-snapshot.yaml step0-cr-snapshot.json 2>/dev/null \
     || rm -f step0-values-snapshot.yaml step0-values-snapshot.json \
              step0-cr-snapshot.yaml step0-cr-snapshot.json
   # `|| true`: ls exits 1 when a glob matches nothing, which is the passing case here and would
   # abort a session running under `set -e` on success.
   ls step0-*-snapshot.* recheck-* 2>/dev/null || true       # must print nothing
   cd -
   ```

   The pass condition is `comparison=clean` for every source, which is `changed=0` at full value
   fidelity. `bytes_match_sealed_snapshot` is beside it to separate the two ways a re-capture can
   differ: `no` with `comparison=clean` is a serialization difference — a different client version
   marshalling identical values — and the gate survives it; `comparison=DIFFERS` is a values change
   applied inside the window that this cutover did not record, and it is a no-go whatever the byte
   comparison says. The changed paths in the output name what to investigate; their values stay in the
   store, in the sealed recheck capture, for the custodian who investigates.

4. **(d) Record the ledger watermark.**

   ```sql
   SELECT COALESCE(MAX(entry_id), 0) AS w FROM ledger_entry;
   ```

   Store the value as `W` in the change record, **beside the Step 0 snapshot pointer.** `W` is a bare
   integer — a ledger position, carrying no owner, no balance and no credential — so it is
   change-record class, while the snapshot it is read alongside is not; the pointer is what ties the
   two together without moving either. `W` is the boundary
   between "state the migration put here" and "state a caller put here", and every later judgement —
   the scheduled reconcile's adjudication and the rollback's replay range — is expressed relative to
   it. It has to be read before gate (e): once callers are writing, `MAX(entry_id)` keeps moving and no
   longer marks the migration boundary, and nothing else in the database records where that boundary was.

   *Gate:* `W` is recorded in the change record, not only in a terminal.

5. **(e) Apply the cutover value set.** This is the action that routes traffic. Apply it against the
   Step 0 snapshot, not against the chart defaults — which is an authorized read of the sealed snapshot
   by the platform operator, its custodian, and the store records it like any other read. The applied
   set stays inside `<restricted-dir>` for the same reason the snapshot does: it is the snapshot plus
   the rows below:

   | Value | Set to | Why |
   | --- | --- | --- |
   | `cashAccount.enabled` | `true` | Deploys the service and sets `CASH_ACCOUNT_ENABLED` for broker and portfolio (`…/templates/broker.yaml:L103-L104`; `…/templates/cash-account.yaml:L15`) |
   | `cashAccount.image.repository` | `<registry>/cash-account@sha256` — the first of the two values recorded in `step0-image-digest.txt`, verbatim | The digest is the only reference that cannot be re-pointed after validation, and the chart joins these two fields with a colon (`…/templates/cash-account.yaml:L65`), so the reference is carried as `repository` = the `@sha256` prefix and `tag` = the hex. The whole `sha256:<hex>` in `tag` renders an invalid reference and the pod never starts — see [`../README.md#carrying-the-digest-through-the-charts-fixed-repositorytag-rendering`](../README.md#carrying-the-digest-through-the-charts-fixed-repositorytag-rendering) |
   | `cashAccount.image.tag` | The 64-character hex digest, with **no** `sha256:` prefix — the second recorded value, verbatim | As above; the pair renders `<registry>/cash-account@sha256:<hex>` |
   | `cashAccount.url` | `http://{{ .Release.Name }}-cash-account-service:8080/cash-account` — the chart default — **if the snapshot differs** | This is the path the controllers are mapped at; broker reads it as `CASH_ACCOUNT_URL` (`…/templates/broker.yaml:L97-L102`) |
   | `cashAccount.exchangeRateUrl` | Unchanged — and **an `https` URL** if this cutover changes it at all | Reaches the service as `CURRENCY_API_URL` (`…/templates/cash-account.yaml:L156-L160`), which `fx/FrankfurterExchangeRateClient` validates in its constructor: the value must use the `https` scheme, name a host, and carry no user-info credentials and no fragment. The refusal is a **start-up** failure — `cashaccount.fx.url (CURRENCY_API_URL) must use the https scheme, not http`, before the port is bound — so a plaintext rate mirror yields pods that never become Ready rather than a degraded rate lookup, and no property relaxes it (the rate multiplies into every cross-currency credit and debit and is written to the immutable ledger). See [`../README.md#currency_api_url-is-https-only-and-refused-at-start-up`](../README.md#currency_api_url-is-https-only-and-refused-at-start-up). If this value is changed here, Step 0 action 6's measurement was taken against the **old** endpoint: re-measure the new one and re-decide `CASHACCOUNT_FX_TIMEOUT` before (g), because the budget covers connection set-up and a different host is a different cost — see [`../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it`](../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it) |
   | `database.*` | **Not changed by this cutover** | The release is already on PostgreSQL as a Step 0 prerequisite, signed off separately, because these values are shared with portfolio |
   | `vault.enabled` | Remains `false` | The enabled branch injects Liberty container arguments a Spring Boot image cannot execute |

   No chart **template** is edited, at this gate or any other.

   *Gate:* the applied values differ from the sealed snapshot in **only** the rows above; the
   **rendered** image is the digest form, not the two values that produced it —

   The first half is **asserted, not read**: the applied capture is compared with the sealed snapshot at
   full value fidelity against the authorized change set, so a change to any other path — including one
   whose value the change record never prints — fails the gate rather than disappearing into a
   presence marker. Write the change set first, from `step0-image-digest.txt` and the table above:

   ```bash
   umask 077
   cd <restricted-dir>
   # Restore the sealed step0-*-snapshot.json baselines here again, and prove them, as at gate (c).
   sha256sum -c ../step0-snapshot.sha256

   # The authorized change set: JSON values, so `true` is the boolean and a string carries its quotes.
   # cashAccount.url is the value as applied - for the chart default that is the literal string with
   # its `{{ .Release.Name }}` expression, because `helm get values` reports values, not rendered output.
   cat > expected-cutover.tsv <<'TSV'
   cashAccount.enabled	true
   cashAccount.image.repository	"<registry>/cash-account@sha256"
   cashAccount.image.tag	"<hex>"
   cashAccount.url	"http://{{ .Release.Name }}-cash-account-service:8080/cash-account"
   TSV

   : > ../step3-values-diff.txt
   for src in values cr; do
     if [ ! -f "step0-${src}-snapshot.json" ]; then continue; fi
     case "$src" in
       values) helm get values <release> -n <namespace> --all -o json > "applied-${src}.json" ;;
       cr)     kubectl get stocktrader <name> -n <namespace> -o json  > "applied-${src}.json" ;;
     esac
     {
       printf 'source=%s\n' "$src"
       if python3 ca-compare.py "step0-${src}-snapshot.json" "applied-${src}.json" \
                                expected-cutover.tsv; then
         printf 'gate=pass\n'
       else
         printf 'gate=FAIL\n'
       fi
     } >> ../step3-values-diff.txt

     # The applied capture is the state callers are about to reach, so it is sealed as restricted
     # evidence in its own right before the local copy is destroyed.
     sha256sum "applied-${src}.json" | tee -a ../step3-applied.sha256
     shred -u "applied-${src}.json" 2>/dev/null || rm -f "applied-${src}.json"
   done

   shred -u step0-values-snapshot.yaml step0-values-snapshot.json \
             step0-cr-snapshot.yaml step0-cr-snapshot.json expected-cutover.tsv 2>/dev/null \
     || rm -f step0-values-snapshot.yaml step0-values-snapshot.json \
              step0-cr-snapshot.yaml step0-cr-snapshot.json expected-cutover.tsv
   ls step0-*-snapshot.* applied-* 2>/dev/null || true       # must print nothing; see gate (c)
   cd -
   ```

   `gate=pass` requires every difference to be `EXPECTED`. An `UNAUTHORIZED_PATH` line is a value this
   cutover did not authorize — a `database.*`, `oidc.*` or any other key — and an `UNAUTHORIZED_VALUE`
   line is an authorized key set to something other than the recorded value, which is where a mistyped
   digest lands. `cashAccount.enabled` and the two image fields must each read `EXPECTED`;
   `cashAccount.url` may read `NOT_APPLIED`, which means the snapshot already held the value and there
   was nothing to change. `step3-values-diff.txt` is the change-record evidence of the apply: changed
   paths, verdicts, and literal values only for the cutover keys the change record is entitled to.

   The loop records every sealed source before the gate is judged rather than aborting at the first
   failure — the same reason gate (c)'s owner comparison runs to the end — so the verdict is read from
   the file and a single `gate=FAIL` line is a no-go however the block itself exited.

   ```bash
   kubectl get deployment <release>-cash-account -n <namespace> \
     -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
   ```

   must print `<registry>/cash-account@sha256:<hex>`, which is checked rather than assumed because the
   invalid mapping differs from the valid one by a single character and fails as an image-pull error
   rather than as a values error; and the new pod passes its startup, readiness and liveness probes
   (`/actuator/startup`, `/actuator/health/readiness`, `/actuator/health/liveness` on port 8080 —
   `…/templates/cash-account.yaml:L204-L222`).

6. **(f) Roll broker and portfolio** so they pick up `CASH_ACCOUNT_ENABLED` and `CASH_ACCOUNT_URL`.
   Both read them from the environment at start-up, so an applied value that nothing restarted has
   changed nothing.

   *Gate:* both deployments are fully rolled and healthy, and the new pods' environment shows the
   intended values.

7. **(g) Confirm routing, then observe the first scheduled reconcile.** One read through broker for an
   owner whose balance is known from the final export:

   Read the owner from the same NUL-delimited list rather than typing it, encode it as one path
   segment, and take the token from the `curl` config file — [Operator command
   safety](#operator-command-safety) applies here exactly as it does at gate (c), and broker's own
   `{owner}` path segment is no more forgiving of an unencoded `/` than this service's:

   ```bash
   umask 077                            # the response names an owner and states that owner's balance
   IFS= read -r -d '' owner < <restricted-dir>/step3-owners.nul
   curl -sS --config ./ca-auth.conf \
        --url "http://<broker-host>:9080/broker/$(ca_urlencode "$owner")" \
     > <restricted-dir>/step3-routing.txt
   ```

   The `cashAccountBalance` and `cashAccountCurrency` fields in broker's response are populated from
   this service's `balance` and `currency`, so a correct value proves the whole path. The response is
   one named owner's balance, so it is sealed exactly as gate (c)'s comparison is, and the change record
   carries the verdict — that the figure matched the final export for the owner read — beside the file's
   checksum.

   Then let the first scheduled reconcile run, and adjudicate it with the **three** checks below. They
   answer different questions and none of them is optional: (g1) was the migration correct at the
   watermark, (g2) is every difference the run reports accounted for by the ledger, and (g3) did the run
   actually complete against the accepted baseline.

   Why three, and why none of them is the reconciler's own output read straight: the reconciler compares
   the frozen export against the target as it is **now**, it takes no watermark argument, and — the part
   that matters — it writes **no row at all** for an owner whose current state agrees with the export. A
   balance loaded wrongly at `W` and carried to the exported figure by later traffic therefore produces
   no variance row to triage, and the run looks clean. After gate (f), neither the run's exit code (`2`
   on any run that records a variance) nor its row set is a verdict on the migration.

   **(g1) The frozen export against the state at `W`.** This is the migration verdict, and it is
   exhaustive over both sides rather than over whatever the reconciler emitted. Every balance change
   writes a `ledger_entry` row in the same transaction as the change and the `ledger_entry_immutable`
   trigger forbids rewriting one, so an owner's last row at or below `W` *is* its state at `W`; gate (b)
   loaded into an empty production schema, so every exported owner has a `MIGRATION_LOAD` row at or
   below `W`. Save this as `step3-asof-w.sql` and run it from the directory holding the checksummed
   frozen export — `\copy` reads the file on the operator's side, and the temporary table needs no
   privilege beyond the connection's own:

   ```sql
   CREATE TEMP TABLE frozen_export (owner TEXT, balance NUMERIC(9,2), currencyc TEXT);
   \copy frozen_export FROM 'cashaccounty.csv' WITH (FORMAT csv, HEADER true)

   WITH export_norm AS (
       -- The export's own conventions: CHAR padding is trimmed and the owner key is uppercased, which is
       -- how the loader filed these owners; an empty unquoted field arrived as NULL.
       SELECT upper(btrim(owner)) AS owner, balance, upper(btrim(currencyc)) AS currency
         FROM frozen_export
   ),
   as_of_w AS (
       SELECT DISTINCT ON (owner)
              owner, entry_id, event_type, currency, available_after, reserved_after
         FROM ledger_entry
        WHERE entry_id <= <W>
        ORDER BY owner, entry_id DESC
   ),
   state_at_w AS (
       -- An owner whose last row at or below W is ACCOUNT_DELETED did not exist at W.
       SELECT * FROM as_of_w WHERE event_type <> 'ACCOUNT_DELETED'
   ),
   compared AS (
       -- FULL OUTER JOIN, so an owner missing from either side is a row rather than a silent omission.
       SELECT COALESCE(e.owner, s.owner) AS owner,
              e.balance AS export_balance, s.available_after AS balance_at_w,
              e.currency AS export_currency, s.currency AS currency_at_w,
              s.reserved_after AS reserved_at_w, s.entry_id AS entry_id_at_w,
              CASE
                WHEN e.owner IS NULL                         THEN 'EXTRA_AT_W'
                WHEN e.balance IS NULL OR e.currency IS NULL THEN 'EXPORT_INCOMPLETE'
                WHEN s.owner IS NULL                         THEN 'MISSING_AT_W'
                WHEN s.available_after <> e.balance          THEN 'BALANCE_AT_W'
                WHEN s.currency <> e.currency                THEN 'CURRENCY_AT_W'
                WHEN s.reserved_after <> 0                   THEN 'RESERVED_AT_W'
                ELSE 'MATCHED'
              END AS verdict
         FROM export_norm e
         FULL OUTER JOIN state_at_w s ON s.owner = e.owner
   )
   SELECT owner, verdict, export_balance, balance_at_w, export_currency, currency_at_w,
          reserved_at_w, entry_id_at_w
     FROM compared
    WHERE verdict <> 'MATCHED'
    ORDER BY verdict, owner;
   ```

   *Gate:* **no rows.** Every verdict is a defect at the watermark and none of them is explainable by
   later traffic — `MISSING_AT_W`, an exported owner the load never applied; `EXTRA_AT_W`, an owner the
   target held that the export does not, in a schema gate (b) required to be empty; `BALANCE_AT_W` and
   `CURRENCY_AT_W`, the load applied the wrong figure; `RESERVED_AT_W`, funds on hold at the watermark,
   which gate (c) required to be zero; `EXPORT_INCOMPLETE`, a NULL balance or currency in the export
   itself, which gate (b) required it not to carry.

   This answer is **fixed**: `W` does not move and the ledger cannot be rewritten, so the query returns
   the same rows tomorrow. Run it with every scheduled reconcile all the same — as an invariant rather
   than a measurement. A row appearing later means history itself changed, which the trigger exists to
   prevent.

   **(g2) Every reported difference accounted for against the ledger.** With (g1) clean the migration
   was right at `W`, so each difference a run reports has to come from post-watermark traffic. This
   check confirms that it did, by comparing the owner's **current** state with its **latest** ledger
   row. It does not ask whether some row exists above `W`: the existence of a later row says nothing
   about whether the current figure is the one that row recorded. Save it as `step3-adjudicate.sql`:

   ```sql
   WITH latest AS (
       SELECT DISTINCT ON (owner)
              owner, entry_id, event_type, currency, available_after, reserved_after
         FROM ledger_entry
        ORDER BY owner, entry_id DESC
   ),
   judged AS (
       SELECT v.owner, v.variance_kind, v.legacy_value, v.migrated_value,
              v.legacy_balance, v.migrated_balance,
              a.available_balance AS current_balance, a.currency AS current_currency,
              l.entry_id AS latest_entry_id, l.event_type AS latest_event_type,
              l.available_after AS latest_available_after, l.currency AS latest_currency,
              CASE
                -- Rows that describe the export or the load itself, not an account's movement: no caller
                -- write can account for them, and gate (b) required the export to produce none.
                WHEN COALESCE(v.legacy_value, '') IN
                     ('NULL_IN_LEGACY', 'INVALID_IN_LEGACY', 'INVALID_OWNER_IN_LEGACY',
                      'MISSING_IN_LEGACY')
                  OR COALESCE(v.migrated_value, '') = 'RESERVATIONS_OUTSTANDING'
                  OR v.variance_kind IN ('RATE_SOURCE', 'TRANSACTION_COUNT')
                  THEN 'SOURCE_ROW'
                -- Neither an account row nor any ledger history: the load never applied this owner,
                -- which is the same defect (g1) reports as MISSING_AT_W.
                WHEN a.owner IS NULL AND l.owner IS NULL THEN 'NEVER_HELD'
                -- The account is gone and its last event says so.
                WHEN a.owner IS NULL AND l.event_type = 'ACCOUNT_DELETED' THEN 'LEDGER_EXPLAINED'
                -- The account is exactly what its last event recorded, in all three figures.
                WHEN a.owner IS NOT NULL
                 AND a.available_balance = l.available_after
                 AND a.reserved_balance  = l.reserved_after
                 AND a.currency          = l.currency
                  THEN 'LEDGER_EXPLAINED'
                ELSE 'DRIFT'
              END AS verdict
         FROM migration_reconciliation v
         JOIN migration_run r     ON r.run_id = v.run_id
         LEFT JOIN cash_account a ON a.owner = v.owner
         LEFT JOIN latest l       ON l.owner = v.owner
        WHERE r.batch_id = '<uuid>'
          AND r.mode = 'RECONCILE'
          AND v.status = 'VARIANCE'
   )
   SELECT owner, variance_kind, legacy_value, migrated_value, legacy_balance, migrated_balance,
          current_balance, current_currency,
          latest_entry_id, latest_event_type, latest_available_after, latest_currency, verdict
     FROM judged
    ORDER BY CASE verdict WHEN 'LEDGER_EXPLAINED' THEN 1 ELSE 0 END, variance_kind, owner;
   ```

   *Gate:* every row's verdict is `LEDGER_EXPLAINED`; `verdict` is the last column, and the query sorts
   the other three first. Each of them fails the gate:

   - **`DRIFT`** — the account does not match its own last ledger event: a state change that wrote no
     event, an account row removed without an `ACCOUNT_DELETED` event, or an account with no ledger
     history at all. That contradicts the audit guarantee rather than a migration figure, and it is a
     stop condition in its own right.
   - **`NEVER_HELD`** — the target has neither an account row nor any ledger history for the owner, so
     the load never applied it; (g1) reports the same defect as `MISSING_AT_W`.
   - **`SOURCE_ROW`** — the export or the load produced a finding that gate (b) had already ruled out,
     so it is news whichever way it is read.

   **(g3) The run itself.** An empty (g2) output means "nothing to explain" only if the run happened;
   otherwise it means nothing was measured. Confirm the run before reading its rows:

   ```sql
   SELECT r.run_id, r.batch_id, r.mode, r.status, r.characterization_status,
          r.legacy_record_count, r.migrated_record_count, r.variance_count,
          r.started_at, r.finished_at
     FROM migration_run r
    WHERE r.batch_id = '<uuid>';
   ```

   *Gate:* **exactly one** row, with `mode = 'RECONCILE'`, `status` in (`CLEAN`, `VARIANCE`),
   `finished_at` not null, and `characterization_status = 'ACCEPTED'`. A `FAILED` or `RUNNING` row, or
   none at all, is a run that did not complete. `DRAFT` means the job could not read the data owner's
   accepted characterization document, and Step 1's rule — a `DRAFT` baseline is never accepted against
   a real export — holds here too, because these runs read the real frozen export.

   **Evaluating the three without losing a status.** Run them as a block that fails loudly rather than
   as a pipeline whose count hides a connection error. `psql` takes its password from `~/.pgpass` or an
   interactive prompt, never from the command line:

   ```bash
   set -euo pipefail
   umask 077                    # (g1) and (g2) emit owner rows whenever they are not clean
   PSQL="psql -h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 -q -At"

   # (g3) first: exactly one completed reconcile run for this batch, on an ACCEPTED baseline.
   $PSQL -c "SELECT count(*) FROM migration_run
              WHERE batch_id = '<uuid>' AND mode = 'RECONCILE'
                AND status IN ('CLEAN','VARIANCE') AND finished_at IS NOT NULL
                AND characterization_status = 'ACCEPTED'" > step3-run-<n>.txt
   test "$(cat step3-run-<n>.txt)" = 1

   # (g1) the export against the state at W: no rows at all.
   $PSQL -f step3-asof-w.sql > <restricted-dir>/step3-asof-w-<n>.txt
   test ! -s <restricted-dir>/step3-asof-w-<n>.txt

   # (g2) every reported difference explained by the ledger: no other verdict.
   $PSQL -f step3-adjudicate.sql > <restricted-dir>/step3-reconcile-<n>.txt
   test "$(grep -cv 'LEDGER_EXPLAINED$' <restricted-dir>/step3-reconcile-<n>.txt || true)" = 0
   ```

   Under `set -e` a failing `test` stops the block and names the check that failed, and because no
   `psql` runs inside a pipeline, a connection or SQL error stops it too instead of printing a `0` that
   reads as a pass. `-q` matters for the same reason: without it the temporary table and the `\copy`
   write `CREATE TABLE` and `COPY <n>` onto standard output, and `step3-asof-w-<n>.txt` would never be
   empty even on a clean run. Repeat the block for every later scheduled run, numbering the three files.

   Both query outputs are written into `<restricted-dir>`: each row of either carries an owner with its
   balances, and (g2)'s rows carry the reconciler's legacy and migrated figures as well. The change
   record gets the run count — which is already a bare `1` — and the roll-up below, whose counts and
   checksums are what the criteria in [Rollback criteria, each
   executable](#rollback-criteria-each-executable) are evaluated from:

   ```bash
   { printf 'completed_runs=%s\n' "$(cat step3-run-<n>.txt)"
     printf 'asof_w_rows=%s\n'    "$(wc -l < <restricted-dir>/step3-asof-w-<n>.txt)"
     printf 'reconcile_rows=%s\n' "$(wc -l < <restricted-dir>/step3-reconcile-<n>.txt)"
     # psql -At delimits with '|', so the verdict is the last field of each row.
     awk -F'|' 'NF { count[$NF]++ }
                END { for (v in count) printf "verdict_%s=%d\n", v, count[v] }' \
         <restricted-dir>/step3-reconcile-<n>.txt | sort
     sha256sum <restricted-dir>/step3-asof-w-<n>.txt \
               <restricted-dir>/step3-reconcile-<n>.txt
   } > step3-reconcile-summary-<n>.txt
   ```

   A clean run rolls up to `asof_w_rows=0` and `verdict_LEDGER_EXPLAINED` as the only verdict line, so
   the change record still carries the whole verdict; a failing run's rows are read in the store by the
   data owner, who adjudicates them per run.

   One note on the reconciler's rows, for reading (g2): its balance and currency checks are
   **independent** and each writes at most one row, so an owner may appear twice; each row is judged the
   same way, against that owner's latest ledger event, so two rows for one owner agree by construction.

### Evidence to capture

| Item | Class | What it is |
| --- | --- | --- |
| `step3-freeze.txt` | change record | The time the CICS transaction was disabled and by whom |
| `step3-export.sha256` | change record | Checksums of the fresh final export — both tiers of Step 1, since gate (b) reuses that procedure: the raw transfer hashes compared with the source side, and the tool-input hashes that are compared with nothing |
| `step3-migration-run.txt` | change record | The `migration_run` rows for gate (b)'s batch, both exit codes and `variance_count`. Gate (b) passes only at **zero** `migration_reconciliation` rows, so a passing file carries no owner; a failing run's per-owner variance listing is restricted and is handled exactly as Step 1's `step1-variances.txt`, with its counts rolled up here |
| `step3-catalog-after.txt` | change record | Both catalog queries re-run at gate (b), diffed against the Step 0 baseline, with the schema each relation was found in. Catalog metadata only |
| `step3-validation.tsv` | **restricted** | Gate (c): one record per owner — encoded owner, status, response body with all three balances — compared against the exported balance. The encoding is not a redaction, so this file is sealed and the data owner reads it in the store |
| `step3-owners.nul` | **restricted** | The owner list gate (c)'s loop was driven from: the final export's complete owner set |
| `step3-validation-summary.txt` | change record | Gate (c)'s roll-up: `owners_in_export`, `owners_compared`, `owners_equal`, `owners_mismatched` and the checksum of `step3-validation.tsv`, beside the image reference the validation pod ran and the `NotFound` confirming it was deleted before (e) |
| `step3-values-unchanged.txt` | change record | Gate (c)'s other half, per sealed source: `bytes_match_sealed_snapshot`, then the full-fidelity comparison of the re-capture against the sealed snapshot performed inside the store — `changed=0` and `comparison=clean` is the pass; any changed path is listed by path, with a value only where the path is an allowlisted cutover key |
| `step3-recheck.sha256` | change record | The checksum of a gate (c) re-capture that did **not** compare clean, sealed for the custodian's investigation. Absent on a clean gate, which is itself the statement that there was nothing to investigate |
| `step3-watermark.txt` | change record | `W`, recorded beside the Step 0 snapshot pointer |
| `step3-values-diff.txt` | change record | Gate (e), per sealed source: the applied capture compared with the sealed snapshot at full value fidelity against `expected-cutover.tsv`, and the `gate=pass` / `gate=FAIL` status that comparison exited with. Every changed path appears with its verdict — `EXPECTED`, `UNAUTHORIZED_PATH` or `UNAUTHORIZED_VALUE` — carrying a literal value only for the cutover keys. The values themselves are compared inside the store, never printed into the change record |
| `step3-applied.sha256` | change record | The checksums of the applied captures gate (e) sealed — the state callers are about to reach, tied to the comparison that passed it |
| `recheck-<src>.{yaml,json}`, `applied-<src>.json` | **restricted** | The gate (c) and gate (e) captures themselves — a re-capture is sealed only when its comparison was not clean, an applied capture always. Same content as the Step 0 snapshot and the same class: they are where a changed value is read, by the custodian, in the store |
| `step3-routing.txt` | **restricted** | The broker read from gate (g): one named owner's `cashAccountBalance` and `cashAccountCurrency`. The change record carries the verdict — that the value matched the final export for the owner read — this file's checksum and its pointer |
| `step3-run-<n>.txt` | change record | Gate (g3) per scheduled reconcile: the completed-run count, which must read `1`. Numbered from `1`, the run gate (g) observed |
| `step3-asof-w-<n>.txt` | **restricted** | Gate (g1) per scheduled reconcile: the frozen export against the state at `W`, which **must be empty**. It is sealed on its class rather than on its contents on the day — a clean run's file is empty, a failing run's names owners with their balances, and the class cannot depend on which one a reader is about to open. The change record carries `asof_w_rows` from `step3-reconcile-summary-<n>.txt`, and `0` is the gate |
| `step3-reconcile-<n>.txt` | **restricted** | Gate (g2) per scheduled reconcile: every `VARIANCE` row of that run with its verdict, all of which must read `LEDGER_EXPLAINED`. Owner, the reconciler's legacy and migrated figures, and the latest ledger row per line |
| `step3-reconcile-summary-<n>.txt` | change record | The per-run roll-up: `completed_runs`, `asof_w_rows`, `reconcile_rows`, one `verdict_<name>` count per verdict, and the checksums of the two restricted files — with the job's `batch_id` and `exit=` log line |
| `step3-characterization.sha256` | change record | The checksum of the `ACCEPTED` characterization document the schedule was given, tying its runs to the revision the data owner signed off |
| `step3-schedule.txt` | change record | The scheduled reconcile as it was actually set up: cadence, realization (CronJob or host scheduler), input and baseline mounts with their checksum files, owner, and the time it was retired |

The post-(e) rollback regime produces its own artifacts, all restricted; they are listed where they are
derived, under [After (e) — a state hand-back, not a repoint](#after-e--a-state-hand-back-not-a-repoint).

### Sign-off required

- **Platform operator and cash-account data owner** at gates **(b)** and **(c)** — the data gates. Both
  sign before gate (e) is applied.
- **Product owner** after gate **(g)**, on the confirmed routing and the adjudicated reconcile.

Both data gates are signed on the rows themselves, read **inside the restricted store**: gate (b)'s
variance listing if it produced one, and gate (c)'s `step3-validation.tsv` owner by owner. What the
change record carries is the summary and the checksum of the artifact that was read —
`step3-validation-summary.txt` reading `owners_mismatched=0`, `step3-values-unchanged.txt` with its
verdict, and gate (b)'s zero-variance run rows. Each signature therefore names the bytes it was given
and can be re-tied to them afterwards, without the owners having been attached to anything.

### Rollback criterion

**Two regimes, and they are not variations of each other.** Which one applies is decided by one
question: has gate (e) been applied?

#### Before (e) — nothing has changed for callers

Unfreeze legacy writes and **apply nothing**. No caller ever reached the new service, the legacy tables
are exactly as the freeze left them, and the loaded target data is inert. Leave the loaded rows in
place or clear the schema at leisure; neither choice affects a caller.

#### After (e) — a state hand-back, not a repoint

The target has accepted writes, so those writes exist nowhere else. Restoring routing without handing
the state back would silently discard them.

Every artifact this regime produces is **restricted-class** — `step3-held.nul`, `step3-releases.txt`,
`step3-ledger-above-watermark.csv` and `step3-rollback-replay.csv`. That is not a judgement about
sensitivity in the abstract: the ledger export carries an owner, a balance and an `order_reference` on
every line, the replay file carries one owner and one absolute balance per line, and the two
reservation files carry reservation ids. So the whole regime is run with `<restricted-dir>` as the
working directory and `umask 077` set — the file names below are unchanged and relative to it, because
the derivations and the replay-file contract are fixed — and every artifact is sealed and its local
copy destroyed once the rollback closes. What the change record carries is `step3-rollback.sha256`, the
row counts and the gate verdicts. A rollback is the moment when the temptation to paste a file into a
ticket is highest, and the urgency changes nothing about who reads that ticket.

In order:

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

   Release each one by reading the ids out of the first query as data — the same discipline as gate
   (c), and the token again from the `curl` config file of [Operator command
   safety](#operator-command-safety). `psql` takes its password from `PGPASSWORD` in the environment,
   never from `argv`:

   ```bash
   umask 077                            # both files below name reservations of named owners
   psql -h <host> -p <port> -U <id> -d <database> -tAc \
        "SELECT reservation_id FROM cash_reservation WHERE state = 'HELD'" \
     | tr '\n' '\0' > step3-held.nul

   kubectl port-forward svc/<release>-cash-account-service 8080:8080 -n <namespace> &
   while IFS= read -r -d '' rid; do
     [ -n "$rid" ] || continue
     curl -sS -X POST --config ./ca-auth.conf \
          --url "http://localhost:8080/cash-account/institutional/reservations/$(ca_urlencode "$rid")/release" \
          -o /dev/null -w "$rid %{http_code}\n"
   done < step3-held.nul | tee step3-releases.txt
   ```

   Both queries must return no rows before step 2 below. The operator sees the same condition as
   `reservedBalance` in `GET /cash-account/institutional/accounts/{owner}`. Release is idempotent by
   state, so a reservation released twice answers `200` with its current state rather than failing —
   which is what makes "release everything, then re-run the queries" a safe loop.

2. **Restore the snapshot values and roll broker and portfolio.** Read `step0-values-snapshot.yaml`
   (or the CR snapshot) out of the restricted store — verify it against `step0-snapshot.sha256` first,
   because a restore is the one action that has to be byte-exact — restore it **verbatim**, then roll
   both callers so they read the restored environment. The read is recorded; the copy is destroyed
   afterwards as [Evidence handling and
   classification](#evidence-handling-and-classification) requires.

   **The trap, stated plainly: `cashAccount.enabled: false` alone does not restore legacy routing.**
   It stops the new service from being deployed and sets broker's `CASH_ACCOUNT_ENABLED` to `false`,
   which disables the cash path rather than pointing it back at the legacy system. What restores legacy
   routing is the snapshot's own `CASH_ACCOUNT_URL` and enablement — which is the entire reason Step 0
   captures the live snapshot instead of trusting the repository's defaults.

3. **Confirm quiescence.** No `ledger_entry` row appended for 60 seconds:

   ```sql
   SELECT COALESCE(max(entry_id), 0) AS high_water FROM ledger_entry;
   ```

   Run it twice, 60 seconds apart: `high_water` must be the **same value in both**. Deriving a replay
   file from a range that is still growing produces a file that is already wrong when it is replayed,
   which is the only thing this gate exists to prevent.

   **Why the high-water mark is the whole check.** `entry_id` is `GENERATED ALWAYS AS IDENTITY` and the
   ledger is append-only — `UPDATE` and `DELETE` are rejected by the `ledger_entry_immutable` trigger and
   `TRUNCATE` by `ledger_entry_immutable_truncate`
   ([`../src/main/resources/schema/cash-account-schema.sql`](../src/main/resources/schema/cash-account-schema.sql))
   — so an unchanged maximum across the interval *is* "no row was appended", and nothing a count could
   add is missing from it. The cost differs sharply, on the one table in this schema that only ever
   grows: `max(entry_id)` is answered by an index-only backward scan of the primary key, reading a
   handful of pages whatever the ledger's size, while `count(*) … WHERE recorded_at > now() - interval
   '60 seconds'` has no index to lead with — the sole `recorded_at` index is
   `(owner, recorded_at DESC, entry_id DESC)`, whose leading column is the owner — so it scans the
   entire ledger, and twice, once per sample. Measured on a 200 000-row ledger on PostgreSQL 12: the
   windowed count is a parallel sequential scan touching 2 667 shared buffers in 24 ms and growing with
   the table; the high-water mark is an index-only scan touching 4 buffers in 0.09 ms and does not.

   If positive confirmation that no session is mid-transaction is also wanted, `pg_stat_activity` shows
   it. Read it as corroboration and never as the gate: a role without `pg_monitor` sees the `state` of
   other roles' sessions as NULL, so this query can report `0` while another role writes.

   ```sql
   SELECT count(*) AS active_sessions
     FROM pg_stat_activity
    WHERE datname = current_database()
      AND pid <> pg_backend_pid()
      AND state <> 'idle';
   ```

4. **Export the closed range above the watermark and checksum it.**

   ```bash
   umask 077                            # owner, balance and order_reference on every line
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
   in its own right, independent of the replay file derived from it — and it is the most concentrated
   customer-data artifact this document produces, so it is sealed and only its checksum, its line count
   and the `<W>` it starts above reach the change record.

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
   | `owner` | The owner the line hands back. **Exactly one line per owner** touched after the watermark, whatever that owner's history in the range |
   | `op` | `X` when that owner's **last** ledger row in the range is `ACCOUNT_DELETED`; otherwise `A` when the owner is absent from **this cutover's** final legacy export and `U` when it is present. The `X` test is applied first |
   | `balance` | The owner's **absolute** end state — `available_after` from that last row. Never a sum, never a delta |
   | `currency` | `currency` from that same last row |
   | `first_entry_id` / `last_entry_id` | The closed ledger range the line summarizes, inclusive at both ends; equal when the owner has one row in the range |
   | `incarnation_id` | The incarnation of that same **last** row: the account life the balance belongs to, so the hand-back can never be applied to an earlier life of the same owner name |

   Ordering is by **ascending `last_entry_id`**, and `seq` simply numbers that order. The file follows
   the delimited conventions of the export format (UTF-8, LF, header line, comma-delimited, two-place
   decimals). **No checksum line is embedded**: the checksum is recorded beside the file in the
   evidence, so the CSV stays free of comment syntax and every line remains a parseable record.

   **One line per owner, not per account life.** The grouping key is the owner alone. An owner deleted
   and created again above the watermark has ledger rows under two `incarnation_id`s, and grouping on
   the pair would emit two lines for it — `X` for the deleted life and `U` for the recreated one.
   Replayed in that order legacy deletes its row and then finds nothing to update, so legacy ends with
   **no** row for an owner the target still holds a balance for. The owner's last row therefore decides
   everything: the operation, the balance, the currency and the incarnation. `RollbackReplayFileTest`
   carries that case as `RYAN` (deleted at entry 109, created again at 110, one `U` line stamped with
   the second incarnation).

   **The reservation rule is read off that last row too, never off the range.** Step 1 above releases
   every `HELD` reservation, so a correct rollback reaches this point with nothing held; the check here
   is that the owner's **end state** holds nothing. It is not `max(reserved_after)` over the range: the
   ledger is append-only, so the rows that recorded a hold released or settled weeks ago are still
   there, and a rule read over all of them would refuse to hand back any owner that has ever had a
   hold — which is every owner with ordinary institutional activity. A non-zero `reserved_after` on the
   last row is a real violation: the file carries one available balance per owner and legacy has no
   reservation concept, so the held amount would vanish from the hand-back. That **aborts the
   derivation before any file is written**, naming the owner and the entry, rather than dropping the
   owner's line. `RollbackReplayFileTest` carries that case as `ERIC` (held and released at 111-112,
   held and settled at 113-114: one line, and the same range with funds still held on entry 114 fails).

   **Why absolute state, and never `C`/`D`.** Replaying the end state through the legacy `A`/`U`/`X`
   codes reproduces the target's state exactly and is **independent of exchange rates**. A credit/debit
   replay would re-apply the legacy `RATES` join to each amount and land on a different balance whenever
   a rate differed from the one the target used — so it would be a rate-sensitive reconstruction of a
   number that is already known exactly. No rate, and no per-transaction detail, is replayed.

   The script below implements the contract against the production schema. Check its output against
   the table above before handing it to the mainframe team: it is a convenience, and the contract is
   the authority. Run it from `<restricted-dir>`, with `cashaccounty.csv` of gate (b)'s **final**
   export staged there; every object it creates is `TEMP`, so the production schema is unchanged by
   it, and both `\copy` commands are client-side like step 4's `TO STDOUT` — no database file-write
   privilege is involved and the file lands where the operator is working. Substitute three recorded
   values before running: `<W>` from `step3-watermark.txt`, and the accepted final load's run id and
   batch id from `step3-migration-run.txt`. The quoted heredoc passes them through untouched, so an
   unsubstituted placeholder fails loudly rather than matching nothing.

   ```bash
   psql -h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 <<'SQL'
   -- The owner set of THIS cutover's final legacy export, staged for the A-versus-U decision.
   CREATE TEMP TABLE final_export_account (owner text, balance text, currencyc text);
   \copy final_export_account FROM 'cashaccounty.csv' WITH (FORMAT csv, HEADER true)
   CREATE TEMP TABLE final_export_owner AS
       SELECT DISTINCT upper(btrim(owner)) AS owner FROM final_export_account;

   -- The owner set gate (b)'s accepted load actually applied, read from that run's own rows.
   CREATE TEMP TABLE final_load_owner AS
       SELECT DISTINCT owner FROM ledger_entry
        WHERE event_type = 'MIGRATION_LOAD' AND run_id = '<gate (b) load run id>';

   -- Abort unless the staged file IS that export: equal cardinality is not equal membership, and a
   -- same-sized wrong export inverts A and U for the owners it disagrees about.
   DO $staged$
   DECLARE loaded_not_staged text; staged_not_loaded text;
   BEGIN
       IF NOT EXISTS (SELECT 1 FROM migration_run
                       WHERE run_id = '<gate (b) load run id>'
                         AND batch_id = '<gate (b) batch id>'
                         AND mode = 'LOAD'
                         AND status = 'CLEAN') THEN
           RAISE EXCEPTION 'run % is not a CLEAN LOAD run of batch %',
               '<gate (b) load run id>', '<gate (b) batch id>'
               USING HINT = 'name the accepted final load recorded in step3-migration-run.txt';
       END IF;

       SELECT string_agg(owner, ', ' ORDER BY owner) INTO loaded_not_staged
         FROM (SELECT owner FROM final_load_owner EXCEPT SELECT owner FROM final_export_owner) d;
       SELECT string_agg(owner, ', ' ORDER BY owner) INTO staged_not_loaded
         FROM (SELECT owner FROM final_export_owner EXCEPT SELECT owner FROM final_load_owner) d;

       IF loaded_not_staged IS NOT NULL OR staged_not_loaded IS NOT NULL THEN
           RAISE EXCEPTION 'staged export is not the export gate (b) loaded: loaded but not staged [%];'
               ' staged but not loaded [%]',
               COALESCE(loaded_not_staged, 'none'), COALESCE(staged_not_loaded, 'none')
               USING HINT = 'stage the checksummed final export of gate (b); no file is written';
       END IF;
   END
   $staged$;

   CREATE TEMP VIEW rollback_replay AS
   WITH ranged AS (
           SELECT owner, incarnation_id, entry_id, event_type,
                  available_after, reserved_after, currency
             FROM ledger_entry
            WHERE entry_id > <W>
        ),
        bounds AS (
           SELECT owner,
                  min(entry_id) AS first_entry_id,
                  max(entry_id) AS last_entry_id
             FROM ranged
            GROUP BY owner                      -- one line per owner, never per account life
        ),
        final_state AS (
           SELECT b.owner, b.first_entry_id, b.last_entry_id,
                  r.event_type, r.available_after, r.reserved_after,
                  r.currency, r.incarnation_id  -- the LAST row's incarnation, balance and currency
             FROM bounds b
             JOIN ranged r ON r.owner = b.owner AND r.entry_id = b.last_entry_id
        )
   SELECT row_number() OVER (ORDER BY f.last_entry_id) AS seq,
          f.owner AS owner,
          CASE WHEN f.event_type = 'ACCOUNT_DELETED' THEN 'X'
               WHEN NOT EXISTS (SELECT 1 FROM final_export_owner e
                                 WHERE e.owner = f.owner) THEN 'A'
               ELSE 'U' END AS op,
          f.available_after::text AS balance,
          f.currency AS currency,
          f.first_entry_id AS first_entry_id,
          f.last_entry_id AS last_entry_id,
          f.incarnation_id AS incarnation_id,
          f.reserved_after AS final_reserved_after
     FROM final_state f;

   -- Abort before any file exists if an owner's END STATE still holds funds. Not a filter: a dropped
   -- line is an owner whose money is never handed back, so this must stop the run instead.
   DO $guard$
   DECLARE outstanding text;
   BEGIN
       SELECT string_agg(format('%s (last entry %s, ledger reserved_after %s, account reserved_balance %s)',
                                r.owner, r.last_entry_id, r.final_reserved_after,
                                COALESCE(a.reserved_balance, 0)), '; ' ORDER BY r.owner)
         INTO outstanding
         FROM rollback_replay r
         LEFT JOIN cash_account a ON a.owner = r.owner
        WHERE r.final_reserved_after <> 0 OR COALESCE(a.reserved_balance, 0) <> 0;
       IF outstanding IS NOT NULL THEN
           RAISE EXCEPTION 'rollback replay derivation aborted: reserved funds outstanding for %', outstanding
               USING HINT = 'release every HELD reservation (step 1 above) and re-run; no file is written';
       END IF;
   END
   $guard$;

   \copy (SELECT seq, owner, op, balance, currency, first_entry_id, last_entry_id, incarnation_id FROM rollback_replay ORDER BY seq) TO 'step3-rollback-replay.csv' WITH (FORMAT csv, HEADER true)
   SQL
   echo "derivation exit=$?"
   ```

   Four notes on that script, each answering a way the derivation can be silently wrong.

   - **`GROUP BY owner`, and the last row supplies the incarnation.** Grouping on
     `(owner, incarnation_id)` emits two lines for an owner deleted and created again above the
     watermark, and replaying them hands legacy a deletion followed by an update of nothing.
   - **The guard reads `final_reserved_after`, not `max(reserved_after)`.** The ledger keeps every hold
     that was ever taken, so a maximum over the range rejects owners whose funds were released or
     settled long ago. It also checks live `cash_account.reserved_balance`, which is the same condition
     the operator saw as `reservedBalance` in step 1. With `ON_ERROR_STOP=1` the `RAISE` aborts the
     script before the `\copy`, so a violation leaves **no** file rather than a partial one — confirm
     `derivation exit=0` and the file's presence together.
   - **`A` is decided by this cutover's own export, staged above.** Not by the absence of a
     `MIGRATION_LOAD` row at or below `W`: the ledger outlives accounts and incarnations, so a load
     from an earlier attempt or an earlier migration of the same owner name makes a genuinely new
     account look pre-existing and emits `U` — and legacy then has no row to update. The staged set is
     the file whose checksum is in the Step 3 evidence, which is what ties the decision to this
     cutover. Which export is staged therefore decides every `A` and every `U`, so the script proves
     it is gate (b)'s by **exact set comparison, in both directions**, and aborts otherwise: an owner
     the load applied but the staged file omits would be emitted `A` and legacy would reject the add it
     already has, while an owner the staged file carries but the load never applied would be emitted
     `U` and legacy would have no row to update. A count comparison cannot see either — staged
     `{A, C}` and loaded `{A, B}` both count 2 — which is why the two `EXCEPT` differences, not
     cardinality, are what raise. The loaded set comes from the `MIGRATION_LOAD` rows of the **run id**
     recorded in `step3-migration-run.txt`, not from the batch id: a load retried after a failure is a
     new `run_id` under the same `batch_id`, so a batch can hold several `LOAD` runs and only the
     accepted `CLEAN` one defines the set — which the script asserts before comparing. Those rows are a
     complete picture of the export because gate (b) loaded into a schema with no `cash_account` rows,
     so the loader inserted every exported owner and wrote one row for each (an unchanged owner writes
     no row, and there were none). Verify the staged file against its recorded checksum before running,
     too, since a truncated copy of the right export is a set difference this comparison then reports:

     ```bash
     grep -q "$(sha256sum cashaccounty.csv | cut -d' ' -f1)" step3-export.sha256 \
       || echo 'staged export does not match the gate (b) checksum evidence'
     ```

     The staging, the assertions and the `\copy` are one psql session on purpose: `final_export_owner`
     and `final_load_owner` are `TEMP` tables and do not outlive it.
   - **`available_after::text`, not `to_char(...)`.** The column is `NUMERIC(9,2)`, so its text form is
     already the two-place decimal the contract requires, including `0.00`; a `to_char` format mask
     renders that value as `.00` and fails the reader's anchored decimal pattern.

   Verify the line count against the owners actually in the range — owners, not owner/incarnation
   pairs, because the pair count exceeds the required line count exactly when an owner was deleted and
   recreated, and would approve a file with two lines for it:

   ```sql
   SELECT count(DISTINCT owner) AS owners_in_range
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

   The file reaches them over the **access-controlled channel the evidence-handling determination
   names**, and over no other: it holds one owner and that owner's balance per line, so mail, chat and a
   ticket attachment are each excluded — they would copy the whole owner set into a system with its own
   readership. The handover is recorded like a read of the store, naming the recipient, the time and the
   checksum from `step3-rollback.sha256`; that checksum is what the mainframe team verifies the file
   against before replaying it, and it is the only part of the file the change record holds.

7. **Reconcile a fresh legacy export against the target** as of the export watermark.

   *Gate:* zero `VARIANCE` rows. This is the proof that the hand-back landed: the legacy system now
   holds the state the target accepted.

8. **Unfreeze legacy writes.** Only after step 7's gate passes. Unfreezing first would let new legacy
   writes interleave with the replay and make the reconcile unreadable.

#### Rollback criteria, each executable

- **Any row from gate (g1)** — the frozen export against the state at `W` — on any scheduled run. Each
  such row is a migration defect that was present at the watermark, and no later activity on that owner
  excuses it.
- **Any gate (g2) row whose verdict is not `LEDGER_EXPLAINED`**: a `DRIFT` row (an account that does not
  match its own last ledger event) or a `SOURCE_ROW` row (a finding about the export or the load that
  gate (b) had ruled out).
- **A scheduled reconcile that did not complete** — gate (g3) failing: no `migration_run` row for the
  batch, a `FAILED` or `RUNNING` row, or `characterization_status = 'DRAFT'`. An empty adjudication from
  a run that never happened is not a clean result.
- **A missed scheduled reconcile**, once the cadence recorded before gate (e) has lapsed and the run is
  not made good: an unevaluated criterion is a failed one, as with any gate here.
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
  | The **evidence** artifacts of Steps 0-3 as well: the sealed values/CR snapshot, the owner-level variance, validation and adjudication files, the ledger export above the watermark, and the rollback replay file — each with its class from [Evidence handling and classification](#evidence-handling-and-classification) | They are the record of how the migration was judged, they carry credentials and customer data, and they are already in the restricted store awaiting exactly this answer. Left out of it, they are held indefinitely by default — the outcome prohibition 4 exists to prevent |
  | For how long | After the first retirement action the exports are the only copy; the period decides how long that copy must survive |
  | On what medium, in what location, under whose control | An export nobody can locate is not retention |
  | Who may read it, and how access is recorded | Financial records usually carry access constraints of their own |
  | When and how it is disposed of, and who authorizes disposal | Retention without a disposal rule becomes indefinite by accident |

  Until that answer exists in writing, **this step does not begin.** Waiting costs nothing: this step is
  reached only after a cutover that is already complete and stable, with every earlier step's evidence
  registered against its own change record — attached or sealed with its pointer, by class — and
  nothing decays while the answer is obtained.

- **An immutable export of the final legacy state**, stored as the retention answer requires:
  `UNLOAD` of `STOCKTRD.CASHACCOUNTY` and `STOCKTRD.FRANKFURT1` and `REPRO` of
  `SYSD.STOCK.HISTORY`, exactly as in Step 1, with checksums recorded.

- **An export of the target's audit state**, covering the ledger and the rows that reference it. All
  three files are restricted-class — they are the complete owner, balance and ledger history of the
  service — so they are produced under `umask 077` in `<restricted-dir>`, sealed as the retention
  answer directs, and represented in the change record by `step4-target-export.sha256`:

  ```bash
  umask 077
  cd <restricted-dir>
  PG="-h <host> -p <port> -U <id> -d <database> -v ON_ERROR_STOP=1 -q"

  psql $PG -c "COPY (SELECT * FROM ledger_entry ORDER BY entry_id) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-ledger-entry.csv
  psql $PG -c "COPY (SELECT * FROM cash_reservation ORDER BY created_at, reservation_id) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-cash-reservation.csv
  psql $PG -c "COPY (SELECT * FROM cash_account ORDER BY owner) TO STDOUT WITH (FORMAT csv, HEADER true)" > step4-cash-account.csv
  ```

  ```bash
  # The checksum file is the change-record item, so it is written beside the restricted directory.
  sha256sum step4-ledger-entry.csv step4-cash-reservation.csv step4-cash-account.csv \
    | tee ../step4-target-export.sha256
  cd -
  ```

- **The agreed post-cutover rollback window has elapsed** with clean scheduled reconciles throughout.
  The window is what makes Step 3's hand-back available; retiring the legacy assets ends it, so the
  window must be over rather than merely uneventful so far.

  "Clean throughout" is checkable, not impressionistic. For every run the cadence recorded before gate
  (e) called for — **with no run missing** — the three files of [Step 3(g)](#actions-3) exist and each
  passes its own gate: `step3-run-<n>.txt` reads `1`, so the run completed on an `ACCEPTED` baseline;
  `step3-asof-w-<n>.txt` is **empty**, so the frozen export still matches the state at `W`; and every
  row of `step3-reconcile-<n>.txt` carries the verdict `LEDGER_EXPLAINED`. The last two are restricted,
  so the window is assembled from each run's `step3-reconcile-summary-<n>.txt` — `asof_w_rows=0` and
  `verdict_LEDGER_EXPLAINED` as the only verdict line say the same thing in counts — and any run whose
  roll-up is not clean is read in the store before the window is judged. An empty adjudication from a
  run that did not happen counts as a missing run, not a clean one. The scheduled reconcile is then
  retired, before the first retirement action below, and the time of its removal is recorded with the
  rest of its evidence.

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

| Item | Class | What it is |
| --- | --- | --- |
| `step4-retention-answer.*` | change record | The written retention requirement from the requesting organization, as received. It is the authority every other row here cites, and the answer that closes the Steps 0-3 evidence question above |
| `step4-legacy-export.sha256` | change record | Checksums of the final `UNLOAD` and `REPRO` exports |
| `step4-target-export.sha256` | change record | Checksums of the `ledger_entry`, `cash_reservation` and `cash_account` exports |
| `step4-ledger-entry.csv`, `step4-cash-reservation.csv`, `step4-cash-account.csv` | **restricted** | The exports themselves: every ledger row with its owner, amounts and `order_reference`, every reservation, and every account balance. Sealed under the retention answer; the change record holds their checksums and nothing else |
| `step4-retention-location.txt` | change record | Where each retained artifact is stored, under whose control, and the disposal date the answer above sets for it |
| `step4-retirements.txt` | change record | Per-asset retirement confirmation, in execution order with timestamps: CICS transaction, plan, package, each table, the cluster |
| `step4-reconciles.txt` | change record | The window's roll-up of the Step 3(g) evidence: one line per scheduled run with its batch id, exit code, completed-run check, (g1) row count and (g2) verdict counts — showing the cadence held with no run missing, every (g1) file empty and every (g2) row `LEDGER_EXPLAINED`; with the time the schedule was retired. Counts and verdicts only, taken from each run's `step3-reconcile-summary-<n>.txt` |

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
| A supported framework line, or written acceptance of the residual advisories that ship on the mandated one | [Step 0](#step-0--prerequisites), on the requesting organization's written determination — stay on 3.3.13 with the stated controls, buy commercial support, or authorize a later minor line — recorded with the authorization of the nine dependency overrides (row D1) | **Pending** |
| The database-identity posture — split DDL-owning and DML-only roles with `SPRING_SQL_INIT_MODE=never` (which needs the chart's second secret key), or the single identity with its residual accepted | Step 0, on the cash-account data owner's recorded decision in `step0-db-identity.txt` | **Pending** |
| Network-layer constraint on who may reach `/actuator` and `/metrics`, or a recorded acceptance of the LOW residual disclosure | Step 0, on the platform owner's record in `step0-actuator-reachability.txt` | **Pending** |
| The FX budget checked against the path between the cluster and the rate provider — the cold connection cost measured from a pod, and `CASHACCOUNT_FX_TIMEOUT` either set from it or recorded as unnecessary | Step 0 action 6, on the platform owner's record in `step0-fx-budget.txt` | **Pending** |
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
| [`../README.md#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them`](../README.md#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them) | The nine advisory-driven version overrides this service ships, the three advisories on the `spring-boot` artifacts that no override can reach with the controls that stand in their place, and the authorization record Step 0 collects — the evidence the framework-support determination is made against |
| [`../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it`](../README.md#the-fx-budget-covers-connection-set-up-and-a-cold-tls-handshake-can-exceed-it) | What `cashaccount.fx.timeout` budgets, the cold-handshake measurement Step 0 action 6 repeats on the release's own network, and how `CASHACCOUNT_FX_TIMEOUT` is applied without a chart change |
| [`legacy-characterization.md`](legacy-characterization.md) | The legacy behaviour, cited to `file:line`. §10 **Acceptance** carries the `Status` field that Step 1's `characterization_status` gate reads; §9.5 is the record-length open item; §9.6 the code page and time zone; §9.7 the `cyrrnbase` / `CURRNBASE` spelling |
| [`../src/test/resources/fixtures/legacy-export/`](../src/test/resources/fixtures/legacy-export/) | The export shapes Steps 1 and 3 must produce — `cashaccounty.csv`, `frankfurt1.csv`, `history.csv`, `history.cp037.bin` — in matched and seeded-mismatch variants, each with a `MANIFEST.md` |
| [`../src/test/resources/fixtures/shadow/`](../src/test/resources/fixtures/shadow/) | The Step 2 stream shapes (`transactions.csv`, `legacy-responses.csv`) and `rollback-replay.csv`, the worked example of the Step 3 replay-file contract |

Chart values and templates referenced throughout — `values.yaml`, `templates/cash-account.yaml`,
`templates/broker.yaml` under `infra/stocktrader-operator/helm-charts/stocktrader/` — are **read and
cited, never edited by this migration.** The cutover is a values change applied by the platform
operator against the Step 0 snapshot; a chart **template** change is a stop-and-flag condition, not a
runbook step.
