<!--
       Copyright 2025 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
-->

# StockTrader - execution-control

The Institutional Execution & Post-Trade Control Hub of the IBM Stock Trader application: a
self-contained Open Liberty microservice that **simulates** institutional equity order handling and
post-trade exception management.

This document is the only operator-facing documentation for the service. There is deliberately no
Helm chart, no `StockTrader` custom-resource field, no GitOps entry and no CI workflow for it —
deployment is standalone and described here in full.

## Purpose and simulation disclaimer

The service runs two flows over synthetic data held entirely in its own memory:

1. **Institutional order submission.** A trader submits an order. Four configurable pre-trade
   controls are evaluated, every result is recorded, and the order is either rejected with an
   auditable reason or accepted and simulated as executed — filled at the order's own `limitPrice`.
2. **Settlement exception handling.** On execution the order awaits affirmation while the client's
   firm and counterparty Standing Settlement Instructions (SSI) are compared. Instructions that
   agree take the order straight to settlement-ready; a differing field opens a settlement
   exception that an operations analyst works through **assign → resolve → settlement-ready**.

Every state change in either flow appends a timestamped event to an immutable, append-only audit
timeline.

### What this service is not

It is a simulation and nothing more:

- **No exchange, FIX or order routing.** Nothing leaves the process. `Execution.venue` is always
  the literal string `SIMULATED`.
- **No market data.** Pricing comes from the submitted `limitPrice`. The service calls **no other
  StockTrader service** — not `stock-quote`, not `broker`, not `portfolio`.
- **No real settlement, custody, clearing, regulatory reporting, sanctions screening or KYC.** The
  SSI comparison is a field-by-field comparison of two seeded records.
- **No real customer or market data.** Every client, symbol, price and settlement instruction is
  synthetic.

The JSON contract makes this impossible to miss:

| Label | Where it appears | Value |
| --- | --- | --- |
| `simulated` | every response entity (`Order`, `Execution`, `SettlementException`, `AuditEvent`, `ClientAccount`, `Position`, `SettlementInstruction`, `ControlLimitsView`) | always `true` |
| `disclaimer` | the same entities | `"Simulated institutional order and post-trade data. No real orders are routed, executed or settled; all clients, symbols, prices and settlement instructions are synthetic."` |
| `synthetic` | reference data only (`ClientAccount`, `Position`, `SettlementInstruction`) | always `true` |
| `source` | `Order` and `SettlementException` | `SEED` for startup-seeded records, `API` for records created by a request |
| `source` | `ControlLimitsView` | `CONFIG` — the effective rules are read from configuration, never stored |
| `venue` | `Execution` | always `SIMULATED` |

`ErrorResponse` carries no label, because it describes no order and no data.

This module is ordinary tracked content in the parent repository, **not** a git submodule and not a
repository of its own. It is entirely self-contained: every file the service needs — source,
configuration, security material, container definition and test wiring — lives under
`backend/execution-control/`, and nothing it needs sits outside that directory.

## Domain model and flows

Three state machines run over two entities. All order-side transitions complete **synchronously
inside the submit request** — there is no queue, no worker and no asynchronous delivery.

**Order lifecycle** (`status`)

```text
(none) -> SUBMITTED -> REJECTED                  any pre-trade control failed
(none) -> SUBMITTED -> ACCEPTED -> EXECUTED      all four controls passed; simulated fill at limitPrice
```

**Post-trade lifecycle** (`postTradeStatus`, begins on execution)

```text
(none) -> PENDING_AFFIRMATION -> EXCEPTION          SSI comparison found a differing field
(none) -> PENDING_AFFIRMATION -> SETTLEMENT_READY   instructions agree ("SSI affirmed (simulated)")
          EXCEPTION           -> SETTLEMENT_READY   the exception was marked settlement-ready
```

**Exception workflow** (`status`)

```text
(none) -> OPEN -> ASSIGNED -> RESOLVED -> SETTLEMENT_READY
                  ASSIGNED -> ASSIGNED          re-assignment to another owner is legal
```

There is **no direct `OPEN → RESOLVED` edge**. Calling `resolve` on an `OPEN` exception with an
`owner` in the body performs both transitions atomically and writes both events (`OPEN → ASSIGNED`
then `ASSIGNED → RESOLVED`); calling it on an `OPEN` exception without an owner fails with
`owner is required`. Any pair outside the tables above — `RESOLVED → RESOLVED`,
`OPEN → SETTLEMENT_READY`, anything out of `SETTLEMENT_READY` — is refused with `409` before any
event is written.

### Exception ageing and the SLA clock

`slaDeadline` is `openedAt + EXCEPTION_SLA_HOURS`. It is computed once, when the exception opens,
and is never recomputed afterwards. `ageHours` and `slaBreached` are the opposite: they are
**derived on every read** and never stored, and both are measured from a reference instant `t` that
depends on the exception's status.

| Status | Reference instant `t` | What that means |
| --- | --- | --- |
| `OPEN`, `ASSIGNED` | now, from the service's UTC clock | the exception keeps ageing while it is being worked, and crosses into breach as soon as `t` passes `slaDeadline` |
| `RESOLVED`, `SETTLEMENT_READY` | `resolvedAt` — the instant the resolution note was recorded | both values freeze at resolution; marking the exception settlement-ready afterwards does not move them |

`ageHours` is the whole hours between `openedAt` and `t`, truncated — a 90-minute-old exception
reports `1`. `slaBreached` is true only when `t` is **strictly after** `slaDeadline`, so an
exception read exactly on its deadline is not yet breached: the same equality rule the pre-trade
controls use.

The two consequences worth knowing when reading `GET /exceptions`: an exception resolved inside its
SLA never reports itself breached later, however long it stays in the store; and an open
exception's `ageHours` and `slaBreached` can differ between two reads with no state transition in
between, because the clock moved and nothing else did.

### Pre-trade controls

All four controls are evaluated on **every** order and all four results are recorded on the order,
so a rejection carries the full picture rather than only the first failure. The threshold rule is
the same everywhere: **a value exactly at the configured threshold passes; only a strictly greater
value rejects.**

| Control | Observed value | Reason recorded on failure | Reason on success |
| --- | --- | --- | --- |
| `MAX_ORDER_NOTIONAL` | `quantity × limitPrice` | `Order notional {n} exceeds MAX_ORDER_NOTIONAL {limit}` | `within limit` |
| `MAX_POSITION_NOTIONAL` | `abs(resulting quantity) × limitPrice`, the whole resulting holding marked at the order price | `Resulting position notional {n} ({q} × {price}) exceeds MAX_POSITION_NOTIONAL {limit}` | `within limit` |
| `RESTRICTED_SYMBOL` | the trimmed, upper-cased symbol | `Symbol {S} is on the restricted list RESTRICTED_SYMBOLS` | `not restricted` |
| `FAT_FINGER` | `quantity × limitPrice`, evaluated independently of `MAX_ORDER_NOTIONAL` | `Order notional {n} exceeds FAT_FINGER_NOTIONAL_THRESHOLD {limit}` | `within limit` |

`rejectionReason` on a rejected order is the failing reasons joined by `"; "`. Amounts are rendered
with two decimals, and the resulting quantity is `existing ± order quantity` (plus for `BUY`, minus
for `SELL`), with `0` when the client holds no position in the symbol.

### Audit timeline

The timeline is **append-only**: there is no update and no delete path, on any endpoint or in any
service. Each `AuditEvent` carries `eventId`, a strictly increasing `sequence`, a UTC `timestamp`,
`entityType` (`ORDER` or `EXCEPTION`), `entityId`, `stateMachine` (`ORDER`, `POST_TRADE` or
`EXCEPTION`), `fromState`, `toState`, `actor` (the caller's principal name — the JWT `upn` or the
HTTP Basic user name) and `reason`.

Because an order carries two interleaved state machines, `GET /orders/{orderId}/events` returns
**both** the `ORDER` and the `POST_TRADE` events for that order in `sequence` order, while
`GET /exceptions/{exceptionId}/events` returns the `EXCEPTION` events.

## API endpoints

The application is served under the context root `/execution-control`. All request and response
bodies are JSON.

| # | Endpoint | Method | Roles | Success | Returns |
| --- | --- | --- | --- | --- | --- |
| 1 | `/orders` | POST | `StockTrader` | 201 | the submitted order in its terminal state, with all four control results |
| 2 | `/orders` | GET | `StockViewer`, `StockTrader` | 200 | one page of orders, seeded and live |
| 3 | `/orders/{orderId}` | GET | `StockViewer`, `StockTrader` | 200 | one order |
| 4 | `/orders/{orderId}/events` | GET | `StockViewer`, `StockTrader` | 200 | that order's `ORDER` and `POST_TRADE` audit events, in `sequence` order |
| 5 | `/exceptions` | GET | `StockViewer`, `StockTrader` | 200 | one page of settlement exceptions, optionally filtered by `status` and `owner` |
| 6 | `/exceptions/{exceptionId}` | GET | `StockViewer`, `StockTrader` | 200 | one settlement exception |
| 7 | `/exceptions/{exceptionId}/events` | GET | `StockViewer`, `StockTrader` | 200 | one page of that exception's `EXCEPTION` audit events |
| 8 | `/exceptions/{exceptionId}/assign` | PUT | `StockTrader` | 200 | the exception, now `ASSIGNED` to the owner in the body |
| 9 | `/exceptions/{exceptionId}/resolve` | PUT | `StockTrader` | 200 | the exception, now `RESOLVED` with the resolution note |
| 10 | `/exceptions/{exceptionId}/settlement-ready` | PUT | `StockTrader` | 200 | the exception, now `SETTLEMENT_READY`; the parent order follows |
| 11 | `/audit` | GET | `StockViewer`, `StockTrader` | 200 | one page of the audit timeline, optionally filtered by `entityType` and `entityId` |
| 12 | `/controls` | GET | `StockViewer`, `StockTrader` | 200 | the five effective control values as configuration supplied them, `source = CONFIG` |
| 13 | `/clients` | GET | `StockViewer`, `StockTrader` | 200 | the three synthetic clients with both settlement instructions each |
| 14 | `/positions` | GET | `StockViewer`, `StockTrader` | 200 | one page of the synthetic positions, as executions have left them |

The five rows that read *one page of* — 2, 5, 7, 11 and 14 — take `offset` and `limit` and serialize
at most 500 records each, so none of them returns a complete collection by contract:
[Paging the collection endpoints](#paging-the-collection-endpoints) is where the clamping rule, the
traversal pattern and what a page deliberately does not tell you are set out. Rows 4
(`/orders/{orderId}/events`) and 13 (`/clients`) are bounded by the domain instead and return
everything they hold.

Those fourteen handlers are the whole application surface. **Any other method on any path — `HEAD`,
`OPTIONS`, `PATCH`, `TRACE` — is refused with `403`**, because `web.xml` covers `GET` on `/*` and
`POST`/`PUT`/`DELETE` on `/*` and then declares `<deny-uncovered-http-methods/>`: a method no
constraint covers is denied rather than allowed. No handler implements `DELETE`; `web.xml` names it
beside `POST` and `PUT`, so a `DELETE` from a `StockTrader` clears authorization and is then
answered `405` by the JAX-RS runtime rather than `403`.

Query filters:

- `GET /exceptions?status=&owner=` — `status` is one of `OPEN`, `ASSIGNED`, `RESOLVED`,
  `SETTLEMENT_READY`; `owner` matches the assigned owner. Both are optional.
- `GET /audit?entityType=&entityId=` — `entityType` is `ORDER` or `EXCEPTION`. Both are optional;
  supplied together they return one entity's timeline.
- `offset` and `limit` on each of the five paged collections — `GET /orders`, `GET /exceptions`,
  `GET /exceptions/{exceptionId}/events`, `GET /audit` and `GET /positions`. Both are optional and
  are clamped rather than refused, and a page holds at most 500 records; on `/exceptions` and
  `/audit` the page is cut from the filtered set, so a `status`, `owner`, `entityType` or
  `entityId` query pages its own matches. See
  [Paging the collection endpoints](#paging-the-collection-endpoints).

Request bodies: `POST /orders` takes `{clientOrderId, clientId, symbol, side, quantity, limitPrice}`
— `side` is `BUY` or `SELL`, `quantity` and `limitPrice` must be positive and inside the per-field
bounds under [Field limits](#field-limits), and `clientOrderId` is the idempotency key.
`PUT …/assign` takes `{owner}`; `PUT …/resolve` takes `{owner, resolutionNote}` with `owner`
optional once the exception is assigned; `PUT …/settlement-ready` takes no body.

`POST /orders` returns **`201` whether the terminal state is `EXECUTED` or `REJECTED`**, because the
order resource exists and is retrievable either way. Read `status`, `rejectionReason` and
`controlResults` on the returned entity to see which happened.

Status codes on failure:

| Condition | Code |
| --- | --- |
| No credentials presented | `401` |
| Authenticated `StockViewer` on a mutating verb (POST/PUT) | `403` |
| Validation failure — missing field, non-positive `quantity`/`limitPrice`, unknown `clientId`, blank `owner` or `resolutionNote` | `400` |
| Request body that cannot be read as JSON — truncated or non-JSON text, an empty body, a field of the wrong JSON type, or an array where an object belongs | `400` |
| Unknown order or exception id | `404` |
| Duplicate `clientOrderId`, or an unsupported lifecycle transition | `409` |
| An in-memory ceiling is exhausted, so a request that would otherwise have been accepted cannot be recorded | `503` |

Every error body is `{status, error, message, path}`, with `message` naming the offending field,
identifier or state — except for a body that could not be read, whose `message` is the fixed text
`request body is not valid JSON`: the parser's own description names internal types and fields and
is never relayed. The `503` is the one code the plan this module was built to does not list; it
exists because the stores are bounded, it is decided last — after `400`, `404` and `409` — and it is
described under [Admission capacity](#admission-capacity) and recorded under
[Deviations from the frozen implementation plan](#deviations-from-the-frozen-implementation-plan).

Both personas map to the `StockTrader` role — the trader who submits orders and the operations
analyst who works exceptions alike — because the estate defines only `StockTrader` and
`StockViewer` and this service introduces no new role. `StockViewer` may read everything.

`/health/*` and `/openapi` are runtime endpoints served **outside** the WAR's context root, so the
role constraints above do not gate them. `/openapi` is served by `mpOpenAPI-4.1`, part of the
`microProfile-7.1` umbrella the service enables.

## Configuration

Configuration is by environment variable only. **In its default mode the service starts with no
configuration supplied at all**: `AUTH_TYPE` defaults to `basic` in `server.xml`, and every
variable that mode reads has a safe default held either in `server.xml` or in
`microprofile-config.properties`, so a container started with no `env` entries seeds its synthetic
data, enforces the documented control limits and answers all three health probes `UP`.

Exactly one variable has no default. `OIDC_JWKS_URL` is **mandatory when `AUTH_TYPE=oidc`**,
because `includes/oidc.xml` resolves it straight into the `mpJwt` consumer's `jwksUri`: select that
mode without supplying it and the JWT consumer has no key source. Every other variable below is an
override of a shipped default.

### Business rules

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `MAX_ORDER_NOTIONAL` | decimal | `1000000.00` (`microprofile-config.properties`) | Ceiling on one order's notional (`quantity × limitPrice`). Strictly greater rejects; a value exactly at the threshold passes |
| `MAX_POSITION_NOTIONAL` | decimal | `5000000.00` (`microprofile-config.properties`) | Ceiling on the absolute resulting position notional for the client and symbol. The whole resulting quantity is marked at the order's `limitPrice`, so the value evaluated equals the `positionNotional` the position will carry if the order fills |
| `FAT_FINGER_NOTIONAL_THRESHOLD` | decimal | `2500000.00` (`microprofile-config.properties`) | Firm-wide anomaly ceiling on one order's notional, evaluated independently of `MAX_ORDER_NOTIONAL` |
| `RESTRICTED_SYMBOLS` | comma-separated list | `RSTRA,RSTRB` (`microprofile-config.properties`) | Symbols that may not be traded, matched against the trimmed and upper-cased order symbol. The defaults are synthetic tickers |
| `EXCEPTION_SLA_HOURS` | integer | `24` (`microprofile-config.properties`) | Hours from exception opening to its SLA deadline: `slaDeadline = openedAt + EXCEPTION_SLA_HOURS`, fixed when the exception opens. `ageHours` and `slaBreached` are derived from that deadline on every read, against the status-dependent reference instant set out under [Exception ageing and the SLA clock](#exception-ageing-and-the-sla-clock) |

### Server and identity

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `AUTH_TYPE` | string | `basic` (`server.xml`) | Selects `includes/<AUTH_TYPE>.xml` — one of `basic`, `ldap`, `oidc`, `none` |
| `JWT_AUDIENCE` | string | `stock-trader` (`server.xml`) | Expected JWT audience, unchanged from the estate |
| `JWT_ISSUER` | string | `http://stock-trader.ibm.com` (`server.xml`) | Expected JWT issuer, unchanged from the estate |
| `OIDC_JWKS_URL` | URL | none — required only when `AUTH_TYPE=oidc` | JWKS endpoint of the OIDC provider, referenced by `includes/oidc.xml`. Not sensitive; supply it as a plain `env` entry |
| `TRACE_SPEC` | string | `*=info` (`server.xml`) | Liberty trace specification |
| `DEFAULT_HTTP_PORT` (`default.http.port`) | integer | `9080` (`server.xml`) | HTTP listener port. Also overridable in the build with `-Dliberty.var.default.http.port` |
| `DEFAULT_HTTPS_PORT` (`default.https.port`) | integer | `9443` (`server.xml`) | HTTPS listener port. Also overridable with `-Dliberty.var.default.https.port` |

### Telemetry

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | URL | `http://jaeger-collector.istio-system.svc.cluster.local:4317` (held as `otel.exporter.otlp.endpoint` in `microprofile-config.properties`) | OTLP target for `mpTelemetry-2.1`. Exporter failures are logged and never fatal, so an absent collector degrades to log noise only |
| `OTEL_SDK_DISABLED` | boolean | `false` (held as `otel.sdk.disabled` in `microprofile-config.properties`) | Mirrors the broker. Set it to `true` for local runs to silence exporter retries |

### Why the defaults live in two places

Each variable has exactly one home, decided by who has to resolve it. **Server-level variables**
(`AUTH_TYPE`, `JWT_AUDIENCE`, `JWT_ISSUER`, `TRACE_SPEC`, `default.http.port`,
`default.https.port`) are resolved by Liberty while it parses `server.xml`, before any application
configuration source exists, so their defaults are `<variable name="…" defaultValue="…"/>` elements
in `server.xml`. The **five business rules** are application configuration read through MicroProfile
Config; their property names equal the environment-variable names exactly, so the default
environment mapping applies without translation, their defaults live only in
`src/main/resources/META-INF/microprofile-config.properties`, and `@ConfigProperty` carries no
`defaultValue`. Liberty also exposes `server.xml` variables to MicroProfile Config, which is how
the readiness probe reads `JWT_AUDIENCE` and `JWT_ISSUER` with nothing supplied by the environment.

Telemetry uses the standard dotted OpenTelemetry keys in `microprofile-config.properties`;
the environment overrides them through MicroProfile Config's normalized names
`OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SDK_DISABLED`, which are also the OpenTelemetry-standard
variable names.

## Build and test

Toolchain: **JDK 17** (`maven.compiler.release` is 17) and **Apache Maven 3.9.11**. Network access
to Maven Central and the Liberty feature repository is required the first time, to fetch the
dependencies and the test-server assembly.

The full gate — unit tests, a real Liberty server, the integration tests and the coverage check:

```bash
mvn -B clean verify
```

It produces `target/ExecutionControl.war`, `target/surefire-reports/TEST-*.xml` (4 unit classes),
the test server under `target/liberty/`, `target/failsafe-reports/TEST-*.xml` plus
`target/failsafe-reports/failsafe-summary.xml` (4 integration-test classes), and
`target/site/jacoco/index.html` with `target/site/jacoco/jacoco.xml`. The build fails on any test
failure and on a line-coverage ratio below 0.80 in the `control` or `lifecycle` package.

Unit tests only, with no Liberty download or server start:

```bash
mvn -B test
```

Unit tests plus the coverage report, when you want the figure without running the integration
tests:

```bash
mvn -B test jacoco:report
```

The WAR alone, for the container build:

```bash
mvn -B clean package -DskipTests
```

`mvn verify` starts and stops a real Open Liberty server around the integration tests
(`liberty-maven-plugin` 3.11.5, assembly `io.openliberty:openliberty-runtime:26.0.0.9`): the server
is created and its features installed at `prepare-package`, started and the WAR deployed at
`pre-integration-test`, and stopped at `post-integration-test`. The test server runs with
`AUTH_TYPE=none`, so `includes/none.xml` is active and the integration tests authenticate with HTTP
Basic as `stock:trader` (`StockTrader`) and `read:only` (`StockViewer`). It binds port 9080; move it
with `-Dliberty.var.default.http.port=<port>`, which shifts the server port and the tests'
`liberty.test.port` expectation together:

```bash
mvn -B clean verify -Dliberty.var.default.http.port=19080 -Dliberty.var.default.https.port=19443
```

The test assembly is deliberately the same Open Liberty release the container base image runs
(26.0.0.9), so an integration test can never pass on a runtime the deployed image does not have.
Keep the two in step on every runtime upgrade.

**Coverage target:** at least 80% line coverage on the `control` and `lifecycle` packages — the
module's business logic — enforced by the JaCoCo `check` rule at `verify` and readable from
`target/site/jacoco/jacoco.xml` or the HTML report. Coverage is measured in the Surefire JVM only;
the integration-test fork and the Liberty JVM are deliberately not instrumented, so client-side
activity cannot inflate the figure.

## Container

Maven must run before `docker build` — the image compiles nothing and copies the WAR that Maven
produced:

```bash
mvn -B clean package -DskipTests && \
  docker build -t execution-control:local . && \
  docker run -d --name ec -p 127.0.0.1:9080:9080 -p 127.0.0.1:9443:9443 \
    -e AUTH_TYPE=none execution-control:local
```

The three steps are chained with `&&` deliberately: run them separately and a failed Maven build
leaves `docker build` copying whatever WAR was in `target/` beforehand, and a failed `docker build`
leaves `docker run` starting the previous image under the same tag. Chained, the first failure
stops the pipeline.

Both published ports name `127.0.0.1` explicitly. A bare `-p 9080:9080` does not mean localhost: it
binds the Docker daemon's default host address, which is `0.0.0.0` unless the daemon was configured
otherwise, and that publishes the cleartext listener on every interface of the machine.

The base image is `icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal`, pinned in
the `Dockerfile` by digest alongside that tag:

```
FROM icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal@sha256:4c84a4fc73413adf3406513b7827eee721591bf139b245fea51b71e00b0dc4bc
```

It carries every Liberty feature, so the `microProfile-7.1`, `mpTelemetry-2.1` and
`appSecurity-5.0` features `server.xml` enables need no install step, and the module's release-17
class files run unchanged on the image's Java 21 runtime.

**Runtime provenance.** The 26.0.0.9 release line is a security floor, not a cosmetic choice: the
servlet request/response smuggling fixes land in 26.0.0.8, so every earlier release — including
the older base the sibling Liberty services still run — is inside the affected range. The digest is
pinned because the tag is mutable: the same tag rebuilt later can resolve to different bytes, and
the digest makes the image reviewable and the rollback exact while the tag keeps the release
readable.

A digest fixes *which* bytes you run. It says nothing about *who* built them, so the digest is the
end of the procedure below, not the whole of it. Before adopting a base image — at the next runtime
upgrade, or any time the pinned digest changes — verify its provenance and record what you verified:

```bash
BASE=icr.io/appcafe/open-liberty:<tag>

# 1. The digest the tag resolves to right now. This is what gets pinned, and
#    what every later step must be about.
docker buildx imagetools inspect "$BASE" --format '{{println .Manifest.Digest}}'

# 2. The build provenance the publisher attests to. Open Liberty publishes an
#    in-toto attestation with predicate type https://slsa.dev/provenance/v1,
#    carried in an attestation manifest alongside each platform manifest.
docker buildx imagetools inspect "$BASE" --format '{{ json (index .Provenance "linux/amd64") }}'

# 3. If that comes back empty, read the attestation manifest straight from the
#    registry: the index entry annotated vnd.docker.reference.type=attestation-manifest
#    has one application/vnd.in-toto+json layer, and its in-toto.io/predicate-type
#    annotation names the predicate.
docker buildx imagetools inspect "$BASE" --raw
```

Check the returned build definition names the builder, source and workflow you expect for an
official Open Liberty release, and reject the image if it does not. Note the limit honestly: at the
time of writing IBM Container Registry publishes SLSA provenance for these images but no Cosign
signature — `sha256-<digest>.sig` is absent — so the verifiable evidence is the attestation plus the
digest, and the digest recorded in the `Dockerfile` is the trust anchor. If your organisation
requires a signature, mirror the base into a registry you control and sign it there as part of
admission, rather than treating an unsigned upstream tag as trusted.

Hold the image you build to a higher standard than the base, because you own its builder. Produce
provenance and an SBOM at build time, verify both after the push, and sign the digest with the
identity your cluster's admission policy trusts:

```bash
IMG=<your-image-repo>/ibmstocktrader/execution-control:1.0.0

docker buildx build --provenance=true --sbom=true -t "$IMG" --push .

# Read both attestations back from the registry copy, not the local one.
docker buildx imagetools inspect "$IMG" --format '{{ json (index .Provenance "linux/amd64") }}'
docker buildx imagetools inspect "$IMG" --format '{{ json (index .SBOM "linux/amd64") }}'

# Sign the digest, and verify against the signer identity you expect. Keyless
# example; substitute your key or your CI's OIDC issuer and subject.
DIGEST=$(docker buildx imagetools inspect "$IMG" --format '{{println .Manifest.Digest}}')
cosign sign "${IMG%:*}@${DIGEST}"
cosign verify "${IMG%:*}@${DIGEST}" \
  --certificate-identity <your-build-identity> \
  --certificate-oidc-issuer <your-oidc-issuer>
```

Only the digest that passed those checks belongs in the manifest's `image:` field, and it is worth
recording alongside the release which digest was verified, by whom and against which signer — that
record, not the tag, is what makes a later rollback or incident review possible.

Add `-e OTEL_SDK_DISABLED=true` for a local run to suppress the OTLP exporter's retries against the
cluster-local collector endpoint. Verify the container with:

```bash
curl -s http://localhost:9080/health/ready
curl -k -u stock:trader https://localhost:9443/execution-control/controls
docker rm -f ec
```

The health call carries no credential, so it uses the cleartext listener — the same one the kubelet
probes in a cluster. The second call carries one, so it uses the TLS listener, exactly as the review
section below does; `-k` is needed because the shipped keystore is self-signed sample material. The
rule does not relax for a local container: the cleartext port is for credential-free traffic
wherever it is published.

## Standalone Kubernetes deployment

This service is not managed by the StockTrader operator or its Helm chart, so it is deployed by
hand. Push the image to a registry your cluster can pull from, then apply the manifest below. It
deploys into the `stocktrader` namespace — create that namespace first if it does not exist, or
substitute your own.

Reference the image you pushed by digest rather than by a tag, so the manifest names exactly the
bytes you verified and a restart cannot silently pick up different ones. Use the digest that came
out of the provenance and signature checks in the Container section above — read it back from the
registry rather than from the local daemon:

```bash
docker buildx imagetools inspect <your-image-repo>/ibmstocktrader/execution-control:1.0.0 \
  --format '{{println .Manifest.Digest}}'
```

`docker inspect --format='{{index .RepoDigests 0}}' <image>` gives the same value for an image the
local daemon has pushed or pulled, if you would rather not reach the registry.

Save the manifest as `execution-control.yml`, replacing the `image:` placeholder with your own
repository and that verified digest:

```yaml
#       Copyright 2025 Kyndryl, All Rights Reserved

#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at

#       http://www.apache.org/licenses/LICENSE-2.0

#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

apiVersion: apps/v1
kind: Deployment
metadata:
  name: execution-control
  labels:
    app: execution-control-stock-trader
spec:
  replicas: 1
  selector:
    matchLabels:
      app: execution-control
  template:
    metadata:
      labels:
        app: execution-control
    spec:
      # This service calls no Kubernetes API, so a projected token would only be an
      # unused credential in the pod.
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        # The user the image's own `USER 1001` and `chown -R 1001:0` establish.
        runAsUser: 1001
        seccompProfile:
          type: RuntimeDefault
      containers:
      - name: execution-control
        image: <your-image-repo>/ibmstocktrader/execution-control@sha256:<the digest you verified>
        ports:
          - containerPort: 9080
          - containerPort: 9443
        # A digest cannot move, so there is nothing to re-pull on restart.
        imagePullPolicy: IfNotPresent
        securityContext:
          allowPrivilegeEscalation: false
          capabilities:
            drop: ["ALL"]
        env:
          # Identity settings.
          - name: AUTH_TYPE
            value: "basic"
          - name: JWT_AUDIENCE
            value: "stock-trader"
          - name: JWT_ISSUER
            value: "http://stock-trader.ibm.com"
          # Required only when AUTH_TYPE=oidc, and then it must name your provider's JWKS endpoint.
          # - name: OIDC_JWKS_URL
          #   value: "https://your-oidc-provider/.well-known/jwks.json"
          # Pre-trade controls.
          - name: MAX_ORDER_NOTIONAL
            value: "1000000.00"
          - name: MAX_POSITION_NOTIONAL
            value: "5000000.00"
          - name: FAT_FINGER_NOTIONAL_THRESHOLD
            value: "2500000.00"
          - name: RESTRICTED_SYMBOLS
            value: "RSTRA,RSTRB"
          # Post-trade exception ageing.
          - name: EXCEPTION_SLA_HOURS
            value: "24"
        # A failed application start registers no check of this service at all. Startup and
        # readiness then answer 503 with an empty `checks` array on this runtime, but liveness
        # answers 200 UP with that same empty array - so liveness alone is not evidence the
        # service works. See "A failed start and the empty check list" in the operational notes
        # before treating these three as a hard gate.
        startupProbe:
          httpGet:
            path: /health/started
            port: 9080
          initialDelaySeconds: 60
          periodSeconds: 30
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 9080
          periodSeconds: 15
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /health/live
            port: 9080
          periodSeconds: 15
          failureThreshold: 3
        resources:
          limits:
            cpu: 1000m
            memory: 1Gi
            ephemeral-storage: 256Mi
          requests:
            cpu: 250m
            memory: 512Mi
            ephemeral-storage: 32Mi
---
apiVersion: v1
kind: Service
metadata:
  name: execution-control-service
  labels:
    app: execution-control
spec:
  type: ClusterIP
  ports:
    # Cleartext: kubelet probes and in-cluster callers only. Anything carrying
    # credentials belongs on 9443 or behind a TLS-terminating ingress.
    - name: http
      protocol: TCP
      port: 9080
      targetPort: 9080
    - name: https
      protocol: TCP
      port: 9443
      targetPort: 9443
  selector:
    app: execution-control
```

Apply it:

```bash
kubectl apply -f execution-control.yml -n stocktrader
```

The probe paths and timings are the contract the StockTrader operator chart applies to its own
services, reused here so this service behaves the same way under Kubernetes. The pod template
carries no annotations at all, and two of the estate's conventions are deliberately not copied:
there are no `prometheus.io/*` annotations, because the requested feature set includes no
`mpMetrics` and so there is no `/metrics` endpoint to advertise; and there is no `git-repo`
annotation, because this service has no repository of its own to name — it is tracked content of
the parent aggregation, not a seventeenth microservice repository.

The pod runs with least privilege: no service-account token is mounted (the service calls no
Kubernetes API), the container runs as the image's non-root UID 1001 under the `RuntimeDefault`
seccomp profile, privilege escalation is refused and every Linux capability is dropped. One
restricted-profile setting is deliberately absent: `readOnlyRootFilesystem` is not set, because
Liberty writes its workarea, output and logs under `/opt/ol/wlp` at startup and would fail to
start against a read-only root without `emptyDir` mounts for those paths.

The `Service` is `ClusterIP` — the minimal exposure — because nothing in the estate routes to this
service: no trader page, no broker adapter, no Istio route. Substituting `type: NodePort` with the
same two ports also works and is what `portfolio-assistant` uses, if you would rather reach it
without a port-forward; note that it puts the cleartext 9080 listener on every node, so restrict it
to 9443 if you do.

Of the two published ports, **9080 is cleartext HTTP** and exists for the kubelet probes and
in-cluster callers; **9443 is the TLS listener** and is where authenticated application traffic
belongs, either directly or through a TLS-terminating ingress.

The two ports are separate `httpEndpoint` elements in `server.xml` for one reason: a response-header
policy in Liberty is scoped to an endpoint, not to a scheme. Both listeners suppress the server
signature and `X-Powered-By`, and both set `X-Content-Type-Options: nosniff` and
`Cache-Control: no-store` — the latter because every application response here is authenticated
JSON that no intermediary or browser should retain. Only the TLS listener adds
`Strict-Transport-Security`, which a client that arrived over cleartext would be right to ignore. An
ingress in front of this service may set the same headers; it must not weaken them.

## Reviewing the service

**Send credentials over 9443, never over 9080.** Port 9080 is plain HTTP: it applies no encryption,
and a `kubectl port-forward` does not add any — the hop from the API server to the pod carries
whatever the listener speaks. An `Authorization: Bearer` header or an HTTP Basic credential sent to
9080 is therefore readable by anything on that path. Use 9080 for the health probes and in-cluster
calls that carry no credential; put every authenticated call on the TLS listener 9443, or behind a
TLS-terminating ingress in front of it.

Forward the TLS port, in a shell you can leave running:

```bash
kubectl -n stocktrader port-forward svc/execution-control-service 9443:9443
```

That gives the API base URL `https://localhost:9443/execution-control`.

The server presents the estate's shared *sample* keystore — self-signed demonstration material
copied from the broker, not a certificate any client trusts — so `curl` needs `-k` to talk to it,
or `--cacert <file>` if you extract the certificate first. That `-k` is itself the reminder: this
material proves nothing about the peer and must be replaced with real certificates (or fronted by
an ingress that terminates real TLS) before anything but a demonstration runs here.

The health endpoints carry no credential, so the cleartext port is adequate for them — it is what
the kubelet probes use in the cluster. Forward it as well, in its own shell:

```bash
kubectl -n stocktrader port-forward svc/execution-control-service 9080:9080
```

```bash
curl -s http://localhost:9080/health/started
curl -s http://localhost:9080/health/ready
curl -s http://localhost:9080/health/live
```

Authenticate according to the deployment's `AUTH_TYPE`. With the default `basic`, present the same
`Authorization: Bearer` token the estate already issues (the trader front end's `jwtSso` builder
issues a compatible one, readable from the browser's cookies):

```bash
curl -k -H "Authorization: Bearer <jwt>" https://localhost:9443/execution-control/exceptions
```

With `AUTH_TYPE=none` — development and tests only — HTTP Basic against the development registry
works instead:

```bash
curl -k -u stock:trader https://localhost:9443/execution-control/exceptions
```

Submit an order (the trader persona). The response is the order in its terminal state, with all
four control results attached:

```bash
curl -k -u stock:trader -X POST https://localhost:9443/execution-control/orders \
  -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"C1","clientId":"INST-001","symbol":"SYNA","side":"BUY","quantity":100,"limitPrice":100.00}'
```

Submit a comparable order for `INST-003` and the execution opens a settlement exception, because
that client's counterparty SSI disagrees with the firm's:

```bash
curl -k -u stock:trader -X POST https://localhost:9443/execution-control/orders \
  -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"C2","clientId":"INST-003","symbol":"SYNA","side":"BUY","quantity":300,"limitPrice":100.00}'
curl -k -u stock:trader "https://localhost:9443/execution-control/exceptions?status=OPEN"
```

Work that exception through to settlement-ready (the operations-analyst persona), substituting the
`exceptionId` the list returned:

```bash
curl -k -u stock:trader -X PUT https://localhost:9443/execution-control/exceptions/EXC-000002/assign \
  -H 'Content-Type: application/json' \
  -d '{"owner":"ops.analyst"}'

curl -k -u stock:trader -X PUT https://localhost:9443/execution-control/exceptions/EXC-000002/resolve \
  -H 'Content-Type: application/json' \
  -d '{"owner":"ops.analyst","resolutionNote":"Counterparty safekeeping account corrected with custodian"}'

curl -k -u stock:trader -X PUT https://localhost:9443/execution-control/exceptions/EXC-000002/settlement-ready
```

Then read the evidence — the effective rules, the reference data and the audit timeline:

```bash
curl -k -u stock:trader https://localhost:9443/execution-control/controls
curl -k -u stock:trader https://localhost:9443/execution-control/clients
curl -k -u stock:trader https://localhost:9443/execution-control/positions
curl -k -u stock:trader https://localhost:9443/execution-control/audit
curl -k -u stock:trader "https://localhost:9443/execution-control/audit?entityType=EXCEPTION&entityId=EXC-000002"
curl -k -u read:only https://localhost:9443/execution-control/orders
```

The last call shows the read-only role at work; the same credentials on any `POST` or `PUT` answer
`403`. On a freshly started service each of those collections fits in one response; on a service
that has been worked, `/orders`, `/positions` and `/audit` return their first 500 records only, and
[Paging the collection endpoints](#paging-the-collection-endpoints) is how you read the rest.

## Seeded synthetic data

`SeedDataLoader` runs once at application start. It writes the reference data — clients, their
settlement instructions and their positions — straight into the reference-data store, because
reference data has no lifecycle to run and therefore no state transition to record. It then submits
three historical orders through the **same** `OrderLifecycleService.submit` call a REST request
enters, as actor `seed`, so the seeded executions, the one seeded open exception and every seeded
audit event are produced by the code path that serves live requests.

### Clients

| Client | Name | Settlement instructions |
| --- | --- | --- |
| `INST-001` | Northwind Asset Management | firm and counterparty SSI agree |
| `INST-002` | Contoso Pension Trust | firm and counterparty SSI agree |
| `INST-003` | Fabrikam Capital Partners | counterparty `safekeepingAccount` differs from the firm's, so **every** execution for this client opens an SSI-mismatch exception |

Each `ClientAccount` carries its two `SettlementInstruction` records, each holding `custodianBic`,
`safekeepingAccount`, `cashAccount` and `placeOfSettlement` — the four fields the SSI comparison
walks.

### Positions

The three seed orders run through the live code path, and executed orders update positions, so the
values `GET /positions` reports after startup are not the values that were seeded. Seeded
`INST-001 SYNA` was 10000 and seeded `INST-003 SYNA` was 2000; the post-startup state is:

| Client | Symbol | Quantity | Last price | Position notional |
| --- | --- | --- | --- | --- |
| `INST-001` | `SYNA` | 10100 | 100.00 | 1,010,000.00 |
| `INST-001` | `SYNB` | 5000 | 50.00 | 250,000.00 |
| `INST-002` | `SYNC` | 20000 | 40.00 | 800,000.00 |
| `INST-002` | `SYND` | 45000 | 100.00 | 4,500,000.00 |
| `INST-003` | `SYNA` | 2500 | 100.00 | 250,000.00 |

`INST-002 SYND` is held at 45000 @ 100.00 — 4,500,000 of the default 5,000,000 resulting-position
ceiling — deliberately, so that a buy of more than 5000 shares at 100.00 trips
`MAX_POSITION_NOTIONAL` while anything up to 5000 passes. It is the holding that makes that
boundary reachable without reconfiguring the service.

### Seed orders

| Client order id | Order | Outcome |
| --- | --- | --- |
| `SEED-001` | `INST-001 BUY SYNA 100 @ 100.00` | `EXECUTED`, post-trade `SETTLEMENT_READY` (SSIs agree) |
| `SEED-002` | `INST-002 BUY RSTRA 10 @ 10.00` | `REJECTED` — `RSTRA` is on the restricted list |
| `SEED-003` | `INST-003 BUY SYNA 500 @ 100.00` | `EXECUTED`, post-trade `EXCEPTION` — opens `EXC-000001` |

All three orders carry `"source": "SEED"`, as does `EXC-000001`, so seeded activity is always
distinguishable from activity a request created (`"source": "API"`).

The effective control rules are **not stored** anywhere. `GET /controls` reports the configuration
as `ControlLimitsProducer` read it from MicroProfile Config, labelled `"source": "CONFIG"`.

Identifiers are zero-padded and monotonic per store: orders `ORD-000001`, executions `EXE-000001`,
exceptions `EXC-000001`.

## Operational notes

### Fail-closed role resolution

This module deliberately does **not** reproduce the `<application-bnd>` element that the sibling
services' `server.xml` uses to bind `StockTrader` to `ALL_AUTHENTICATED_USERS`, because that binding
makes `StockViewer` unenforceable — every authenticated caller would hold the trading role. Roles
here resolve from the token's `groups` claim (Liberty's `mpJwt` reads `groups` by default), or from
the `basicRegistry` groups of `includes/none.xml` when `AUTH_TYPE=none`.

The accepted consequence: a deployment using `AUTH_TYPE=oidc` with an identity provider whose
tokens carry no `groups` claim **fails closed** — every protected application request under
`/execution-control` answers `403` to an authenticated caller, because neither role resolves for
them. The runtime endpoints are unaffected: `/health/*` and `/openapi` sit outside the WAR's
context root, so the `web.xml` constraints never gate them and they keep answering normally. That
combination — all three probes `UP` while every API call returns `403` — is the signature of this
misconfiguration. The single remedy is to configure the identity provider to emit a `groups` claim
containing `StockTrader` or `StockViewer`. Re-adding the `ALL_AUTHENTICATED_USERS` binding is
**not** offered as a remedy: it would grant every authenticated caller `StockTrader` and defeat the
restriction of the mutating verbs that this service requires.

### A failed start and the empty check list

The three health checks are CDI beans, so they exist only if the application started. If it did not
— a throw inside the seed observer that runs at `@Initialized(ApplicationScoped.class)`, or any
deployment failure that stops the WAR being installed — **no check of this service is registered**,
and what each probe answers is then the runtime's decision rather than this service's. Measured on
the delivered runtime (Open Liberty 26.0.0.9, `mpHealth-4.0`) with the application deliberately not
installed:

| Probe | No application started | Service healthy |
| --- | --- | --- |
| `/health/started` | `503` `{"status":"DOWN","checks":[]}` | `200`, one `ExecutionControl` check, `"message":"Started"` |
| `/health/ready` | `503` `{"status":"DOWN","checks":[]}` | `200`, one `ExecutionControl` check, `"message":"Ready"` with `jwtAudience` and `jwtIssuer` |
| `/health/live` | **`200` `{"status":"UP","checks":[]}`** | `200`, one `ExecutionControl` check, `"message":"Live"` with the store counts |

Readiness and startup fail closed there, but **not** because of the probes in this module: they are
`DOWN` because Liberty contributes the application's own start state to those two endpoints. That is
a runtime default this service neither owns nor can assert in its own tests. Liveness gets no such
contribution, and MicroProfile Health's outcome with no registered check is an overall `UP` — so a
pod whose application never installed still answers liveness `200 UP`. Read liveness alone as
evidence the service works and you will route traffic to a pod where every call under
`/execution-control` answers `404`.

**Read the `checks` array, not only the status.** A healthy answer names this service, and that name
is the thing worth asserting on:

```json
{"status":"UP","checks":[{"name":"ExecutionControl","status":"UP","data":{"message":"Ready","jwtAudience":"stock-trader","jwtIssuer":"http://stock-trader.ibm.com"}}]}
```

An empty `checks` array on any of the three is therefore the signature of a start that never
completed — whatever status sits beside it — and it is distinct from a service that started and is
still seeding, which registers its check and reports `DOWN` with
`"message": "Seed data not yet loaded"`. The server log holds the cause the probe cannot report: a
successful deployment logs `CWWKZ0001I: Application ExecutionControl started`, and in its place sits
the `CWWKZ` message that names the failure — `CWWKZ0014W` when the WAR is not at the location
`server.xml` names, `CWWKZ0021E` when it is there but cannot be started.

A hard gate should not rest on that runtime default. Gating on an application path is what proves
the application is installed, and the `httpGet` form cannot express it: under `AUTH_TYPE=basic` (and
under `none`) every path below `/execution-control` answers `401` to the credential-free kubelet,
and the kubelet counts only `2xx` and `3xx` as success. An `exec` probe can, because it reads the
status code itself — `401` and `403` prove the application is deployed and enforcing, while `404`
proves it is absent:

```yaml
        readinessProbe:
          exec:
            command:
            - sh
            - -c
            - 'code=$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" http://localhost:9080/execution-control/controls) || exit 1; case "$code" in 200|401|403) exit 0 ;; *) exit 1 ;; esac'
          periodSeconds: 15
          timeoutSeconds: 5
          failureThreshold: 3
```

Both halves of that command are load-bearing, and a shorter form fails open. `|| exit 1` is what
makes a refused or timed-out connection a failure: `curl` writes the status text `000` and exits
non-zero, and a predicate that only compared the text would pass a listener that never answered.
The `case` whitelist is what keeps the gate hard: anything that is not the expected `200`, `401` or
`403` — a `404` from an undeployed application, a `5xx` from a broken one, an empty `000` — fails
rather than being read as "not a 404, so healthy". `timeoutSeconds` is raised because the kubelet
allows an `exec` probe one second by default, less than `--max-time` needs.

`curl` is present in the base image (`/usr/bin/curl`), so the probe needs nothing added to it. The
manifest above ships the `httpGet` form because it is the contract the operator chart applies to
every other StockTrader service; substitute the `exec` form when a deployment must never route to a
pod whose application failed to install.

### Inherited demonstration security material

The module carries copies of the estate's sample security material so that cross-service JWT
acceptance works out of the box:

- `src/main/liberty/config/resources/security/key.p12` — the shared sample private key.
- `src/main/liberty/config/resources/security/trust.p12` — byte-identical to the broker's, holding
  the `jwtSigner` key. That byte-for-byte identity is precisely what lets this service accept the
  tokens the rest of the estate issues.
- The hard-coded sample keystore password configured in `server.xml`, which both stores share. The
  value is in that file and is deliberately not repeated here.
- The plaintext development users in `includes/none.xml` (`stock:trader` in `StockTrader`,
  `read:only` in `StockViewer`).

**This is sample material, not usable credentials.** It is shipped for demonstration and testing
only. Replace the keystores and the password for any deployment you care about, and treat
`AUTH_TYPE=none` as a development and test mode only — it disables JWT verification entirely and
accepts the hard-coded users above.

### In-memory storage, and data lost on restart

All state is held in in-memory data structures owned solely by this service, in two shapes. Orders
with their executions, settlement exceptions, client reference data and positions live in
`ConcurrentHashMap` stores, where every mutation is one atomic per-key operation. The audit
timeline is not a map: it is an append-only list whose ordinals come from an `AtomicLong`, appended
under a single monitor so that an event's ordinal, its timestamp and its place in the list can
never disagree, and read out only as an unmodifiable copy.

State shares nothing with the Portfolio JDBC schema or the Account CouchDB documents, and it
requires no datastore, no message broker and no credential of its own: there is nothing to
provision before the service runs.

The corollary is that **all data is lost on restart**. Every order, execution, exception and audit
event created through the API disappears, and the seed set described above is re-created from
scratch on every startup. That is acceptable for simulated, synthetic data and is the reason no
datastore is introduced; it also means the identifiers restart from `ORD-000001`, `EXE-000001` and
`EXC-000001` each time, so do not treat them as durable references across restarts.

#### Admission capacity

Because nothing here expires and nothing is ever deleted, every structure carries a ceiling. The
ceilings are code constants, not configuration: raising them does not make the design hold more
state safely, it only moves the point at which the heap runs out, and a deployment that needs more
state needs a datastore rather than a larger number here.

| Structure | Ceiling | What is refused at it |
| --- | --- | --- |
| Orders (with their executions) | 10,000 | `POST /orders` — no further order is admitted |
| Settlement exceptions | 10,000 | `POST /orders` — an order that might open a break is not admitted, because an execution must never find nowhere to record one |
| Positions (distinct client and symbol) | 5,000 | `POST /orders` — only an order that would open a *new* holding; an order in a holding the client already has stays admissible, since a fill rewrites that entry and adds no key |
| Audit events | 150,000 | `POST /orders`, `PUT …/assign`, `PUT …/resolve` and `PUT …/settlement-ready` — the step is refused rather than taken unrecorded |

A refusal answers **`503 Service Unavailable`** with the usual `ErrorResponse` body. It is `503`
and not `429` because the exhausted ceiling belongs to the whole service rather than to the calling
client: no caller clears it by slowing down, and no per-client quota was crossed. There is no
`Retry-After` header, because the headroom returns when the service restarts and at no interval
this service could honestly name.

Every refusal is decided **before any state change and before any audit event**, so a `503` leaves
no order, no position movement, no exception and no timeline entry behind — a refusal is
indistinguishable from a request that was never sent. The one qualification is the race the
position claim settles: where several first fills in *distinct* symbols contend for the last free
slot, a loser that had already passed the gate is refused inside the fill step, so its order stops
at `SUBMITTED` with its one submission event and no position is created. Only a ceiling claim can
put a refusal there, because a claim is taken inside the very step it guards and a contender the
gate admitted can still lose it. The resulting-share-range check is not one of them: it and the
fill it admits run inside one lock on that holding, so it is settled before the `clientOrderId` is
reserved and before any order is stored, whatever else is submitting against the same holding.

**Every ceiling is an atomic claim, so every one of them is exact.** Orders, exceptions and
positions are claimed with a compare-and-set counter taken inside the step that would create the
record — for a position, inside the same `compute` that creates its key — and the audit ceiling is
enforced under the monitor that fixes the timeline's size, before an event's ordinal is taken. The
size comparison a flow makes first is a **gate, not a reservation**: it decides *which* refusal a
caller gets, declining the whole flow at its first statement rather than abandoning it part-way,
while the claim inside each structure is the **authority** that no amount of concurrency can pass.
A claim that produced no record — a duplicate `clientOrderId` refused after admission, or controls
that rejected the order before its position was created — is handed back, so a refusal never
retires a slot for the life of the process.

**A ceiling never masks the answer a caller earned.** Identity and legality are settled before
capacity, so a duplicate `clientOrderId` still answers `409` naming the key, an unknown order or
exception id still answers `404`, and an unsupported lifecycle transition still answers `409` — on
a saturated service exactly as on an empty one. A malformed body still answers `400` ahead of all
of them. Only a request that would otherwise have been accepted is answered `503`.

For scale: 10,000 orders, 10,000 exceptions, 5,000 positions and 150,000 audit events retain
roughly 75 MB. The audit ceiling sits above what the entity ceilings imply: an order whose
settlement instructions mismatch and which is then worked to the end consumes ten events — six for
its submission, including the exception's `OPEN`, then one assign, one resolve and two for
settlement-ready — so 10,000 fully worked orders imply 100,000. Keeping the ceiling above that
figure is what lets a fully worked estate record its own last transitions, and what keeps the
record from ever being the thing that refuses a state change.

#### Field limits

The same reasoning bounds what one request may store. The text limits are semantic — what an
identifier and a ticker are — and the two numeric limits are representation ceilings, the range this
module can compute an amount in. A value that crosses any of them answers `400 Bad Request` naming
the field, before any identifier is reserved.

| Field | Limit |
| --- | --- |
| `clientOrderId` | 64 characters |
| `clientId` | 64 characters |
| `symbol` | 12 characters, written in `A-Z`, `0-9`, `.` or `-` after canonicalization |
| `owner` | 64 characters |
| `resolutionNote` | 1024 characters |
| `quantity` | a whole number of shares, greater than zero and at most `9223372036854775807` — the signed 64-bit range the JSON integer is read into. The client's resulting holding is held to that same range, so a quantity that would carry an existing position past it is refused too |
| `limitPrice` | at most `1000000000000.00` per share, a whole number of cents, and inside the amount range every stored and rendered amount shares: at most 12 decimal places, 40 significant digits and a magnitude below `1E+38` |

The order those two are checked in matters, because each refusal carries a different message. A
price is refused as outside the amount range *before* it is converted to cents, since the
conversion is the expansion the range exists to prevent: `1e200000000` is a dozen characters to
send and a two-hundred-million-digit number to hold. So a compact exponent answers
`limitPrice is outside the supported amount range (…)`, a representable price above the ceiling
answers `limitPrice must not exceed 1000000000000.00`, and a price with a third decimal answers
`limitPrice must be a whole number of cents` — never rounded, because rounding `0.001` to `0.00`
would clear every configured notional ceiling and fill at no cost. The one-trillion ceiling is not a
control: a price sitting on it is admitted and then judged by the configured pre-trade limits like
any other. It exists so that this price multiplied by any admissible share count still lands inside
the amount range above, which is what keeps a notional computable.

`quantity` is the one field whose ceiling is not reported as a field violation at its very edge: a
number beyond the signed 64-bit range is not an integer this field can hold at all, so it is refused
while the body is being read — `400` with the fixed text `request body is not valid JSON` — rather
than by the validation that names `quantity`. Everything inside that range — zero, a negative, or a
value that would overflow the client's holding — is a field violation and names the field.

Required text fields are checked with Java's `isBlank` and stored with `strip`, so a value made
only of Unicode whitespace — `U+2003` EM SPACE, for instance — is refused as absent rather than
accepted as present, and surrounding padding never reaches a stored record, an audit reason or the
`clientOrderId` idempotency key. `U+00A0` NO-BREAK SPACE is not whitespace by that definition and
is deliberately left in place.

#### Paging the collection endpoints

Every collection that grows takes `offset` and `limit` query parameters and serializes at most one
page, so no response can duplicate and serialize the whole estate:

| Endpoint | Paged |
| --- | --- |
| `GET /orders` | yes |
| `GET /exceptions` | yes — the page is cut from the filtered set, so a `status` or `owner` query pages its own matches |
| `GET /exceptions/{exceptionId}/events` | yes — `ASSIGNED → ASSIGNED` is a legal edge, so one exception's history grows with every re-assignment |
| `GET /audit` | yes, including when narrowed by `entityType` and `entityId` |
| `GET /positions` | yes |
| `GET /clients` | no — the three client records are written by the startup seed and no code path adds a fourth |
| `GET /orders/{orderId}/events` | no — an order can never carry more than six events, because neither the order nor the post-trade transition table has a self-edge |

Both parameters are clamped rather than validated, so no existing caller breaks: `offset` below
zero becomes zero, an `offset` past the end returns an empty page, and a `limit` that is absent,
zero, negative or above the maximum page size of **500** becomes 500. Ordering is the one each
endpoint already documented — order id, exception id, client then symbol, audit sequence — and it
is established over the whole collection before the page is cut, so consecutive pages neither
overlap nor skip a record.

**What a page does not tell you — an accepted risk.** A page is the same bare JSON array the
endpoint has always returned. It carries no total count, no next-offset and no `Link` header, so a
caller holding 500 records cannot tell a collection that ends there from one that was truncated
without asking for the next page. `GET /audit` is where that is most visible: it returns at most 500
of a timeline that can hold 150,000 events, so reading the timeline in full is a traversal rather
than one call.

**Traversal by `offset` is the intended access pattern.** Walk it with an *effective* limit — a
number from 1 to 500 — and step and compare against that same number, never against a larger one you
asked for: a `limit` of 1000 is clamped to 500, and a walk that read 500 records and expected 1000
would treat a full page as a short one and stop with the rest unread. Sending no `limit` at all is
the same case, since an absent value is clamped to 500 too.

Over a collection nothing is writing to, the walk is then exact: the ordering is established over
the whole collection before the page is cut, so incrementing `offset` by the effective limit neither
repeats nor misses a record, and the first page shorter than that limit — an empty page included —
is the end. That short page is the termination condition; there is nothing else to consult.

Under concurrent writes it is best-effort, because a page is a cut and not a snapshot. Orders,
exceptions and audit events are ordered by monotonic identifier or sequence, so every new record
lands *after* the cursor and a walk in progress picks it up on a later page. Positions are ordered
by client and then symbol, so a first fill in a new symbol can insert a key *before* the cursor:
that shifts everything after it one offset higher, which both repeats a record the walk already read
and can leave the newly inserted key unvisited. Nothing is ever deleted, so no record that existed
when the walk began can vanish from it — but a `/positions` walk over a service taking orders is not
a consistent picture of any single instant, and a caller that needs one has to re-read from
`offset=0` while nothing is filling.

```bash
# Read the whole timeline a page at a time. Stop at the first page shorter than the limit sent.
OFFSET=0
while :; do
  PAGE=$(curl -k -s -u stock:trader \
    "https://localhost:9443/execution-control/audit?offset=$OFFSET&limit=500")
  COUNT=$(printf '%s' "$PAGE" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
  printf '%s\n' "$PAGE"          # …or hand the page to whatever consumes it
  [ "$COUNT" -lt 500 ] && break
  OFFSET=$((OFFSET + 500))
done
```

No count-and-next-offset envelope is offered, and that is a decision rather than an omission:
wrapping the records would change every collection response from an array into an object, breaking
every existing caller and every example in this document, and it would widen an API surface whose
paging parameters are themselves still awaiting ratification — see
[Deviations from the frozen implementation plan](#deviations-from-the-frozen-implementation-plan).
For a simulated, in-memory service whose whole estate is bounded at 150,000 events, the traversal
above is sufficient and the missing metadata is accepted. A consumer that genuinely needs an exact
total, or a stable cursor across concurrent writes, needs that envelope: raise it as an API change
with the module owner rather than inferring a total by probing offsets.

## Deviations from the frozen implementation plan

The plan this module was built to is frozen, and it pins the Liberty release, two test dependencies,
the HTTP perimeter, an exhaustive 71-file inventory and an exhaustive endpoint-and-status matrix.
Eight delivered facts sit outside those pins. Every one of them came out of the module's security
review, each is deliberate, and **none has been ratified by a plan owner** — so they are recorded
here as ratification pending, with what reverting each one would cost. This register is the evidence
for that decision, not the decision itself: ratifying a row is a human act, and until it happens the
row's status is the one stated here.

| # | What the plan pins | What is delivered instead | Where it lives | Why it diverges |
| --- | --- | --- | --- | --- |
| 1 | Liberty **25.0.0.9** as both the container base image and the integration-test assembly, the latter as `com.ibm.websphere.appserver.runtime:wlp-webProfile10` (§0.1.3, §0.3.1, §0.6.1, §0.8.1) | Liberty **26.0.0.9** in both: the digest-pinned base image `icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal`, and the test assembly `io.openliberty:openliberty-runtime:26.0.0.9` | `Dockerfile` `FROM`; the `liberty-maven-plugin` `<assemblyArtifact>` in `pom.xml`; quoted in [Container](#container) and [Build and test](#build-and-test) | Liberty 17.0.0.3 through 26.0.0.7 sit inside the servlet request/response smuggling advisories **CVE-2026-15064** (CWE-444, inconsistent interpretation of HTTP requests, CVSS 3.1 **8.7 High**) and **CVE-2026-15325**; the fixes first ship in 26.0.0.8. The Web Profile assembly publishes no release past 26.0.0.4 — itself inside that range — so the full runtime assembly is the only way to hold the test server at the release the image runs |
| 2 | `org.apache.cxf:cxf-rt-rs-client` **4.1.1** (§0.3.1) | **4.1.8**, test scope | `pom.xml` test dependencies | 4.1.1 brings `cxf-core` 4.1.1, affected by **CVE-2026-49875** / GHSA-gw93-jmqp-6572 (XXE, CWE-611) and fixed in 4.1.6 |
| 3 | `org.eclipse.parsson:parsson` **1.1.7** (§0.3.1) | **1.1.9**, test scope | `pom.xml` test dependencies | **CVE-2026-9563**: 1.1.5 through 1.1.7 impose no JSON input-size limit (CWE-400) |
| 4 | A single `httpEndpoint`, mirroring the broker's (§0.6.1) | Two endpoints, one per scheme, each with its own `<headers>` policy, plus `httpOptions removeServerHeader="true"` and `webContainer disableXPoweredBy="true"` | `src/main/liberty/config/server.xml`; described under [Standalone Kubernetes deployment](#standalone-kubernetes-deployment) | The mirrored endpoint advertised the server signature, `X-Powered-By` and `$WSEP` and set no `X-Content-Type-Options`, cache policy or HSTS (CWE-200, CWE-693). Liberty scopes a header policy to an endpoint and not to a scheme, so one endpoint serving both ports cannot assert HSTS to TLS clients alone — and RFC 6797 has a cleartext client ignore it |
| 5 | An exhaustive 71-file inventory, with three typed lifecycle failures and three `ExceptionMapper`s (§0.2.3, §0.6.1) | 74 files: a fourth typed failure, a fourth mapper, and one package-private paging helper | `lifecycle/CapacityExceededException.java`, `rest/CapacityExceededExceptionMapper.java`, `rest/PageBounds.java` | The ceilings under [Admission capacity](#admission-capacity) need a refusal no existing mapper expresses, and one clamped page-bounds helper keeps the 500-record policy in a single place. Both answer CWE-770 / CWE-400 — unbounded growth in a process that evicts nothing |
| 6 | The status matrix 201 and 200 on success, 400, 401, 403, 404 and 409 on failure (§0.7.1, §0.8.4) | The same, plus **`503 Service Unavailable`** for an exhausted ceiling | `rest/CapacityExceededExceptionMapper.java`; documented under [Admission capacity](#admission-capacity) | The same root cause as row 5. `503` rather than `429` because the exhausted ceiling belongs to the service and not to the caller, and it is decided after identity and legality, so no caller loses the `400`, `404` or `409` it earned |
| 7 | Fourteen handlers whose only query parameters are `status`, `owner`, `entityType` and `entityId` (§0.7.1) | The same fourteen handlers, five of them additionally accepting optional `offset` and `limit` | `rest/{OrderResource,SettlementExceptionResource,AuditResource,ReferenceDataResource}.java`; documented under [Paging the collection endpoints](#paging-the-collection-endpoints) | A full read serialized the whole estate. The parameters are optional and clamped rather than validated, so a caller that sends neither sees exactly the body it saw before for any collection under 500 records |
| 8 | The same 71-file inventory and its three `ExceptionMapper`s (§0.2.3, §0.6.1) | 76 files: two further mappers, `JsonbExceptionMapper` and `ProcessingExceptionMapper`, so a request body JSON-B cannot read answers `400` with the fixed text `request body is not valid JSON` instead of the runtime's default `500` carrying the deserializer's own message | `rest/JsonbExceptionMapper.java`, `rest/ProcessingExceptionMapper.java` | The plan's `400` for a bad request (§0.7.1) had no mapper behind it for a body that never reached validation, and the default answer named internal types and fields — CWE-209. `400` is already in the plan's status matrix, so this adds files, not a status |

### What ratification does and does not decide

Functionally these eight change nothing the plan specifies. The same three Liberty features are
enabled, the ports, context root, WAR name and probe paths are unchanged, the fourteen handlers and
their success codes are unchanged, and the module's own gate — unit tests, integration tests against
a started Liberty server, and both line-coverage gates — is green on the delivered tree. Rows 1 to 4
are a runtime and a perimeter the plan could not have named, because the advisories post-date it;
rows 5 to 7 are the smallest surface that bounds a service holding all of its state in memory,
and row 8 closes the one client-triggerable `500` the plan's status matrix never listed.

**No row may be reverted to bring the code back to the plan.** Reverting row 1 returns the service
to a Liberty release inside the smuggling-advisory range; rows 2 and 3 to a test client and parser
with known CVEs; row 4 to advertising its product and version and asserting no content-type or cache
policy over authenticated JSON; rows 5 to 7 to stores that grow until the heap is gone and
collection reads that serialize everything they hold; row 8 to a malformed body answered `500`
with the deserializer's text.

What an owner has to settle is narrower than the list looks, because some rows are one decision:
row 1 is a single runtime choice spanning the image and the test assembly, which must stay in step
on every upgrade, and rows 5 and 6 are a single refusal design — the status is what the mapper
exists to return. Declining a row is only sound if its replacement carries the same protection: for
row 1 that means Liberty 26.0.0.8 or later whatever assembly it arrives in, for rows 5 to 7 an
eviction policy or a datastore, never the removal of the ceilings, and for row 8 any mapper that
answers the same fixed `400`.

**Ratifying a row** means recording a decision against its number — accepted as a deviation from
the plan, or accepted as an addition to the plan's file and API inventories for rows 5 to 8 — with
the owner's name and the date, in whatever register governs the plan; the plan itself is frozen and
is not edited to accommodate any of this. The rows are numbered so a decision can cite one without
restating it, and a ratified row's status belongs here beside its number so this document and that
decision cannot drift apart. Two facts an owner should have in hand when deciding: nothing in rows
1 to 8 changes an interface a caller or another service depends on, and every one of them is
exercised by the module's own gate, which is green on the delivered tree.
