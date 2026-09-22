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
| 2 | `/orders` | GET | `StockViewer`, `StockTrader` | 200 | every order, seeded and live |
| 3 | `/orders/{orderId}` | GET | `StockViewer`, `StockTrader` | 200 | one order |
| 4 | `/orders/{orderId}/events` | GET | `StockViewer`, `StockTrader` | 200 | that order's `ORDER` and `POST_TRADE` audit events, in `sequence` order |
| 5 | `/exceptions` | GET | `StockViewer`, `StockTrader` | 200 | settlement exceptions, optionally filtered by `status` and `owner` |
| 6 | `/exceptions/{exceptionId}` | GET | `StockViewer`, `StockTrader` | 200 | one settlement exception |
| 7 | `/exceptions/{exceptionId}/events` | GET | `StockViewer`, `StockTrader` | 200 | that exception's `EXCEPTION` audit events |
| 8 | `/exceptions/{exceptionId}/assign` | PUT | `StockTrader` | 200 | the exception, now `ASSIGNED` to the owner in the body |
| 9 | `/exceptions/{exceptionId}/resolve` | PUT | `StockTrader` | 200 | the exception, now `RESOLVED` with the resolution note |
| 10 | `/exceptions/{exceptionId}/settlement-ready` | PUT | `StockTrader` | 200 | the exception, now `SETTLEMENT_READY`; the parent order follows |
| 11 | `/audit` | GET | `StockViewer`, `StockTrader` | 200 | the whole audit timeline, optionally filtered by `entityType` and `entityId` |
| 12 | `/controls` | GET | `StockViewer`, `StockTrader` | 200 | the five effective control values as configuration supplied them, `source = CONFIG` |
| 13 | `/clients` | GET | `StockViewer`, `StockTrader` | 200 | the three synthetic clients with both settlement instructions each |
| 14 | `/positions` | GET | `StockViewer`, `StockTrader` | 200 | the synthetic positions, as executions have left them |

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

Request bodies: `POST /orders` takes `{clientOrderId, clientId, symbol, side, quantity, limitPrice}`
— `side` is `BUY` or `SELL`, `quantity` and `limitPrice` must be positive, and `clientOrderId` is
the idempotency key. `PUT …/assign` takes `{owner}`; `PUT …/resolve` takes
`{owner, resolutionNote}` with `owner` optional once the exception is assigned;
`PUT …/settlement-ready` takes no body.

`POST /orders` returns **`201` whether the terminal state is `EXECUTED` or `REJECTED`**, because the
order resource exists and is retrievable either way. Read `status`, `rejectionReason` and
`controlResults` on the returned entity to see which happened.

Status codes on failure:

| Condition | Code |
| --- | --- |
| No credentials presented | `401` |
| Authenticated `StockViewer` on a mutating verb (POST/PUT) | `403` |
| Validation failure — missing field, non-positive `quantity`/`limitPrice`, unknown `clientId`, blank `owner` or `resolutionNote` | `400` |
| Unknown order or exception id | `404` |
| Duplicate `clientOrderId`, or an unsupported lifecycle transition | `409` |

Every error body is `{status, error, message, path}`, with `message` naming the offending field,
identifier or state.

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
(`liberty-maven-plugin` 3.11.5, assembly `wlp-webProfile10:25.0.0.9`): the server is created and
its features installed at `prepare-package`, started and the WAR deployed at
`pre-integration-test`, and stopped at `post-integration-test`. The test server runs with
`AUTH_TYPE=none`, so `includes/none.xml` is active and the integration tests authenticate with HTTP
Basic as `stock:trader` (`StockTrader`) and `read:only` (`StockViewer`). It binds port 9080; move it
with `-Dliberty.var.default.http.port=<port>`, which shifts the server port and the tests'
`liberty.test.port` expectation together:

```bash
mvn -B clean verify -Dliberty.var.default.http.port=19080 -Dliberty.var.default.https.port=19443
```

**Coverage target:** at least 80% line coverage on the `control` and `lifecycle` packages — the new
business logic — enforced by the JaCoCo `check` rule at `verify` and readable from
`target/site/jacoco/jacoco.xml` or the HTML report. Coverage is measured in the Surefire JVM only;
the integration-test fork and the Liberty JVM are deliberately not instrumented, so client-side
activity cannot inflate the figure.

## Container

Maven must run before `docker build` — the image compiles nothing and copies the WAR that Maven
produced:

```bash
mvn -B clean package -DskipTests && \
  docker build -t execution-control:local . && \
  docker run -d --name ec -p 9080:9080 -e AUTH_TYPE=none execution-control:local
```

The three steps are chained with `&&` deliberately: run them separately and a failed Maven build
leaves `docker build` copying whatever WAR was in `target/` beforehand, and a failed `docker build`
leaves `docker run` starting the previous image under the same tag. Chained, the first failure
stops the pipeline.

The base image is `icr.io/appcafe/open-liberty:25.0.0.9-full-java21-openj9-ubi-minimal` — the same
base the sibling Liberty services use. It carries every Liberty feature, so the `microProfile-7.1`,
`mpTelemetry-2.1` and `appSecurity-5.0` features `server.xml` enables need no install step. The
module's release-17 class files run unchanged on the image's Java 21 runtime.

Add `-e OTEL_SDK_DISABLED=true` for a local run to suppress the OTLP exporter's retries against the
cluster-local collector endpoint. Verify the container with:

```bash
curl -s http://localhost:9080/health/ready
curl -s -u stock:trader http://localhost:9080/execution-control/controls
docker rm -f ec
```

## Standalone Kubernetes deployment

This service is not managed by the StockTrader operator or its Helm chart, so it is deployed by
hand. Push the image to a registry your cluster can pull from, then apply the manifest below. It
deploys into the `stocktrader` namespace — create that namespace first if it does not exist, or
substitute your own.

Save the manifest as `execution-control.yml`, replacing the `image:` placeholder with your own
repository and tag:

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

#Deploy the pod
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
      containers:
      - name: execution-control
        image: <your-image-repo>/ibmstocktrader/execution-control:latest
        ports:
          - containerPort: 9080
          - containerPort: 9443
        imagePullPolicy: Always
        env:
          # Identity. Every value here is the shipped default; the service also starts with no env at all.
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
#Deploy the service
apiVersion: v1
kind: Service
metadata:
  name: execution-control-service
  labels:
    app: execution-control
spec:
  type: ClusterIP
  ports:
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

The `Service` is `ClusterIP` — the minimal exposure — because nothing in the estate routes to this
service: no trader page, no broker adapter, no Istio route. Substituting `type: NodePort` with the
same two ports also works and is what `portfolio-assistant` uses, if you would rather reach it
without a port-forward.

## Reviewing the service

Forward the service port into your shell:

```bash
kubectl -n stocktrader port-forward svc/execution-control-service 9080:9080
```

That gives the API base URL `http://localhost:9080/execution-control` and the health URLs
`http://localhost:9080/health/started`, `http://localhost:9080/health/ready` and
`http://localhost:9080/health/live`.

Authenticate according to the deployment's `AUTH_TYPE`. With the default `basic`, present the same
`Authorization: Bearer` token the estate already issues (the trader front end's `jwtSso` builder
issues a compatible one, readable from the browser's cookies):

```bash
curl -H "Authorization: Bearer <jwt>" http://localhost:9080/execution-control/exceptions
```

With `AUTH_TYPE=none` — development and tests only — HTTP Basic against the development registry
works instead:

```bash
curl -u stock:trader http://localhost:9080/execution-control/exceptions
```

Submit an order (the trader persona). The response is the order in its terminal state, with all
four control results attached:

```bash
curl -u stock:trader -X POST http://localhost:9080/execution-control/orders \
  -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"C1","clientId":"INST-001","symbol":"SYNA","side":"BUY","quantity":100,"limitPrice":100.00}'
```

Submit a comparable order for `INST-003` and the execution opens a settlement exception, because
that client's counterparty SSI disagrees with the firm's:

```bash
curl -u stock:trader -X POST http://localhost:9080/execution-control/orders \
  -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"C2","clientId":"INST-003","symbol":"SYNA","side":"BUY","quantity":300,"limitPrice":100.00}'
curl -u stock:trader "http://localhost:9080/execution-control/exceptions?status=OPEN"
```

Work that exception through to settlement-ready (the operations-analyst persona), substituting the
`exceptionId` the list returned:

```bash
curl -u stock:trader -X PUT http://localhost:9080/execution-control/exceptions/EXC-000002/assign \
  -H 'Content-Type: application/json' \
  -d '{"owner":"ops.analyst"}'

curl -u stock:trader -X PUT http://localhost:9080/execution-control/exceptions/EXC-000002/resolve \
  -H 'Content-Type: application/json' \
  -d '{"owner":"ops.analyst","resolutionNote":"Counterparty safekeeping account corrected with custodian"}'

curl -u stock:trader -X PUT http://localhost:9080/execution-control/exceptions/EXC-000002/settlement-ready
```

Then read the evidence — the effective rules, the reference data and the audit timeline:

```bash
curl -u stock:trader http://localhost:9080/execution-control/controls
curl -u stock:trader http://localhost:9080/execution-control/clients
curl -u stock:trader http://localhost:9080/execution-control/positions
curl -u stock:trader http://localhost:9080/execution-control/audit
curl -u stock:trader "http://localhost:9080/execution-control/audit?entityType=EXCEPTION&entityId=EXC-000002"
curl -u read:only http://localhost:9080/execution-control/orders
```

The last call shows the read-only role at work; the same credentials on any `POST` or `PUT` answer
`403`.

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
