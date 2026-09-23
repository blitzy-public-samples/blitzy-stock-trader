# Cash Account (Modernized)

A standalone Spring Boot 3.3.13 / Java 21 cash **ledger** service that replaces the CICS/COBOL program in
[`backend/cash-account-cobol`](../cash-account-cobol/README.md) behind the seam `backend/broker` already calls — the
MicroProfile REST Client interface
`com.ibm.hybrid.cloud.sample.stocktrader.broker.client.CashAccountClient`. The retail contract, its verbs, its
`?amount=` query parameter and its `{"owner","balance","currency"}` wire shape are reproduced exactly, so moving
traffic off the mainframe is a **deployment-values change and nothing else**: no caller code changes, and no Helm
chart template changes.

Beyond parity the service adds what the legacy single-balance model had no analogue for: an **available/reserved**
balance split, an explicit reservation state machine (hold → settle/release/expire) for a future institutional order
service, and an append-only, immutable `ledger_entry` audit trail that is queryable the instant a transaction commits.

## Module placement

This module is a **plain tracked directory in the umbrella checkout — not a git submodule.** Every other module under
`backend/`, `frontend/`, `infra/` and `tools/` is a submodule registered in the repository-root `.gitmodules`
(16 entries, `backend/cash-account-cobol` among them). **No `.gitmodules` entry is added for this module**, and none is
edited: the umbrella repository carries these files directly so the new service can be built and reviewed in the same
checkout as the seam it has to satisfy and the chart it has to conform to.

### `target/` is ignored, so the parent's boundary gate stays clean

The module carries its own **`.gitignore`**, and it ignores exactly one path: `target/`. Every sibling under
`backend/`, `frontend/`, `infra/` and `tools/` is a submodule with ignores of its own and the umbrella repository has
none, so without that line this module's build output is untracked content in the parent tree — after the build this
README mandates (`./mvnw -B clean verify`), `git status --porcelain` at the repository root reported

```text
?? backend/cash-account-modernized/target/
```

— the whole build tree, some 60 MB of it, that a blanket `git add -A` would commit, and a boundary check that can no
longer tell this deliverable's files from its artifacts. With the file in place the same command after the same build
reports nothing, so the gate —
only `backend/cash-account-modernized/` may appear at the parent, and no submodule may be dirty — reads clean without
a pathspec or a manual `rm -rf`. Both halves of that claim are checkable in any checkout:

```bash
cd backend/cash-account-modernized && ./mvnw -B clean verify
cd ../.. && git status --porcelain                              # prints nothing
git check-ignore -v backend/cash-account-modernized/target      # names .gitignore:<line>:target/
git check-ignore -v backend/cash-account-modernized/README.md   # exits 1 - nothing but target/ is ignored
```

The last command is why the file stays at one pattern: a wider rule could hide a deliverable file from the very check
this file exists to keep honest. The file is itself a **deviation** — AAP 0.2.1's frozen inventory carries no
`.gitignore` — so it is recorded as row **D5** of the [authorization record](#authorization-record) rather than taken
silently. The minimal-change criterion (AAP 0.7.7, "only files under `backend/cash-account-modernized/` are created")
still holds: nothing outside this module changes.

## What is in here

Four separable concerns share one Maven module and one datastore:

| Concern | Package | What it is |
| --- | --- | --- |
| Retail contract surface | `…cashaccount.retail` | The six endpoints `CashAccountClient` issues, wire-identical to the legacy request codes `Q/A/U/X/C/D` |
| Institutional reservation surface | `…cashaccount.institutional` | Additive hold / settle / release / read endpoints with idempotent writes, under a separate path space |
| Migration and reconciliation tooling | `…cashaccount.migration.{export,load,reconcile}` | Export-format readers (delimited and IBM037 binary), loader, and a reconciler that records variances as rows |
| Dual-run comparator | `…cashaccount.migration.shadow` | Replays a captured transaction stream through the service layer and compares it with captured legacy responses |

Supporting packages: `domain` (the `Money` type, entities and the reservation state machine), `persistence` (Spring
Data JPA repositories — the only path to the database), `fx` (the outbound exchange-rate client), `audit` (the ledger
append and its query DTO), `error` (the closed error taxonomy and its three renderers) and `config` (datasource guard,
security, JWT decoding, Jackson, the FX client and the `/metrics` shim).

## Scope

**No existing file in any module, chart or document is modified by this deliverable.** Everything new lives under
`backend/cash-account-modernized/`. In particular:

- `backend/broker` is untouched — `CashAccountClient`, `CashAccount`, the `web.xml` role split and the
  `CASH_ACCOUNT_ENABLED` / `CASH_ACCOUNT_URL` handling all stay as they are. The new service satisfies them as-is.
- `backend/cash-account-cobol` (COBOL, DB2 DDL/BIND, VSAM definition JCL) is read and cited for characterization, never
  edited.
- `infra/stocktrader-operator` is referenced, never edited: no chart template, `values.yaml` or `values-metadata.yaml`
  is created or changed here.
- Wiring an institutional caller to the reservation endpoints is deliberately **out of scope**. There is no
  `backend/execution-control` (or any other institutional order service) in this repository; the reservation surface is
  exercised only by this module's own tests until such a caller is specified separately.

What this deliverable **builds** is the replacement service plus its migration mechanism, proven against synthetic
fixtures. What it **hands off** — bulk migration against the real DB2 for z/OS and VSAM history, a live shadow-mode
dual-run, the controlled cutover and decommission — is written up in
[`docs/operational-runbook.md`](docs/operational-runbook.md) and is **documented, never executed** here. See
[Prohibitions](#prohibitions).

## Build and run

Everything below runs from the module directory with the wrapper it ships; the only host requirements are
a JDK 21, a Docker daemon for the integration suite and a PostgreSQL instance for anything that is not a
test.

### Prerequisites

| Requirement | Why |
| --- | --- |
| JDK 21 | `maven.compiler.release` is 21, the highest level the estate documents |
| Maven | None to install: `./mvnw` is the entry point. The wrapper is `only-script` at wrapper version 3.3.2 and pins Apache Maven 3.9.11, copied verbatim from `backend/portfolio-assistant` — the highest Maven the estate documents |
| Docker | Required by the `*IT` classes only. They start a `postgres:12.22-alpine` container through Testcontainers; PostgreSQL 12 is the major version the estate's Azure module provisions, so every DDL feature is proven at the compatibility floor |
| A PostgreSQL 12+ instance | Required for any non-test run (`java -jar`, container run, the migration tooling). Nothing else in the module needs a network |

### Build

```bash
cd backend/cash-account-modernized
./mvnw -B clean verify
```

`verify`, not the siblings' `package`: Surefire runs the `*Test` classes and Failsafe runs the `*IT` classes, and
Failsafe's `integration-test` and `verify` goals only execute in the `verify` phase. `package` would build the jar and
silently skip the entire integration suite, which is where the contract, security, audit, actuator and reconciliation
evidence is produced. `./mvnw -B clean verify` is therefore the module's whole gate — there is no coverage threshold,
enforcer or lint plugin bound to it, matching the rest of the estate.

Targeted invocations used by the acceptance criteria:

```bash
# The characterization document must exist, with its citations, before any reconciliation code is trusted.
./mvnw -B test -Dtest=CharacterizationDocPresentTest

# The legacy arithmetic: truncate2(stored ± rates × amount), absolute value on a negative result, overflow modulus.
./mvnw -B test -Dtest=LegacyBalanceCalculatorTest

# The retail contract, driven through a verbatim copy of broker's own client interface.
./mvnw -B verify -Dit.test=RetailContractIT
```

`-Dtest=…` selects Surefire (unit) classes and `-Dit.test=…` selects Failsafe (integration) classes; the two
properties are not interchangeable.

### The drift guard is a failure, not a skip

`CashAccountClientDriftTest` compares this module's two test-tree copies of broker's `CashAccountClient` and
`CashAccount` — kept under broker's original packages so the contract tests exercise the *real* interface — against
broker's sources, from the first line beginning with `package` to end of file. It resolves broker's root from
`-Dcashaccount.broker.source` when set, and otherwise as `../broker` relative to the module directory, which is
exactly where the umbrella checkout places it (both plugins pass `cashaccount.module.basedir=${project.basedir}` so
the resolution never depends on a forked JVM's working directory).

If that path is absent the test **fails** rather than skipping, because a silent skip is how a copy drifts from the
interface it is supposed to prove. The single escape is an explicit `-Dcashaccount.standalone=true`, which asserts a
genuinely standalone checkout with no broker sources to compare against — never a convenience flag for a red build.

### Run locally

```bash
java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar
```

with `JDBC_KIND=postgres`, `JDBC_HOST`, `JDBC_DB`, `JDBC_ID`, `JDBC_PASSWORD` and `AUTH_TYPE` from the
[configuration map](#configuration). `JDBC_HOST` and `JDBC_DB` have **no default**: supply the wrong one and you
get a connection error, supply neither and start-up stops with a message naming the variable and its property.
`JDBC_PORT` may be omitted only because `5432` is PostgreSQL's own port. For example:

```bash
# The password is prompted for and written to a 0600 file, never typed into the command line: argv is
# readable by every process on the host (`ps -o args=`) and an interactive shell keeps it in HISTFILE.
# `printf` is a shell builtin, so the value never becomes another process's argv.
umask 077
IFS= read -rsp 'JDBC_PASSWORD: ' CA_PW; echo
printf 'JDBC_KIND=postgres\nJDBC_HOST=localhost\nJDBC_PORT=5432\nJDBC_DB=trader\nJDBC_ID=<local-db-user>\nAUTH_TYPE=basic\nJDBC_PASSWORD=%s\n' "$CA_PW" > ca-db.env
unset CA_PW

# Load it without letting the shell interpret any value - `set -a; . ca-db.env` would execute a
# password containing ';' or '$' instead of loading it.
while IFS='=' read -r k v; do [ -n "$k" ] && export "$k=$v"; done < ca-db.env
java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar

# Afterwards
unset JDBC_PASSWORD
shred -u ca-db.env 2>/dev/null || rm -f ca-db.env
```

The schema is applied at start-up (see [Schema application](#schema-application)), so an empty database is enough —
and so the store this run reaches gains the seven tables, the `ledger_entry_reject()` function and the
`ledger_entry_immutable` and `ledger_entry_immutable_truncate` triggers. **Point it at a local throwaway database**, never at a shared or production store;
that is the same rule `docs/operational-runbook.md` Step 0 states for its own memory-fit check.

Once the log reports the port, these all answer `200`:

```bash
curl -s http://localhost:8080/actuator/startup            # six start-up lifecycle steps (the chart's startupProbe)
curl -s http://localhost:8080/actuator/health/readiness    # {"status":"UP"} — readinessState + db
curl -s http://localhost:8080/actuator/health/liveness     # {"status":"UP"} — livenessState only
curl -s http://localhost:8080/actuator/prometheus          # Micrometer scrape, canonical path
curl -s http://localhost:8080/metrics                      # same scrape, for the chart's path-less annotation
```

Everything under `/cash-account/**` requires a token in every mode except `AUTH_TYPE=none`; see [Security](#security).
Those five paths are the exception: they are `permitAll`, because a kubelet and a Prometheus scraper present no
credential, and the chart's probes target them by path. Nothing else is exposed — `management.endpoints.web.exposure`
lists `health`, `startup` and `prometheus` only, so `env`, `beans`, `configprops`, `heapdump`, `threaddump`, `loggers`,
`mappings`, `shutdown` and the rest answer `404 UNSUPPORTED_PATH`.

`/actuator/startup` is the one probe whose response would otherwise describe the application rather than its state. A
`GET` is the endpoint's *snapshot* operation, not its drain, so the same payload is re-served on every probe; with the
start-up recorder unfiltered that payload measured **170,993 bytes**, carrying 447 `beanName` tags and 221
fully-qualified class names to any peer that could reach the port. `CashAccountApplication` therefore filters the
recorder to the six application-lifecycle steps — `environment-prepared`, `context-prepared`, `context-loaded`,
`spring.context.refresh`, `started`, `ready` — which are the steps that carry no tag at all, so what remains is step
names and durations: a little over a kilobyte, and still the slow-start diagnosis the endpoint exists for.
`actuator/ActuatorProbesIT` asserts both halves (a `200` carrying `spring.boot.application.ready`, and no `beanName`
or `spring.beans.instantiate` anywhere in the body). Two things are deliberately *not* done about it: dropping
`startup` from the exposure list would fail every pod's `startupProbe`, and re-pointing that probe at a health group
is a chart template change (AAP 0.3.4). What is still published is the endpoint descriptor's own Spring Boot version,
which is why `docs/operational-runbook.md` Step 0 asks the platform owner to constrain who may reach `/actuator` and
`/metrics` at the network layer.

### There is no servlet context path

`server.servlet.context-path` is deliberately unset. The chart probes `/actuator/startup`,
`/actuator/health/readiness` and `/actuator/health/liveness` at the **root** of port 8080 while publishing the retail
base URL to broker as `http://<release>-cash-account-service:8080/cash-account`. Both can hold at once only if the
`/cash-account` prefix lives on the controllers (`@RequestMapping("/cash-account")` on `RetailCashAccountController`,
`"/cash-account/institutional"` on `ReservationController`) and actuator stays at the root. A context path would break
every probe at once, and the chart template cannot be edited to accommodate one.

## Configuration

Every value the deployment supplies is already injected by the existing chart template; the service binds those
variables and adds none. `src/main/resources/application.yml` is the declared source of truth for all of it.

| Environment variable (chart source) | Spring property | Default |
| --- | --- | --- |
| `CURRENCY_API_URL` ← `cashAccount.exchangeRateUrl` | `cashaccount.fx.url` | `https://api.frankfurter.app/latest` — **`https` only**; start-up fails naming `CURRENCY_API_URL` and the offending scheme (below) |
| `JDBC_KIND` ← `database.kind` | `cashaccount.jdbc.kind` | `postgres` — **and nothing else starts** (below) |
| `JDBC_HOST` ← `database.host` | `cashaccount.jdbc.host` | **none** — start-up fails naming `JDBC_HOST` and `cashaccount.jdbc.host` |
| `JDBC_PORT` ← `database.port` | `cashaccount.jdbc.port` | `5432` |
| `JDBC_DB` ← `database.db` | `cashaccount.jdbc.database` | **none** — start-up fails naming `JDBC_DB` and `cashaccount.jdbc.database` |
| `JDBC_SSL` ← `database.ssl` | `cashaccount.jdbc.ssl` | `false` |
| `cert_defaultTrustStore` ← configMap key `ssl.certs`, injected only when `global.specifyCerts` is on | `cashaccount.jdbc.trust-store-pem` | empty |
| `JDBC_ID` ← `database.id` (chart secret) | `spring.datasource.username` | empty |
| `JDBC_PASSWORD` ← `database.password` (chart secret) | `spring.datasource.password` | empty |
| `AUTH_TYPE` ← `global.auth` | `cashaccount.security.auth-type` | `basic`; accepted values `none`, `basic`, `ldap`, `oidc` |
| `JWT_ISSUER` ← `jwt.issuer` | `cashaccount.security.jwt.issuer` | `http://stock-trader.ibm.com` |
| `JWT_AUDIENCE` ← `jwt.audience` | `cashaccount.security.jwt.audience` | `stock-trader` |
| `OIDC_JWKS_URL` ← `oidc.jwksUrl` | `cashaccount.security.jwt.jwks-url` | empty; read only when `AUTH_TYPE=oidc` |

### `CURRENCY_API_URL` is `https`-only, and refused at start-up

`fx/FrankfurterExchangeRateClient.requireEndpoint`, called from that class's constructor, checks the value while the
context is refreshing, so a non-conforming endpoint stops start-up before the port is bound: the pod never becomes
ready — the chart's `startupProbe` has nothing to probe — and the container log is the only place that says why. It
is not a degraded rate lookup that a later request reports. The value must satisfy four checks, and every refusal
names the property and the chart variable together, so a log line leads straight back to this section:

- scheme `https`, compared case-insensitively —
  `cashaccount.fx.url (CURRENCY_API_URL) must use the https scheme, not http`, the rejected scheme named because its
  grammar cannot carry a forged log record, while the rest of the value is never echoed;
- a host — `must name a host`, which `https:///latest` fails;
- no `user:password@` user information — `must carry no user-info credentials`, since that credential would live in
  a configMap value that reaches logs and metrics tags;
- no `#fragment` — `must carry no fragment`, which is never put on the wire and so cannot mean what it reads as.

**No property relaxes any of it**, and plaintext is refused rather than warned about because this rate is not
advisory data: it is multiplied into every cross-currency credit and debit and the product is written to the
immutable ledger, so an on-path attacker who can rewrite an HTTP answer moves money (CWE-319, CWE-345).
`config/FxClientConfig` builds the client's `HttpClient` with `Redirect.NORMAL` rather than `ALWAYS` to close the
same hole from the other side — a redirect may not walk the endpoint back down to HTTP. An internal rate mirror is
therefore reachable only over TLS; for a developer's stub see
[A local or CI FX stub must be served over TLS](#a-local-or-ci-fx-stub-must-be-served-over-tls).

### `JDBC_KIND` is guarded, not defaulted

`config/DataSourceGuardConfig` accepts only `postgres` (trimmed, case-insensitively) and fails start-up with an
explicit message naming the property, the chart variable and the offending value for anything else. Half-starting
against a store this schema was never written for is worse than not starting: the DDL, the `ledger_entry_immutable`
trigger and every `NUMERIC(9,2)` column assume PostgreSQL.

`database.kind` is a **chart-global** value (still `db2` in the repository) shared with `backend/portfolio`, which
already reaches PostgreSQL through the same `JDBC_*` variables. Moving the release to PostgreSQL is therefore a
**separately completed and signed-off prerequisite** — `docs/operational-runbook.md` Step 0, owned by the platform
owner — and never part of a cash-account cutover.

### How the JDBC URL is assembled

`spring.datasource.url` is not a static property, and it is not an accepted input either. `DataSourceGuardConfig`
assembles `jdbc:postgresql://<host>:<port>/<database>` and, when `JDBC_SSL=true`, appends `?ssl=true&sslmode=verify-ca`
plus **exactly one** trust source:

- `&sslrootcert=<file>` when `cert_defaultTrustStore` carries PEM text. The PEM is written to a private temporary
  file and pgJDBC's default `LibPQFactory` verifies the server chain against it.
- `&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` when that variable is absent, so trust rides on the JVM's
  `cacerts`.

These are the same properties `backend/portfolio/src/main/liberty/config/includes/postgres.xml` uses
(`ssl`, `sslMode=verify-ca`, `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`), with Liberty's `cert_*` keystore
import convention replaced by pgJDBC's `sslrootcert` file — the chart delivers the certificate as PEM text, not as a
keystore, and only one of the two mechanisms may appear in a URL.

A `spring.datasource.url` arriving from any other property source — `SPRING_DATASOURCE_URL` in the container
environment, a system property, a command-line argument — is **never** honoured as written, because that one value
would otherwise decide the dialect, the host, the credentials and the TLS mode together, none of which the
`JDBC_KIND` guard can see:

- While `JDBC_HOST` or `JDBC_DB` carries a value — every chart deployment, since both come from non-optional
  configMap keys — a supplied URL is **refused** and start-up fails naming both sources. Change
  `database.host`/`.port`/`.db` in the release values instead.
- While `JDBC_SSL=true`, a supplied URL is **refused** whatever it says. `sslmode=verify-ca` validates the server's
  certificate chain but not its hostname — that is what `verify-full` adds — so under TLS the host is itself a trust
  decision: a URL naming its own host could reach a different server presenting any certificate the same CA ever
  signed, and the connection would still verify. Where TLS is demanded the host comes from `JDBC_HOST`, `JDBC_PORT`
  and `JDBC_DB` alone.
- A run that carries no chart variables and no TLS (a hand-started process, or `LedgerImmutabilityIT`'s second
  application context) keeps the URL's **host, port and database only**. The scheme must be `jdbc:postgresql://`;
  `user:password@host` user information, a `user=` or `password=` parameter, any `ssl*` parameter, more than one
  host, extra path segments and any parameter outside `ApplicationName`, `assumeMinServerVersion`, `connectTimeout`,
  `currentSchema`, `defaultRowFetchSize`, `loggerFile`, `loggerLevel`, `loginTimeout`, `reWriteBatchedInserts`,
  `socketTimeout` and `tcpKeepAlive` are refused by name, so a supplied URL can neither weaken nor claim TLS.
- No refusal message quotes the URL or a parameter value, because either may be the credential it was refused for.

Every resolved URL — assembled or normalized — also carries the driver's two bounds,
`connectTimeout=2&socketTimeout=30` by default, from the properties in the next section. A supplied URL that names
one of the two keeps **its own** value for it and is not given a second copy: pgJDBC honours the last occurrence of
a repeated parameter, so a duplicate would leave the URL saying one thing and the connection doing another.

`DataSourceGuardConfigTest` covers both TLS shapes, the `JDBC_KIND` guard, every refusal above, the appended bounds
and their precedence.

### How long anything waits for the database

Four values, and together they are the whole answer. None of them is a framework default left in place.

| Property | Default | What it bounds |
| --- | --- | --- |
| `spring.datasource.hikari.connection-timeout` | `2000` ms | Waiting for a pooled connection — the bound a caller feels |
| `spring.datasource.hikari.validation-timeout` | `1000` ms | Testing a pooled connection, which happens **inside** the borrow above |
| `spring.datasource.hikari.initialization-fail-timeout` | `30000` ms | Start-up only: how long the pool retries, once a second, before the context fails |
| `cashaccount.jdbc.connect-timeout` / `cashaccount.jdbc.socket-timeout` | `PT2S` / `PT30S` | One TCP-and-TLS handshake, and one read on an established connection |

Left at their own defaults, Hikari waits 30 s for a connection and pgJDBC waits for ever on a read. With the
database unreachable that produced a measured **30.0 s** for every database-dependent request and for
`/actuator/health/readiness` — whose `db` indicator borrows from this same pool — before returning the `503` that
had already been decided at the first refused connection. The status and body were right; the wait was not. Each
one occupied a Tomcat worker for the whole 30 s, broker propagates retail calls with no client timeout of its own,
and the scheduled expiry sweep blocked for the same 30 s. The exchange-rate hop is capped at `PT2S` precisely so a
provider cannot outlive its caller; the datastore is the other outbound hop and now carries the same ceiling, so a
caller gets `503 DATASTORE_UNAVAILABLE` with `Retry-After: 5` in about two seconds instead of thirty.

Why each value is what it is:

- **`validation-timeout` at or below `connection-timeout`.** The aliveness test of a pooled connection runs inside
  the borrow, so Hikari's own 5 s default would declare a dead-but-open connection invalid only after the 2 s
  ceiling had passed, making the ceiling a claim rather than a fact.
- **`initialization-fail-timeout` at 30 s.** The 30 s borrow default used to give cold start its tolerance by
  accident. Stated explicitly, the pool retries once a second across that window before failing the context, so a
  pod whose database is still starting still starts — a tighter borrow budget must not turn a slow database into a
  crash loop. It is deliberately **not** negative: a negative value starts the pod with no database at all, while
  `spring.sql.init` and `ddl-auto=validate` both have to run against a real one first.
- **`socket-timeout` generous where the pool's budget is tight.** It is a ceiling on the slowest legitimate single
  round trip, not a request budget: the longest this service can legitimately wait on one statement is
  `schema/cash-account-schema.sql`'s `pg_advisory_lock` while another pod applies the same script. Whole seconds
  only — the driver takes an `int` of seconds and reads `0` as *no timeout*, so `PT0.5S` would be truncated into
  exactly the unbounded wait the setting exists to remove, and `DataSourceGuardConfig` refuses it instead.
- **A saturated pool answers the same way.** Every operation here is a single-row read or write, so 2 s of
  unbroken contention is real overload, and a retryable `503` is the honest answer to it rather than a queue.

One thing this does **not** reach, stated so it is not mistaken for covered: the chart's `readinessProbe` declares
`periodSeconds: 15` and `failureThreshold: 3` but no `timeoutSeconds`, so the kubelet applies its 1 s default, and a
2 s answer still arrives after the probe attempt has been abandoned. The pod leaves the Service endpoints after the
same three failed periods either way — a timed-out attempt and a `DOWN` body score identically — and what changes
is that the server no longer holds a worker per probe for 30 s. Making the kubelet *read* the `DOWN` body needs
`timeoutSeconds` on the probe, which lives in
`infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml` — a read-only template here (AAP
0.3.4), so it belongs to the chart's owners. The alternative, a sub-second `connection-timeout`, is deliberately not
taken: opening a TLS connection to a managed PostgreSQL instance can legitimately take several hundred
milliseconds, and spurious `503`s on a healthy database would be a worse defect than a late probe body.

All four are retunable as plain container environment with no chart change:
`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT`, `CASHACCOUNT_JDBC_SOCKET_TIMEOUT`, and so on.

### Properties with no environment binding

These have internal defaults; nothing in the chart supplies them, and nothing needs to:

| Property | Default | Why it is a property at all |
| --- | --- | --- |
| `cashaccount.security.jwt.public-key-location` | `classpath:security/jwtsigner.pem` | Tests repoint it at an ephemeral per-JVM key instead of shipping key material |
| `cashaccount.security.all-authenticated-hold-stocktrader` | `true` | Parity with the siblings' `ALL_AUTHENTICATED_USERS → StockTrader` binding; `false` is the strict mode (see [Security](#security)) |
| `cashaccount.fx.base-currency` | `USD` | Broker's default account currency, so a same-currency account short-circuits to a rate of exactly `1` with no network call |
| `cashaccount.fx.timeout` | `PT2S` | Connect and read budget; a slow rate provider must surface as `503`, not as a request that outlives its caller |
| `cashaccount.jdbc.connect-timeout` | `PT2S` | Bounds one TCP-and-TLS handshake to the database, appended to the assembled URL as pgJDBC's `connectTimeout`. A host that drops packets rather than refusing them otherwise sits in the kernel's connect retry, leaving the socket behind after the pool has already given up on it. See [How long anything waits for the database](#how-long-anything-waits-for-the-database) |
| `cashaccount.jdbc.socket-timeout` | `PT30S` | Bounds a **read** on an established connection, appended as pgJDBC's `socketTimeout`. Nothing else here bounds it at all: the driver's default is `0`, so a statement in flight when the peer disappears waits for ever, and the pool's `connection-timeout` governs obtaining a connection rather than using one |
| `cashaccount.fx.accepted-currencies` | The 31 ISO codes `AUD BGN BRL CAD CHF CNY CZK DKK EUR GBP HKD HUF IDR ILS INR ISK JPY KRW MXN MYR NOK NZD PHP PLN RON SEK SGD THB TRY USD ZAR` | The **estate allowlist**, adopted verbatim from the `allowed_currencies` CHECK the estate's PostgreSQL init template already enforces — **not** the set the exchange-rate API serves, which is a 30-code subset of it: as of 2026-09-22 the configured provider's `/v1/currencies` omits `BGN` and `GET /latest?from=USD&to=BGN` answers `404`. Acceptance is therefore not a promise of convertibility — a `BGN` account is created and read normally, and only a cross-currency `credit`/`debit` for it fails, with `503 EXCHANGE_RATE_UNAVAILABLE`, the balance unchanged and no ledger row. `BGN` is kept rather than dropped because this list also decides what a legacy export may be **loaded** with (an out-of-set currency is recorded as `CURRENCY` / `INVALID_IN_LEGACY` and the account is not migrated), so dropping it would silently strand a `BGN`-denominated legacy account. A deployment whose accounts must all be convertible narrows the list — `CASHACCOUNT_FX_ACCEPTED_CURRENCIES=USD,EUR,…`, no chart change — and `application.yml` is the single authority every consumer binds |
| `cashaccount.reservation.default-ttl` | `PT24H` | A hold nobody settles or releases must not strand funds indefinitely |
| `cashaccount.reservation.expiry-sweep-interval` | `PT60S` | Bounds how long an overdue hold keeps money out of the available balance |
| `cashaccount.migration.batch-chunk-size` | `50` | Rows a bulk `load` or `reconcile` writes before it flushes and clears the persistence context. Unbounded, one load would hold every entity it has written until the transaction ends, which is an out-of-memory failure halfway through a migration window. It decides nothing about correctness — the whole load is one transaction either way (AAP 0.6.3), so the same rows land whatever it is — and `50` is deliberately the same number as `spring.jpa.properties.hibernate.jdbc.batch_size`, so a flush maps onto **whole** JDBC batches instead of straddling them. Raise both together or neither. It is **not** a `tool.*` key (below), and an operator sizing a migration window overrides it on the command line: `--cashaccount.migration.batch-chunk-size=<rows>` |

All of them are overridable through Spring's relaxed binding — `CASHACCOUNT_FX_TIMEOUT`,
`CASHACCOUNT_SECURITY_ALL_AUTHENTICATED_HOLD_STOCKTRADER`, and so on — as plain container environment, **without any
chart change**. That is the point of leaving them unbound rather than inventing chart keys for them.

### Request intake bounds

Nothing in this stack limited a request body: Tomcat's post-size cap covers form encodings only, so a JSON body was
bounded by nothing and Jackson materialized whatever arrived **before** any field validation ran — heap and CPU in a
2Gi pod for the cost of sending bytes. The largest payload this service defines is a few hundred bytes with every
field at its maximum (owner 32 characters of `A-Z 0-9 . _ -`, order reference 64), so these caps are ample headroom
and still trivial to refuse. They are framework keys with no chart binding, overridable as plain container environment
(`SERVER_MAX_REQUEST_BODY_SIZE`, and so on) without any chart change.

| Property | Value | What it bounds |
| --- | --- | --- |
| `server.max-request-body-size` | `8KB` | The request body the application parses. `config/RequestBodySizeLimitFilter` binds this key, refuses a declared `Content-Length` above it **before reading a byte**, and counts a chunked body as it is read — answering `413 REQUEST_TOO_LARGE` in the standard [`ApiError`](#error-model) shape either way |
| `server.max-http-request-header-size` | `8KB` | The request line and headers — Tomcat's own default, stated explicitly because it is the bound on the one caller-controlled input the filter above cannot see: the text of the `?amount=` query parameter |
| `server.tomcat.max-http-form-post-size` | `8KB` | Form bodies, which never reach the filter's counter because the container parses them off the native stream to build the parameter map. This service consumes `application/json` only, so the value is a cap rather than a facility |
| `server.tomcat.max-swallow-size` | `64KB` | How much of a body the application refused to read Tomcat will still drain to keep the connection usable — large enough that a rejected request receives its `413` instead of a reset connection, small enough that draining is never the attack. It is also the bound on a body nothing reads at all, such as one sent to a `GET` mapping or an actuator path |

**What they cannot bound, stated so it is never mistaken for covered.** Every value above governs what this process
*reads*; none governs what the network *delivers*, because the connector has accepted the bytes before any of them
applies. A parsed body is capped at 8KB and a refused one is drained at no more than 64KB, so no request costs this
pod more than that — but a caller can still spend its own bandwidth pushing bytes at the connector. Closing that
outer layer belongs to the ingress, service mesh or API gateway in front of the Service and is a **deployment**
change this module may not make (AAP 0.3.4 makes a chart template change a [stop-and-flag](#stop-and-flag)
condition, and AAP 0.2.4 puts values, CRDs and GitOps resources out of scope); it is recorded for the platform owner
under [Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory).
`REQUEST_TOO_LARGE` is one of the three error codes this deliverable added beyond the AAP's own table — the other
two are the media-type pair `UNSUPPORTED_MEDIA_TYPE` and `NOT_ACCEPTABLE` — and all three are recorded in the same
place.

### Tool-profile properties

Active only under `--spring.profiles.active=tool` (`src/main/resources/application-tool.yml`), which also disables the
web server. See [Migration and reconciliation tooling](#migration-and-reconciliation-tooling).

| Property | Meaning |
| --- | --- |
| `tool.command` | `load`, `reconcile` or `shadow-compare` |
| `tool.input` | Directory holding the export or shadow files |
| `tool.batch-id` | UUID shared by the `load` and the `reconcile`/`shadow-compare` of one runbook step; each invocation is still its own `migration_run` row |
| `tool.rate-source` | `legacy-table` (default) or `live` |
| `tool.legacy-charset` | `IBM037` — the assumed region code page for binary history |
| `tool.legacy-timezone` | `UTC` — the assumed region zone for `YYYYMMDD`/`HHMMSS` stamps |
| `tool.history-record-length` | `57` or `100`; **mandatory for binary history input**, because a record length is declared from the CICS FILE definition, never inferred from a file |

`tool.legacy-charset` and `tool.legacy-timezone` are properties rather than constants precisely because the region's
CCSID and time zone are not in this repository — see [Open items](#open-items).

Those seven are the whole namespace: `MigrationToolRunner` **rejects any `tool.*` option outside them**, so a
mistyped flag fails the invocation instead of being silently ignored. The one knob a migration run also needs —
how many rows a load or reconcile flushes by — therefore lives outside it, at
`cashaccount.migration.batch-chunk-size` ([above](#properties-with-no-environment-binding)), beside the JDBC batch
size it has to match; `--cashaccount.migration.batch-chunk-size=<rows>` passes straight through the runner's typo
guard.

### Injected but deliberately not consumed

The chart template also injects the following, and this service **ignores every one of them**. They address Open
Liberty and a Java cash-account service that was never built; binding any of them would declare a Redis, Kafka or CQRS
path this module excludes and carries no dependency for.

- `TRACE_SPEC`
- `REDIS_URL`
- `KAFKA_USER`, `KAFKA_API_KEY`, `KAFKA_ADDRESS`, `KAFKA_CASH_ACCOUNT_TOPIC`, `KAFKA_BROKER_TOPIC`
- `CQRS_ENABLED`
- `WLP_LOGGING_CONSOLE_*` and `WLP_LOGGING_MESSAGE_*`

Do not wire them later by accident: an unconsumed variable is harmless, whereas a half-wired Kafka or CQRS path would
be a second write channel past the ledger.

## Chart values consumed — referenced, never edited

The service is deployed by the chart that already exists at
`infra/stocktrader-operator/helm-charts/stocktrader`. These are the keys it consumes. **No chart template,
`values.yaml` or `values-metadata.yaml` is created or edited by this deliverable.**

| Chart key | Repository default | What it reaches in this service |
| --- | --- | --- |
| `cashAccount.enabled` | `false` | Gates the whole Deployment, and reaches broker and portfolio as `CASH_ACCOUNT_ENABLED` |
| `cashAccount.url` | `http://{{ .Release.Name }}-cash-account-service:8080/cash-account` | Broker's `CASH_ACCOUNT_URL`; matches the controllers' `/cash-account` mapping |
| `cashAccount.exchangeRateUrl` | `https://api.frankfurter.app/latest` | `CURRENCY_API_URL` → `cashaccount.fx.url`; **must stay an `https` URL** ([above](#currency_api_url-is-https-only-and-refused-at-start-up)) |
| `cashAccount.image.repository` / `.tag` | `ghcr.io/ibmstocktrader/cash-account` / `1.0.0` | The image the pod runs; set to the digest-pinned image the [build path](#image-build-scan-and-push) produces |
| `database.kind` / `.host` / `.port` / `.db` / `.ssl` / `.id` / `.password` | `db2` and DB2 host values | `JDBC_KIND` / `JDBC_HOST` / `JDBC_PORT` / `JDBC_DB` / `JDBC_SSL` / `JDBC_ID` / `JDBC_PASSWORD` |
| `jwt.issuer` / `jwt.audience` | `http://stock-trader.ibm.com` / `stock-trader` | `JWT_ISSUER` / `JWT_AUDIENCE` |
| `oidc.jwksUrl` | placeholder | `OIDC_JWKS_URL`, read only when `AUTH_TYPE=oidc` |
| `global.auth` | `basic` | `AUTH_TYPE` |
| `global.healthCheck` | `true` | Gates the three probes; with it off nothing probes the actuator endpoints |
| `global.monitoring` | `true` | Gates the `prometheus.io/*` annotations |
| `global.specifyCerts` | `false` | Gates the `cert_defaultTrustStore` injection |
| `vault.enabled` | `false` | **Must remain `false`** (below) |

### Replica count is the operator's to choose

The chart already carries the scaling keys, consumed as they are and edited by nothing here:
`cashAccount.replicas` (`1`), `cashAccount.autoscale` (`false`), `cashAccount.maxReplicas` (`10`) and
`cashAccount.cpuThreshold` (`75`). `templates/cash-account.yaml` lines 26-27 render `replicas` only while
`autoscale` is false, and lines 233-271 render an `autoscaling/v2` HorizontalPodAutoscaler with
`minReplicas` = `replicas`, `maxReplicas` = `maxReplicas` and a CPU-utilization target of `cpuThreshold`
when it is true. Turning autoscaling on is therefore a values change, and the service is safe under it for
four specific reasons:

- **No request state lives in a pod.** Every account, reservation and ledger row is in PostgreSQL, and
  `config/SecurityConfig` runs `SessionCreationPolicy.STATELESS` with CSRF disabled because the API is
  bearer-token JSON (lines 143-144), so there is no session to pin a caller to a pod: no sticky routing, no
  shared session store, and any replica can answer any request.
- **The one file a pod writes is its own.** With `JDBC_SSL=true` and `cert_defaultTrustStore` injected,
  `config/DataSourceGuardConfig` writes that PEM to a private temporary file in the pod's own filesystem —
  owner-only permissions, deleted on exit — and points `sslrootcert` at that path, so each replica
  materializes its own copy and no path is shared between pods.
- **Start-up serializes instead of racing.** The schema initializer takes `pg_advisory_lock(724300101)` on
  its single connection before the first statement and releases it after the last, and every statement is
  re-runnable, so the Nth pod's pass changes nothing and never drops the immutability guard — the
  mechanism described under [Schema application](#schema-application).
- **N expiry sweepers produce one outcome per hold.** `ReservationService.sweepExpiredReservations()` runs
  on every replica at `cashaccount.reservation.expiry-sweep-interval`. One pass collects at most 200
  candidate identifiers with an unlocked projection query, then gives each candidate its own transaction
  that locks the owner's `cash_account` row first (`findByOwnerForUpdate`), claims the reservation row
  under a `SKIP LOCKED` write lock, and only then re-checks that it is still `HELD` and still overdue. A
  second sweeper — or a settle or release that won the race — finds the row already terminal under the
  account lock and writes nothing, so exactly one terminal state and exactly one ledger row result
  whatever the replica count. The account row is locked first on every path, request and sweep alike:
  one fixed lock order is what keeps the wait-for graph acyclic, and contention then surfaces as
  `409 CONCURRENT_MODIFICATION` rather than as a lost update.

### Deployment-shape conformance

A reviewer can see by inspection that the existing template already reaches this service:

| Template attribute | What the chart expects | What the service exposes |
| --- | --- | --- |
| Workload annotation | `prism.subkind: Spring` | A Spring Boot jar |
| Container ports | `8080` and `8443` | An HTTP listener on 8080. 8443 is declared for template conformance only: the chart provisions no TLS material for this service, and Kubernetes never requires a declared port to be bound |
| Startup probe | `httpGet /actuator/startup` on 8080, `initialDelaySeconds: 60`, `periodSeconds: 30`, `failureThreshold: 3` | The actuator `startup` endpoint, which exists only because the application installs a `BufferingApplicationStartup(2048)` and `startup` is in `management.endpoints.web.exposure.include`. The recorder is filtered to the six application-lifecycle steps, so the probe the kubelet repeats every 30 s answers with step names and durations rather than an inventory of beans and classes — see [Run locally](#run-locally) |
| Readiness probe | `httpGet /actuator/health/readiness` on 8080, `periodSeconds: 15` | Health group `readiness` = `readinessState,db`. A pod that cannot reach its ledger leaves the Service endpoints |
| Liveness probe | `httpGet /actuator/health/liveness` on 8080, `periodSeconds: 15` | Health group `liveness` = `livenessState` only. `db` is deliberately excluded: a failing liveness probe restarts the container, so including it would turn one database outage into a rolling crash loop |
| Metrics scrape | `prometheus.io/scrape: 'true'` and `prometheus.io/port: "8080"` — **no path** | `GET /metrics` returns the Micrometer Prometheus scrape beside the canonical `/actuator/prometheus`. The shim exists because the annotation names no path and the template cannot be edited to add one |
| Resources | limits cpu `1000m` / memory `2Gi`, requests cpu `500m` / memory `1Gi` | Nothing is pinned in the image; the base image's `run-java.sh` sizes the heap from the container memory limit. Fit is confirmed by the [manual memory-fit check](#image-build-scan-and-push) — no in-repo test asserts it |

### `vault.enabled` must stay `false`

When `vault.enabled` is true the template injects Liberty-specific container arguments
(`/opt/ol/helpers/runtime/docker-server.sh`) that a Spring Boot image cannot execute, alongside the Vault agent
annotations. The template is **not** edited to accommodate this service, so the release must deploy with
`vault.enabled: false`; the chart's own default is already `false`. A Vault-compatible argument shape for a non-Liberty
workload is an [open item](#open-items) for the chart's owners, not a change made here.

### Cutover is a values set, applied by the operator

Nothing in this repository routes traffic to the new service, and nothing here should. At cutover the platform operator
applies a values set — `cashAccount.enabled: true`, `cashAccount.image.*` at the recorded digest, `cashAccount.url` at
the chart default — against a **captured live snapshot** of the release (`helm get values <release>` or
`kubectl get stocktrader <name> -o yaml`), which is also what a rollback restores. The procedure, its gates, its
evidence and its sign-offs are `docs/operational-runbook.md` Step 3. This deliverable changes no such value; see
[Prohibitions](#prohibitions).

## Endpoints

The service exposes two path spaces and one error payload: the retail contract broker already calls, which
is fixed by that caller and reproduced exactly, and the additive institutional reservation surface, which no
caller in this repository reaches yet. Every failure in either is rendered by the closed error model below.

### Retail contract — the seam

Reproduced exactly as `CashAccountClient` issues it. Every success is `200` with the body
`{"owner": "<stored uppercase owner>", "balance": <plain decimal>, "currency": "<ISO code>"}`; `balance` is written as
plain decimal text, never exponent notation, because the caller and the contract test both read it literally.

| Legacy code | Verb and path | Request | Errors |
| --- | --- | --- | --- |
| `Q` | `GET /cash-account/{owner}` | — | `404` ACCOUNT_NOT_FOUND |
| `A` | `POST /cash-account/{owner}` | body `{owner, balance, currency}`, **required**; the body's `owner` is ignored and the path decides; an absent or null `balance` is `0.00`; an absent, null or blank `currency` is the configured base currency, `USD` | `409` ACCOUNT_ALREADY_EXISTS, `400` INVALID_OWNER / INVALID_AMOUNT / INVALID_CURRENCY, `422` AMOUNT_OUT_OF_RANGE |
| `U` | `PUT /cash-account/{owner}` | body `{owner, balance, currency}`, **required** — an absolute overwrite of the available balance and the currency, with the same three field rules as `POST` | `404`, `400`, `422`, `409` RESERVATIONS_OUTSTANDING |
| `X` | `DELETE /cash-account/{owner}` | — (returns the deleted account) | `404`, `409` RESERVATIONS_OUTSTANDING |
| `D` | `PUT /cash-account/{owner}/debit?amount=<decimal>` | `amount` bound from its string form to `BigDecimal`, then scaled to 2 decimals `DOWN` | `404`, `400` INVALID_AMOUNT, `422` INSUFFICIENT_FUNDS, `422` AMOUNT_OUT_OF_RANGE, `503` EXCHANGE_RATE_UNAVAILABLE |
| `C` | `PUT /cash-account/{owner}/credit?amount=<decimal>` | as debit | `404`, `400`, `422` AMOUNT_OUT_OF_RANGE, `503` EXCHANGE_RATE_UNAVAILABLE |

**Both writes need a body, and `PUT` overwrites rather than patches.** A `POST` or `PUT` carrying no body — or a literal
JSON `null` — is rejected with `400` INVALID_AMOUNT and is never read as a request for `0.00`. Inside the body, the
`owner` component is ignored, because the path variable is authoritative and the two agree in every call broker makes;
an absent or null `balance` is `0.00`; an absent, null or blank `currency` is `cashaccount.fx.base-currency`, `USD` as
shipped. Both defaults are legacy behaviour rather than convenience: the COMMAREA field `WS-BALANCE PIC 9(7)V99`
[`CASH00.cbl:L56`](../cash-account-cobol/COBOL/CASH00.cbl) could not be null, so an unset field arrived as zeros, and
broker substitutes `USD` before it calls. The consequence is worth stating plainly, because `PUT` is an absolute
overwrite of both columns: `PUT /cash-account/JOHN` with the body `{}` sets that account to `0.00 USD` and writes an
`ACCOUNT_UPDATED` ledger row saying so. Send the full `{owner, balance, currency}` object on every write.

Retail `balance` is the **available** balance. With no reservation outstanding it equals the total, which is the only
state the legacy single-balance program could ever be in, so parity is exact; with funds held it is the spendable
amount, which is what a retail debit has to be checked against. A `0` amount is accepted exactly as the legacy `C`/`D`
accepted it: `200`, balance unchanged, one zero-amount ledger row, so transaction counts still reconcile.

### Institutional reservation surface

Additive, under its own path space. No retail path, verb, query parameter or payload schema changes by its existence.

| Verb and path | Request | Success |
| --- | --- | --- |
| `POST /cash-account/institutional/accounts/{owner}/holds` | header `Idempotency-Key` (required); body `{orderReference, amount, currency, expiresAt?}` | `201` with the hold in state `HELD`. A replay with the same key and an identical payload returns `200` with the original body and `Idempotent-Replayed: true`; the same key with a different payload is `422` IDEMPOTENCY_KEY_REUSED |
| `POST /cash-account/institutional/reservations/{reservationId}/settle` | body `{amount?}` — absent or null settles the full held amount; a partial amount settles it and releases the remainder; `0` is legal | `200` with state `SETTLED`; settling an already-settled reservation returns its current state |
| `POST /cash-account/institutional/reservations/{reservationId}/release` | — | `200` with state `RELEASED`; releasing an already released or expired reservation returns its current state |
| `GET /cash-account/institutional/reservations/{reservationId}` | — | `200` with the reservation |
| `GET /cash-account/institutional/accounts/{owner}` | — | `200` `{owner, currency, availableBalance, reservedBalance, totalBalance}` |
| `GET /cash-account/institutional/accounts/{owner}/ledger?since=&limit=` | `since` optional, an inclusive ISO-8601 (UTC) lower bound on `recordedAt`; `limit` optional, default `100`, maximum `1000` | `200` with the entries ordered `recordedAt DESC, entryId DESC`. Rows outlive the account, so a deleted owner still returns its audit trail; an owner with neither rows nor an account returns `[]` |

A hold's `currency` must equal the account currency (`400` CURRENCY_MISMATCH); nothing converts on the institutional
path. Holds, settlements, releases and expiries each write their ledger row in the **same** transaction as the balance
change, so `…/ledger` returns it on the very next call — no polling, no eventual consistency.

Two interactions a retail caller can observe, and only after an institutional caller has acted on the same owner:

1. Retail `balance` reports the spendable amount, which is lower than the total while funds are held.
2. Retail `PUT` and `DELETE` answer `409 RESERVATIONS_OUTSTANDING` while a `HELD` reservation exists, because
   overwriting or deleting an account with funds on hold would corrupt the reservation.

### Error model

Every error — from a controller, from Spring MVC's own routing, or from the security filter chain before any
controller runs — is the same payload:

```json
{"code": "ACCOUNT_NOT_FOUND", "message": "Cash account not found.", "owner": "JOHN", "timestamp": "2026-01-01T00:00:00Z"}
```

`owner` and `reservationId` appear only where the condition has them. `CashAccountErrorCode` binds each code to
exactly one HTTP status, and it is the only place that mapping lives — this is what replaces the legacy `SQLCODE`
return channel, which dropped the sign in a `X(10)` field and reported only the last statement's code.

| Code | HTTP | Condition |
| --- | --- | --- |
| `ACCOUNT_NOT_FOUND` | 404 | No such owner (legacy `SQLCODE 100` on `Q/U/X/C/D`) |
| `ACCOUNT_ALREADY_EXISTS` | 409 | Create on an existing owner (legacy `-803`) |
| `INVALID_OWNER` | 400 | Blank, longer than 32 characters (legacy truncated silently to 15), or spelled with anything outside `A-Z 0-9 . _ -` after trim and uppercase |
| `INVALID_AMOUNT` | 400 | Missing, non-numeric or negative `amount` — the sign is judged before the value is scaled, so a sub-cent negative such as `-0.001` is rejected rather than normalized to `0.00`; a hold of `≤ 0`; a settlement above the held amount |
| `INVALID_CURRENCY` | 400 | Not a three-letter code in the accepted set, after trim and uppercase |
| `CURRENCY_MISMATCH` | 400 | Hold currency ≠ account currency |
| `INVALID_REQUEST_FIELD` | 400 | A request member the vocabulary above has no code for — a hold's `orderReference` (blank, absent or over 64 characters) or an `expiresAt` that is not an ISO-8601 instant. The message names the member, e.g. `Validation failed on 'orderReference'.`, and the member's own name selects the code, so an `amount` failure is still `INVALID_AMOUNT` and a `currency` failure still `INVALID_CURRENCY`. Beyond the AAP's own error table, recorded with the three below under [Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory) |
| `INSUFFICIENT_FUNDS` | 422 | The operation would drive the available balance negative (legacy stored the absolute value) |
| `AMOUNT_OUT_OF_RANGE` | 422 | The result leaves `0.00 … 9999999.99` (legacy dropped high-order digits). It is also the answer to a **hold** above that ceiling, which is the same input class the retail credit and debit answer this way — `INSUFFICIENT_FUNDS` is reserved for what it names, a hold larger than the available balance |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | A hold without the `Idempotency-Key` header |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Known key, different payload |
| `RESERVATION_NOT_FOUND` | 404 | No such reservation |
| `INVALID_TRANSITION` | 409 | Settle from `RELEASED`/`EXPIRED`, or release from `SETTLED` |
| `RESERVATIONS_OUTSTANDING` | 409 | Retail `PUT`/`DELETE` while a `HELD` reservation exists |
| `EXCHANGE_RATE_UNAVAILABLE` | 503 + `Retry-After: 5` | The rate is missing, unreachable or unparsable; the balance is unchanged and no ledger row is written (legacy returned success over an uninitialized rate) |
| `DATASTORE_UNAVAILABLE` | 503 + `Retry-After: 5` | Database unreachable (legacy `-911` / `-913` / `-904`) |
| `UNSUPPORTED_PATH` | 404 | An unmapped path — fail closed, where the legacy `EVALUATE` fell through as success. Also the answer on the retail seam for the one reserved segment, `/cash-account/institutional` and its `/debit` and `/credit` in any casing: those reach the retail `/{owner}` mappings, and serving them would make the institutional namespace a retail account. Owner identity is untouched — the loader still carries a legacy row named `INSTITUTIONAL`, and the institutional endpoints still read it |
| `UNSUPPORTED_METHOD` | 405 | A known path with an unmapped verb |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | A request body in a media type no configured converter reads — `text/plain`, `application/xml`, or the `application/x-www-form-urlencoded` a `curl -d` sends when no `Content-Type` is given. The rejection carries `Accept: application/json`, naming what this service does read. Beyond the AAP's own error table, and recorded with `REQUEST_TOO_LARGE` under [Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory) |
| `NOT_ACCEPTABLE` | 406 | An `Accept` header this service cannot satisfy (`application/xml`, `text/html`). The body is still the `ApiError` shape in `application/json` — a rejection that could not be rendered would be no rejection at all. Recorded in the same place |
| `REQUEST_TOO_LARGE` | 413 | A request body above `server.max-request-body-size` (8KB), refused on its declared `Content-Length` or counted mid-read — see [Request intake bounds](#request-intake-bounds). One of the three codes here with **no** legacy counterpart and no entry in the AAP's own error table: a COMMAREA is a fixed-length structure, so an oversized request was unrepresentable rather than rejected, and a 413 cannot be reported without a code of its own. Recorded under [Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory) |
| `INVALID_QUERY` | 400 | `limit` below 1 or above 1000, or an unparsable `since` |
| `CONCURRENT_MODIFICATION` | 409 + `Retry-After: 1` | A lock conflict. One second, not five: the conflict clears as soon as the competing transaction commits |
| `UNAUTHORIZED` | 401 | Missing or invalid token, rendered by the filter chain's entry point |
| `FORBIDDEN` | 403 | Authenticated but lacking the required role, rendered by the access-denied handler |
| `INTERNAL` | 500 | Any unexpected exception |

That is the complete set: **26 codes**, one status each, nothing else reachable — the 22 the AAP's error table
declares, with the statuses it declares, plus `REQUEST_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `NOT_ACCEPTABLE` and
`INVALID_REQUEST_FIELD`. Each of the four exists because the condition it names is a caller mistake that has to be
reported and the closed vocabulary can express it in no other way that stays true; all four are on the same
signable register row.

Every one of them is reachable from outside a handler as well as from inside one, which is a property of *where*
they are rendered rather than of the table above. Three renderers exist for the rejections no controller sees, and
all three produce the identical payload and the identical security headers:

| Rejection decided by | Renderer | What it answers |
| --- | --- | --- |
| Spring Security's `StrictHttpFirewall`, before any filter runs — a path holding `//`, a matrix parameter, an encoded path separator, a control character, or a method outside `HEAD, DELETE, POST, GET, OPTIONS, PATCH, PUT` | `error/ApiErrorRequestRejectedHandler`, installed on `WebSecurity` by `config/SecurityConfig` | `400 INVALID_QUERY`. The default handler called `sendError(400)` with no body, which the container turned into an ERROR dispatch that the chain then denied as anonymous, so the caller received `401 UNAUTHORIZED` — on `/actuator` paths that require no authentication too |
| The servlet container, for a request that reached a context — a failure thrown out of the chain, an asynchronous dispatch that failed | `error/ApiErrorController`, which replaces Spring Boot's `BasicErrorController`; `config/SecurityConfig` admits `DispatcherType.ERROR` so it is reachable | The `ApiError` for the status the container set. A direct `GET /error` is an ordinary REQUEST dispatch, matches no rule and stays denied |
| Tomcat, before any servlet context exists — a header block over `server.max-http-request-header-size`, an unparsable request line, an encoded path separator | `error/ApiErrorReportValveCustomizer`, a host-pipeline valve installed after Spring Boot's own `ErrorReportValve` so it reports first | The `ApiError` for the status Tomcat set, replacing Tomcat's minimal HTML page |

## Security

The service validates the **existing** estate token. Broker propagates the caller's JWT unchanged as the
`Authorization` header — `org.eclipse.microprofile.rest.client.propagateHeaders=Authorization,Proxy-Authorization` in
`backend/broker/src/main/resources/META-INF/microprofile-config.properties` — so this service verifies the same RS256
token, signer, issuer and audience the estate already mints, and introduces no new identity mechanism.

### Modes

`AUTH_TYPE` (`cashaccount.security.auth-type`) selects one of four:

| Mode | How a token is verified |
| --- | --- |
| `basic` (default), `ldap` | Against the `jwtSigner` **public** certificate at `cashaccount.security.jwt.public-key-location`, plus an issuer validator, an audience validator and an `exp`-presence validator. The two modes differ only in the user registry Liberty siblings authenticate against, which is upstream of this service and invisible to it |
| `oidc` | Against the JWKS at `OIDC_JWKS_URL`, mirroring the `jwksUri` broker resolves from the same variable. The same validators apply — the mode changes where the verification key comes from, nothing about what a valid token must claim |
| `none` | **No authentication at all.** The service's own path space is `permitAll` |

`exp` must be **present**, not merely unexpired. Spring Security's default timestamp validator compares the claim to
the clock only when the token carries it, so a token minted without one would otherwise be a credential that never
expires — and this service keeps no per-token state, so it can revoke nothing. Requiring the claim refuses nothing
the estate issues: the siblings mint `expiry="12h"`. No shorter maximum lifetime is imposed on top of `exp`.

`none` is a deliberate dev/test deviation from the siblings, not parity: their `none.xml` disables `mpJwt` but still
enables a dummy `basicRegistry` and adds `overrideHttpAuthMethod="BASIC"`, so a caller still presents credentials.
This service replicates neither. **`AUTH_TYPE=none` is unfit for any shared environment** — treat it as a
single-developer convenience and nothing more. An unrecognized `AUTH_TYPE` fails start-up rather than being guessed:
reading it as `none` would silently open the service, and reading it as `basic` would authenticate against a mechanism
the operator did not ask for.

### Roles

The token's `groups` claim becomes `ROLE_StockTrader` / `ROLE_StockViewer` authorities. Each value is matched
**exactly**, through a closed lookup of those two estate group names — a value differing only by surrounding
whitespace or a control character (`StockTrader `, `\tStockTrader`, `StockTrader\u0000`) is a different value and
grants nothing, and no caller-supplied text ever becomes an authority name. The filter chain then decides in this
order:

1. `/actuator/**` and `/metrics` — `permitAll`. The chart's probes carry no credentials, so an authenticated probe
   path means the pod never passes its startup probe and the deployment never rolls.
2. `/cash-account/institutional/**` — `hasRole('StockTrader')`.
3. `GET /cash-account/{owner}` — `hasAnyRole('StockViewer','StockTrader')`.
4. `POST`, `PUT`, `DELETE /cash-account/{owner}` and `PUT /cash-account/{owner}/{debit,credit}` —
   `hasRole('StockTrader')`.
5. Anything else under `/cash-account/**` — `authenticated()`, so an authenticated caller asking for an unsupported
   path or verb reaches Spring MVC and receives the `404 UNSUPPORTED_PATH` / `405 UNSUPPORTED_METHOD` `ApiError`, while
   an unauthenticated one is refused with `401`.
6. `anyRequest()` — `denyAll()`. This is the analogue of broker's `deny-uncovered-http-methods` for everything outside
   this service's path space, and it holds in every mode, `none` included.

Rules 2–4 reproduce broker's `web.xml` GET-versus-write split.

**The deployed default makes that split latent, on purpose.** `cashaccount.security.all-authenticated-hold-stocktrader`
defaults to `true`, which grants `StockTrader` to every authenticated principal — mirroring the siblings'
`ALL_AUTHENTICATED_USERS → StockTrader` binding in broker's `server.xml`. Through broker today, any authenticated
caller may write; the new service behaves the same way rather than tightening a rule mid-migration. Setting it to
`false` is a supported, documented **strict mode** in which only the token's `groups` decide, and it is tested as its
own configuration.

### The caller's token goes nowhere

This service makes no downstream call that carries the caller's credentials. Its only outbound call is to the public
exchange-rate API, and forwarding `Authorization` there stays prohibited: the FX client attaches exactly one default
header — a fixed `User-Agent: cash-account-service`, which identifies the service and deliberately carries no version,
since the JDK's own default `User-Agent` would otherwise publish the runtime's exact patch level to that third-party
provider. The currency-conversion test asserts that header and the absence of `Authorization`, `Proxy-Authorization`
and `Cookie` on the recorded FX request.

### Key hygiene

**Only the public X.509 certificate ships**, at `src/main/resources/security/jwtsigner.pem`. It was exported from the
shared Liberty trust store with:

```bash
IFS= read -rsp 'trust store password: ' TRUST_STORE_PASSWORD; echo
export TRUST_STORE_PASSWORD
keytool -exportcert -rfc -alias jwtsigner \
  -keystore ../broker/src/main/liberty/config/resources/security/trust.p12 \
  -storetype PKCS12 -storepass:env TRUST_STORE_PASSWORD \
  > src/main/resources/security/jwtsigner.pem
unset TRUST_STORE_PASSWORD
```

Supply the trust store's configured password (see broker's `server.xml`) through the environment as shown; it is not
reproduced here. **`-storepass:env` names the variable; plain `-storepass "$TRUST_STORE_PASSWORD"` would not do.** The
shell expands the latter before `keytool` runs, so the password itself lands in `keytool`'s `argv`, where `ps -o args=`
shows it to every process on the host for the life of the command — the non-echoing prompt prevents terminal echo and a
history entry, and does nothing about `argv`. With `:env`, `keytool` reads the variable itself and only the variable's
*name* is ever an argument. `-storepass:file <0600-file>` is the equivalent when a prompt is impractical; delete the
file afterwards. Both forms are accepted by the JDK 21 `keytool` this module builds with.

`keytool -exportcert -rfc` emits a `-----BEGIN CERTIFICATE-----` PEM, which
`config/JwtDecoderConfig` reads through a `CertificateFactory`; it also accepts a `-----BEGIN PUBLIC KEY-----` PEM,
which is what the tests' ephemeral keys produce.

**Neither `trust.p12` nor `key.p12` is copied into this module**, and no keystore is. The reason is specific rather
than stylistic: that shared store's `jwtSigner` alias is a *private* key entry, and the siblings ship it inside every
image — a known defect this module does not repeat. A public certificate is all a resource server needs to verify a
signature, so a private key in this image would be pure liability.

Tests never use the real signer. `JwtTestTokens` generates an **ephemeral RSA-2048 key pair per test JVM**, writes its
public key as PEM to a temporary directory and repoints `cashaccount.security.jwt.public-key-location` at it through
`@DynamicPropertySource`. **No key material is checked in**, and tokens signed by the real `jwtSigner` are verified
only in a deployed environment.

## Migration and reconciliation tooling

The same jar is the migration CLI. Under the `tool` profile the web server is off and `MigrationToolRunner` executes
one command per invocation:

```bash
# One batch id per runbook step, shared by the load and the reconcile that judges it.
BATCH_ID=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')

java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=tool \
     --tool.command=load \
     --tool.input=src/test/resources/fixtures/legacy-export/matched \
     --tool.batch-id="$BATCH_ID" \
     --tool.history-record-length=100

java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=tool \
     --tool.command=reconcile \
     --tool.input=src/test/resources/fixtures/legacy-export/matched \
     --tool.batch-id="$BATCH_ID"

java -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=tool \
     --tool.command=shadow-compare \
     --tool.input=src/test/resources/fixtures/shadow/matched \
     --tool.batch-id="$BATCH_ID"
```

The tool profile needs the same `JDBC_*` environment as a normal run. Each invocation is its own `migration_run` row;
the shared `--tool.batch-id` is how a `reconcile` names the `load` it judges.

**A `shadow-compare` window depends on a completed `load` in the same database, whatever batch id it carries.** The
replay prices its cross-currency lines from the staged `frankfurt1` rates under `tool.rate-source=legacy-table`, and
those rows belong to a `load` run. The window resolves that run in two steps: the completed `load` of its own batch id
if there is one — which is why the block above reuses `$BATCH_ID` — and otherwise **the most recent completed load in
the schema**, which is what the runbook's per-window batch ids resolve to. Either way the run that priced the window
is named in its log:

```text
Legacy rate lookups resolve against staging run <run> of batch <batch>
Batch <batch> holds no completed load, so legacy rate lookups resolve against staging run <run> of batch <batch> - this schema's most recent completed load
```

With **no** completed load anywhere in the schema there is no rate to price with, and each cross-currency line is
recorded as a `REJECTED_BY_TARGET` row with status `VARIANCE` and migrated value `EXCHANGE_RATE_UNAVAILABLE`, so the
window exits `2` rather than reporting agreement it never measured. A target that cannot price a line the captured
legacy reply priced successfully is reporting its own environment, so that row is an outstanding variance and never an
accepted exception — unlike the over-debit the legacy stored as an absolute value, which is one
([`tool.rate-source`](#toolrate-source) and the fixture expectations below).

`--tool.history-record-length=100` appears on the `load` alone, and it is not optional there: that fixture directory
carries a binary `history.cp037.bin`, and for binary history the record length is **declared, never inferred** — the
runner refuses the invocation without it before reading a row. `100` is the length that directory's fixture uses (eight
padded records, 800 bytes; `57` would be rejected because 800 is not a multiple of it). The `reconcile` and
`shadow-compare` commands carry no such argument because neither opens a history file: `reconcile` reads the account
and rate exports and `shadow-compare` reads the two captured streams, so requiring it of them would reject a valid
invocation over a file it never touches.

| Exit code | Meaning |
| --- | --- |
| `0` | Clean — no variance rows |
| `2` | Variance — the run completed and recorded variances |
| `1` | Error — the run failed |

A variance is never only a log line: every one is a `migration_reconciliation` row carrying the owner, the variance
kind, the legacy value, the migrated value, the signed difference and a status (`MATCHED`, `VARIANCE`,
`ACCEPTED_EXCEPTION`), summarized by its `migration_run`. That is what an operator signs off against, and a log cannot
be signed off.

A `load` runs in **one** database transaction: either the whole export is applied and the run is recorded
`CLEAN`/`VARIANCE`, or nothing is applied and the run is `FAILED`. A retry is therefore simply a new run under the same
batch id, and a partial unique index on `ledger_entry` guarantees a completed run can never write a second
`MIGRATION_LOAD` row for an owner.

### Input shapes

Delimited files — `cashaccounty.csv`, `frankfurt1.csv`, `history.csv` and `target-state.csv`:

- UTF-8, LF line endings, an optional BOM ignored.
- The first line is a header naming the columns; readers key by header name, never by position. Headers are
  `owner,balance,currencyc`; `currnkey,currnbase,amount,rates,loaddt`;
  `name,event_date,event_time,request_code,balance,currency,retcode`. `target-state.csv` carries the
  `cashaccounty.csv` columns, because it describes a divergent *migrated* state in the same shape.
- RFC 4180 quoting: a field containing a comma, a quote or a newline is double-quoted with embedded quotes doubled.
  Trailing `CHAR` padding may be present and is right-trimmed.
- NULL is an **empty unquoted field**. The literal text `NULL` is not recognized — it would be indistinguishable from
  an owner or currency legitimately spelled that way.
- Decimals are plain text matching `-?\d+\.\d{2}`: no thousands separators and no exponent. `loaddt` is `YYYY-MM-DD`;
  history stamps are `YYYYMMDD` and `HHMMSS`.

`frankfurt1.csv` accepts **either `currnbase` or `cyrrnbase`** as the second column header. The shipped DB2 DDL
declares the column `cyrrnbase` while the copybook and the program's own `SELECT` use `CURRNBASE`; which spelling the
deployed catalog carries cannot be read from this repository, so the reader accepts both rather than guessing (see
[Open items](#open-items)).

Binary history — `history.cp037.bin`:

- Raw EBCDIC bytes, decoded field by field in `tool.legacy-charset` (default `IBM037`), never as a whole record in the
  platform default charset.
- Fixed-length records at the field offsets of the 57-byte layout the program writes. Balances are unsigned zoned
  decimal, nine digits, implied scale 2.
- The record length is **declared**, never inferred: `tool.history-record-length` must be `57` (what the program
  writes) or `100` (the cluster's `RECSZ`), the file length must be an exact multiple of it, and anything else is
  rejected with an explicit error. Bytes 57–99 of a padded record are treated as padding.

### `tool.rate-source`

Parity has to be judged on identical inputs, so the default is `legacy-table`: expected values are computed from the
staged `frankfurt1` rate rows with the legacy arithmetic. With `tool.rate-source=live` the live exchange-rate lookup is
used instead, and a balance difference that the rate difference fully explains is recorded as a `RATE_SOURCE` variance
with status `ACCEPTED_EXCEPTION`; anything left over is a `BALANCE` variance with status `VARIANCE`.

### Proven against fixtures only

The tooling is verified against the synthetic fixtures under `src/test/resources/fixtures/` — DB2-UNLOAD-shaped CSVs,
an IBM037 history dump and shadow transaction streams, each directory in a **matched** and a **seeded-mismatch**
variant, with a `MANIFEST.md` recording which characterization finding and which seed every file encodes:

- `src/test/resources/fixtures/legacy-export/{matched,seeded-mismatch}/` — `cashaccounty.csv`, `frankfurt1.csv`,
  `history.csv`, `history.cp037.bin`, and `target-state.csv` in the seeded variant
- `src/test/resources/fixtures/shadow/{matched,seeded-mismatch}/` — `transactions.csv` and `legacy-responses.csv`,
  plus `src/test/resources/fixtures/shadow/rollback-replay.csv`
- `src/test/resources/fixtures/fx/frankfurter-latest-usd.json` — the recorded exchange-rate response shape

Matched fixtures must reconcile with zero variance; seeded fixtures must flag exactly the seeded rows and nothing else.

Running any of this against the real DB2 for z/OS or the real VSAM history is **not** something this deliverable does.
That is `docs/operational-runbook.md` Step 1, with its own preconditions, read-only credentials, disposable rehearsal
schema and data-owner sign-off — see [Prohibitions](#prohibitions).

## Image build, scan and push

**This section is the only place the build → scan → push path is documented, because no CI workflow exists for this
module.** The umbrella repository has no root `.github` directory, and every sibling's workflow executes only inside
that sibling's own repository, so a workflow file placed under `backend/cash-account-modernized/` could not run here.
When the module is promoted to its own repository, a workflow following
`backend/portfolio/.github/workflows/build-test-push-azure-acr.yml` reproduces exactly the steps below — that is a
promotion-time task, not part of this deliverable.

Run the steps in this order. Half of them are scans and they answer different questions: steps 2 and 3 read the
build inputs before an image exists, steps 5 and 6 read the assembled artifact. **Step 6 is the only one that can
stop a promotion**, and it is the reason this is a promotion path rather than a build recipe.

```bash
cd backend/cash-account-modernized

# 1. The gate. The image is only worth building if this passes.
./mvnw -B clean verify

# 2. Scan the build inputs. This sees what no image scan can: coordinates the parent POM manages
#    that never ship as a jar of their own. It must run AFTER step 1, because it reads the parent
#    chain out of the local Maven repository that step populated.
trivy fs --scanners vuln --severity CRITICAL,HIGH pom.xml

# 3. Scan the resolved dependency set against the NVD. This step CANNOT RUN without the feed
#    credential or mirror below, which is the promotion pipeline's to provision; the credential
#    reaches it through a 0600 settings file rather than through argv.
./mvnw -B -s "$CI_SETTINGS" org.owasp:dependency-check-maven:13.0.0:check \
       -Dformat=JSON -DfailBuildOnCVSS=11

# 4. Build the image. The Dockerfile only packages; nothing compiles inside it.
docker build -t <registry>/cash-account:<version> .

# 5. Scan the image, informational. Non-blocking, matching the siblings' workflow shape: every
#    finding is reported, including the ones nobody can act on yet.
trivy image --exit-code 0 --severity CRITICAL,HIGH,MEDIUM <registry>/cash-account:<version>

# 6. Scan the image, blocking. THIS is the promotion gate: any CRITICAL or HIGH that has a fix
#    available stops the promotion. Findings with no fix are reported by step 5, not gated here.
trivy image --exit-code 1 --severity CRITICAL,HIGH --ignore-unfixed <registry>/cash-account:<version>

# 7. Push, only after step 6 exits 0.
docker push <registry>/cash-account:<version>

# 8. Capture the digest. This is the reference that matters.
docker inspect --format '{{index .RepoDigests 0}}' <registry>/cash-account:<version>
```

Where the runner has no `trivy` binary, the three Trivy scans run from the official image, which is how they were
exercised for this module. Each mount earns its place: the Docker socket is what lets the scanner read an image
that exists only locally, the cache is what stops it re-downloading its vulnerability database on every call, and
the Maven repository is what step 2 resolves the parent POM chain against:

```bash
# Steps 5 and 6 — the assembled image.
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$HOME/.cache":/root/.cache \
  aquasec/trivy:0.58.2 image --exit-code 1 --severity CRITICAL,HIGH --ignore-unfixed \
  <registry>/cash-account:<version>

# Step 2 — the build inputs. No socket; a read-only module and a read-only local repository instead.
docker run --rm -v "$HOME/.cache":/root/.cache -v "$HOME/.m2":/root/.m2:ro -v "$PWD":/src:ro -w /src \
  aquasec/trivy:0.58.2 fs --scanners vuln --severity CRITICAL,HIGH pom.xml
```

The captured **digest** — not the tag — is the immutable reference the runbook's cutover value set uses. A tag can be
re-pointed after the fact; a digest cannot, which is what makes "the image we validated" and "the image the pod runs"
the same statement. Step 8 only answers after step 7: a locally built image carries no `RepoDigests` entry until it has
been pushed to a registry.

### The base image is pinned ahead of the sibling's tag, deliberately

`Dockerfile` pins `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24@sha256:908540a9…` — the head of the
`1.24` stream (build `1.24-3.1790007358`), carrying RHEL 9.8 and `java-21-openjdk-headless 21.0.12.1.1-1.2.el9`.
`backend/account/src/main/docker/Dockerfile.jvm:L94` still names `1.21`, and this module followed that tag until the
difference was measured on the assembled image with the scan of step 5:

| Base | OS content | JDK package | HIGH + CRITICAL | With a fix available |
| --- | --- | --- | --- | --- |
| `1.21` (the sibling's tag) | `redhat 9.5` | `21.0.6.0.7-1.el9` | 75 | **64**, including ten CVEs against the JDK itself, remediated no later than `21.0.12.0.8` |
| `1.24` (pinned here) | `redhat 9.8` | `21.0.12.1.1-1.2.el9` | 11 | **0** |

What the sibling supplies is the *provenance* — the Java 21 runtime, the non-root `185` user contract, the
`run-java.sh` entrypoint — and that is inherited unchanged. What it does not supply is a reason to ship a tag frozen a
year earlier: the tag is the only place that choice can be made, and nothing about matching a sibling's string makes an
unpatched OpenJDK safe. **Reverting the pin to `1.21` is not an option on the table — that tag is the vulnerable one.**

This pin therefore departs from the frozen dependency inventory this module was planned against, which names the
sibling's `1.21` explicitly. It is the same shape of outstanding decision as the rows in
[Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory) and needs the requesting
organization's authorization on the same terms and at the same point — the runbook's Step 0 prerequisites, where the
platform owner's sign-offs are already collected.

A pin goes stale by sitting still, so re-derive the stream head rather than trusting this page. The first command
answers which digest a tag currently resolves to, without pulling; the second and third say what is inside it:

```bash
curl -sI -H 'Accept: application/vnd.docker.distribution.manifest.list.v2+json' \
     https://registry.access.redhat.com/v2/ubi9/openjdk-21-runtime/manifests/1.24 \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="docker-content-digest"{print $2}'

docker pull registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24   # its Digest: line must match

docker run --rm --entrypoint rpm registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24 \
  -q java-21-openjdk-headless redhat-release
```

Confirm the `Accept` negotiation returned `application/vnd.docker.distribution.manifest.list.v2+json` before pinning:
a per-architecture digest builds on one architecture and fails on every other, and the failure surfaces as an image
pull error on a node nobody tested on. Moving the pin means changing the tag and the digest in the same edit, with the
gate below re-run against the rebuilt image — never the digest alone, which would leave the tag naming a release the
image is not.

### What the blocking gate can and cannot decide

Step 6 gates on `CRITICAL,HIGH` **with `--ignore-unfixed`**, and both halves of that are deliberate. Gating on every
HIGH cannot be satisfied by any version of this image: eleven findings in the current base have no fix in any tag —
`CVE-2026-53613` (util-linux libraries), `CVE-2026-66046` (expat), `CVE-2026-74860` and `CVE-2026-86140` (libxml2),
`CVE-2026-86145` and `CVE-2026-89161` (pcre2) — so a gate that counted them would fail every build, and a gate that
fails every build is switched off within a week. Gating on the fixable set asks the one question a promotion can act
on: *is there a patched package we have not taken?* On the `1.21` base the answer was yes, 64 times over; on the
current pin it is no.

Run against the module as it stands, **that gate exits 1** — and where it fails is the point of having it. The OS
layer is clean: `Total: 0 (HIGH: 0, CRITICAL: 0)`. The failure is entirely in the shipped jars, `Total: 10 (HIGH: 9,
CRITICAL: 1)`, whose fix versions all lie outside the mandated framework line. Add `--pkg-types os` or
`--pkg-types library` to see the two layers separately, which is what tells you whose problem a failure is:

| Gate invocation | Verdict on the current pin | Verdict on the previous `1.21` pin |
| --- | --- | --- |
| `--pkg-types os` (the base image) | exit **0**, `Total: 0` | exit **1**, `Total: 64 (HIGH: 64)` |
| `--pkg-types library` (the shipped jars) | exit **1**, `Total: 10 (HIGH: 9, CRITICAL: 1)` | identical — the jars are the same |

The left column is what a rebase can fix and the right column is what this gate would have caught a year ago; the
library row is the framework-line decision in [Open items](#open-items), which only the requesting organization can
close. So promotion cannot currently claim a clean gate, and that is the honest state rather than a defect in the
gate: a `.trivyignore` file or a `--skip-files` argument would turn a CRITICAL advisory into a silent pass, which is
exactly the failure mode the previous base image demonstrated. The runbook's Step 0 sign-off carries the alternative —
a named finding, why no available version removes it, and the compensating control, recorded as an outstanding
decision.

The informational pass in step 5 is what keeps the unfixable set visible rather than suppressed — it is run first, and
its full output is Step 0 evidence. Three rules follow from that split:

- A finding with **no fix** is reported, recorded, and does not block. It is re-read at each promotion, because "no
  fix available" is a statement about today.
- A finding **with a fix** blocks. The remedy is a rebuild on a newer base or a dependency bump, never an exception —
  the gate exists precisely because a stale base is invisible otherwise.
- Where a fixable finding genuinely cannot be taken (no newer base carries it, or the bump breaks the module), the
  promotion does not proceed on a suppression. Record it as an outstanding decision for the requesting organization,
  the way every other deviation in this document is recorded, and name the compensating control.

The Java layer of the same image scan reports the shipped jars and is read the same way; the advisories it currently
raises, the nine dependency overrides that answer most of them, and the framework-line decision that blocks the rest
are recorded in [Open items](#open-items) and its deviations register.

### Dependency scanning needs a vulnerability feed the pipeline must provision

Step 3 is a **required checklist item that this environment cannot execute**, and the reason is external to the
module. `dependency-check-maven` 13 refuses to contact the NVD without a credential:

```text
[ERROR] Error updating the NVD Data
org.owasp.dependencycheck.data.update.exception.UpdateException: Error updating the NVD Data
Caused by: io.github.jeremylong.openvulnerability.client.nvd.NvdApiException:
          Invalid API Key, length of 0 too short to provided a masked partial key
[ERROR] 	NoDataException: No documents exist
```

Dropping to the older plugin generation does not help: the JSON 1.1 feeds it downloaded are retired and answer
`403 Forbidden` for `nvdcve-1.1-modified.meta`. The NVD 2.0 API itself is reachable, so this is not a network
question — it is a provisioning one. Any **one** of these makes step 3 runnable, and the promotion pipeline must
supply one of them:

| Provision | Plugin property | When to choose it |
| --- | --- | --- |
| An NVD API key | `nvdApiKey` | The default. Free from NVD, per-organization, and it also lifts the keyless request throttle. It is a **credential** — see below for where it is put |
| An internal NVD API mirror | `nvdApiEndpoint` | The organization already proxies the 2.0 API |
| An internal datafeed mirror | `nvdDatafeedUrl`, plus `nvdUser` / `nvdPassword` if it is authenticated | A runner with no route to `services.nvd.nist.gov` |
| A pre-populated cache | `dataDirectory` with `autoUpdate=false` | An air-gapped runner, fed from a cache refreshed elsewhere; `nvdValidForHours` governs how long a populated cache is trusted |

Each of those is the plugin's own user property, so a non-secret one travels as `-D<name>=<value>` and none of them
needs a POM change; the plugin is not declared in `pom.xml` at all and is invoked by coordinate, which keeps the scan
out of `verify` and off every developer build.

The key is the exception, because the obvious way to pass it is the wrong one on a shared runner: a
`-DnvdApiKey=<value>` argument is readable by every process on the host (`ps -o args=`) and an interactive shell
keeps it in `HISTFILE` — and `-DnvdApiKey="$NVD_API_KEY"` is no better, since the shell expands it before `exec` and
the key lands in `argv` either way. Maven does not interpolate `${env.…}` in a command-line property, and the place
that *is* interpolated — a `<nvdApiKey>` element in plugin configuration — would mean declaring the plugin in
`pom.xml`. So supply it as a property of an active profile in a `0600` settings file the pipeline renders from its
secret manager, and name that file on the command line instead:

```bash
./mvnw -B -s "$CI_SETTINGS" org.owasp:dependency-check-maven:13.0.0:check \
       -Dformat=JSON -DfailBuildOnCVSS=11
```

```xml
<!-- $CI_SETTINGS: mode 0600, rendered per run from the secret manager, deleted with the runner.
     NVD_API_KEY_PLACEHOLDER is substituted by the pipeline, never committed anywhere. -->
<settings><profiles><profile>
  <id>nvd</id><activation><activeByDefault>true</activeByDefault></activation>
  <properties><nvdApiKey>NVD_API_KEY_PLACEHOLDER</nvdApiKey></properties>
</profile></profiles></settings>
```

On a single-tenant ephemeral runner the `-D` form is the common practice and its exposure is bounded by the runner's
lifetime; on anything shared or long-lived it is a credential leak to every process on the box, the same reason the
database password is handled the way [Run locally](#run-locally) handles it.

A key supplied through that file does reach the plugin, and the failure text is how you tell: with no key at all the
run dies at `length of 0 too short to provided a masked partial key`, whereas a key the NVD rejects produces
`Invalid API Key: 0000d-*****-0cafe` — the plugin masking the value in its own output, which is also what makes that
log safe to keep as Step 0 evidence.

Until one is provisioned, the promotion path is not left without dependency coverage, and the two Trivy scans are that
coverage rather than a formality:

- **Step 2, the build inputs.** `trivy fs --scanners vuln pom.xml` resolves the parent POM chain, so it reports
  advisories against coordinates the parent *manages*. On this module it returns the image scan's whole Java layer
  plus exactly one row that layer cannot contain: `CVE-2026-22733` against `spring-boot-starter-actuator`, a
  starter that is a POM rather than a jar and so is present in no image at all.

  **It resolves that chain from the local Maven repository, and a missing chain looks like a clean result.** Run it
  after step 1, on the same runner, so `~/.m2/repository` is populated; from the container form, mount the
  repository (`-v "$HOME/.m2":/root/.m2:ro`) or the scan has nothing to resolve against. Without it the scan
  prints `WARN [pom] Dependency version cannot be determined. Child dependencies will not be found.` and then
  reports nothing at all — a silent empty pass, which is the one outcome this step exists to prevent. Treat that
  warning as a failed scan, not a clean one.
- **Step 5, the assembled image.** Every OS package and every jar actually inside the artifact, which is the only
  view that reflects what a pod runs.

Keep both steps in the pipeline after a feed is provisioned. They overlap with `dependency-check` on the shipped jars
and none of the three subsumes the others: the NVD report is per-coordinate and archivable, step 2 sees
managed-but-unshipped coordinates and no OS package whatsoever, and step 5 is the only view of the base image's 131
RPMs — the layer this module's own stale-base finding lived in.

### Carrying the digest through the chart's fixed `repository:tag` rendering

The chart renders the container image as one expression and always joins the two values with a colon —
`image: "{{ .Values.cashAccount.image.repository }}:{{ .Values.cashAccount.image.tag }}"`
(`infra/stocktrader-operator/helm-charts/stocktrader/templates/cash-account.yaml:L65`) — and that template is **not
edited**: changing it is a [stop-and-flag](#stop-and-flag) condition. A digest reference is `<name>@sha256:<hex>`, so it
survives that rendering by being split at its **final colon**, with the `@sha256` algorithm prefix carried on the
repository value and the hex on the tag value:

| Value | Set to |
| --- | --- |
| `cashAccount.image.repository` | `<registry>/cash-account@sha256` |
| `cashAccount.image.tag` | the 64-character hex digest, with **no** `sha256:` prefix |

Rendered, the pair produces `<registry>/cash-account@sha256:<hex>` — a digest-pinned reference. Nothing constrains the
split: both fields are plain strings in `values.yaml` and in the operator CRD alike, where
`cashAccount.image.repository` and `.tag` are declared `type: string` with no pattern
(`infra/stocktrader-operator/config/crd/bases/operators.ibm.com_stocktraders.yaml`). Derive both from the captured
reference rather than retyping either:

```bash
REF=$(docker inspect --format '{{index .RepoDigests 0}}' <registry>/cash-account:<version>)
echo "cashAccount.image.repository: ${REF%:*}"   # <registry>/cash-account@sha256
echo "cashAccount.image.tag:        ${REF##*:}"  # the 64-character hex digest
```

**The obvious mapping is invalid and must not be attempted.** Putting the whole `sha256:<hex>` into `tag` renders
`<registry>/cash-account:sha256:<hex>`, which is not a reference at all: a container runtime answers `invalid reference
format` for that form and a kubelet rejects it the same way, so the pod never starts and the failure surfaces as an
image-pull error during the change window rather than as a values mistake. The two forms are one typo apart, which is
why the runbook verifies the **rendered** result and not the values it came from: at
[cutover gate (e)](docs/operational-runbook.md#step-3--controlled-cutover), `helm template` — or, once applied,
`kubectl get deployment <release>-cash-account -o jsonpath='{.spec.template.spec.containers[0].image}'` — must print
the `@sha256:` form before routing is confirmed.

### Memory-fit check (manual, and it has to be)

**Run it against a disposable database, never against a shared or production store.** Start-up applies
`schema/cash-account-schema.sql` (`spring.sql.init.mode=always`), so the store this check reaches gains the seven
tables, the `ledger_entry_reject()` function and the `ledger_entry_immutable` and `ledger_entry_immutable_truncate`
triggers. Turning the initializer off is
not the alternative — `spring.jpa.hibernate.ddl-auto=validate` then fails start-up, and readiness includes the `db`
indicator, so the check needs a real database that is genuinely expendable.

```bash
# A throwaway instance and a throwaway credential, both destroyed at the end. --env-file keeps the
# credential out of every argv: docker parses KEY=VALUE literally, with no shell parsing or expansion.
umask 077
{ printf 'POSTGRES_USER=memfit\nPOSTGRES_DB=memfit\nPOSTGRES_PASSWORD='; openssl rand -hex 24; } > memfit-db.env
{ printf 'JDBC_KIND=postgres\nJDBC_HOST=ca-memfit-db\nJDBC_PORT=5432\nJDBC_DB=memfit\nJDBC_ID=memfit\nAUTH_TYPE=basic\nJDBC_PASSWORD='
  sed -n 's/^POSTGRES_PASSWORD=//p' memfit-db.env; } > memfit-app.env

docker network create ca-memfit-net
docker run -d --name ca-memfit-db --network ca-memfit-net --env-file ./memfit-db.env \
  -v ca-memfit-data:/var/lib/postgresql/data postgres:12.22-alpine
docker run -d --name ca-memfit-app --network ca-memfit-net --memory=2g --cpus=1 \
  --env-file ./memfit-app.env -p 8080:8080 <registry>/cash-account:<version>

# Poll: `docker run -d` returns when the process started, not when the application answers, and this
# one applies the schema first. A single immediate curl measures nothing.
code=000
for i in $(seq 1 30); do
  code=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' \
              http://localhost:8080/actuator/health/readiness) || code=000
  [ "$code" = 200 ] && break
  sleep 2
done
printf 'readiness=%s after %ss\n' "$code" "$((i * 2))"
[ "$code" = 200 ] && docker logs ca-memfit-app 2>&1 | grep -iE 'MaxRAM|Started CashAccountApplication' \
                  || { echo 'MEMORY-FIT CHECK FAILED'; docker logs --tail 50 ca-memfit-app; }

# Teardown runs on success and on failure alike, and removes the data volume by name: postgres declares
# /var/lib/postgresql/data as a VOLUME, so removing the container alone leaves the cluster on the host.
docker rm -fv ca-memfit-app ca-memfit-db; docker network rm ca-memfit-net
docker volume rm ca-memfit-data
shred -u memfit-db.env memfit-app.env 2>/dev/null || rm -f memfit-db.env memfit-app.env

# Each of these must print nothing.
docker ps -a --filter name=ca-memfit --format '{{.Names}}'; docker volume ls -q --filter name=ca-memfit-data
```

The container must reach readiness inside the chart's envelope (limits cpu `1000m` / memory `2Gi`). The check is manual
because **no in-repo test asserts memory fit**: the base image's `run-java.sh` derives the heap from the container
memory limit (`JAVA_MAX_MEM_RATIO`, emitting `-XX:MaxRAMPercentage`), so the answer depends on the runtime limit rather
than on anything a unit or integration test can see. Record the result as a `docs/operational-runbook.md` Step 0
evidence item, alongside the image digest — that step carries the executed form of this check, its prohibition and its
gate, and `postgres:12.22-alpine` is used here because it is the estate's provisioned major version and the floor this
schema is written against.

## Local development notes

Four things surprise a developer running this module outside the chart, and each is recorded because the
surprise is by design and the remedy is never an edit to another module: broker's local-dev URL default,
what the fail-closed error model does to a broker request, why a stand-in rate endpoint has to speak TLS, and
how the schema reaches an empty database.

### Broker's local-dev default URL ends in `/account`, and stays that way

`backend/broker/src/main/liberty/config/jvm.options` line 3 defaults
`…broker.client.CashAccountClient/mp-rest/url` to `http://cash-account-service:8080/account`, while the chart publishes
`cashAccount.url` as `http://{{ .Release.Name }}-cash-account-service:8080/cash-account`. The chart's value wins in
every deployment, so this mismatch is invisible in a cluster; it only surfaces when someone runs broker outside the
chart. In that case, point `CASH_ACCOUNT_URL` at `…/cash-account` yourself.

**Broker is out of scope and is not modified** — not this line, not anything else. Recording the mismatch is the
correct action; editing broker is a [stop-and-flag](#stop-and-flag) condition.

### Why the fail-closed error model is safe to adopt

Broker wraps every cash-account call in `try`/`catch Throwable` and logs the failure, so an explicit HTTP error status
from this service never fails a broker request. That is precisely what makes fail-closed behaviour safe here: where
the legacy program answered an unknown request code with a success-looking return field and echoed the caller's own
amount back as a balance, this service answers `404`/`405`/`503` with an `ApiError`, and broker degrades exactly as it
already does when the cash service is unreachable.

### A local or CI FX stub must be served over TLS

Standing a rate endpoint up locally is the ordinary way to exercise cross-currency credit and debit without reaching
a third party, and it cannot be `http://localhost:<port>`: the rule under
[`CURRENCY_API_URL` is `https`-only, and refused at start-up](#currency_api_url-is-https-only-and-refused-at-start-up)
runs in a constructor and no property relaxes it, so a plaintext stub yields a process that exits during start-up.
Serve the stub over TLS with a self-signed certificate for the host the URL names, and hand the JVM a truststore
holding that certificate — a copy of the JDK's own, so the shared `cacerts` gains nothing:

```bash
umask 077
# The stub's own key pair and self-signed certificate. `-storepass:env` keeps even this throwaway password out of
# argv, for the reason stated under Run locally.
IFS= read -rsp 'stub keystore password: ' CA_FX_STUB_PW; echo; export CA_FX_STUB_PW
keytool -genkeypair -alias fx-stub -keyalg RSA -keysize 2048 -validity 30 \
        -dname CN=fx-stub.localhost -ext san=dns:fx-stub.localhost \
        -keystore fx-stub.p12 -storetype PKCS12 -storepass:env CA_FX_STUB_PW
keytool -exportcert -rfc -alias fx-stub -keystore fx-stub.p12 -storetype PKCS12 \
        -storepass:env CA_FX_STUB_PW > fx-stub-cert.pem

# A copy of the JDK truststore, PKCS12 because that is what the JDK 21 `cacerts` file already is. Neither the copy
# nor `changeit` is a secret — public certificates and the JDK's own default password — so both sit in the clear.
cp "$JAVA_HOME/lib/security/cacerts" ca-fx-truststore.p12
keytool -importcert -noprompt -alias ca-fx-stub -file fx-stub-cert.pem \
        -keystore ca-fx-truststore.p12 -storetype PKCS12 -storepass changeit

# The stub serves HTTPS on <port> with fx-stub.p12; only the truststore and the endpoint differ from Run locally.
CURRENCY_API_URL=https://fx-stub.localhost:<port>/latest \
java -Djavax.net.ssl.trustStore=ca-fx-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit \
     -jar target/cash-account-modernized-1.0.0-SNAPSHOT.jar

# Afterwards
unset CA_FX_STUB_PW
shred -u fx-stub.p12 ca-fx-truststore.p12 2>/dev/null || rm -f fx-stub.p12 ca-fx-truststore.p12
```

The subject alternative name has to equal the host in `CURRENCY_API_URL`, because the JDK HTTP client verifies the
hostname as well as the chain. Get it wrong and start-up **succeeds** while every cross-currency operation answers
`503 EXCHANGE_RATE_UNAVAILABLE` with the balance untouched — a running service with no rates, which is the harder of
the two symptoms to read.

**No test needs any of this.** `fx/CurrencyConversionTest` binds spring-test's `MockRestServiceServer` to the client
builder, so the suite never opens a socket to a rate provider, and it is also where every refusal above is asserted
(`endpointMustBeHttpsWithAHostAndNoCredentials`). The stub is for a hand-started process only.

### Schema application

`src/main/resources/schema/cash-account-schema.sql` holds the seven tables, their constraints and indexes, the
`ledger_entry_reject()` function and the `ledger_entry_immutable` and `ledger_entry_immutable_truncate` triggers. It is
applied at start-up by Spring Boot's
own SQL initialization (`spring.sql.init.mode=always`,
`spring.sql.init.schema-locations=classpath:schema/cash-account-schema.sql`) with
`spring.jpa.hibernate.ddl-auto=validate`, so any drift between the entities and the DDL stops start-up instead of being
silently auto-corrected against a ledger.

Two mechanics are worth knowing before editing that file:

- `spring.sql.init.separator` is `;;` and **every statement in the script ends with `;;`**. Spring's script runner
  splits on a single `;`, which would sever the function body and the trigger's `DO` block. The separator and the
  terminators are one contract — changing either alone breaks start-up.
- The script is written to be re-runnable: a session advisory lock serializes concurrent pod start-ups,
  `CREATE TABLE IF NOT EXISTS` and `CREATE OR REPLACE FUNCTION` are idempotent, and the trigger is created inside a
  `DO` block guarded on `pg_trigger` for this relation and schema, so a restart never drops the immutability guard.
  It uses no feature newer than PostgreSQL 12, the version the estate provisions.

Spring Boot was chosen for this rather than Flyway or Liquibase because neither appears in any `pom.xml` in this
checkout, `backend/portfolio` hand-applies its `createTables.ddl`, and the mechanism is part of the mandated framework.
The same file can be applied by hand — under the DDL-owning role of the hardened posture below — with the
connection's coordinates in the environment and the password reaching `psql` through its own prompt or a `0600` file:

```bash
# The non-secret coordinates are environment variables, so no part of the connection is a command-line argument.
export PGHOST='<host>' PGPORT=5432 PGDATABASE='<database>' PGUSER='<ddl-owning-role>'
export PGSSLMODE=verify-ca PGSSLROOTCERT='<ca-bundle.pem>'   # omit both only for a local throwaway database

# Interactive: psql asks for the password itself, so it reaches neither argv nor HISTFILE.
psql -v ON_ERROR_STOP=1 -f src/main/resources/schema/cash-account-schema.sql

# Non-interactive (a pipeline step): the password lives in a 0600 file that psql opens itself.
umask 077
IFS= read -rsp "password for $PGUSER: " CA_DDL_PW; echo
printf '%s:%s:%s:%s:%s\n' "$PGHOST" "$PGPORT" "$PGDATABASE" "$PGUSER" "$CA_DDL_PW" > ca-ddl.pgpass
unset CA_DDL_PW
PGPASSFILE=./ca-ddl.pgpass psql -w -v ON_ERROR_STOP=1 -f src/main/resources/schema/cash-account-schema.sql
shred -u ca-ddl.pgpass 2>/dev/null || rm -f ca-ddl.pgpass
```

**Never hand `psql` a connection URI — `psql "postgres://user:password@host/db"`, or the `$DATABASE_URL` such a URI is
conventionally kept in.** The shell expands it before `psql` runs, so the password becomes part of `psql`'s `argv`,
where `ps -o args=` shows it to every process on the host for the life of the command, and an interactive shell keeps
the same string in `HISTFILE` long afterwards. The variables above carry only the non-secret coordinates;
`PGPASSFILE` names a file `psql` opens by itself (`~/.pgpass` is its default location), and `psql` **ignores** that
file unless its permissions are `0600` or tighter — which is what `umask 077` guarantees. Escape any `:` or `\` in
the password with a backslash there, since `:` is the field separator. `PGPASSWORD` would also keep the secret out of
`argv`, but a process environment is readable through `ps -e` or `/proc` on some systems, so the file is the safer of
the two; `-w` keeps the non-interactive form from blocking on a prompt when that file is missing or rejected, so the
step fails loudly instead of hanging. `PGSSLMODE=verify-ca` with `PGSSLROOTCERT` is libpq's spelling of the TLS
posture this service's own JDBC URL carries, so the hand-applied path verifies the same server chain rather than a
weaker one.

`psql` tolerates the `;;` terminators (the second semicolon is an empty statement), so the one file serves both the
start-up initializer and the hand-applied path — and running it twice changes nothing.

**Hardened production posture.** The chart supplies a single database identity, used for DDL and DML alike, exactly as
portfolio's hand-applied DDL is. Under that identity the immutability trigger protects the ledger against every
application code path but not against the identity itself, which **owns** `ledger_entry` because it applied this
script — and in PostgreSQL `DROP`, `ALTER` and therefore `ALTER TABLE … DISABLE TRIGGER` come with ownership rather
than with a grantable privilege. Measured against `postgres:12.22-alpine` as that identity:
`DROP TRIGGER ledger_entry_immutable ON ledger_entry` **succeeds**, and a following
`UPDATE ledger_entry SET amount = 0` rewrites the audit row. The next start-up re-creates both guards, because this
script is idempotent — so the exposure is a window rather than a permanent loss — but a leaked `JDBC_PASSWORD`, or any
application-level flaw that reaches the datasource, reaches it. A second, statement-level
`ledger_entry_immutable_truncate` trigger (`BEFORE TRUNCATE`) closes the one statement a row-level trigger never sees,
so `TRUNCATE ledger_entry` is refused to the application, but a `TRUNCATE` by the owner after dropping that trigger is
not.

The hardened arrangement separates the two identities. Nothing in this module changes; the script is the same file,
applied by the first of them:

1. Apply the script once with `psql` under a **DDL-owning role**, per the command above.
2. Create the **runtime role**, which owns nothing. Substitute the release's own names and take the password from the
   secret manager, never from a literal:

   ```sql
   CREATE ROLE <runtime-role> LOGIN PASSWORD '<from the secret manager>';
   GRANT CONNECT ON DATABASE <database> TO <runtime-role>;
   GRANT USAGE ON SCHEMA <schema> TO <runtime-role>;
   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA <schema> TO <runtime-role>;
   ALTER DEFAULT PRIVILEGES IN SCHEMA <schema>
     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO <runtime-role>;
   ```

   No `REVOKE` is needed and none is listed: the role is granted no `TRUNCATE` and no `TRIGGER`, and it owns nothing,
   which is the whole of it. `ledger_entry.entry_id` is `GENERATED ALWAYS AS IDENTITY`, so its sequence is internally
   owned by the table and needs no separate `USAGE` grant.
3. Run the service as that role with **`SPRING_SQL_INIT_MODE=never`** — one environment variable, no chart template
   change and no code change, because `spring.sql.init.mode` is bound to it in `application.yml`. `database.id` and
   `database.password` then carry the runtime role rather than the owner.

What the split buys, measured as the runtime role on the same server — each refusal arriving **before** the trigger is
consulted, with every ledger row intact:

| Statement | Refused with |
| --- | --- |
| `DROP TRIGGER ledger_entry_immutable ON ledger_entry` | `42501 must be owner of relation ledger_entry` |
| `ALTER TABLE ledger_entry DISABLE TRIGGER ledger_entry_immutable` | `42501 must be owner of table ledger_entry` |
| `DROP FUNCTION ledger_entry_reject() CASCADE` | `42501 must be owner of function ledger_entry_reject` |
| `TRUNCATE ledger_entry` | `42501 permission denied for table ledger_entry` |
| `UPDATE ledger_entry SET amount = 0` | `P0001 ledger_entry is append-only: UPDATE is rejected` (this script's own guard) |
| `DELETE FROM ledger_entry` | `P0001 ledger_entry is append-only: DELETE is rejected` |

`audit/LedgerImmutabilityIT` provisions exactly that role against the test container and asserts exactly those
refusals, so the posture is a tested property of this module rather than a recommendation. The service is fully
functional under it: started as the runtime role with `SPRING_SQL_INIT_MODE=never`, readiness answers `200`, a retail
create and credit, an institutional hold and the ledger query all succeed — the role needs nothing beyond the four
grants above. Against a store where the schema has **not** been applied, that same setting fails start-up at
`spring.jpa.hibernate.ddl-auto=validate`, which is the intended order of operations rather than a fallback.

Supplying the second identity to the pod, however, needs a **second secret key in the chart**, which is a template
change this module may not make (AAP 0.3.4 → [stop and flag](#stop-and-flag); AAP 0.11.2 records it as an
[open item](#open-items)). So the posture a release runs is a **deployment decision that has to be recorded, not
defaulted**: the default here is `always`, because it is the only default a chart deployment can start with, and it is
the weaker of the two. `docs/operational-runbook.md` Step 0 is where the decision is taken and signed — either the
runtime role exists and `SPRING_SQL_INIT_MODE=never` is set, or the single-identity residual above is accepted in
writing by the data owner, with the re-creation-on-restart behaviour as the compensating control.

**Accepted limitation:** there is no versioned migration history. A future schema change needs either a versioned
migration tool or a hand-written `ALTER` step appended to this file, applied in the same idempotent style.

## Documents

| Document | What it is |
| --- | --- |
| [`docs/legacy-characterization.md`](docs/legacy-characterization.md) | The behaviour of the CICS/COBOL program, read from its source with an inline `file:line` citation behind every claim: what `amount` resolves to in the credit/debit computation, owner casing, the missing dispatch catch-all, the write-only audit file, the acceptance baseline, and the disposition of every legacy behaviour (preserved, deliberately changed, or not replicated). It ends with an **Acceptance** section carrying `Status: DRAFT \| ACCEPTED`, the named reviewer and the date. The tooling copies that status into every `migration_run`, and a `DRAFT` characterization can exercise fixtures but can never be signed off against a real export |
| [`docs/operational-runbook.md`](docs/operational-runbook.md) | Step 0 prerequisites and Steps 1–4 — bulk migration rehearsal and reconciliation, shadow-mode dual-run, controlled cutover, decommission — each with preconditions, actions, evidence, sign-offs and a rollback criterion. **Every step is documented here and executed by the platform operator and the mainframe team, never by this deliverable** |

## Open items

Two registers, kept apart because they need different answers. The first is what this deliverable could not settle
from the repository: each item is raised rather than guessed, and `docs/legacy-characterization.md` carries the detail
and the citation for those that came from the legacy source. The second —
[Deviations from the frozen AAP inventory](#deviations-from-the-frozen-aap-inventory) — is what this deliverable
settled *differently from the plan it was built against*; each of those needs an authorization rather than an answer.

| Open item | What settles it |
| --- | --- |
| **Data-retention requirement for decommission.** This repository contains no retention policy, audit-control matrix or compliance artifact | An explicit **written** answer from the requesting organization before runbook Step 4 runs. **Defaulting a retention period is prohibited** |
| VSAM record length: the program writes 57 bytes into a cluster defined `RECSZ(100 100)` | The CICS FILE/FCT definition (`RECORDFORMAT`, `RECORDSIZE`), or a real `REPRO`/`PRINT` sample. Meanwhile `tool.history-record-length` accepts either length and the decoder tolerates both |
| The CICS region's code page and time zone (assumed `IBM037` and `UTC`) | Confirmation from the mainframe team. Both are `tool.*` properties, not constants, precisely so the answer is configuration rather than a code change |
| Whether a deadlock or timeout reached the legacy caller as `-911` (unit of work already rolled back) or as `-913` (left open) | The `DROLLBACK` attribute of the region's `DB2CONN`/`DB2ENTRY` definition, which no file in the legacy module declares. Nothing here depends on the answer: this service runs one transaction per request and maps both codes' conditions to `503 DATASTORE_UNAVAILABLE` |
| The rate-table column spelled `cyrrnbase` in the shipped DDL and `CURRNBASE` in the copybook and the program's `SELECT` | The column name in the real DB2 catalog. The export reader accepts either header meanwhile |
| **Spring Boot 3.3.x is past open-source end of life, and the exposure that follows is measured rather than presumed — which makes this a production gate, not a note.** 3.3.13 (19 June 2025) closed open-source support for the line, every 3.x line has since followed, and 3.3 is the line the plan mandates. The nine per-artifact overrides [below](#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them) close every advisory raised against the Spring, Spring Data, Jackson, Logback, Micrometer, Tomcat, pgJDBC and Nimbus artifacts this service ships — the one CRITICAL among them. What they cannot reach is the `spring-boot` artifact itself, whose version **is** the parent's: CVE-2026-22733 (authentication bypass under the actuator CloudFoundry endpoints, CVSS 8.1, fixed 3.5.12/4.0.4), CVE-2026-40973 (3.5.14/4.0.6) and, in `spring-boot-autoconfigure`, CVE-2026-41001 (3.5.15/4.0.7). None is reachable in this service's configuration and each has a stated control [below](#what-the-nine-cannot-reach-and-the-controls-that-stand-in-their-place), but a control is not a patch, and on a frozen line no patch exists | The requesting organization decides **before production**, and the decision is one of three: stay on 3.3.13 with those controls and the residual risk accepted in writing, buy commercial support for the line, or authorize a later minor line. Runbook [Step 0](docs/operational-runbook.md#step-0--prerequisites) does not pass without that answer. A later-line move is not a version bump alone: it has to revisit the two call sites these overrides already put on deprecated-for-removal APIs, named in the [operational consequence](#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them) below |
| The estate's Azure module provisions PostgreSQL **12**, which is past community end of life; the schema is written to that floor and tested on `postgres:12.22-alpine` | The platform owners decide whether to raise the server version. Nothing in this module requires it |
| `database.kind` is chart-global, and this service requires `postgres` | Confirmation of the target estate's relational store. If DB2 must remain for portfolio, a separate datasource path would need a chart template change → [stop and flag](#stop-and-flag) |
| Ledger immutability against a privileged operator needs a DML-only runtime role beside a DDL-owning role, but the chart supplies **one** database identity — which owns `ledger_entry` and can therefore drop the guard and rewrite an audit row, re-created only at the next start-up | The chart's owners decide whether to add a second secret key (a template change). The module side is already done and tested: the role's grants are in [Hardened production posture](#schema-application), `SPRING_SQL_INIT_MODE=never` selects it with no chart change, and `audit/LedgerImmutabilityIT` proves the refusals. Runbook Step 0 records which posture the release runs, or the data owner's written acceptance of the single-identity residual |
| `vault.enabled` must remain `false`, because the enabled branch injects Liberty-specific container arguments | A Vault-compatible argument shape for a non-Liberty workload, decided by the chart's owners |
| The rounding the (absent) z/OS Connect mapping applied to a caller amount with more than two decimals | The z/OS Connect API/SAR definition. This service scales `DOWN` to 2, consistent with COBOL's default truncation, and every parity fixture uses two-decimal amounts so no expected value depends on the choice |
| The `NUMERIC(9,2)` ceiling of `9,999,999.99`, inherited to preserve legacy precision exactly | The requesting organization confirms the ceiling or authorizes widening (one DDL change plus the `Money` bounds; parity within range is unaffected) |

### Deviations from the frozen AAP inventory

The plan this module was built against freezes four inventories: the dependency versions (AAP 0.9.1), the error-code
vocabulary (AAP 0.6.2), the verbatim wrapper copy (AAP 0.2.3, 0.8.1) and the test-volume ceiling (AAP 0.7.6). Security
and regression work exceeded each of them, and the first five rows below are those four numbers (the dependency inventory takes two rows: the library overrides and
the container base-image pin). **Every change they
name is in the tree and none of it is to be reverted** — each is either a fix for a named vulnerability or the
coverage that guards a fixed defect — but each needs the requesting organization's authorization, and this register
is where that outstanding decision is visible rather than buried at a code site. The last two rows are the other two
shapes the same question takes: work the AAP puts outside this module that only the platform owner can do, and a file
beyond the plan's frozen **file** inventory (AAP 0.2.1) that this repository's own boundary-hygiene gate requires.

| Open item | What settles it |
| --- | --- |
| **Nine dependency versions override AAP 0.9.1's frozen inventory** — `org.postgresql:postgresql` `42.7.13` (the AAP pins `42.7.7`, matching `backend/portfolio`), and eight coordinate sets the Spring Boot 3.3.13 BOM would otherwise decide: `tomcat.version` `10.1.60` (BOM `10.1.42`), `micrometer.version` `1.15.12` (`1.13.15`), `spring-security.version` `6.5.11` (`6.3.10`), `spring-framework.version` `6.2.19` (`6.1.21`), `spring-data-bom.version` `2025.0.13` (`2024.0.13`), `jackson-bom.version` `2.18.11` (`2.17.3`), `logback.version` `1.5.38` (`1.5.18`) and `nimbus-jose-jwt.version` `9.37.4` (transitive `9.37.3`, a coordinate the BOM manages no version for at all, so it is pinned in `pom.xml`'s `<dependencyManagement>`). Each closes a named advisory the mandated 3.3 line ships no newer parent to inherit, declared per coordinate in `pom.xml` and detailed [below](#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them). Measured on the packaged jar with the same instrument that found them — a Trivy image scan of the built image — the set takes the Java layer from **40 advisory rows (1 CRITICAL, 9 HIGH, 22 MEDIUM, 8 LOW) to 3**, and the 3 that remain are named [below](#what-the-nine-cannot-reach-and-the-controls-that-stand-in-their-place) | The requesting organization authorizes the nine coordinates, recording what the set costs as well as what it fixes: it lifts the Spring, Spring Data, Jackson, Logback and Micrometer lines above Boot 3.3.13's tested dependency matrix, a combination **no Spring compatibility statement covers**. The evidence offered in its place is this module's own gate — `./mvnw -B clean verify`, 141 tests, green on exactly this set — plus the runtime checks and the narrow, named surfaces in the [operational consequence](#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them) below. **Reverting to the AAP-pinned versions is not an option on the table** — those versions are the vulnerable ones; the alternative that is on the table is the framework-line decision in the [open items](#open-items) above, which removes the need for the overrides rather than the need for the fix. Decision row **D1** of the [authorization record](#authorization-record) |
| **The container base image is pinned ahead of AAP 0.9.1's frozen tag** — `Dockerfile` names `registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24@sha256:908540a9…` (the head of the `1.24` stream, by tag and multi-arch manifest-list digest) where the AAP and `backend/account/src/main/docker/Dockerfile.jvm:L94` name `1.21`. Measured on the assembled image with the promotion gate's own scanner, the move takes the OS and JDK layer from 75 HIGH findings (64 with a fix available, ten of them against `java-21-openjdk-headless` itself) to 11 with **no** fix available; the image contract — Java 21 runtime, non-root `185`, `run-java.sh`, ports 8080/8443 — is inherited unchanged. Detailed [above](#the-base-image-is-pinned-ahead-of-the-siblings-tag-deliberately) | The requesting organization authorizes the tag and digest as a deviation from the frozen dependency inventory, on the same terms as the library overrides in row D1. **Reverting to `1.21` reinstates the 64 fixable findings** and fails the blocking promotion gate in [Image build, scan and push](#image-build-scan-and-push); the pin is re-derived and dated at each promotion under runbook Step 0. Decision row **D6** of the [authorization record](#authorization-record) |
| **Four error codes beyond AAP 0.6.2's closed 22-code vocabulary, and one code changed within it** — `REQUEST_TOO_LARGE` → `413`, raised by `config/RequestBodySizeLimitFilter` and carried out of a mid-read stream by `error/RequestBodyTooLargeException`; the media-type pair `UNSUPPORTED_MEDIA_TYPE` → `415` and `NOT_ACCEPTABLE` → `406`, rendered by `error/ApiExceptionHandler` for Spring's `HttpMediaTypeNotSupportedException` and `HttpMediaTypeNotAcceptableException`; and `INVALID_REQUEST_FIELD` → `400` for a request member the AAP names no code for. Purely additive: every AAP-declared code is present with the status the AAP declares. Each exists because the condition cannot be reported without a code of its own, and the alternatives break the invariant the enum is for — a `413`, `415` or `406` carrying a `400`'s code makes the code-to-status binding untrue on the wire, and `400 INVALID_AMOUNT` tells a caller its amount was wrong when its body was never parsed at all. The media-type pair closes a runtime defect rather than a hypothetical: unhandled, a wrong `Content-Type` or an unsatisfiable `Accept` was answered `500 INTERNAL` with an `ERROR` record, reporting a caller's mistake as a server fault and contradicting the fail-closed model of AAP 0.4.3. `INVALID_REQUEST_FIELD` closes the same class of untruth on the institutional surface, where a blank, absent or over-length `orderReference` and an unparsable `expiresAt` were all answered `INVALID_AMOUNT` about an amount the caller had sent correctly. The changed code is the **hold** above the `NUMERIC(9,2)` ceiling, now `422 AMOUNT_OUT_OF_RANGE` rather than `422 INSUFFICIENT_FUNDS`: the AAP's per-endpoint column for `…/holds` lists only `INSUFFICIENT_FUNDS` at `422`, but `AMOUNT_OUT_OF_RANGE` is the AAP's own code for that input class at that same status, and the retail credit and debit answer the identical input with it, so the two surfaces no longer report one condition two ways | The requesting organization authorizes the four codes and the one changed code. For `REQUEST_TOO_LARGE` the only alternative is a decision to accept an unbounded request body, because removing the code removes the `413` path, which **is** the remediation ([Request intake bounds](#request-intake-bounds)). For the media-type pair the alternative is a `400` carrying one of the existing codes — available, and rejected here only because the status would then be untrue; `error/FailClosedIT` would need its two media-type assertions changed with it. For `INVALID_REQUEST_FIELD` and the hold's `AMOUNT_OUT_OF_RANGE` the alternative is the literal per-endpoint list of AAP 0.6.2, which means reinstating a code that names the wrong field and a second code for one condition; no status class changes either way, so the decision is about the vocabulary rather than the contract's shape. Decision row **D2** of the [authorization record](#authorization-record) |
| **The Maven wrapper properties are not the verbatim copy AAP 0.2.3 and 0.8.1 call for** — `.mvn/wrapper/maven-wrapper.properties` adds exactly two lines to `backend/portfolio-assistant`'s file (lines 20–21: a provenance comment and `distributionSha256Sum=0d7125e8…eeadb`). Lines 1–19 are byte-identical, `mvnw` and `mvnw.cmd` are byte-identical, `wrapperVersion`, `distributionType` and `distributionUrl` are unchanged, and the build resolves the same Apache Maven 3.9.11; `pom.xml`'s verified-build-inputs comment records how the value was derived | The requesting organization authorizes the two lines. **The checksum stays**: without it the wrapper downloads and executes an unverified distribution, which is the defect CWE-494 names. If verbatim copying must hold to the byte, the owners supply an equivalent control outside the file — a repository- or runner-level integrity policy that pins the same distribution. Decision row **D3** of the [authorization record](#authorization-record) |
| **The suite executes 148 tests against AAP 0.7.6's "approximately 72"** — Surefire 56 plus Failsafe 92, 0 skipped. Every addition traces to a named regression a prior checkpoint's fix left behind, and the ceiling's qualitative prohibitions are honoured: zero `@ParameterizedTest`, `@RepeatedTest` and `@TestFactory`, no exploratory or redundant variants, Mockito excluded from the build. Per-family accounting, and the command that re-derives the number from the build's own XML rather than from this table, are [below](#test-volume-148-executed-against-a-ceiling-of-72) | The requesting organization authorizes the overshoot as regression coverage. **No test is to be deleted to reach the number**: each one guards a defect a prior checkpoint fixed, so deleting it restores the defect's cover, not the plan. The executed count is folded into the declared scenarios below — 72 declared plus 72 named regressions — and the decision is row **D4** of the [authorization record](#authorization-record) |
| **A request-body cap at the edge is outstanding, and only the platform owner can set it** — the in-process controls bound what this pod reads (8KB parsed, 64KB drained), never what the network delivers to the connector. Setting it in the ingress, service mesh or API gateway is a deployment change this module may not make: AAP 0.3.4 makes a chart template change a [stop-and-flag](#stop-and-flag) condition and AAP 0.2.4 puts values, CRDs and GitOps resources out of scope | The platform owner sets a request-body limit at or below this module's 8KB for the `/cash-account` path space — for example nginx-ingress `client_max_body_size`, or an Envoy buffer limit — and captures as evidence an over-limit `POST` refused at the edge before it reaches a pod |
| **The module ships a one-line `.gitignore` (`target/`), a file AAP 0.2.1's inventory does not carry** — without it every build leaves `?? backend/cash-account-modernized/target/` in the parent tree, so the boundary gate this repository is checked with cannot tell the deliverable's files from some 60 MB of build output, and a blanket `git add -A` commits the artifacts; described under [Module placement](#target-is-ignored-so-the-parents-boundary-gate-stays-clean) | The requesting organization authorizes the file, on the same terms as the rows above because it is a file beyond the frozen inventory. Rejecting it means accepting that untracked line permanently or suppressing it per checkout (`echo 'backend/cash-account-modernized/target/' >> .git/info/exclude`), which no clone inherits from the repository and which therefore has to be repeated by every reviewer. Decision row **D5** of the [authorization record](#authorization-record) |

#### The nine dependency overrides, and why no BOM version remediates them

| Coordinate | AAP 0.9.1 / BOM | Shipped | Advisory and why the shipped version is the floor |
| --- | --- | --- | --- |
| `org.postgresql:postgresql` | `42.7.7` | `42.7.13` | CVE-2026-42198: in 42.2.0 up to but excluding 42.7.11 the **server** dictates the SCRAM-SHA-256 PBKDF2 iteration count, so an impersonated or compromised database can force unbounded client-side CPU work at login, which `loginTimeout` does not bound. 42.7.11 adds the `scramMaxIterations` cap and is the fixed floor; 42.7.13 is the current 42.7.x and additionally fails closed on a SCRAM channel-binding downgrade |
| `org.apache.tomcat.embed:*` (via `tomcat.version`) | `10.1.42` | `10.1.60` | CVE-2025-61795: 10.1.0-M1 through 10.1.46 delay cleanup of multipart upload temporary files on an error path, so repeated failed uploads exhaust disk; fixed in 10.1.47, and 10.1.60 is the latest 10.1.x. The property is the parent's own, so the whole embed set moves together rather than one artifact being lifted out of its release. `spring.servlet.multipart.enabled=false` removes the affected code path **beside** this bump, not instead of it |
| `io.micrometer:*` (via `micrometer.version`) | `1.13.15` | `1.15.12` | CVE-2026-40984: crafted HTTP requests against Micrometer's HTTP-server instrumentations cause a denial of service in 1.13.0–1.13.18 (the BOM-managed 1.13.15 is inside that range), 1.14.0–1.14.15, 1.15.0–1.15.11, 1.16.0–1.16.5 and ≤ 1.9.17. The only fixed versions are **1.15.12** and 1.16.6, so no 1.13.x or 1.14.x remediates anything and staying on the BOM line is not available; 1.15.12 is the lowest fixed version. The advisory's other remedy — disabling HTTP server instrumentation — is deliberately not taken, because it would delete `http_server_requests_seconds_count`, the metric runbook Step 3's rollback criterion is written on |
| `org.springframework.security:*` (via `spring-security.version`) | `6.3.10` | `6.5.11` | **CVE-2026-22732 (CRITICAL)** against `spring-security-web` — the one CRITICAL advisory on anything this module ships — fixed in 6.5.9 and 7.0.4, so no 6.3.x or 6.4.x release remediates it. 6.5.11 is the lowest version that also closes CVE-2026-22746 (`-core`) and CVE-2026-22748 (`-oauth2-jose`), both 6.5.10, and CVE-2026-41706 and CVE-2026-47838 (`-web`), both 6.5.11. `SecurityConfig`'s eager header-writing post-processor stays beside the bump rather than being retired by it: the bump removes the vulnerable code, the post-processor keeps the six-header contract true on every rejection path, and neither depends on the other. 6.5.x requires Spring Framework 6.2.x — the row below is not an independent choice |
| `org.springframework:*` (via `spring-framework.version`) | `6.1.21` | `6.2.19` | CVE-2025-41249 (`spring-core`, fixed 6.2.11), CVE-2026-41842 and CVE-2026-41845 (`spring-webmvc`) and CVE-2026-41850 (`spring-expression`), plus thirteen further MEDIUM/LOW advisories across those three artifacts whose highest fixed version is also 6.2.19. Every fix lies on 6.2.x, so the entire 6.1 line remediates nothing; 6.2.19 is the lowest version that closes all of them. The parent's own property drives the `spring-framework-bom` import, so the whole `org.springframework:*` set moves as one release — the only safe way to move a framework whose modules share internal APIs. 6.2.19 is also the exact `spring-core` version `spring-security-web` 6.5.11 declares, so this row and the one above it are one consistent pairing rather than two versions chosen separately |
| `org.springframework.data:*` (via `spring-data-bom.version`) | `2024.0.13` | `2025.0.13` | CVE-2026-41716 (HIGH) with CVE-2026-41711 and CVE-2026-41721 against `spring-data-commons`, all fixed in 3.5.12, which the 2025.0.x train carries and the BOM's 2024.0.13 train (commons 3.3.13) cannot. 2025.0.13 is the current 2025.0.x. Spring Data JPA moves with it to 3.5.13 while Hibernate stays at the BOM's 6.5.3.Final: no Hibernate advisory is outstanding, so nothing justifies moving the ORM as well |
| `com.fasterxml.jackson.core:*` (via `jackson-bom.version`) | `2.17.3` | `2.18.11` | GHSA-r7wm-3cxj-wff9 (`jackson-core`) and CVE-2026-54512, CVE-2026-54513, CVE-2026-54514 and CVE-2026-59888 (`jackson-databind`) are fixed in 2.18.8; CVE-2026-54515 in 2.18.9 and CVE-2026-18401 (`jackson-core`) in 2.18.6. Nothing on 2.17.x remediates any of them. 2.18.11 is the current 2.18.x and the lowest line that closes the set; 2.21.x/2.22.x carry the fixes too and are deliberately not taken, 2.18.x being the line Spring Boot's own next minor is built against and therefore the smallest step off the mandated matrix. The money wire contract rides on this bump — `JacksonConfig`'s `USE_BIG_DECIMAL_FOR_FLOATS` and `WRITE_BIGDECIMAL_AS_PLAIN` must keep rendering `balance` as a plain decimal, which `RetailContractIT` asserts on the raw response text |
| `ch.qos.logback:*` (via `logback.version`) | `1.5.18` | `1.5.38` | CVE-2025-11226 (arbitrary code execution, fixed 1.5.19) with CVE-2026-1225 (1.5.25), CVE-2026-9828 (1.5.33), CVE-2026-10532 (1.5.34) and CVE-2026-13006 (1.5.37) against `logback-core`; 1.5.38 is the current 1.5.x and the lowest version closing all five. The 1.6.x line is a new minor this module has no reason to adopt. CVE-2025-11226 additionally needs Janino on the classpath, reached through a conditional logback configuration: **no `janino` or `commons-compiler` artifact is on any classpath here, and none may be added** — the bump removes the vulnerable code and that absence keeps the precondition unmet. slf4j stays at the BOM's 2.0.17, which 1.5.38 requires |
| `com.nimbusds:nimbus-jose-jwt` (`<dependencyManagement>`) | unmanaged, transitive `9.37.3` | `9.37.4` | CVE-2025-53864: up to 9.37.3 a JWT claim set is parsed with no nesting bound, so deep nesting can exhaust the stack; 9.37.4 is the drop-in patch. The BOM manages no version for this coordinate — it arrives through `spring-security-oauth2-jose`, which is how 9.37.3 shipped unnoticed — so a managed entry is the only way to fix its floor. 9.37.4 is also what `spring-security` 6.5.11 resolves, so the pin changes nothing today and exists so that no future change to the security line can take the recursion fix away silently. Tomcat's 8KB request-header ceiling, which bounds a bearer token's reachable nesting depth, stays as defence in depth |

Operational consequence, and the thing to re-check on any future version move: this set lifts Spring Framework,
Spring Security, Spring Data, Jackson, Logback and Micrometer above Spring Boot 3.3.13's tested dependency matrix.
No Spring compatibility statement covers the combination, so the evidence is this module's own and it is narrow by
nature — `./mvnw -B clean verify` green on exactly these versions (148 tests, 0 failures), `ActuatorProbesIT`
asserting 200 on `/metrics` and `/actuator/prometheus` with the `http_server_requests_seconds_count` series that
runbook Step 3's rollback criterion reads, `RetailContractIT` asserting the plain-decimal `balance` on the raw
wire, `RoleEnforcementIT` asserting both role modes and the security headers on success and on both filter-chain
rejections, and the repository surfaces the integration tests exercise. **Re-assert that gate whole after any
change to the Boot parent or to one of these nine**, because it is standing in for the compatibility statement
nobody has issued.

Two call sites are the specific cost of having moved: `config/SecurityConfig.java`'s
`addObjectPostProcessor(...)`, which carries the eager header-writing workaround, and
`error/ApiExceptionHandler.java`'s `MethodValidationResult.getAllValidationResults()` now compile against APIs
**deprecated and marked for removal** in Spring Security 6.5 and Spring Framework 6.2 respectively — two build
warnings today, two required rewrites whenever the framework-line decision moves this module to a 7.x-era line.
Neither is a defect and neither changes behaviour; both are named here so the line move is costed before it is
authorized rather than discovered during it.

#### What the nine cannot reach, and the controls that stand in their place

The `spring-boot` and `spring-boot-autoconfigure` artifacts carry the **parent's** version, so no property in
`pom.xml` can move them and the overrides above stop at their edge. Three advisories therefore ship — two of them
the only Spring rows a post-override scan still reports — and each is recorded here with the control that makes it
unreachable rather than with an assurance that it is harmless:

| Advisory | Fixed in | Why it is not reachable here | What would actually fix it |
| --- | --- | --- | --- |
| CVE-2026-22733 — authentication bypass under the actuator CloudFoundry endpoints, CVSS 8.1, `spring-boot` 3.3.0–3.3.17 | 3.5.12 / 4.0.4 | Structural, and verified by request rather than by reading: `SecurityConfig`'s matcher list ends in `anyRequest().denyAll()`, which covers `/cloudfoundryapplication` and everything under it — those paths answer **401** with no credential and **403 `FORBIDDEN`** with a valid `StockTrader` token. The CloudFoundry actuator auto-configuration activates only in the presence of `VCAP_APPLICATION`, which this deployment does not set, and the service declares no endpoint in that path space | The framework-line decision in the [open items](#open-items). Nothing in this module |
| CVE-2026-40973 — `spring-boot` 3.3.13 | 3.5.14 / 4.0.6 | Same path-space catch-all and the same actuator exposure limit: only `health`, `startup` and `prometheus` are exposed (plus the `/metrics` shim), and all 13 sensitive actuator endpoints answer 404 | As above |
| CVE-2026-41001 — `spring-boot-autoconfigure` 3.3.13 | 3.5.15 / 4.0.7 | As above | As above |

A control is not a patch. These three are exactly why the framework-line question is a production gate in the
[open items](#open-items) and a Step 0 precondition in the runbook, and not an entry on a backlog.

One further advisory is in neither category, and is recorded here rather than left for the next scan to
rediscover: `org.apache.logging.log4j:log4j-api` `2.23.1` carries CVE-2026-49844 (MEDIUM — `MapMessage.asJson`
emits invalid JSON for a non-finite float), fixed in 2.25.5, and `log4j2.version` would reach it in one line. It
is **not** overridden here because it lies outside the advisory set this module's security review raised, and
because nothing in the service can exercise the affected path: the artifact is present only as the
`log4j-to-slf4j` bridge that `spring-boot-starter-logging` ships, no `log4j-core` backend is on any classpath
(verified in the packaged jar's 82 libraries), and this module logs through SLF4J and Logback with no
`MapMessage` anywhere. Whether to take the tenth override is a one-property decision for the module's owners,
stated here so it is a decision rather than an omission.

#### Test volume: 148 executed against a ceiling of 72

Counts are from `./mvnw -B clean verify` (Surefire `*Test`, Failsafe `*IT`), 0 skipped. They are re-derivable from
the build's own reports rather than from this table, which is what makes the number reviewable instead of asserted:

```bash
cd backend/cash-account-modernized && ./mvnw -B clean verify
# Prints 148 and the per-class tally the table below is built from (56 Surefire + 92 Failsafe).
python3 - <<'PY'
import collections, glob, xml.etree.ElementTree as ET
per = collections.Counter()
for report in sorted(glob.glob('target/*-reports/TEST-*.xml')):
    for case in ET.parse(report).getroot().iter('testcase'):
        # Grouped by the testcase's own classname, never by the report's file name: Surefire files a @Nested
        # class's methods under the nested name and writes a tests="0" report for the outer class.
        per[case.get('classname').rsplit('.', 1)[-1]] += 1
print('executed test methods:', sum(per.values()))
for name, count in sorted(per.items()):
    print(f'  {count:>3}  {name}')
PY
```

The executed number is the AAP's own scenario count plus the regressions the fixes left behind, and the third column
is that difference: **72 declared + 76 fix-mandated regressions = 148**. Every family's addition is named in the
paragraph below, so the overshoot is accounted for scenario by scenario rather than asserted as a total.

| Family | AAP 0.7.6 declared | Added by fixes | Executed | Classes |
| --- | --- | --- | --- | --- |
| State-transition unit | 8 | 1 | 9 | `ReservationStateMachineTest` |
| Money / arithmetic unit | 11 | 3 | 14 | `MoneyTest` 7, `LegacyBalanceCalculatorTest` 3, `CharacterizationDocPresentTest` 4 |
| Currency conversion | 6 | 10 | 16 | `CurrencyConversionTest` 10, `ExchangeRateSourceWiringTest` 6 |
| Owner normalization and datasource | 5 | 7 | 12 | `OwnerNormalizerTest` 7, `DataSourceGuardConfigTest` 5 |
| Export decoding | 3 | 0 | 3 | `VsamHistoryRecordDecoderTest` |
| Contract through the caller's client | 10 | 8 | 18 | `RetailContractIT` 17, `CashAccountClientDriftTest` 1 |
| Institutional and audit immediacy | 13 | 11 | 24 | `ReservationLifecycleIT` 22, `AuditImmediacyIT` 2 |
| Audit immutability | 2 | 1 | 3 | `LedgerImmutabilityIT` |
| Security | 5 | 10 | 15 | `RoleEnforcementIT` |
| Fail-closed | 2 | 10 | 12 | `FailClosedIT` |
| Deployment shape | 1 | 1 | 2 | `ActuatorProbesIT` |
| Reconciliation and dual-run | 6 | 14 | 20 | `LoaderIT` 10, `ReconciliationIT` 6, `ShadowComparatorIT` 3, `RollbackReplayFileTest` 1 |
| **Total** | **72** | **76** | **148** | 21 classes |

Where the extra 76 came from: the families that grew most are the ones a security or correctness fix reached.
Currency conversion gained the outbound-request assertions — no `Authorization` header forwarded, an https-only
endpoint with no credentials, a bounded response, and a real stalling socket proving the `cashaccount.fx.timeout`
budget bounds body reception — and the wiring proofs that the staged legacy rate source can never reach the request
path and that the tool profile starts, and prices from the staged table, without ever constructing the live client.
The datasource guard gained the driver-bound case (`connectTimeout`/`socketTimeout` appended, sub-second values
refused), owner normalization the reserved `institutional` segment, the characterization test the packaged copy of
the document, and audit immutability the `TRUNCATE` refusal beside `UPDATE` and `DELETE`. Owner
normalization grew again for the identifier rule: the refused character classes, and the assertion that the
`CHECK` constraint in `schema/cash-account-schema.sql` still carries the normalizer's own expression — with the
seam's own refusal of a markup owner asserted in `RetailContractIT`. Security grew into a two-mode matrix (the deployed parity grant, and the strict `groups`-only
mode as a second context) with `HEAD`-equals-`GET` authorization, the security-header assertions on success and on
both filter-chain rejections, the https-only JWKS case, a token carrying no `exp` claim refused `401` on the retail and
institutional surfaces, and the near-miss `groups` spellings (`"StockTrader "`, a tab, a newline, a NUL) refused `403`
in strict mode where only the exact name is admitted. Institutional grew the idempotency-hash cases — expiry
omitted, `10.0` versus `10.00`, a key retained from a deleted account's life — and the three two-thread races.
Reconciliation and dual-run grew a live-rate-source mode beside the default `legacy-table` one, plus the
held-funds and ledger-source classifications. Fail-closed grew an `OPTIONS` case, the two framings of the body
cap (declared `Content-Length`, and counted mid-read), and the malformed-metadata answers — a firewall-refused
header value as `400`, an unsupported `Content-Type` as `415`, an unsatisfiable `Accept` as `406`, a validation
failure naming its field, the bare institutional prefix refused on the retail seam, a request line the firewall
refuses (`//`, a matrix parameter) rendered `400 INVALID_QUERY` rather than `401`, and a rejection Tomcat decides before
any servlet context exists rendered as `ApiError` JSON with the security headers. The contract pair that took
that family from 15 to 17 closes a condition AAP 0.6.2 declares and AAP 0.7.6's scenario list does not enumerate: retail `PUT`
and retail `DELETE` answering `409 RESERVATIONS_OUTSTANDING` while a reservation is `HELD`. Until they existed the
whole suite stayed green with that guard removed, which would have made an account with funds on hold overwritable
and deletable — the invariant the reserved balance exists to protect (AAP 0.6.3). None of them is a parameterized
matrix or an exploratory test, and each is the regression a specific fix left behind.

#### Authorization record

The six rows above that need an authorization are recorded here, in the same shape and with the same discipline as
the characterization document's acceptance block (`docs/legacy-characterization.md` section 10): a status, a named
authority, a date and a reference, written as single lines in a table so a reader — or a simple `grep` — can tell at
a glance which decisions are outstanding.

**Who may change a status.** Only the requesting organization may move a row from `PENDING` to `AUTHORIZED`, and must
record the authorizer's name and role, the date, and a reference to where the decision is minuted (a change record, a
risk acceptance, a ticket). Nobody building or reviewing this module may fill these fields on the organization's
behalf, for the same reason the retention requirement may not be defaulted: an authorization nobody granted is worse
than a deviation plainly marked outstanding. `AUTHORIZED` closes the row; `REJECTED` means the deviation must be
removed, which for D1–D3 and D6 reinstates the vulnerability the change fixed, for D4 deletes regression coverage and for D5
returns the build output to the parent's boundary gate, so a rejection needs the replacement control named in that
row's "what settles it" cell. The runbook's Step 0 prerequisites are the natural point to collect these, since that
step already gathers the platform owner's sign-offs before any cutover action.

**What each row needs to be closable**, so that no authorizer has to reconstruct it: D1 the nine coordinates, the
statement that the set sits above Boot 3.3.13's tested matrix with this module's own gate as its only evidence, and
the two deprecated-for-removal call sites the move creates — all three in
[the nine dependency overrides](#the-nine-dependency-overrides-and-why-no-bom-version-remediates-them), whose last
table also carries the three advisories no override can reach, so an authorizer signing D1 can see both what the
overrides fix and what they leave standing; D2 each code and its status, and the one code an over-ceiling hold now answers with; D3 the two wrapper lines; **D4 the executed count, which is
re-derivable from the build in the two commands under [Test volume](#test-volume-148-executed-against-a-ceiling-of-72)
so the number on this row can be checked rather than believed, together with the per-family attribution of every test
above the 72 declared scenarios**; D5 the single ignored pattern and the gate it keeps clean; D6 the tag, the
manifest-list digest and the measured before/after finding counts in
[the base-image section](#the-base-image-is-pinned-ahead-of-the-siblings-tag-deliberately). Each of the six is
stated in full in the [deviations table](#deviations-from-the-frozen-aap-inventory) above; this register adds only the
decision.

| # | Deviation, and the inventory it departs from | Status | Authorized by (name, role) | Date | Reference |
| --- | --- | --- | --- | --- | --- |
| D1 | The nine dependency overrides (AAP 0.9.1) — pgJDBC `42.7.13`, Tomcat `10.1.60`, Micrometer `1.15.12`, Spring Security `6.5.11`, Spring Framework `6.2.19`, Spring Data `2025.0.13`, Jackson `2.18.11`, Logback `1.5.38`, nimbus-jose-jwt `9.37.4` — including that the set sits above Boot 3.3.13's tested matrix on this module's own gate alone, and the two deprecated-for-removal call sites it creates | PENDING | — | — | — |
| D2 | `REQUEST_TOO_LARGE` → `413`, `UNSUPPORTED_MEDIA_TYPE` → `415`, `NOT_ACCEPTABLE` → `406` and `INVALID_REQUEST_FIELD` → `400` as four codes beyond the 22, plus an over-ceiling hold answering `422 AMOUNT_OUT_OF_RANGE` in place of `422 INSUFFICIENT_FUNDS` (AAP 0.6.2) | PENDING | — | — | — |
| D3 | The two lines added to the wrapper properties: the provenance comment and `distributionSha256Sum` (AAP 0.2.3, 0.8.1) | PENDING | — | — | — |
| D4 | 148 executed tests — 72 declared scenarios plus 76 fix-mandated regressions (AAP 0.7.6) | PENDING | — | — | — |
| D5 | A tracked one-line `.gitignore` (`target/`) beyond AAP 0.2.1's file inventory | PENDING | — | — | — |
| D6 | Base image `ubi9/openjdk-21-runtime:1.24@sha256:908540a9…` in place of the frozen `1.21` tag (AAP 0.9.1, 0.6.1), including the manifest-list digest pin and its re-derivation at each promotion | PENDING | — | — | — |

Legend: `PENDING` — the change is in the tree and the decision is outstanding. `AUTHORIZED` — approved, with the
authority, date and reference recorded on that row. `REJECTED` — the deviation must be removed together with the
replacement control its register row names.

## Prohibitions

These hold regardless of what access an executing environment happens to have.

- **Do not connect to, read from, or write to the real DB2 for z/OS or the VSAM KSDS.** The tooling is verified against
  fixtures only. The mainframe data is production state owned by the requesting organization, and live migration is
  runbook Step 1.
- **Do not run the dual-run comparator against the live legacy system.** It is proven against synthetic transaction
  streams. A live shadow run needs capture infrastructure, sign-off and a rollback plan that the runbook documents but
  this deliverable does not own.
- **Do not change any deployment configuration value that would route traffic to this service.**
  `cashAccount.enabled`, `cashAccount.url`, `database.kind`, the StockTrader CR and broker's `CASH_ACCOUNT_URL` stay
  exactly as they are. That cutover needs only these values is verified by inspection, not by flipping them.
- **Take no decommissioning or retention action.** No CICS, DB2 or VSAM asset is retired, exported for retention or
  deleted. The repository holds no retention policy and defaulting one is prohibited.

Nothing in this README claims that real DB2 or VSAM data has been migrated or reconciled, that shadow mode has run
against production traffic, or that any cutover has been performed. Criteria that can only be closed by the
operational hand-off — migration with zero variance in production, a dual-run clean for N consecutive windows, a
completed cutover — live in `docs/operational-runbook.md` as pending sign-offs.

## Stop and flag

If implementing or operating this module ever appears to require one of the following, **stop and report** rather than
proceeding. The list of existing files that would have to change is empty by design, and a non-empty list is the
signal that a design assumption has broken:

| Apparent need | What holds instead |
| --- | --- |
| A code change in `backend/broker` | `CashAccountClient` and `CashAccount` are unchanged, and this service satisfies them as-is — proven by contract tests through a verbatim copy plus a drift guard |
| Adding or editing a Helm chart template | The existing `templates/cash-account.yaml` already probes, exposes and injects everything this service needs; the service conforms to the template, never the reverse |
| Wiring `backend/execution-control` — or any caller — to the reservation endpoints | That module is absent from this repository and the institutional surface is deliberately a follow-on, exercised only by this module's tests |
| Changing an existing module's import, interface or configuration reference | This refactor is additive; that list is empty |

Report the exact file, template attribute or seam behaviour believed incompatible. Do not work around it.
