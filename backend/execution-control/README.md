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
| 1 | `/orders` | POST | `StockTrader` | 201 | the submitted order in its terminal state, with all four control results, and a `Location` header carrying the path that addresses it |
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
at most 500 records each, so none of them returns a complete collection by contract. Each of those
five answers with the page metadata that makes the truncation visible and traversable —
`X-Total-Count` (the size of the whole collection the page was cut from, after any filter),
`X-Page-Offset`, `X-Page-Limit` (the page size actually applied, after clamping) and an RFC 8288
`Link` carrying `first`, `prev`, `next` and `last` — so a caller holding a full page can tell a
collection that ends there from one that was cut.
[Paging the collection endpoints](#paging-the-collection-endpoints) is where the clamping rule, the
metadata, the traversal pattern and the one thing a page still does not promise are set out. Rows 4
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
  `SETTLEMENT_READY`; `owner` matches the assigned owner. Both are optional, and a blank value on
  either means "no filter". `status` is stripped and upper-cased before matching — the same
  canonicalization `side` and `symbol` get on the submit path — so `?status=open` narrows to `OPEN`.
  Any other token is refused with `400` and the message `status must be one of OPEN, ASSIGNED,
  RESOLVED, SETTLEMENT_READY`: a filter has no nearest honest reading, and silently ignoring it
  would answer a narrowed query with the whole collection.
- `GET /audit?entityType=&entityId=` — `entityType` is `ORDER` or `EXCEPTION`. Both are optional;
  supplied together they return one entity's timeline. A value matching nothing returns an empty
  page rather than an error, because the audit collection itself always exists.
- `offset` and `limit` on each of the five paged collections — `GET /orders`, `GET /exceptions`,
  `GET /exceptions/{exceptionId}/events`, `GET /audit` and `GET /positions`. Both are optional and
  are clamped rather than refused — including when the value is not a number at all — and a page
  holds at most 500 records; on `/exceptions` and `/audit` the page is cut from the filtered set, so
  a `status`, `owner`, `entityType` or `entityId` query pages its own matches, and the page links
  carry that filter forward. See
  [Paging the collection endpoints](#paging-the-collection-endpoints).

Request bodies: `POST /orders` takes `{clientOrderId, clientId, symbol, side, quantity, limitPrice}`
— `side` is `BUY` or `SELL`, `quantity` and `limitPrice` must be positive and inside the per-field
bounds under [Field limits](#field-limits), and `clientOrderId` is the idempotency key.
`PUT …/assign` takes `{owner}`; `PUT …/resolve` takes `{owner, resolutionNote}` with `owner`
optional once the exception is assigned; `PUT …/settlement-ready` takes no body.

`POST /orders` returns **`201` whether the terminal state is `EXECUTED` or `REJECTED`**, because the
order resource exists and is retrievable either way. Read `status`, `rejectionReason` and
`controlResults` on the returned entity to see which happened. The `201` carries
`Location: /execution-control/orders/{orderId}`, so a client learns where the record lives from the
response itself rather than by reassembling the path around an id parsed out of the body. That
header is a path and carries no scheme and no host by design: the scheme and authority of a request
URI are supplied by the caller's own `Host` and `X-Forwarded-Proto` headers, so building the value
from them would echo any address a caller chose back as this service's own. A client resolves it
against the URL it sent the request to, which is the only address it has any reason to trust.

Status codes on failure:

| Condition | Code |
| --- | --- |
| No credentials presented | `401` |
| Authenticated `StockViewer` on a mutating verb (POST/PUT) | `403` |
| Validation failure — missing field, non-positive `quantity`/`limitPrice`, unknown `clientId`, blank `owner` or `resolutionNote` | `400` |
| A `status` filter value on `GET /exceptions` that is not one of the four workflow states | `400` |
| Request body that is not one complete JSON object of single-valued fields — truncated or non-JSON text, an empty body, an array where an object belongs, content appended after the end of the document, an entity shorter than its declared `Content-Length`, or a declared field carrying a JSON array or object where one value belongs | `400` |
| Unknown order or exception id | `404` |
| Duplicate `clientOrderId`, or an unsupported lifecycle transition | `409` |
| An in-memory ceiling is exhausted, so a request that would otherwise have been accepted cannot be recorded. The `message` names the `*_CAPACITY` variable to raise; raise it and restart, or restart to clear | `503` |
| A request no resource ever sees — a URI the web container refuses to decode (`400`), no resource at the path (`404`), no resource accepting the method (`405`) or the request's media type (`415`), nothing acceptable to the `Accept` header (`406`) | `400`, `404`, `405`, `406`, `415` |

Every error body is `{status, error, message, path}`, with `message` naming the offending field,
identifier or state — except for a body that could not be read, whose `message` is the fixed text
`request body is not valid JSON`: the parser's own description names internal types and fields and
is never relayed. The `503` is the one code the plan this module was built to does not list; it
exists because the stores are bounded, it is decided last — after `400`, `404` and `409` — and it is
described under [Admission capacity](#admission-capacity) and recorded under
[Deviations from the frozen implementation plan](#deviations-from-the-frozen-implementation-plan).

**Runbook for a `503` on `POST /orders`** (or on any `PUT …/assign`, `…/resolve` or
`…/settlement-ready`): one of the four in-memory ceilings is exhausted, and the `message` names the
environment variable that governs it — `ORDER_CAPACITY`, `SETTLEMENT_EXCEPTION_CAPACITY`,
`POSITION_CAPACITY` or `AUDIT_EVENT_CAPACITY`. The remedy is to raise that variable and restart, or
simply to restart: all state is in memory, so a restart clears it either way and returns the whole
ceiling. Nothing evicts and nothing expires, so the headroom never returns on its own. To see this
coming rather than discover it here, read `admission` in the health data — `ACCEPTING` or
`SATURATED` — beside the `orderHeadroom`, `exceptionHeadroom`, `positionHeadroom` and
`auditEventHeadroom` figures on `/health/live` and `/health/ready`; the service also logs a warning
once when a structure falls to a tenth of its ceiling and once when it saturates. The sizing
figures, the start-up validations and the full data-key list are under
**Operational notes > Admission capacity**.

That envelope covers the last row too, which no resource method decides. Those statuses are settled
before or after the application runs — by the web container while it resolves the URI, or by the
Jakarta REST runtime while it matches a resource — and each layer answers with its own default: a
container-rendered HTML page that names the runtime class and line number that threw, or a status
with no body at all. Neither is parseable by a client written against this API, and the first
discloses internals. So `web.xml` maps those five statuses (and `500`) to
`rest/ContainerErrorServlet`, and `rest/WebApplicationExceptionMapper` gives the envelope to the
statuses the Jakarta REST runtime raises for itself, both producing the same
`{status, error, message, path}` from the same `ErrorResponse` type as every other refusal. The
`message` is fixed per status and says what to change rather than what failed — a URI carrying an
encoded path separator (`%2F`, `%5C`), which the web container refuses before any application code
runs, is answered `400`
`{"error":"Bad Request","message":"The request URI contains an encoded path separator, which this service does not accept","path":"/exceptions/EXC%2F000001","status":400}` —
and headers the runtime computed are carried across, so a `405` still names the methods it will
accept in `Allow`. That answer holds however the encoded separator is arranged, including one that
would climb out of the context root if it were decoded, such as `/orders/..%2f..%2fcontrols`, and
holds on every request rather than on the first: `-DinvocationCacheSize=0` in
`src/main/liberty/config/jvm.options` turns off the web container's invocation cache, which would
otherwise keep a servlet wrapper for a URI the container had just refused and, on the next identical
request, derive the path info by cutting an eighteen-character context root off a URI that
canonicalizes to nine — answering with its own `500` page rather than this one. That file's comment
carries the detail; the property is load-bearing and costs nothing measurable on a WAR with one
servlet and no filters. `webContainer displayCustomizedExceptionText` in `server.xml` bounds the
same class of page independently, so any exception the container still decides for itself renders
one fixed sentence instead of the class, line and message that threw.

One refusal sits below even that, and nothing inside this WAR can reach it — no error page, no
filter, and no Liberty setting, because the web container writes the response itself before it has
selected a web application to route it to. A URI it cannot decode is refused there: an encoded null
byte (`%00`), an encoded CR or LF, a malformed escape such as `%zz`, or a `..%2F` sequence that
climbs above the server root (`/orders/..%2F..%2F..%2F..%2Fetc%2Fpasswd`). The answer is `400` and
the body is the runtime's, in one of two shapes — for a JSON client, the single key
`{"error_message" : "CWWWC0005I: The request URI has invalid or improperly encoded characters: [/execution-control/orders/&#37;00]"}`
as `application/json`; for `Accept: text/html`, the same text as `<H1>…</H1><BR>`. It is the one
answer this service publishes that is not the envelope above, and the only place a Liberty message
id is observable: the caller's own URI is echoed back with every character HTML-entity-escaped
(`&#37;` for the `%` above, `&lt;` for a `<`), no class, stack, product version or service data
appears, nothing is written to the log, and the endpoint's `X-Content-Type-Options: nosniff` and
`Cache-Control: no-store` still apply. Because the refusal precedes the security constraints, an
unauthenticated caller can reach it. A deployment that must not expose the message id should refuse
malformed URIs at the gateway in front of the service — an ingress or mesh rule rejecting a request
line containing `%00`, `%0D`, `%0A` or a malformed escape — since only a hop ahead of Liberty can
answer in its place.

`401` and `403` are the two failures deliberately left as the container renders them. They are
answered by the security collaborator alongside the authentication challenge, before any of this
service's code is reachable, and rewriting them would change the authentication surface rather than
the error surface. A `401` therefore carries no body and a `403` carries the container's short
`Error 403: AuthorizationFailed` text.

Both personas map to the `StockTrader` role — the trader who submits orders and the operations
analyst who works exceptions alike — because the estate defines only `StockTrader` and
`StockViewer` and this service introduces no new role. `StockViewer` may read everything.

`/health/*` and `/openapi` are runtime endpoints served **outside** the WAR's context root, so the
role constraints above do not gate them — and neither does the error surface above, which belongs to
the WAR. `/openapi` is served by `mpOpenAPI-4.1`, part of the `microProfile-7.1` umbrella the
service enables — YAML by default, JSON with `Accept: application/json`; the same umbrella publishes
a `/jwt/` web app that has nothing to do with this service. What each of them answers, and which of
them to keep off the perimeter, is under
[The runtime endpoints beside the application](#the-runtime-endpoints-beside-the-application).

The published contract carries every status and body in the two tables above, not only the success
ones: `POST /orders` is documented as `201` with its `Location` header, the error statuses each
operation can return are declared against the `ErrorResponse` schema, and the paged collections
declare their metadata headers. That is declared rather than inferred, because the error bodies are
produced by `ExceptionMapper` providers the contract generator never sees — undeclared, it offered a
single `200` per operation and no error model, so a client generated from it had no type for a
refusal and treated the real `201` as unexpected.

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
| `MAX_ORDER_NOTIONAL` | decimal | `1000000.00` (`microprofile-config.properties`) | Ceiling on one order's notional (`quantity × limitPrice`). Strictly greater rejects; a value exactly at the threshold passes. Must be at least `0.01` — see [Unusable limits fail the start](#unusable-limits-fail-the-start) |
| `MAX_POSITION_NOTIONAL` | decimal | `5000000.00` (`microprofile-config.properties`) | Ceiling on the absolute resulting position notional for the client and symbol. The whole resulting quantity is marked at the order's `limitPrice`, so the value evaluated equals the `positionNotional` the position will carry if the order fills. Must be at least `0.01` |
| `FAT_FINGER_NOTIONAL_THRESHOLD` | decimal | `2500000.00` (`microprofile-config.properties`) | Firm-wide anomaly ceiling on one order's notional, evaluated independently of `MAX_ORDER_NOTIONAL`. Must be at least `0.01` |
| `RESTRICTED_SYMBOLS` | comma-separated list | `RSTRA,RSTRB` (`microprofile-config.properties`) | Symbols that may not be traded, matched against the trimmed and upper-cased order symbol. The defaults are synthetic tickers. An empty value is a valid setting and restricts nothing |
| `EXCEPTION_SLA_HOURS` | integer | `24` (`microprofile-config.properties`) | Hours from exception opening to its SLA deadline: `slaDeadline = openedAt + EXCEPTION_SLA_HOURS`, fixed when the exception opens. `ageHours` and `slaBreached` are derived from that deadline on every read, against the status-dependent reference instant set out under [Exception ageing and the SLA clock](#exception-ageing-and-the-sla-clock). `0` is a valid policy — an exception is then due the instant it opens — but a negative value is refused, since it would put every deadline before its own opening |

#### Unusable limits fail the start

The three notional ceilings must each be at least `0.01`, and `EXCEPTION_SLA_HOURS` must not be
negative. A ceiling of zero or less is not a strict configuration but an unusable one: no order with
a positive quantity and a positive price can sit under it, so the service would reject every order
ever submitted — the three seeded ones included — while readiness, which reads no limit, went on
reporting `UP`. A sub-cent ceiling is the same thing arrived at differently, because the effective
ceiling is the one normalized to two decimals: `0.001` becomes `0.00`.

Such a value is therefore refused when the limits are built, which is during application start-up:
`SeedDataLoader` submits the seeded orders from the `@Initialized(ApplicationScoped.class)`
observer, and that forces the `ControlLimits` producer. The outcome is the same failed start a
non-convertible value produces — the server comes up (`CWWKF0011I`) and the application does not, so
nothing is ever served against a limit nobody can satisfy:

| Surface | Response |
| --- | --- |
| `/health/started`, `/health/ready` | `503` `{"status":"DOWN","checks":[]}` |
| `/health/live` | `200` `{"status":"UP","checks":[]}` — the runtime is alive; the application is not installed |
| every `/execution-control/…` path | `404`, carrying no exception text |

`messages.log` names the offending variable three times over: a `SEVERE` line from
`ControlLimitsProducer` listing the whole submitted set, then `SRVE0283E`/`SRVE0265E` carrying the
refusal out of the context initialization, then `CWWKZ0012I: The application ExecutionControl was
not started.` The refusal itself is one line, and it is the one to act on:

```
MAX_ORDER_NOTIONAL must be greater than zero, not -1.00
MAX_ORDER_NOTIONAL must be at least 0.01, not 0.001
EXCEPTION_SLA_HOURS must not be negative, not -5
```

Fix the variable and restart; nothing persists, so a corrected start-up is a clean one. There is no
kill switch here by design — a deployment that should accept no orders is one that should not be
running, and a limit nobody can satisfy is indistinguishable at the API from a service that has
silently stopped working.

### Server and identity

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `AUTH_TYPE` | string | `basic` (`server.xml`) | Selects `includes/<AUTH_TYPE>.xml` — one of `basic`, `ldap`, `oidc`, `none` |
| `JWT_AUDIENCE` | string | `stock-trader` (`server.xml`) | Expected JWT audience, unchanged from the estate |
| `JWT_ISSUER` | string | `http://stock-trader.ibm.com` (`server.xml`) | Expected JWT issuer, unchanged from the estate |
| `OIDC_JWKS_URL` | URL | none — required only when `AUTH_TYPE=oidc` | JWKS endpoint of the OIDC provider, referenced by `includes/oidc.xml`. Not sensitive; supply it as a plain `env` entry |
| `LTPA_KEYS_PASSWORD` | password | the inherited demonstration value, held `{xor}`-encoded in `server.xml` and not printed here — see [Inherited demonstration security material](#inherited-demonstration-security-material) | Password the server encrypts and reads its LTPA key file with. It exists as a variable because the attribute has no default in Liberty, and a server that invents one per boot cannot read the key file it wrote on the previous boot — see [Authentication across a restart](#authentication-across-a-restart). Demonstration material, and the same value the two keystores carry: set your own in any real deployment, keep it identical across replicas, and delete the existing key file when you change it. Plaintext, `{xor}` and `{aes}` are all accepted — the attribute decodes after the variable is substituted — so a `securityUtility encode` value works here as it does on the keystores |
| `TRACE_SPEC` | string | `*=info` (`server.xml`) | Liberty trace specification |
| `MAX_REQUEST_SIZE_BYTES` | integer | `8192` (`server.xml`) | Ceiling on one incoming message — request line, headers and body together. Raise it only for a deployment whose identity provider issues unusually large bearer tokens |
| `DEFAULT_HTTP_PORT` (`default.http.port`) | integer | `9080` (`server.xml`) | HTTP listener port. Also overridable in the build with `-Dliberty.var.default.http.port` |
| `DEFAULT_HTTPS_PORT` (`default.https.port`) | integer | `9443` (`server.xml`) | HTTPS listener port. Also overridable with `-Dliberty.var.default.https.port` |

Three transport behaviours come with those rows, and none of them is visible in the API contract
because each is settled by the HTTP channel or by a request filter ahead of every resource method.

**An oversize message is refused, and how it is answered depends on how it was framed.** A request
beyond `MAX_REQUEST_SIZE_BYTES` is refused either way, nothing is created — no order, no identifier
reservation, no audit event — and the status is `413 Request Entity Too Large` either way, but the
body differs because the two framings are discovered at different moments:

- A message declaring a `Content-Length` over the limit is refused as its headers are parsed, in
  about two milliseconds, with an **empty body** and a closed connection. It is the one refusal this
  service makes that carries no `ErrorResponse`, because no application code ran to produce one —
  which is the point of bounding the message here, where the alternative is the server allocating a
  megabyte of JSON to find one order in it.
- A `Transfer-Encoding: chunked` message declares no length, so the limit can only be applied as the
  body is read, after the application has asked for the entity. That refusal is answered `413` with
  the ordinary `{status, error, message, path}` envelope and the message `request message exceeds the
  configured size limit, so the body was not read`. Chunked bodies inside the limit are accepted
  normally.

The ceiling covers the whole message, so the body allowance is what is left after the caller's
headers: with an ordinary header block of a few hundred bytes the default leaves close to 8 KB for
the body, against the 1,047 bytes the largest legitimate body needs (a 64-character owner and a
1024-character resolution note).

**A compressed request body is refused, `415`.** No endpoint accepts a `Content-Encoding` other than
`identity`: a coded body is answered `415 Unsupported Media Type` with the standard envelope, the
message `request bodies must not be compressed; send the body uncompressed and omit
Content-Encoding`, and an `Accept-Encoding: identity` header naming what to send instead. The reason
is arithmetic rather than taste. The size ceiling counts the bytes that arrive, so a content coding
would let a caller spend 7 KB of wire on 7 MB of heap — a thousand-fold amplification of the one
bound this service places on an incoming request — while no legitimate caller needs it, the largest
body any endpoint takes being about a kilobyte of JSON. The channel's own inbound decompression is
therefore switched off as well (`AutoDecompression="false"`), so nothing inflates a request body
even before the filter refuses it. Request and response compression are independent: the next
paragraph still applies in full.

**Responses are compressed when the caller offers an encoding.** Every endpoint serves
`application/json`, and each record repeats the same constant disclaimer, so a collection page is
unusually compressible: a 500-event `GET /audit` page of about 235 KB is served in under 10 KB with
`Accept-Encoding: gzip`, roughly a 25-fold reduction, and a 500-order `GET /orders` page of about
145 KB in about 5 KB.
Responses carry `Vary: Accept-Encoding`, and a caller that offers no encoding receives the identical
uncompressed body, so nothing about the payload's content changes — only how many bytes of it cross
the wire.

Four identity and perimeter decisions sit beside those variables and are deliberately *not*
variables: they are fixed elements of `server.xml`, so no deployment can weaken them by setting an
environment value, and they hold for all four `AUTH_TYPE` modes.

- **This service mints no tokens, and cannot.** Liberty's own JWT builder endpoint is published
  whatever this module does — `mpJwt-2.1` requires the `jwt-1.0` feature, both arrive inside the
  mandated `microProfile-7.1` umbrella, and their bundles expose no setting that withdraws the
  endpoint. Removing a builder element does not help either: the runtime auto-provides a
  `defaultJWT` builder as soon as `jwt-1.0` is active, signing with the same estate key. So
  `server.xml` claims that id and pins it to `keyAlias="execution-control-mints-no-tokens"`, an
  alias neither keystore holds. `GET /jwt/ibm/api/defaultJWT/token` and
  `…/defaultJWT/jwk` consequently answer `200` with an **empty body** to every identity, and the
  first attempt files an FFDC incident naming `CWWKS6016E` and that alias — the intended record
  that something asked this service to sign. Token **verification** is a different element and is
  untouched: `includes/{basic,ldap,oidc}.xml`'s `mpJwt` still accepts estate-issued tokens against
  `jwtSigner` in `trust.p12`.
- **No single sign-on cookie is issued.** `singleSignonEnabled="false"` means an authenticated
  response carries no `StockTraderSSO` cookie, nothing is replayed, and each request is
  authenticated from the credential it presents — which is what makes the audit `actor` the
  identity of that request rather than of a cookie acquired earlier. `ssoRequiresSSL="true"` and
  `sameSiteCookie="Strict"` sit beside it as defence in depth for a deployment that re-enables SSO.
- **A credential does not degrade the runtime endpoints.**
  `useAuthenticationDataForUnprotectedResource="false"` stops the security collaborator
  authenticating an unprotected resource merely because the request carries authentication data.
  `/health/*` and `/openapi` therefore answer their own status whatever `Authorization` header
  arrives; see
  [The runtime endpoints beside the application](#the-runtime-endpoints-beside-the-application) for
  the failure this prevents. Protected resources are unaffected — `web.xml`'s constraints still
  demand authentication, so the perimeter is exactly as strict as before.
- **A path no web app claims names nothing.** `enableWelcomePage="false"` and
  `appOrContextRootMissingMessage="Not Found"` replace Liberty's welcome page and its
  "Context Root Not Found" page — both of which named the product and its exact release — with a
  `404` whose only text is `Not Found`.

### Telemetry

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | URL | `http://jaeger-collector.istio-system.svc.cluster.local:4317` (held as `otel.exporter.otlp.endpoint` in `microprofile-config.properties`) | OTLP target for `mpTelemetry-2.1`. Exporter failures are logged and never fatal, so an absent collector degrades to log noise only |
| `OTEL_SDK_DISABLED` | boolean | `false` (held as `otel.sdk.disabled` in `microprofile-config.properties`) | Mirrors the broker. Set it to `true` for local runs to silence exporter retries |
| `OTEL_PROPAGATORS` | comma-separated list | `tracecontext` (held as `otel.propagators` in `microprofile-config.properties`) | Which context propagators the SDK installs. Narrower than OpenTelemetry's own `tracecontext,baggage` default, for the reason below |

**Why baggage propagation ships off.** The W3C baggage propagator in OpenTelemetry below 1.62.0
allocates without bound while parsing an inbound `baggage` header — CVE-2026-45292 /
GHSA-rcgg-9c38-7xpx, scored 5.3 by GitHub and 7.5 by the other assigning authority — and no Open
Liberty release ships a fixed SDK: 26.0.0.9, the newest, carries OpenTelemetry 1.48.0 in both the
API this module compiles against and the runtime bundle that executes it. The SDK is enabled here,
so with the upstream propagator list that header would be parsed on every request, including
unauthenticated ones. Naming only `tracecontext` leaves the baggage propagator uninstalled, so the
header is never read. Nothing else about telemetry changes: `traceparent` and `tracestate` still
propagate, every `@WithSpan` span is still recorded, and the SDK stays on. Restore the upstream
default (`OTEL_PROPAGATORS=tracecontext,baggage`) once the runtime ships OpenTelemetry 1.62.0 or
later — and treat it as a control to re-check rather than a setting to forget, because an image
scanner cannot confirm it for you: Liberty repackages OpenTelemetry as the OSGi bundle
`io.openliberty.io.opentelemetry.internal.2.1` with its Maven coordinates stripped, so a scan of
the image reports nothing about it. See
[Dependency and image scanning](#dependency-and-image-scanning).

### Admission capacity

| NAME | Type | Default (where it is held) | Meaning |
| --- | --- | --- | --- |
| `ORDER_CAPACITY` | integer | `10000` (`microprofile-config.properties`) | Ceiling on how many orders this process will admit. At it, `POST /orders` answers `503` and no further order is admitted. Minimum `3`, the three orders the seed set submits |
| `SETTLEMENT_EXCEPTION_CAPACITY` | integer | `10000` (`microprofile-config.properties`) | Ceiling on stored settlement exceptions. At it, `POST /orders` answers `503` for *any* order, because an execution must never find nowhere to record a break. Minimum `1`, the one exception the seed set opens |
| `POSITION_CAPACITY` | integer | `5000` (`microprofile-config.properties`) | Ceiling on distinct client-and-symbol holdings. At it, only an order that would open a *new* holding answers `503`; an order in a holding the client already has still fills. Minimum `5`, the five positions the seed set writes |
| `AUDIT_EVENT_CAPACITY` | integer | `150000` (`microprofile-config.properties`) | Ceiling on recorded audit events. At it, `POST /orders` and all three `PUT` workflow steps answer `503` rather than take a state change unrecorded. Minimum `13`, the thirteen events the seed set writes, and at least ten times `ORDER_CAPACITY` |

All four are validated at start-up, so a ceiling below its minimum — or an `AUDIT_EVENT_CAPACITY`
below ten times `ORDER_CAPACITY` — fails the deployment with a message naming the key and the
figure it must reach, rather than failing the first seeded submission. The arithmetic, the sizing
figures, the headroom the health data publishes and the `503` runbook are under
**Operational notes > Admission capacity**.

### Why the defaults live in two places

Each variable has exactly one home, decided by who has to resolve it. **Server-level variables**
(`AUTH_TYPE`, `JWT_AUDIENCE`, `JWT_ISSUER`, `TRACE_SPEC`, `default.http.port`,
`default.https.port`) are resolved by Liberty while it parses `server.xml`, before any application
configuration source exists, so their defaults are `<variable name="…" defaultValue="…"/>` elements
in `server.xml`. The **five business rules** and the **four admission ceilings** are application
configuration read through MicroProfile Config; their property names equal the environment-variable
names exactly, so the default environment mapping applies without translation, their defaults live
only in `src/main/resources/META-INF/microprofile-config.properties`, and `@ConfigProperty` carries
no `defaultValue` — a misspelled or deleted key therefore fails start-up loudly instead of silently
trading against a limit, or sizing a store to a ceiling, nobody configured. Liberty also exposes
`server.xml` variables to MicroProfile Config, which is how the readiness probe reads
`JWT_AUDIENCE` and `JWT_ISSUER` with nothing supplied by the environment.

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
(`liberty-maven-plugin` 3.12.3, assembly `io.openliberty:openliberty-runtime:26.0.0.9`): the server
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

### Dependency and image scanning

Software-composition analysis is opt-in rather than part of `verify`, because it needs what this
repository cannot carry: an NVD API key, and a local vulnerability database built from it.

```bash
NVD_API_KEY=<your key> mvn -B -Pdependency-check dependency-check:check
```

The `dependency-check` profile pins `org.owasp:dependency-check-maven` 13.0.0 and configures it for
this module. The key is read from the `NVD_API_KEY` **environment variable**
(`nvdApiKeyEnvironmentVariable`) rather than from a POM value or `-DnvdApiKey`, because Maven echoes
plugin configuration and command lines in its debug output. `skipTestScope` is `false`, so the test
REST client and JSON parser are scanned as well as the runtime APIs, and the reports land in
`target/dependency-check-report.html` and `.json`. It reports rather than gates: `failBuildOnCVSS`
keeps the plugin default of 11, which no score can exceed. Turn it into a gate when you want one:

```bash
NVD_API_KEY=<your key> mvn -B -Pdependency-check dependency-check:check -DfailBuildOnCVSS=7
```

The profile is never active by default, so an ordinary `mvn clean verify` neither runs the goal nor
resolves the plugin. Without a key the goal fails before it analyses anything:

```
UpdateException: Error updating the NVD Data
  caused by NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
NoDataException: No documents exist
```

Request a key at <https://nvd.nist.gov/developers/request-an-api-key>; the first run with one
populates the database, which takes minutes.

**When no key and no scanner binary are available** — the state of a host whose egress proxy blocks
GitHub release assets, so `trivy`, `grype`, `syft` and Dependency-Check's vulnerability cache cannot
be fetched — these substitutes cover the same ground with `curl` and the Docker daemon:

```bash
# Every coordinate this module resolves, asked about in one OSV.dev request
mvn -B -q dependency:list -DincludeScope=test -DoutputFile=/tmp/ec-deps.txt
awk -F: 'NF>=5 {gsub(/^[ \t]+/,"",$1);
    printf "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"%s:%s\"},\"version\":\"%s\"}\n", $1, $2, $(NF-1)}' \
  /tmp/ec-deps.txt | sort -u | paste -sd, - | sed 's/^/{"queries":[/; s/$/]}/' > /tmp/ec-osv.json
curl -s -X POST -d @/tmp/ec-osv.json https://api.osv.dev/v1/querybatch

# The image, scanned by a containerised trivy instead of an installed one
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy:latest image --scanners vuln execution-control:local
```

Build-plugin classpaths are the third surface, and `dependency:resolve-plugins` is the wrong tool
for it here: it reports each plugin's own declared dependencies and ignores the plugin-level
`<dependencies>` this POM pins, so it still shows the versions those pins replace. Read the realms
Maven actually populates instead:

```bash
mvn -X -B clean verify | grep -A30 'Populating class realm'
```

Two things are worth knowing before reading any of these reports. **A clean image scan is not a
clean bill of health.** Trivy reads 1,679 packages out of this image and reports zero findings, but
it names Liberty's repackaged libraries by OSGi bundle — `dev:io.openliberty.io.opentelemetry.2.1`,
never `io.opentelemetry:opentelemetry-api:1.48.0` — so the OpenTelemetry release inside the runtime,
and the advisory behind [`OTEL_PROPAGATORS`](#telemetry), are invisible to it. Pair image scans with
Open Liberty fix-pack tracking. **And the one advisory the dependency scan does report** is that
same OpenTelemetry API: it arrives transitively with `microprofile-telemetry-api` and is `provided`
by the runtime, so there is no version of it this module can choose — which is why its mitigation is
configuration rather than an upgrade.

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

That run is a smoke test and leaves the container's filesystem writable. The deployment does not:
[the pod runs with a read-only root filesystem](#standalone-kubernetes-deployment), and the same
posture is worth reproducing locally before a change to the image reaches a cluster — the `docker`
equivalent of it is in that section.

The base image is `icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal`, pinned in
the `Dockerfile` by digest alongside that tag:

```
FROM icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal@sha256:4c84a4fc73413adf3406513b7827eee721591bf139b245fea51b71e00b0dc4bc
```

It carries every Liberty feature, so the `microProfile-7.1`, `mpTelemetry-2.1` and
`appSecurity-5.0` features `server.xml` enables need no install step, and the module's release-17
class files run unchanged on the image's Java 21 runtime.

**Heap bounds ship with the server configuration.** `src/main/liberty/config/jvm.options` sets
`-Xms64m` and `-Xmx384m`, and the Dockerfile's copy of that directory carries it into the image, so
the container, the Maven integration-test server and a local `server run` are bounded identically.
The numbers are absolute rather than a percentage of available memory on purpose: a JVM in a
container that carries no memory limit of its own reads the whole machine's memory, and one measured
on a 3.1 TB build host chose a 30 GB maximum heap and reached 1.9 GB resident for a live set of
about 71 MB. A full estate at the [admission ceilings](#admission-capacity) retains roughly 130 MB,
so 384 MB leaves about three times the live set for collection headroom and sits inside the
`memory: 1Gi` limit the manifest below requests. Raise both together, never one alone: a heap larger
than the pod's limit is an OOM kill waiting for load, and a pod larger than the heap is memory
nothing will use.

**Key material is owner-readable only inside the image.** `COPY` preserves the mode of what it
copies, so the two PKCS12 stores under `resources/security/` would arrive at `0644` — readable by
every identity in the image, and both of them hold a private key (`key.p12` the server's own,
`trust.p12` the shared `jwtsigner` key). The `Dockerfile` therefore follows its `chown -R 1001:0`
with a `chmod 600` over `resources/security/*.p12`. The tracked files keep the mode they carry in
the broker, whose copies they are byte-for-byte; the tightening is a layer of the image, not a
change to the repository. Re-check it on any image you build:

```bash
docker run --rm --entrypoint stat execution-control:local -c '%n %a %U' \
  /opt/ol/wlp/usr/servers/defaultServer/resources/security/key.p12 \
  /opt/ol/wlp/usr/servers/defaultServer/resources/security/trust.p12
```

Both lines must read `600 default` — `default` is the account name uid 1001 carries in the base
image, and it is the only identity that ever runs this server, so owner-only access is the whole of
what the runtime needs. That makes the material harder to read; it does not make it safe to use. It
is still shared demonstration material, and what to replace it with is under
[Inherited demonstration security material](#inherited-demonstration-security-material).

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
          # Nothing in the image is written at runtime: the three paths Liberty does write
          # are the volumes below. This is the setting that stops a compromised application
          # process rewriting the runtime jars under /opt/ol/wlp/lib or the server.xml that
          # selects AUTH_TYPE and the mpJwt consumer for the next restart - both of which
          # the base image leaves writable by the runtime UID.
          readOnlyRootFilesystem: true
        volumeMounts:
          # WLP_OUTPUT_DIR: the OSGi workarea and the LTPA key file the server generates on
          # first boot. The image primes a workarea during its build and this volume masks
          # it, so the server rebuilds it once per pod - measured at about 1.5s of extra
          # startup, against a startupProbe that allows 60.
          - name: liberty-output
            mountPath: /opt/ol/wlp/output
          # Where LOG_DIR resolves: /opt/ol/wlp/logs is a symlink to /logs in this image,
          # and messages.log and the verbose-GC log are written through it.
          - name: liberty-logs
            mountPath: /logs
          # java.io.tmpdir.
          - name: tmp
            mountPath: /tmp
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
          # The password the LTPA key file is encrypted with. server.xml carries a demonstration
          # default; supply your own here, and keep it stable over time, or a restarted server
          # will not read the key file it wrote. It is a credential, so it comes from a Secret rather
          # than a literal - create it with:
          #   kubectl -n stocktrader create secret generic execution-control-ltpa \
          #     --from-literal=keysPassword='<your value>'
          - name: LTPA_KEYS_PASSWORD
            valueFrom:
              secretKeyRef:
                name: execution-control-ltpa
                key: keysPassword
          # Ceiling on one incoming message (request line, headers and body together). Raise it
          # only if your identity provider issues unusually large bearer tokens.
          - name: MAX_REQUEST_SIZE_BYTES
            value: "8192"
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
        # Sized around the shipped heap bounds (-Xms64m / -Xmx384m in jvm.options): the limit
        # leaves the JVM's non-heap memory - class metadata, code cache, thread stacks and the
        # shared class cache - room above a full 384Mi heap, and the request covers a steady
        # state that has never needed more than about half of it. Raising -Xmx without raising
        # this limit turns a memory-hungry moment into an OOM kill.
        resources:
          limits:
            cpu: 1000m
            memory: 1Gi
            ephemeral-storage: 256Mi
          requests:
            cpu: 250m
            memory: 512Mi
            ephemeral-storage: 32Mi
      # The only writable paths in the pod. Each carries a sizeLimit so a log or workarea
      # that grows without bound evicts the pod instead of filling the node, and the three
      # together come to the ephemeral-storage limit above - which they now account for in
      # full, because a read-only root leaves nothing else to write to. A freshly started
      # server holds about 1.5 MiB across all three, so the room is for log growth.
      volumes:
        - name: liberty-output
          emptyDir:
            sizeLimit: 128Mi
        - name: liberty-logs
          emptyDir:
            sizeLimit: 64Mi
        - name: tmp
          emptyDir:
            sizeLimit: 64Mi
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
seccomp profile, privilege escalation is refused, every Linux capability is dropped, and the root
filesystem is read-only.

That last setting is the one that matters most here, and it is why the manifest carries three
`emptyDir` volumes. The base image is built for platforms that run it under an arbitrary UID, so it
ships `/opt/ol/wlp` and `/opt/ol/wlp/lib` group-writable (`775 default:root`) and the server
directory at `770`; the image's `chown -R 1001:0` over the configuration it copies then leaves
`server.xml` writable by its owner. Without `readOnlyRootFilesystem` the application identity can
rewrite both the runtime jars it executes and the `server.xml` that selects `AUTH_TYPE` and the
`mpJwt` consumer — which the next restart would load. No permission set inside the image can close
that, because the runtime has to own the configuration it reads; the pod-level control can, and
does. Every Liberty service in this estate inherits the same base image and the same writable
install, so this is the module-side remedy rather than a fix to the image.

Liberty needs exactly three writable paths, and each is one of those volumes:

| Path | Why it must be writable |
| --- | --- |
| `/opt/ol/wlp/output` | `WLP_OUTPUT_DIR` — the OSGi workarea, and the LTPA key file generated on first boot |
| `/logs` | Where `LOG_DIR` resolves: `/opt/ol/wlp/logs` is a symlink to it, and `messages.log` and the verbose-GC log are written through it |
| `/tmp` | `java.io.tmpdir` |

Verified rather than assumed: with those three writable and everything else read-only the server
starts clean — all three health endpoints `UP`, an authenticated order submit answering `201`, not
one warning or error in `messages.log` — while `/opt/ol/wlp/lib` and `server.xml` refuse writes. Two
costs come with it, both small. Startup moves from about 3.0s to 4.6s, because the volume masks the
workarea the image primed and the server rebuilds it once per pod. And the LTPA key file now lives
in the output volume: it survives a container restart within the pod, but a replaced pod generates a
new one — which is why `LTPA_KEYS_PASSWORD` comes from a Secret and has to stay stable.

`docker` has the same two controls, so the pod's filesystem posture can be reproduced locally before
it is trusted in a cluster:

```bash
docker run -d --name ec --read-only \
  --tmpfs /opt/ol/wlp/output --tmpfs /logs --tmpfs /tmp \
  -p 127.0.0.1:9080:9080 -p 127.0.0.1:9443:9443 \
  -e AUTH_TYPE=none -e OTEL_SDK_DISABLED=true execution-control:local
```

One limit to know: the base image's certificate and serviceability helpers do write under the
configuration directory, and those writes fail against a read-only root. Setting `SSL=true` or
`TLS=true`, mounting certificates for the entrypoint to import (`TLS_DIR`),
`SEC_IMPORT_K8S_CERTS=true`, or `SERVICEABILITY_NAMESPACE` makes it write a config dropin or re-link
`/opt/ol/wlp/logs`, and the container then exits during startup. None of them is used here — the
service ships its own keystore and truststore, and its own keystore dropin is what stops the
entrypoint generating another — so leave them unset. A deployment that genuinely needs one has to
relax `readOnlyRootFilesystem`, because an `emptyDir` over the configuration directory would mask
the configuration itself.

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
signature, `X-Powered-By` and `$WSEP`, both strip `io.openliberty.trace`, and both assert the same
five response headers; only the TLS listener adds a sixth.

| Header | Value | Where it applies |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | every response, both listeners |
| `Cache-Control` | `no-store` | every response, both listeners — every application response here is authenticated JSON that no intermediary or browser should retain |
| `X-Frame-Options` | `DENY` | every response, both listeners |
| `Referrer-Policy` | `no-referrer` | every response, both listeners |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` | every response that does not already carry a policy of its own, both listeners |
| `Strict-Transport-Security` | `max-age=31536000` | the TLS listener only — a client that arrived over cleartext would be right to ignore it |

The last three are there because this origin does answer with HTML a browser will render, even
though every response the API contract above describes is JSON: the container's own `403` page, and
the Swagger UI at `/openapi/ui/`. `X-Frame-Options` and `Referrer-Policy` are asserted
unconditionally — nothing served here is meant to be framed, and no response on either listener
declares a referrer policy of its own. The content-security policy is asserted only where the
response carries none, and that condition is load-bearing rather than cautious: a browser enforces
the *intersection* of two policies rather than the later one, and Liberty's Swagger UI ships a
policy that its own inline bootstrap needs, so asserting `default-src 'none'` over it would blank
that page while protecting nothing this service serves. Every response this service produces
carries no policy of its own and so receives the restrictive one, which names `frame-ancestors`,
`base-uri` and `form-action` explicitly because `default-src` does not cover them.

`io.openliberty.trace` is removed for the reason `$WSEP` is, and one more. `mpTelemetry` puts the
trace and span id of the request on every response the application produces, so besides naming the
runtime the header hands any caller — including one whose request was refused — a correlation id
into the server's own traces. Nothing diagnostic is lost by removing it: those ids stay in
`trace.log` and reach the collector over OTLP, which is where a deployment correlates them, and an
ingress that wants to hand a client a correlation id of its own can add one.

Both halves of the policy are checkable from outside in one command each — the first prints the
three document policies and nothing for the trace header, the second adds HSTS:

```bash
curl -sS -D - -o /dev/null -u stock:trader http://localhost:9080/execution-control/controls \
  | grep -i -e '^x-frame-options' -e '^content-security-policy' -e '^referrer-policy' -e '^io.openliberty'
curl -sSk -D - -o /dev/null -u stock:trader https://localhost:9443/execution-control/controls \
  | grep -i -e '^strict-transport-security' -e '^content-security-policy'
```

An ingress in front of this service may set the same headers; it must not weaken them.

Both ports also answer three web apps that belong to the runtime rather than to this service —
`/health/`, `/openapi/` and `/jwt/`, all published by the feature set. None of them is part of the
API above, and `/jwt/` is the one that has to be kept off every route: its builder here holds no
signing key, so it issues nothing, but `GET /jwt/ibm/api` still answers `500` with the runtime's own
exception text to an unauthenticated caller, and no setting available to this module changes that.
A path this server serves no application for — `/`, or a miscased probe path — answers a bare `404`
naming neither the product nor its version. The `NetworkPolicy` and the ingress path allow-list
that keep the runtime apps off a published route, the reason each exists, and the residual
behaviours that documentation rather than configuration has to carry are under
[The runtime endpoints beside the application](#the-runtime-endpoints-beside-the-application).

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

### `AUTH_TYPE` is trusted deployment input

`AUTH_TYPE` does not name a mode this service interprets; it names a file. `server.xml` carries
`<include location="${server.config.dir}/includes/${AUTH_TYPE}.xml"/>`, and that line and the four
`includes/*.xml` files it reaches are the broker's, verbatim but for one deleted element (rows 15
and 16 of [Deviations from the frozen implementation
plan](#deviations-from-the-frozen-implementation-plan)) — which is precisely what lets this service
accept the tokens the rest of the estate issues. The variable therefore selects the
authentication mechanism itself, so **set it from the deployment, to one of `basic`, `ldap`, `oidc`
or `none`, and never from anything a caller can influence.**

A value outside that set behaves in one of two ways, and only one of them is an outage:

- **It resolves to no file, and the service fails closed.** `AUTH_TYPE=bogus` logs
  `CWWKG0090E: The …/includes/bogus.xml configuration resource does not exist` and leaves no user
  registry configured (`CWWKS3005E`), so every request under `/execution-control` answers `401`. A
  value that already carries the extension fails the same way with the extension applied twice
  (`AUTH_TYPE=none.xml` → `…/includes/none.xml.xml`), and an empty value asks for `…/includes/.xml`.
  All three probes still answer `200 UP` throughout, so this looks from outside exactly like the
  missing-`groups` misconfiguration above: read the server log to tell them apart.
- **It resolves to a different include, and that mechanism is silently the one in force.**
  `AUTH_TYPE=../includes/none` traverses back into the same directory, and Liberty loads
  `includes/none.xml`: the development HTTP Basic registry is active, `stock:trader` reads and
  writes normally, `read:only` is held to reads, and nothing marks the substitution — the only trace
  is the ordinary `CWWKG0028A: Processing included configuration resource: …/includes/none.xml`, and
  the server logs no warning or error at all.

The second case grants nothing the supported value `none` does not — anonymous requests are still
`401`, and the role split still holds — but it means a mistyped or externally supplied `AUTH_TYPE`
can quietly downgrade a JWT-verifying deployment to the bundled demonstration users instead of
failing. Pin the value in the `Deployment` (the manifest above sets it explicitly), and treat
whoever can set it as trusted.

Do **not** fork the include mechanism to validate the value locally. Those four files staying the
broker's is what the estate's JWT trust rests on, and a local variant would be a second copy of that
mechanism to keep in step for a check the deployment already owns. The one element removed from
three of them is a token *issuer* and not part of that trust; anything this module needs to add to
the security configuration belongs in its own `server.xml`, which is where the elements of row 16
live for exactly this reason.

### The runtime endpoints beside the application

Open Liberty publishes four web apps of its own alongside this one, and the server log names them
all at startup — `CWWKT0016I` lists `/execution-control/`, `/health/`, `/openapi/`, `/openapi/ui/`
and `/jwt/`. Only the first is this service's; the other four arrive with the feature set:
`/health/` with `mpHealth-4.0`, `/openapi/` and `/openapi/ui/` with `mpOpenAPI-4.1`, and `/jwt/`
with `jwt-1.0`, which `mpJwt-2.1` requires — every one of them inside the `microProfile-7.1`
umbrella `server.xml` enables. None can be dropped without dropping the umbrella, so they are
documented here rather than removed, and the perimeter is where they are restricted: see
[Restricting the perimeter](#restricting-the-perimeter).

**`/jwt/*` is not part of this service's contract, and it issues nothing.** It is Liberty's own JWT
builder endpoint, this service never calls it, and nothing in the API above depends on it. Left at
the runtime's defaults it was worse than useless: every identity the active registry authenticated
could mint an RS256 token signed with the estate's shared `jwtSigner` key and carrying the estate
issuer and audience — a role-less account included — which the sibling services, several of which
bind `StockTrader` to `ALL_AUTHENTICATED_USERS`, would then honour. `server.xml` closes that by
claiming the `defaultJWT` builder id and pinning it to a key alias no keystore holds, which is the
only lever that works: the endpoint cannot be unpublished, and a deleted builder is auto-provided
again by the runtime with the same estate key. Measured on this runtime with the fix in place:

| Request | Answer |
| --- | --- |
| `GET /jwt/ibm/api/defaultJWT/token` over TLS as `stock:trader`, and as `read:only` | `200` with a **zero-byte** body — no token. Identity cannot change it: there is no key to sign with |
| `GET /jwt/ibm/api/defaultJWT/jwk` over TLS | `200` with a **zero-byte** body — no key, no `kid` |
| `GET /jwt/ibm/api/defaultJWT/token` over the cleartext listener | `404` with the body `Error 404: CWWKS6052E: HTTP scheme is used at the specified endpoint: …, HTTPS is required.` — the endpoint is TLS-only, and that notice carries a message id and the caller's own URI, no class, stack or product version |

The first attempt writes four de-duplicated FFDC incidents naming
`CWWKS6016E: … The alias [execution-control-mints-no-tokens] is not present in the KeyStore as a
key entry`, and repeat attempts add none. That is the intended record that something asked this
service to sign; startup itself is clean. Verification is unaffected — the `mpJwt` element of
`includes/{basic,ldap,oidc}.xml` still accepts the estate's tokens against `jwtSigner` in
`trust.p12` — and so is every application endpoint.

Do not route `/jwt/*` even so: a deployment that exposes this pod should publish
`/execution-control` and the three probe paths and nothing else. Two answers there are the
runtime's own and no module-level setting reaches them, so they are documented rather than fixed:

- `GET /jwt/ibm/api`, with or without credentials, answers **`500`** with a 111-byte
  `java.lang.NullPointerException` message from the runtime's `JwtRequestFilter`. The body carries
  the message text only — **no stack frames**; the frames appear in the FFDC incident, not in the
  response — and it names no application type of this service. Identical on the untouched runtime.
- In the MP-JWT modes (`AUTH_TYPE=basic`, `ldap`), the TLS token endpoint itself answers **`500`**
  with `java.lang.NullPointerException: Cannot invoke
  "com.ibm.ws.security.registry.UserRegistry.getRealm()"`, anonymously and with credentials alike,
  because `/jwt` is a *protected* resource and those includes configure no user registry. It is
  pre-existing runtime behaviour, present identically on a baseline image, and the remedy is **not**
  to add a registry to `includes/basic.xml`: that would create development users in the production
  authentication mode. The endpoint mints nothing in that mode either.

**The probes answer whatever credential arrives.** `/health/live`, `/health/ready`,
`/health/started` and `/health` are unprotected resources, and Liberty's security collaborator
would otherwise authenticate them opportunistically whenever a request merely carries
authentication data — which in a registry-less MP-JWT deployment dereferenced a null `UserRegistry`
and turned every probe carrying an `Authorization` header into a `500` with that exception text.
Any ingress or service mesh that forwards the caller's credential to all paths produces exactly
that request, and a `500` there is a failed liveness or readiness check, so
`server.xml` sets `useAuthenticationDataForUnprotectedResource="false"`. All twelve cells of
{`live`, `ready`, `started`} × {no header, a syntactically valid but forged `Bearer`, a valid
`Basic` credential, an unknown scheme} now answer `200 UP`, and the module's integration tests
assert them. Stripping `Authorization` for the probe paths at the perimeter remains worth doing;
it is no longer what keeps the probes answering.

**Only four paths exist under `/health`.** Everything else there, and every path no application
claims, answers a `404` — but not the same `404`, and the difference is the whole of what a
perimeter has to know:

| Path | Answer |
| --- | --- |
| `/health/live`, `/health/ready`, `/health/started` | `200` (or `503`) with this service's `ExecutionControl` check |
| `/health`, with or without a trailing slash | `200` (or `503`) with all three checks |
| any other sub-path — `/health/ready/extra`, `/health/xyz` | `404`, logged by the runtime and filed as an FFDC incident. **Its body names `jakarta.servlet.ServletException`, `java.io.FileNotFoundException` and `SRVE0190E`** — the runtime's own web app answering, before any `<error-page>` or filter of this module, and no module-level setting changes it (`webContainer displayCustomizedExceptionText` has no effect on it; measured). Documented, not fixed: keep the sub-path off the perimeter with `pathType: Exact` below |
| `/HEALTH/ready`, `/Health/Ready` | `404` — path matching is case-sensitive. No web app claims that context root, so the answer is the HTTP dispatcher's: a minimal page whose only text is `Not Found`, naming neither the product, nor its version, nor any type. Until `server.xml` set `appOrContextRootMissingMessage`, this was the product's "Context Root Not Found" page |
| `/`, and any other path no application claims | `404` with that same `Not Found` page. Until `server.xml` set `enableWelcomePage="false"`, this was Liberty's welcome page, titled with the product and its exact release |

The first of those 404s is answered by Liberty's file-serving extension, which logs
`SRVE0190E: File not found`
with a stack trace and files an FFDC incident for it — behaviour of the runtime's web app, not of
anything this module can intercept, and reachable by a caller with no credentials. Two settings in
`server.xml` bound what an anonymous caller can do with it: `hideMessage` keeps `SRVE0190E` out of
`messages.log` and `console.log` (it is redirected to `trace.log`, with its stack, and the
`FFDC1015I` incident notice still appears, so nothing is lost), and `maxFileSize`/`maxFiles` cap the
log files it writes to. The FFDC directory itself is bounded by the runtime, which de-duplicates
incidents by signature — 70 requests to 70 distinct unknown sub-paths produced 5 files and 4
incident notices, not 70 — and `maxFfdcAge="1d"` ages those files out across restarts. `TRAS3001I`
in the log names every hidden message at startup, so the hiding is never invisible.

**The probe paths do not enforce the HTTP method.** Liberty's health servlet answers `POST`, `PUT`,
`DELETE`, `PATCH`, `OPTIONS` and `HEAD` on `/health/ready` exactly as it answers `GET` — `200` with
the full health JSON, all three checks executed, including a `POST` carrying a JSON body. Only
`TRACE` is refused (`403`). Content negotiation is unenforced with it: `Accept: text/plain` and
`Accept: application/xml` both return JSON. Read a `200` on `POST /health/ready` as the probe
surface answering, never as an application route: the health checks only read store counts and
resolved configuration, so no verb against them changes anything. The `web.xml` constraints and
`<deny-uncovered-http-methods/>` that refuse those verbs under `/execution-control` cannot reach
here — `/health` is outside the WAR's context root — and the health servlet is the runtime's, so
restricting the verbs is the perimeter's job, below.

#### Restricting the perimeter

Nothing in the estate routes to this service, and the `Service` is `ClusterIP` for that reason. When
a deployment does publish it, publish `/execution-control` and the three probe paths and nothing
else, and keep `/jwt/*` off every route: at an ingress, by listing only those paths; inside the
cluster, by admitting only the node the pod runs on to the cleartext port and only in-cluster
callers to the TLS port. The kubelet's own probes are unaffected either way, because it connects to
the pod directly rather than through a route.

The allow-list is what the two residual runtime answers rest on, so it is not optional decoration.
`/jwt/*` issues nothing here, but `GET /jwt/ibm/api` still returns the runtime's `500` and its
exception message to anyone who can reach the port, and the module's own configuration cannot
change that; an unknown sub-path under `/health` still returns a `404` naming
`jakarta.servlet.ServletException` and `SRVE0190E` for the same reason. Both are the runtime's
web apps rather than this WAR, both are reachable without credentials, and the perimeter is the
only place either can be refused. Enforce it in the cluster and not only at an ingress: an ingress
protects a published route, while a `NetworkPolicy` also covers anything that can already route to
the pod.

Save this beside the manifest and apply it into the same namespace to close 9080 to everything but
in-cluster callers and the node the pod runs on:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: execution-control-ingress
  labels:
    app: execution-control
spec:
  podSelector:
    matchLabels:
      app: execution-control
  policyTypes:
    - Ingress
  ingress:
    # Kubelet probes arrive from the node the pod runs on, not from a pod network
    # address, so the node CIDR is what admits them. Substitute your cluster's.
    - from:
        - ipBlock:
            cidr: 10.0.0.0/8
      ports:
        - protocol: TCP
          port: 9080
    # In-cluster callers that hold a token reach the TLS listener only.
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: stocktrader
      ports:
        - protocol: TCP
          port: 9443
```

A `NetworkPolicy` selects ports, not paths, so the path allow-list belongs to whatever terminates
HTTP in front of the pod. With the NGINX ingress controller that is one rule per published prefix
and no wildcard host rule:

```yaml
  rules:
    - host: execution-control.example.com
      http:
        paths:
          - path: /execution-control
            pathType: Prefix
            backend: { service: { name: execution-control-service, port: { number: 9443 } } }
          - path: /health/live
            pathType: Exact
            backend: { service: { name: execution-control-service, port: { number: 9080 } } }
          - path: /health/ready
            pathType: Exact
            backend: { service: { name: execution-control-service, port: { number: 9080 } } }
          - path: /health/started
            pathType: Exact
            backend: { service: { name: execution-control-service, port: { number: 9080 } } }
```

`pathType: Exact` on the three probe paths is what keeps the stray-sub-path 404s above — and the
exception class names one of them discloses — off the perimeter, and leaving `/jwt`, `/openapi` and
`/openapi/ui` out of the list is what keeps them unreachable from outside the cluster. Add `/openapi` deliberately if the generated contract is meant
to be published; it carries no data, only the schema. Restricting the verbs on the probe paths is the
same ingress's job — the controller-specific form varies, so it is not reproduced here.

One property of that list is load-bearing: dot segments are removed before a request is routed to a
web app, so `/execution-control/%2e%2e/jwt/ibm/api` arrives as `/jwt/ibm/api` — measured on this
runtime, which answers it exactly as the direct path does. A proxy that matches the **normalized**
path (NGINX does) therefore refuses it against the list above, while one that matches the raw URI
would admit it: confirm that behaviour in whatever terminates HTTP rather than assuming a
`/execution-control` prefix confines the request. Inside the WAR the same normalization is harmless,
because it happens before the `web.xml` constraints are matched — a mutating verb sent through a
normalized path is still `401` without credentials and `403` for a `StockViewer`, both measured.

**Where a forged `Host` still reaches, and where it does not.** This service's own responses never
repeat the address a caller claimed: both `Location` and every `Link` relation are paths, so a
`Host` or `X-Forwarded-Proto` of the caller's choosing changes nothing in them — measured, with
`Host: evil.example` on `POST /orders` answering `201` and `Location: /execution-control/orders/…`,
and the same `Host` on every paged read answering targets that begin `/execution-control/`. Liberty's
own `/openapi/ui` web app is a different matter: it sits outside the WAR, so no code here can
intercept it, and it answers `GET /openapi/ui` with a `302` that does follow the claimed host —
measured as `Host: evil.example` → `302 Location: http://evil.example/openapi/ui/`. The path
allow-list above already keeps it off a published perimeter, which is the remedy for a deployment
that does not publish it; a deployment that does publish it has to pin the accepted `Host` at
whatever terminates HTTP, because the runtime, not this module, decides that redirect. Two further
measurements on the same runtime: `X-Forwarded-Proto` is honoured, so a claimed scheme still reaches
anything the *runtime* builds from the request URI — that same redirect flips to `https://` while
its host and port stay the ones the request arrived on — while `X-Forwarded-Host` is not honoured at
all. Nothing this module builds reads either header.

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
acceptance works out of the box. Every credential named below is inherited — copied from
`backend/broker`, byte for byte where the artefact is a file — and none of it is generated here.
This section inventories all of it, because a reader deciding what to replace, or whether to enable
`AUTH_TYPE=none`, needs to know exactly what works if they do not.

**The two keystores.** Both are PKCS12, both are byte-identical to the broker's copies
(`key.p12` md5 `e48b3a7ae6c468a8efe3c1dac1737573`, `trust.p12` md5
`f9877a2a20184663ccdff97be85b1ac1`), and that identity is the point: it is what lets this service
verify the tokens the rest of the estate issues. `trust.p12` holds eight entries, of which this
service uses one:

| Store | Entry | Type | Subject | Expires | Used here |
| --- | --- | --- | --- | --- | --- |
| `key.p12` | `default` | private key | `CN=Stock Trader, OU=Cloud Engagement Hub, O=IBM, C=US` | 2030-06-06 | yes — the TLS identity the HTTPS listener presents |
| `trust.p12` | `jwtsigner` | private key (2048-bit RSA, SHA256withRSA) | `CN=Stock Trader, OU=Cloud Engagement Hub, O=IBM, L=Durham, ST=NC, C=US` | 2030-08-15 | yes — `mpJwt` verifies every bearer token against it under `AUTH_TYPE=basic`/`ldap`, where the includes name it `jwtSigner` (keystore aliases are case-insensitive) |
| `trust.p12` | `stock-trader` | trusted certificate | `CN=Stock Trader, OU=Cloud Engagement Hub, O=IBM, C=US` | 2030-06-06 | no |
| `trust.p12` | `apiconnect` | trusted certificate | `CN=*.apiconnect.ibmcloud.com` | **2021-04-08 — expired** | no |
| `trust.p12` | `ibm-id` | trusted certificate | `CN=idaas.iam.ibm.com` | **2021-01-19 — expired** | no |
| `trust.p12` | `iex` | trusted certificate | `CN=*.iexapis.com` | **2020-11-29 — expired** | no |
| `trust.p12` | `openwhisk` | trusted certificate | `CN=us-south.functions.cloud.ibm.com` | **2020-09-30 — expired** | no |
| `trust.p12` | `twitter` | trusted certificate | `CN=api.twitter.com` | **2021-04-10 — expired** | no |
| `trust.p12` | `watson` | trusted certificate | `CN=*.watsonplatform.net` | **2020-12-30 — expired** | no |

Six of those eight entries are long-expired anchors for third-party endpoints — IBM API Connect,
IBM Id, IEX Cloud, IBM Cloud Functions, Twitter and Watson — that this service has no way to reach:
it opens no outbound connection of any kind, prices every order from the order's own `limitPrice`,
and holds all of its data in memory. They are inert rather than dangerous, and they are not a
substitute for real trust: an expired anchor validates nothing, so no peer becomes trusted by
sitting in this file. What they are is dead weight that cannot be cleaned up from here — pruning
them means editing a store that `broker`, `portfolio` and `trader` carry byte for byte, and a
store edited in this module alone would no longer be the file those services hold, which is the
one property that makes cross-service JWT acceptance work. The fix therefore belongs at estate
level: prune the shared `trust.p12` for all four services in one change, and re-verify each
service's JWT path against the pruned store. Re-derive this table at any time with

```bash
# Prompts for the store password, which is the value server.xml holds in its {xor} form.
keytool -list -v -storetype PKCS12 \
  -keystore src/main/liberty/config/resources/security/trust.p12
```

**The keystore password.** One value opens both stores, and `LTPA_KEYS_PASSWORD` defaults to that
same value. `server.xml` carries it in `securityUtility`'s `{xor}` form rather than as a literal,
which keeps the plaintext out of the source tree, out of the image layer and out of any log or
review that echoes the file. It goes no further than that, and should not be read as protection:
Liberty decodes `{xor}` with no key and no configuration — that is exactly what lets the inherited
stores open with nothing supplied — so anyone holding this file recovers the value, and the value
should be treated as known. The literal appears nowhere in this module, in this README included.
`{aes}` is accepted in the same places, but its encryption key must then be supplied to the server
(`securityUtility encode --encoding=aes --key=…` alongside `wlp.password.encryption.key`), which
replaces one piece of demonstration material with a real secret to store and rotate; use it when
you have somewhere to keep that key.

**The development registry.** `includes/none.xml` is in force only when `AUTH_TYPE=none`, and it
defines six plaintext users. All six work against this service the moment that mode is enabled:

| User | Password | Group | What it can do here |
| --- | --- | --- | --- |
| `stock` | `trader` | `StockTrader` | everything: every `GET`, `POST /orders`, and the three exception `PUT`s |
| `debug` | `debug` | `StockTrader` | the same as `stock` |
| `john.alcorn@kyndryl.com` | `traderPwd` | `StockTrader` | the same as `stock` |
| `read` | `only` | `StockViewer` | every `GET`; every mutating verb answers `403` |
| `other` | `other` | none | authenticates, then `403` everywhere — `GET` included |
| `admin` | `admin` | none, but named in `<administrator-role>` | Liberty's administrative role, which is not an application role: `403` on every path under `/execution-control`. It is still a credential on the server |

Re-derive that list with

```bash
grep -E '<user name=|<member name=|<group name=|<user>' src/main/liberty/config/includes/none.xml
```

One deliberate divergence from those inherited copies is recorded rather than silent: the
`jwtBuilder` element is deleted from `includes/{none,basic,ldap}.xml`, so those three files are no
longer byte-identical to the broker's — rows 15 and 16 of
[Deviations from the frozen implementation plan](#deviations-from-the-frozen-implementation-plan).

**This is sample material, not usable credentials.** It is shipped for demonstration and testing
only. Replace the keystores and the password for any deployment you care about, and treat
`AUTH_TYPE=none` as a development and test mode only — it disables JWT verification entirely and
accepts all six hard-coded users above.

Four replacements make a deployment stand on its own material, and none of them is optional for
anything beyond a demonstration:

- **Fresh keystores, per environment.** Generate your own pair (`keytool -genkeypair` for the TLS
  identity, plus the signing key your token issuer uses) and mount them over
  `resources/security/`. Until you do, the consequence of the inherited pair is estate-wide: the
  same `jwtSigner` private key sits in `broker`, `portfolio`, `trader` and this module, so anyone
  who obtains any one of those images can mint tokens that all four accept.
- **Your own keystore password**, on both `keyStore` elements in `server.xml` — plaintext or
  `securityUtility`-encoded, since the form only decides who can read it over your shoulder.
- **`LTPA_KEYS_PASSWORD` from a `Secret`.** The manifest above already wires it that way
  (`valueFrom.secretKeyRef`); supply the same value on every replica, and keep it stable, for the
  reasons under [Authentication across a restart](#authentication-across-a-restart).
- **`AUTH_TYPE` anything but `none`.** `basic` (or `ldap`/`oidc`) verifies a signed token;
  `none` accepts the six passwords printed above.

Which of the two mechanisms is in force is decided by one variable, and that makes the variable
itself part of the perimeter: see
[`AUTH_TYPE` is trusted deployment input](#auth_type-is-trusted-deployment-input) for the values it
accepts and for what an unsupported one does.

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

#### Authentication across a restart

What a restart loses is the data, and nothing else: the service keeps authenticating every role on
every path, and the two are worth separating because the one thing it does keep across a restart is
its LTPA key file.

**No session or cookie spans a restart, because none is issued.** `server.xml` sets
`singleSignonEnabled="false"`, so an authenticated response carries no `Set-Cookie` at all — the
`StockTraderSSO` cookie the copied includes name is never handed out, and no `JSESSIONID` is either.
A caller therefore presents its credential, `Bearer` token or HTTP Basic, on **every** request, and
gets the same answer before and after a restart with nothing to re-establish. That is also what
makes the audit `actor` the identity of the request that caused the transition: with no cookie in
play there is nothing that could be evaluated ahead of the `Authorization` header, and nothing that
remains a usable credential after the header stops being sent. A deployment that re-enables SSO
takes on the opposite: a bearer cookie whose holder is whoever obtained it.

The key file is a separate matter and still exists. Liberty creates and reads it whether or not SSO
is enabled — the test server logs `CWWKS4103I`/`CWWKS4104A`/`CWWKS4105I` and writes
`resources/security/ltpa.keys` on a boot with single sign-on off — so the password below governs it
exactly as before.

That file is written on the first boot and read on every later one, and it is encrypted with
`LTPA_KEYS_PASSWORD`. Liberty gives the underlying attribute no default of its own, so a server
with nothing supplying one invents a password per boot and then cannot read the file it wrote
before. The failure that produces is worth recognising, because it looks like nothing and breaks
everything: `javax.crypto.BadPaddingException` and `CWWKS4106E` while the keys are loaded, then
`CWWKS4000E: … TokenService instance of type Ltpa2 could not be found` on the first authenticated
request, and from there every request under `/execution-control` answers `401` for every role and
every credential — while `/health/started`, `/health/ready` and `/health/live` all still answer
`200 UP`, because the token service is not something a health check of this application can see. A
kubelet keeps such a pod in service indefinitely.

The shipped default password is what prevents that. Since no SSO cookie is issued, a shared value
is no longer what lets replicas accept each other's callers — every replica authenticates every
request on its own — but the value must still be stable over time, because a key file written under
one password cannot be read under another. Two consequences follow for an operator:

- **Set your own value, and set it once.** It is demonstration material, identical to the keystore
  password, and every deployment should override it — but with a value that is the same on every
  replica and stable over time, supplied from a `Secret` rather than the manifest's `env` block.
- **Changing it invalidates an existing key file.** A server restarted with a new password against
  a key file written under the old one fails in exactly the way described above. Delete
  `${server.output.dir}/resources/security/ltpa.keys` when you rotate the password. In a container
  with no volume mounted over that path the file is part of the writable layer, so recreating the
  container is enough; a `docker restart` or a pod restart is not. The image itself carries no key
  file — priming the class cache at build time creates one, and the `Dockerfile` removes it again,
  both so that a deployment supplying its own password is not handed a file encrypted with the
  default one, and so that no key material is shared by every copy of a published image.

#### Admission capacity

Because nothing here expires and nothing is ever deleted, every structure carries a ceiling. The
ceilings are **operator-sized**: each is one environment variable, read through MicroProfile Config
at start-up, defaulting to the figure in the table below. Raising one does not make the design hold
more state safely — it moves the point at which the heap runs out, and the JVM's own heap ceiling
has to move with it — but it does mean a deployment whose volume exceeds a default has a remedy
other than a restart. A deployment that needs unbounded state still needs a datastore rather than a
larger number here.

| Structure | Governing variable | Default ceiling | Minimum | What is refused at it |
| --- | --- | --- | --- | --- |
| Orders (with their executions) | `ORDER_CAPACITY` | 10,000 | 3 | `POST /orders` — no further order is admitted |
| Settlement exceptions | `SETTLEMENT_EXCEPTION_CAPACITY` | 10,000 | 1 | `POST /orders` — an order that might open a break is not admitted, because an execution must never find nowhere to record one |
| Positions (distinct client and symbol) | `POSITION_CAPACITY` | 5,000 | 5 | `POST /orders` — only an order that would open a *new* holding; an order in a holding the client already has stays admissible, since a fill rewrites that entry and adds no key |
| Audit events | `AUDIT_EVENT_CAPACITY` | 150,000 | 13, and at least 10 × `ORDER_CAPACITY` | `POST /orders`, `PUT …/assign`, `PUT …/resolve` and `PUT …/settlement-ready` — the step is refused rather than taken unrecorded |

**Validated at start-up, never at the first request.** A configured ceiling below its minimum, or an
`AUDIT_EVENT_CAPACITY` below ten times `ORDER_CAPACITY`, fails the deployment with a message naming
the key and the figure it must reach. Each minimum is exactly what the startup seed set consumes —
three orders, one exception, five positions, thirteen audit events — because a lower ceiling would
fail the seed load instead of a request, leaving the service permanently un-ready with no caller to
report the refusal to. The coupling rule holds the guarantee that the record is never the thing that
refuses a state change: an order whose settlement instructions mismatch and which is then worked to
the end consumes ten events — six for its submission, including the exception's `OPEN`, then one
assign, one resolve and two for settlement-ready — so 10,000 fully worked orders imply 100,000, and
the shipped 150,000 sits above that. Without the rule, `ORDER_CAPACITY=20000` alone would silently
make the timeline the binding constraint at half the orders an operator had just paid for.

**Headroom is published before anything is refused.** `/health/live` and `/health/ready` both carry
the same ten data keys — `orderCapacity` and `orderHeadroom`, `exceptionCapacity` and
`exceptionHeadroom`, `positionCapacity` and `positionHeadroom`, `auditEvents`, `auditEventCapacity`
and `auditEventHeadroom`, and `admission`, which is `ACCEPTING` or `SATURATED`. `admission` turns
`SATURATED` when any structure that gates admission has run out: the order store, the exception
store, the timeline with fewer than the six events one submission can write, or the position store.
Both probes stay `UP` at saturation, deliberately: every `GET`, and the whole
assign/resolve/settlement-ready workflow, still function at the order ceiling, so reporting `DOWN`
would take the pod out of its Service and break those too — while a Deployment never restarts a pod
for a failing readiness probe, so the refusals would continue with the reads broken as well. The
data is the signal; the status is not. The service also logs a warning **once** per structure when
its remaining headroom falls to a tenth of its ceiling, and **once** when it saturates, each naming
the variable to raise.

**Runbook at a `503`.** Read `message` on the error body: it names the variable that governs the
exhausted ceiling. Either raise that variable and restart the pod, or simply restart it — all state
is in memory, so a restart returns the whole ceiling either way, at the cost of every order,
exception and audit event created since the last start (the seed set is re-created). Nothing evicts
and nothing expires, so waiting does not help. For sizing: the default set — 10,000 orders, 10,000
exceptions, 5,000 positions and 150,000 audit events — retains roughly 75 MB, so budget about
75 MB for every 10,000 orders' worth of the four ceilings raised together, and raise the container
memory limit and the JVM heap with them. Eviction is deliberately not offered: the audit timeline
has no update or delete path by design, and discarding orders would break the `clientOrderId`
idempotency contract — a re-submitted key would stop answering `409` — and orphan the audit events
that reference the discarded order.

A refusal answers **`503 Service Unavailable`** with the usual `ErrorResponse` body. It is `503`
and not `429` because the exhausted ceiling belongs to the whole service rather than to the calling
client: no caller clears it by slowing down, and no per-client quota was crossed. There is no
`Retry-After` header, because the headroom returns when an operator raises the ceiling or restarts
the service and at no interval this service could honestly name; the remedy travels in the message
instead.

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
retires a slot for the life of the process. The published headroom is counted in claims for the
same reason: a claim held by a submission still in flight is capacity the service will not grant
twice.

**A ceiling never masks the answer a caller earned.** Identity and legality are settled before
capacity, so a duplicate `clientOrderId` still answers `409` naming the key, an unknown order or
exception id still answers `404`, and an unsupported lifecycle transition still answers `409` — on
a saturated service exactly as on an empty one. A malformed body still answers `400` ahead of all
of them. Only a request that would otherwise have been accepted is answered `503`.

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
| `quantity` | a whole number of shares, greater than zero and at most `9223372036854775807` — the signed 64-bit range a share count is stored in. A fraction is refused, never narrowed to a whole count. The client's resulting holding is held to that same range, so a quantity that would carry an existing position past it is refused too |
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

`quantity` is read as a JSON number and validated the same way, for the same reason. A share count
is whole and a JSON number is not, so the two have to be reconciled somewhere — and reconciling them
in the deserializer means a submitted `1.5` becomes `1` before any validation can see it: the order
is stored, filled and audited for a quantity nobody sent, and the caller is told nothing. So the
submitted number reaches the validation intact and a fraction is refused there, answering
`quantity must be a whole number of shares, not 1.5`. Trailing zeros and an exponent are not
fractions — `100.00` and `1E+2` both denote a whole hundred shares and both fill as one — while
`0.5` is named as the fraction it is rather than as a value that is not greater than zero, which is
what truncating it to zero used to report. Beyond that: a count above the range answers
`quantity must not exceed 9223372036854775807`, and a compact exponent is refused while still
narrow, reported by its representation rather than by rendering its digits. A number too large for
JSON itself to hold — an exponent outside the `int` range, say — still fails while the body is being
read and answers `400` with the fixed text `request body is not valid JSON`, because no field exists
yet to name.

Required text fields are checked with Java's `isBlank` and stored with `strip`, so a value made
only of Unicode whitespace — `U+2003` EM SPACE, for instance — is refused as absent rather than
accepted as present, and surrounding padding never reaches a stored record, an audit reason or the
`clientOrderId` idempotency key. `U+00A0` NO-BREAK SPACE is not whitespace by that definition and
is deliberately left in place.

**The shape of the entity is settled before any field is read.** A request body must be exactly one
JSON object, each of whose declared fields carries one value, and the object must be the whole
entity. Three refusals enforce that, all `400`:

| Body | Answer |
| --- | --- |
| A declared field carrying a JSON array or object — `"limitPrice": [1,2,3]`, `"quantity": [1,2]`, `"owner": ["a1","a2"]` | `400` naming the field: `limitPrice must be a single value, not a JSON array` |
| Anything after the end of the document — `{…}xyz`, a second appended document, a stray byte | `400 request body is not valid JSON` |
| Fewer bytes delivered than the request's own `Content-Length` declared | `400 request body is not valid JSON` |

The first exists because a JSON-B implementation binds an array to a single-valued field by taking
its **last** element: left alone, `"limitPrice": [1,2,3]` fills at `3` while any gateway, inline
control or audit reader that takes the first element believes the price was `1`, and
`"symbol": ["RSTRA"]` is unwrapped before the restricted-symbol control ever sees a symbol. The
second and third exist because a deserializer stops reading at the closing brace: without them a
body carrying a second order, or a message the caller never finished sending, is indistinguishable
from a clean submission to the caller and yet carries an instruction this service never ran. Padding
the document with the whitespace RFC 8259 allows between tokens is not trailing content, so an
indented or newline-padded body is still accepted.

What this does **not** refuse is a property the request model does not declare. Unknown members are
ignored, structures included, so a caller that posts a response entity back — `execution` object,
`controlResults` array and all — is answered with the engine's own values rather than a `400`; only
the fields that bind are held to one value. A field of the wrong *scalar* type is refused where it
always was: a `"quantity": true` cannot be read at all and answers the fixed
`request body is not valid JSON`, while a numeric string is read and then validated.

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
zero, negative or above the maximum page size of **500** becomes 500. A value that is not a whole
number at all — `limit=abc`, `offset=2.5` — is clamped the same way and reads as absent, answering
`200` with the nearest page that exists rather than an error. Ordering is the one each endpoint
already documented — order id, exception id, client then symbol, audit sequence — and it is
established over the whole collection before the page is cut, so consecutive pages neither overlap
nor skip a record.

Only the page bounds behave this way. A *filter* value that cannot be interpreted — `status=BOGUS`
on `GET /exceptions` — is refused with `400`, because a filter has no nearest honest reading and
answering a narrowed query with the whole collection would be worse than refusing it.

**What a page tells you about the collection it came from.** The body is the same bare JSON array
the endpoint has always returned — no envelope, so nothing that reads these collections has to
change — and the metadata travels in headers beside it:

| Header | Meaning |
| --- | --- |
| `X-Total-Count` | Records in the whole collection this page was cut from, after any `status`, `owner`, `entityType` or `entityId` filter. Compare it with the page size to know whether you are holding all of it |
| `X-Page-Offset` | The offset this page starts at, after clamping |
| `X-Page-Limit` | The page size **actually applied**, after clamping. This is the number a traversal steps by — a `limit` of 1000 is served as 500, and a walk stepping by what it asked for would skip half the collection |
| `Link` | RFC 8288 relations `first`, `prev`, `next` and `last`, each a path-and-query reference — `</execution-control/orders?offset=1&limit=1>` — carrying every other query parameter this request sent. No scheme and no host, for the reason the `Location` header carries none either, so a target is resolved against the URL the page was read from. `next` is present only when records remain, so its absence is the end of the collection |

`GET /audit` is where this matters most: it returns at most 500 of a timeline that can hold 150,000
events, so reading the timeline in full is a traversal rather than one call — and `X-Total-Count`
is what tells a consumer that, rather than leaving a full page to look like a complete record.

**Traversal by `Link` is the intended access pattern.** Follow `rel="next"` until it is absent; the
service computes each offset, so no caller has to. Each target is a path and query rather than a
full URL, so a walk joins it to the base URL it started from — one line in a client, and the reason
nothing a caller puts in `Host` can send a walk somewhere else. Traversing by hand instead works on
the same terms — step `offset` by `X-Page-Limit`, never by the limit you asked for — and either way
`X-Total-Count` is the number of records a complete walk will have read.

Over a collection nothing is writing to, the walk is exact: the ordering is established over the
whole collection before the page is cut, so incrementing `offset` by the applied limit neither
repeats nor misses a record. Two independent termination conditions agree — the absence of a `next`
link, and the first page shorter than the applied limit — and an empty page is the end either way.

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
# Read the whole timeline by following the next link. The first response says how many events
# there are; the walk ends when the service stops offering a next page. Each link is a path, so
# the walk resolves it against the base URL it chose - which is what a client does with a relative
# reference, and what keeps the traversal on the host it started on.
BASE="https://localhost:9443"
NEXT="/execution-control/audit?limit=500"
while [ -n "$NEXT" ]; do
  HEADERS=$(curl -k -s -u stock:trader -D - -o /tmp/ec-page.json "$BASE$NEXT")
  printf 'total=%s applied-limit=%s\n' \
    "$(printf '%s' "$HEADERS" | awk 'tolower($1)=="x-total-count:"{print $2}' | tr -d '\r')" \
    "$(printf '%s' "$HEADERS" | awk 'tolower($1)=="x-page-limit:"{print $2}' | tr -d '\r')"
  cat /tmp/ec-page.json          # …or hand the page to whatever consumes it

  # The next page's path, taken from the Link header; empty on the last page, which ends the loop.
  NEXT=$(printf '%s' "$HEADERS" | tr ',' '\n' | sed -n 's/.*<\(.*\)>; rel="next".*/\1/p' | tr -d '\r')
done
```

The metadata is carried in headers rather than in a body envelope, deliberately: wrapping the
records would change every collection response from an array into an object and break every
existing caller and every example in this document, while headers add the count and the cursor
without touching the body. What a page still does not promise is a *stable* cursor across
concurrent writes — see the paragraph above on `/positions` — and the paging surface itself remains
awaiting ratification, see
[Deviations from the frozen implementation plan](#deviations-from-the-frozen-implementation-plan).
A consumer that needs a point-in-time snapshot of a collection being written to has to re-read from
`offset=0` while nothing is filling; `X-Total-Count` is exact for the instant it was taken, and is
never inferred by probing offsets.

### Latency, and where to set an SLO

Measured against a container built from this tree (`AUTH_TYPE=none`, published on loopback) on a
12-vCPU, 2.9 GB host carrying a load average near 11 from co-tenant work — conservative numbers from
a shared machine rather than a clean benchmark:

| Client | Concurrent writers | n | p50 | p95 | max |
| --- | --- | --- | --- | --- | --- |
| `curl`, a new process and connection per request | 1 | 40 | 5.2 ms | 7.9 ms | 52 ms |
| Keep-alive client, one connection reused | 1 | 100 | 3.2 ms | 4.8 ms | 41 ms |
| `curl`, a new process and connection per request | 20 | 400 | 7.1 ms | 14.0 ms | 29.8 ms |
| Keep-alive client, 20 connections reused | 20 | 800 | 5.2 ms | 9.8 ms | 19.5 ms |

Every sample is a `POST /orders` that returned `201`; throughput was 265 writes/s single-threaded
and 3,357 writes/s at 20 threads on the keep-alive client. Two things follow, and both matter to
anyone writing an SLO for this service.

**Derive p95 targets from a concurrent measurement, not a single-client one.** The tail under
concurrency sits well above the solo figure — 14 ms against 8 ms here, and the module's performance
review recorded a worst case of 34 ms p95 (54 ms max) at the same 20 writers — so a target set from
a quiet single client is a target the first burst misses. Tens of milliseconds at 20 concurrent
writers is the honest figure for this deployment shape, and it is far inside the 500 ms write and
200 ms read budgets that review applied.

**Do not read one phase's drift as degradation.** Within a single concurrent phase here, the p95 of
the first 200 requests was 20.0 ms and of the last 200 was 11.5 ms — it improved — while that review
saw one phase worsen by 5.8× and an identical repeat of it improve by 0.15×. The jitter is not the
service ageing under its own state: the review recorded zero garbage collections across a
1,000-request write run, and sequential writes at 2,491 orders were faster than at 89 orders. What
moves is the client and the host. A per-request-connection driver pays process and connection setup
on every sample — the keep-alive rows above are 30–40% faster at p95 at both concurrencies for
identical server work — and a shared host lends its neighbours' load to every percentile.

Before tightening anything, then: re-measure on an unshared host with a connection-reusing client,
and compare like phases rather than consecutive ones. If a tighter tail is still required after
that, the place to look is the deployment — CPU allocation, and how callers handle connections —
rather than this service's request path, which is in-memory end to end and makes no outbound call.

## Deviations from the frozen implementation plan

The plan this module was built to is frozen, and it pins the Liberty release, two test dependencies,
four build-plugin versions, the HTTP perimeter, an exhaustive 71-file inventory and an exhaustive
endpoint-and-status matrix. Each numbered row below is a delivered fact outside those pins. Every
one of them came out of the module's security review, each is deliberate, and each is recorded here
with what reverting it would cost.

**Rows 1, 2, 3 and 4** have since been re-examined against the running service and carry a recorded
decision to keep them as delivered. The rest carry no decision yet and remain **ratification
pending**. Every row's current status, the evidence behind it and what is still outstanding are in
[Decisions recorded against these rows](#decisions-recorded-against-these-rows) immediately below
the table. This register is the evidence a decision rests on rather than the decision itself:
ratifying a row into the plan is a human act, the plan is frozen and is not edited to accommodate
any of this, and until an owner acts a row's status is the one stated here.

| # | What the plan pins | What is delivered instead | Where it lives | Why it diverges |
| --- | --- | --- | --- | --- |
| 1 | Liberty **25.0.0.9** as both the container base image and the integration-test assembly, the latter as `com.ibm.websphere.appserver.runtime:wlp-webProfile10` (§0.1.3, §0.3.1, §0.6.1, §0.8.1) | Liberty **26.0.0.9** in both: the digest-pinned base image `icr.io/appcafe/open-liberty:26.0.0.9-full-java21-openj9-ubi-minimal`, and the test assembly `io.openliberty:openliberty-runtime:26.0.0.9` | `Dockerfile` `FROM`; the `liberty-maven-plugin` `<assemblyArtifact>` in `pom.xml`; quoted in [Container](#container) and [Build and test](#build-and-test) | Liberty 17.0.0.3 through 26.0.0.7 sit inside the servlet request/response smuggling advisories **CVE-2026-15064** (CWE-444, inconsistent interpretation of HTTP requests, CVSS 3.1 **8.7 High**) and **CVE-2026-15325**; the fixes first ship in 26.0.0.8. The Web Profile assembly publishes no release past 26.0.0.4 — itself inside that range — so the full runtime assembly is the only way to hold the test server at the release the image runs |
| 2 | `org.apache.cxf:cxf-rt-rs-client` **4.1.1** (§0.3.1) | **4.1.8**, test scope | `pom.xml` test dependencies | 4.1.1 brings `cxf-core` 4.1.1, affected by **CVE-2026-49875** / GHSA-gw93-jmqp-6572 (XXE, CWE-611) and fixed in 4.1.6. The supply-chain review then found a second advisory on the same artefact — **CVE-2026-50645** / GHSA-ghvc-7hp8-2g2v, no per-message limit on attachment headers (CWE-770, availability High), fixed in 4.1.7 — which is why the delivered version is 4.1.8 rather than 4.1.6 |
| 3 | `org.eclipse.parsson:parsson` **1.1.7** (§0.3.1) | **1.1.9**, test scope | `pom.xml` test dependencies | **CVE-2026-9563**: 1.1.5 through 1.1.7 impose no JSON input-size limit (CWE-400). The fix is 1.1.8; the NVD record scores it 7.5 and names "published Maven Central artifacts before version 1.1.8" |
| 4 | A single `httpEndpoint`, mirroring the broker's (§0.6.1) | Two endpoints, one per scheme, each with its own `<headers>` policy — `X-Content-Type-Options`, `Cache-Control`, `X-Frame-Options`, `Referrer-Policy` and a conditional `Content-Security-Policy` on both, `Strict-Transport-Security` on the TLS one, with `$WSEP` and `io.openliberty.trace` removed from both — plus `httpOptions removeServerHeader="true"` and `webContainer disableXPoweredBy="true"` | `src/main/liberty/config/server.xml`; described under [Standalone Kubernetes deployment](#standalone-kubernetes-deployment) | The mirrored endpoint advertised the server signature, `X-Powered-By` and `$WSEP` and set no `X-Content-Type-Options`, cache policy or HSTS (CWE-200, CWE-693). Liberty scopes a header policy to an endpoint and not to a scheme, so one endpoint serving both ports cannot assert HSTS to TLS clients alone — and RFC 6797 has a cleartext client ignore it. The three document policies close the same class of gap on the HTML this origin still serves — Liberty's `403` page and the Swagger UI — where framing and referrer leakage were unrestricted (CWE-1021, CWE-200); and `io.openliberty.trace`, which `mpTelemetry` adds to every application response, named the runtime and handed every caller a per-request trace id, which is exactly what the other three suppressions exist to prevent |
| 5 | An exhaustive 71-file inventory, with three typed lifecycle failures and three `ExceptionMapper`s (§0.2.3, §0.6.1) | 78 files: a fourth typed failure, a fourth mapper, one package-private paging helper, and the four capacity classes that make the ceilings configurable and publish their headroom | `lifecycle/CapacityExceededException.java`, `rest/CapacityExceededExceptionMapper.java`, `rest/PageBounds.java`, `dao/CapacityLimits.java`, `dao/CapacityLimitsProducer.java`, `dao/AdmissionCounter.java`, `health/AdmissionCapacityReport.java` | The ceilings under [Admission capacity](#admission-capacity) need a refusal no existing mapper expresses, and one clamped page-bounds helper keeps the 500-record policy in a single place. Both answer CWE-770 / CWE-400 — unbounded growth in a process that evicts nothing. The four capacity classes make each ceiling one environment variable read through the same MicroProfile Config mechanism as the control limits, and surface its remaining headroom in the health data, so an exhausted ceiling is an operator-sizeable and observable condition rather than a permanent refusal cleared only by a restart |
| 6 | The status matrix 201 and 200 on success, 400, 401, 403, 404 and 409 on failure (§0.7.1, §0.8.4) | The same, plus **`503 Service Unavailable`** for an exhausted ceiling, whose `message` names the `*_CAPACITY` variable to raise | `rest/CapacityExceededExceptionMapper.java`; documented beside the failure-code table under [API endpoints](#api-endpoints) and in full under [Admission capacity](#admission-capacity) | The same root cause as row 5. `503` rather than `429` because the exhausted ceiling belongs to the service and not to the caller, and it is decided after identity and legality, so no caller loses the `400`, `404` or `409` it earned. The ceilings are configurable, so the body carries the remedy — the variable to raise — rather than only the diagnosis, and `admission` in the health data reports whether the service is still accepting before any caller sees this code |
| 7 | Fourteen handlers whose only query parameters are `status`, `owner`, `entityType` and `entityId` (§0.7.1) | The same fourteen handlers, five of them additionally accepting optional `offset` and `limit` and answering with `X-Total-Count`, `X-Page-Offset`, `X-Page-Limit` and an RFC 8288 `Link`; `POST /orders` additionally answers with `Location` | `rest/{OrderResource,SettlementExceptionResource,AuditResource,ReferenceDataResource}.java`, `rest/PageBounds.java`; documented under [Paging the collection endpoints](#paging-the-collection-endpoints) | A full read serialized the whole estate. The parameters are optional and clamped rather than validated, so a caller that sends neither sees exactly the body it saw before for any collection under 500 records. The metadata is what keeps the bound honest against §0.1.1's audit timeline "readable in full": a page that reports the size of the collection it was cut from is traversable, where a silently truncated one reads as complete. Headers and not a body envelope, so every collection response stays the bare JSON array §0.7.2's labelling contract and every example here describe |
| 8 | The same 71-file inventory and its three `ExceptionMapper`s (§0.2.3, §0.6.1) | 80 files: two further mappers, `JsonbExceptionMapper` and `ProcessingExceptionMapper`, so a request body JSON-B cannot read answers `400` with the fixed text `request body is not valid JSON` instead of the runtime's default `500` carrying the deserializer's own message | `rest/JsonbExceptionMapper.java`, `rest/ProcessingExceptionMapper.java` | The plan's `400` for a bad request (§0.7.1) had no mapper behind it for a body that never reached validation, and the default answer named internal types and fields — CWE-209. `400` is already in the plan's status matrix, so this adds files, not a status |
| 9 | `web.xml` carrying the roles and the three constraint elements of §0.7.1 and nothing else, with the plan's whole error surface being those `ExceptionMapper`s (§0.6.1, §0.7.1) | The same constraints, plus six `<error-page>` mappings, the servlet they resolve to, and one further mapper, so a refusal decided before or after a resource method runs carries the same `{status, error, message, path}` envelope as one the application decides — held there on every request by two runtime settings the plan does not mention, `-DinvocationCacheSize=0` in `jvm.options` and `webContainer displayCustomizedExceptionText` in `server.xml`, and carved out in exactly one place: a URI the web container will not decode is refused before a web application is selected and answers the runtime's own `400`, which no error page, filter or Liberty setting can replace | `src/main/webapp/WEB-INF/web.xml`, `rest/ContainerErrorServlet.java`, `rest/WebApplicationExceptionMapper.java`, `src/main/liberty/config/jvm.options`, `src/main/liberty/config/server.xml`; described under [API endpoints](#api-endpoints) | A URI the web container refuses to decode was answered with the container's HTML page naming the runtime class and line that threw (CWE-209), and a path, method, media type or `Accept` header the Jakarta REST runtime refused was answered with a status and no body at all. Every one of those statuses is already in the plan's matrix, so this adds files, not a status. The two runtime settings close the same disclosure where the mappings alone could not reach it: the invocation cache kept a servlet wrapper for a URI the container had refused and answered every second encoded-separator traversal with a `500` naming the class and line that threw, and the customized exception text bounds any container-decided page that remains. The carve-out is published rather than papered over, with the gateway rule that closes it, so the envelope this service promises is the envelope it delivers |
| 10 | The three stores and `AuditTimeline` have "no dependencies and a single public no-arg constructor" (§0.6.2) | Each also has a public `@Inject` constructor taking `CapacityLimits`; the public no-arg constructor is retained and delegates to `CapacityLimits.defaults()` | `dao/{OrderStore,SettlementExceptionStore,ReferenceDataStore}.java`, `audit/AuditTimeline.java` | A ceiling that is configuration has to reach the structure it bounds, and the constructor is the only place it can arrive once and be final. The no-arg constructor stays, so CDI can still generate the `@ApplicationScoped` proxy and the unit tests still build the whole graph with `new` and no mocking library; a graph built that way carries exactly the shipped defaults, so nothing the plan specifies changes for a deployment that configures nothing. The alternative — threading a ceiling through `append(...)`, `evaluateAndFill(...)` and `putPosition(...)` — would scatter the invariant across every caller and let two callers of one store disagree on it |
| 11 | Two telemetry keys in `microprofile-config.properties`, `otel.sdk.disabled=false` and `otel.exporter.otlp.endpoint`, mirroring the broker (§0.3.2.2, §0.6.1) | The same two, and a third: `otel.propagators=tracecontext` | `src/main/resources/META-INF/microprofile-config.properties`; documented under [Telemetry](#telemetry) | The SDK is enabled, and OpenTelemetry's own propagator default installs the W3C baggage propagator, which below 1.62.0 allocates without bound while parsing an inbound `baggage` header — **CVE-2026-45292** / GHSA-rcgg-9c38-7xpx (CWE-770), on unauthenticated request paths as much as authenticated ones. No Open Liberty release ships a fixed SDK: 26.0.0.9, the newest, carries OpenTelemetry 1.48.0. Naming only `tracecontext` leaves that propagator uninstalled while trace-context propagation and every `@WithSpan` span survive, so the control costs nothing the plan asked for |
| 12 | `maven-war-plugin` **3.4.0**, `maven-compiler-plugin` **3.14.0**, `maven-resources-plugin` **3.3.1** and `liberty-maven-plugin` **3.11.5**, with no plugin-level dependency overrides (§0.3.1) | **3.5.1**, **3.16.0**, **3.5.0** and **3.12.3**, plus four pinned plugin dependencies: `plexus-utils` 4.0.3 on the war, resources and JaCoCo plugins, `commons-io` 2.22.0 on JaCoCo, and `plexus-archiver` 4.14.0 on the war plugin | `pom.xml` `<build><plugins>` | The plan's pins put eight advisory groups on the build classpath: `plexus-utils` CVE-2025-67030, `commons-io` CVE-2024-47554, `plexus-archiver` CVE-2023-37460, three `commons-compress` advisories, `commons-lang3` CVE-2025-48924, `snappy` CVE-2024-36124 and two `jackson-core` advisories. The version bumps close every one except `plexus-utils` and JaCoCo's `commons-io`, for which no release of the owning plugin ships a fixed artefact — hence the overrides. `plexus-archiver` 4.14.0 is pinned because the 4.10.4 that war 3.5.1 resolves brings `io.airlift:aircompressor` 0.27 (CVE-2025-67721), and 4.14.0 drops that dependency rather than upgrading it. JaCoCo itself stays at the pinned 0.8.13: no later release fixes anything here. None of these artefacts ships in the WAR or the image, so the exposure is the build host — which is where the estate's release engineering runs |
| 13 | The war plugin configured with `failOnMissingWebXml=false` (§0.6.1) | The same, plus `<archive><addMavenDescriptor>false</addMavenDescriptor></archive>` | `pom.xml` war-plugin configuration | The plugin otherwise writes `META-INF/maven/com.stocktrader/execution-control/pom.xml` and `pom.properties` into the deployed WAR, handing anyone who obtains that artefact a version-precise inventory of every dependency it was built against (CWE-200). Liberty never reads `META-INF/maven`, so nothing at runtime notices the absence |
| 14 | The seven build plugins of §0.3.1, and no others | The same seven, plus `org.owasp:dependency-check-maven` 13.0.0 inside an opt-in `dependency-check` profile that no default build activates | `pom.xml` `<profiles>`; documented under [Dependency and image scanning](#dependency-and-image-scanning) | The estate carries no software-composition analysis, and the review that asked for one could not run it: Dependency-Check 9 and later require an NVD API key, which no build host here provides. Carrying the profile makes the scan a pinned, configured, ready-to-run procedure the moment a key exists, instead of a command someone reconstructs under pressure. It is inactive by default, so a build without a key behaves exactly as the plan's build does |
| 15 | `includes/{basic,none,oidc,ldap}.xml` as verbatim copies of the broker's, whose byte-for-byte identity is what the estate's JWT trust rests on (§0.2.3, §0.4.2) | The same four files with exactly one element deleted from three of them: `<jwtBuilder id="defaultJWT" keyStoreRef="defaultTrustStore" keyAlias="jwtSigner" issuer="${JWT_ISSUER}" audiences="${JWT_AUDIENCE}"/>` is gone from `none.xml`, `basic.xml` and `ldap.xml` (`oidc.xml` never carried it). Nothing else in any of the four differs, and the `mpJwt` consumer, `trust.p12` and the `${JWT_ISSUER}`/`${JWT_AUDIENCE}` values are untouched | `src/main/liberty/config/includes/{none,basic,ldap}.xml`; described under [Server and identity](#server-and-identity) and [The runtime endpoints beside the application](#the-runtime-endpoints-beside-the-application) | That element made Liberty's token endpoint sign with the estate's shared `jwtSigner` key, so every identity the active registry authenticated — including accounts holding neither role — could mint an RS256 token carrying the estate issuer and audience, which the sibling services that bind `StockTrader` to `ALL_AUTHENTICATED_USERS` would honour as a write privilege (CWE-269, CWE-522). The estate trust the plan protects is carried by the `mpJwt` consumer and `trust.p12`, both unchanged, so nothing about accepting the estate's tokens moves; what the deletion removes is this module's ability to *issue* them. Deletion alone would not close it — the runtime auto-provides a builder (row 12) — but leaving the estate alias declared in a file whose only purpose here is verification would re-arm the endpoint the moment row 12 were reverted |
| 16 | `server.xml` carrying the feature set, the port variables, the keystores, the `AUTH_TYPE`/JWT variables, the include, `ltpa` and the `webApplication`, with the two documented omissions and nothing else (§0.6.1, §0.4.2) | The same, plus three elements between `ltpa` and `webApplication`: `<webAppSecurity useAuthenticationDataForUnprotectedResource="false" singleSignonEnabled="false" ssoRequiresSSL="true" sameSiteCookie="Strict"/>`, `<httpDispatcher enableWelcomePage="false" appOrContextRootMissingMessage="Not Found"/>` and `<jwtBuilder id="defaultJWT" keyStoreRef="defaultKeyStore" keyAlias="execution-control-mints-no-tokens"/>` | `src/main/liberty/config/server.xml`; described under [Server and identity](#server-and-identity), [The runtime endpoints beside the application](#the-runtime-endpoints-beside-the-application) and [Authentication across a restart](#authentication-across-a-restart) | Each closes one measured defect that no application code can reach. `useAuthenticationDataForUnprotectedResource="false"`: Liberty authenticated the unprotected runtime endpoints opportunistically, and in the registry-less MP-JWT modes the basic-auth authenticator then dereferenced a null `UserRegistry`, so any `Authorization` header turned `/health/*` and `/openapi` into `500` with an internal `NullPointerException` — the platform probe contract of §0.4.5 and §0.7.1 failing for any ingress that forwards a credential (CWE-248, CWE-209). `singleSignonEnabled="false"`: the `StockTraderSSO` cookie was a complete credential, was accepted over the cleartext listener and was evaluated ahead of the `Authorization` header, so the audit `actor` followed the cookie rather than the presented credential, which §0.4.2 and §0.7.2 require (CWE-287, CWE-614); `ssoRequiresSSL` and `sameSiteCookie` remain as defence in depth for a deployment that re-enables SSO. `httpDispatcher`: the server root served Liberty's welcome page naming the product and its exact release, and a miscased probe path served its "Context Root Not Found" page (CWE-200). `jwtBuilder`: the fix for row 11 — the endpoint cannot be unpublished, because `jwt-1.0` arrives with the mandated `microProfile-7.1` umbrella and its bundles expose no setting, and a deleted builder is auto-provided again with the same estate key, so pinning the id to an alias no keystore holds is what stops it signing; it lives in `server.xml` rather than an include so it covers all four `AUTH_TYPE` modes. No port, context root, status, endpoint, role or feature changes, and the module's own gate is green on all of it |

### Decisions recorded against these rows

| Row(s) | Decision of record | Recorded | Still outstanding |
| --- | --- | --- | --- |
| 1 | **Keep as delivered.** Do not revert the Liberty runtime to 25.0.0.9 — neither the base image nor the test assembly | 2026-09-22, by the module's infrastructure and configuration review (its finding F02), which re-verified the delivered runtime against a running container built from this tree; re-affirmed the same day by its supply-chain review (finding G4-10), which confirmed 26.0.0.9 is the current Open Liberty fix pack and that no later release exists | An owner's ratification of the plan amendment, **and the estate-level action this row implies**: `broker`, `portfolio` and `trader` still run the tag-only `icr.io/appcafe/open-liberty:25.0.0.9-full-java21-openj9-ubi-minimal`, inside the affected range of CVE-2026-15064, CVE-2026-15325, CVE-2026-15328 and CVE-2026-14981. Each should be moved to `26.0.0.9` and digest-pinned. Those modules are outside this module's write scope, so the change belongs to whoever owns them |
| 2, 3 | **Keep as delivered.** Do not revert the test REST client to `cxf-rt-rs-client` 4.1.1 or the test JSON parser to `parsson` 1.1.7 | 2026-09-22, by the module's supply-chain security review (findings G4-10 and G4-02), which re-verified both advisories against the NVD and OSV records and both delivered versions against the resolved dependency tree | An owner's ratification of the plan amendment |
| 4 | **Keep as delivered.** Keep the two scheme-specific `httpEndpoint` elements and the header policy | 2026-09-22, by the same review (its finding F03), which re-verified both listeners and their response headers against that container | An owner's ratification of the plan amendment |
| 11, 12, 13, 14 | None recorded — ratification pending. Each was delivered in answer to a specific supply-chain finding — G4-01, G4-02, G4-04 and G4-03 respectively — and each is verified in this tree rather than argued; the evidence is below | — | An owner's decision on each |
| 5, 6, 7, 8, 9, 10, 15, 16 | None recorded — ratification pending | — | An owner's decision on each |

What that review observed, so either decision can be audited without re-running it.

**Row 1.** The running container reports `Open Liberty 26.0.0.9` — `productInfo version` and the
launch banner `Open Liberty 26.0.0.9/wlp-1.0.117.cl260920260824-0859` on the image's OpenJ9 Java 21
— the `Dockerfile` pins that release by digest, `pom.xml` holds the matching
`openliberty-runtime` 26.0.0.9 test assembly, and the module's own gate is green on both. IBM's
bulletin for the servlet smuggling advisories names Liberty **17.0.0.3 through 26.0.0.7** as
affected — CVE-2026-15064 (CWE-444; scored 8.9 by IBM and 8.7 in the CVE record, which is the figure
row 1 quotes), CVE-2026-15325 (8.7), CVE-2026-15328 (7.4) and CVE-2026-14981 (CWE-400, 7.5) — and
offers a release inside that range one remedy: Fix Pack **26.0.0.8** or later, or the interim fix
for APAR PH72191. The plan's 25.0.0.9 is inside the range and the delivered 26.0.0.9 is outside it,
which is why the revert was refused outright rather than scheduled.

**Row 4.** Both listeners answer `GET /execution-control/controls` with `200` to `stock:trader` —
cleartext on `${default.http.port}` and TLS on `${default.https.port}`, the latter over HTTP/2 — and
Liberty logs them separately as `CWWKO0219I … defaultHttpEndpoint` and
`CWWKO0219I … defaultHttpsEndpoint-ssl`. Neither response carries a `Server` or `X-Powered-By`
header, both carry `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`, and only the TLS
response carries `Strict-Transport-Security: max-age=31536000` — the per-endpoint scoping that one
shared endpoint cannot express.

The three document policies and the removal of `io.openliberty.trace` were added to both blocks
afterwards, on the module's runtime security review, which measured the policy over thirteen
response classes across both listeners — `200`, `401`, `403` (Liberty's own page, the one HTML
error document left), `404`, `405`, the pre-dispatch `400`, an `OPTIONS` refusal, `/health/ready`,
`/openapi` and `/openapi/ui/` — and found `X-Frame-Options`, `Content-Security-Policy` and
`Referrer-Policy` absent from every one of them, and `io.openliberty.trace` present on every
response the application produced, carrying that request's own trace and span id. All thirteen now
answer with `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and no trace header, and twelve
of them with `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`. The
thirteenth is `/openapi/ui/`, which keeps the runtime's own `default-src 'self'; script-src 'self'
'unsafe-inline'; …` and still renders every operation — the reason that one header is conditional,
measured rather than assumed: a browser enforces the intersection of two policies, so overriding
this one blanks the page. Framing was then attempted from a cross-origin page: both the JSON API
and the Swagger UI were refused, the first by `frame-ancestors 'none'` and the second by
`X-Frame-Options: DENY` alone, each frame ending as an empty document.

**Rows 2 and 3.** `mvn dependency:list` on the delivered tree resolves `org.apache.cxf:cxf-core`
4.1.8 and `org.eclipse.parsson:parsson` 1.1.9, and an OSV.dev query over all 60 resolved coordinates
returns no advisory against either. The same query returns GHSA-ghvc-7hp8-2g2v against `cxf-core`
4.1.1, with 4.1.7 as its first fixed release; parsson's advisory is published through the NVD rather
than OSV, and that record gives CVE-2026-9563 a base score of 7.5 with the fix in 1.1.8. Both
artefacts are test scope and absent from the WAR and the image, so the revert cost is a build-time
exposure rather than a runtime one — and there is nothing on the other side of it to gain.

**Rows 11 to 14.** Each is verified in the tree rather than argued. Row 11: an invalid
`otel.propagators` value placed in the shipped properties file makes the running container log
`io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException: Unrecognized value for
otel.propagators`, which is what proves the file's value — not a default — is what the SDK
autoconfiguration reads, and the shipped `tracecontext` produces no such error while the same
container answers `200` to a request carrying a `baggage` header. Row 12: the plugin realms Maven
populates carry `plexus-utils` 4.0.3, `commons-io` 2.20.0 or later, `plexus-archiver` 4.14.0,
`commons-compress` 1.28.0, `commons-lang3` 3.18.0 or later and `jackson-core` 2.21.4, and an OSV
query over all 79 coordinates on the seven declared plugins' realms returns nothing. Row 13: the
packaged WAR has no `META-INF/maven` entry, and its manifest still carries only `Created-By` and
`Build-Jdk-Spec`. Row 14: `mvn -Pdependency-check dependency-check:check` reaches the pinned plugin
and stops only at the absent NVD key, while a default `mvn clean verify` never mentions it.

A recorded decision is narrower than a ratification. It settles the code — this is what ships, and
this is why a revert was refused — and it leaves the plan amendment, the act that makes a delivered
fact the pinned one, to the owner. Until that happens, read rows 1 to 4 as settled for the code and
open for the plan, and the remaining rows as open for both.

### What ratification does and does not decide

Functionally none of these rows changes anything the plan specifies. The same three Liberty features
are enabled, the ports, context root, WAR name and probe paths are unchanged, the fourteen handlers
and their success codes are unchanged, and the module's own gate — unit tests, integration tests
against a started Liberty server, and both line-coverage gates — is green on the delivered tree.
Rows 1 to 4 are a runtime and a perimeter the plan could not have named, because the advisories
post-date it; rows 5 to 7 are the smallest surface that bounds a service holding all of its state in
memory; rows 8 and 9 close the two answers the plan's status matrix lists but left to a default — a
client-triggerable `500` carrying the deserializer's own message, and a refusal rendered by the
container or the Jakarta REST runtime rather than by this service — row 10 is what lets those bounds
be sized by the operator who has to live with them, and rows 11 to 14 are the build descriptor and
one configuration key: an advisory in the runtime's own telemetry library that only configuration
can reach, advisory-bearing artefacts on the build classpath, a dependency inventory the deployed
artefact need not carry, and a scan procedure the estate did not have. Rows 15 and 16 are the identity and
runtime-perimeter elements the module's security review measured on the running service: they move
no endpoint, status, role or feature, and one of them is what stops this service issuing
estate-signed tokens.

**No row may be reverted to bring the code back to the plan.** Reverting row 1 returns the service
to a Liberty release inside the smuggling-advisory range; rows 2 and 3 to a test client and parser
with known CVEs; row 4 to advertising its product and version and asserting no content-type or cache
policy over authenticated JSON; rows 5 to 7 to stores that grow until the heap is gone and
collection reads that serialize everything they hold; row 8 to a malformed body answered `500`
with the deserializer's text; row 9 to an HTML error page naming the runtime class that threw and to
bodyless statuses no client can parse; row 10 to ceilings no deployment can size, where the only
remedy for an exhausted one is a restart that discards every record; row 11 to parsing an inbound
`baggage` header with an OpenTelemetry release that puts no bound on it, on unauthenticated requests
included; row 12 to eight advisory groups on the build classpath, one of them a path traversal in
the archive extractor the build runs; row 13 to shipping a version-precise dependency inventory
inside the deployed WAR; and row 14 to having no scan procedure at all, which is how the estate
arrived at a module-by-module reconstruction of one. Reverting rows 15 and 16
returns the service to issuing estate-signed tokens to every identity its registry authenticates, to
probes that answer `500` to any credential presented, to an SSO cookie that outranks the credential
on the request carrying it, and to a welcome page naming the product and its exact release.

What an owner has to settle is narrower than the list looks, because some rows are one decision:
row 1 is a single runtime choice spanning the image and the test assembly, which must stay in step
on every upgrade, rows 5 and 6 are a single refusal design — the status is what the mapper exists to
return — and rows 12 to 14 are one build descriptor, none of which reaches the deployed service.
Declining a row is only sound if its replacement carries the same protection: for row 1 that means
Liberty 26.0.0.8 or later whatever assembly it arrives in, for rows 5 to 7 an eviction policy or a
datastore, never the removal of the ceilings, for row 8 any mapper that answers the same fixed
`400`, for row 9 any arrangement that keeps container internals out of an error body and puts this
service's envelope on the statuses it does not decide, for row 10 any other route by which a
configured ceiling reaches the structure it bounds — eviction is not one, because the timeline has
no delete path and discarding orders would break the `clientOrderId` idempotency contract — for row
11 a runtime carrying OpenTelemetry 1.62.0 or later, after which the upstream propagator list is
safe again, and for rows 12 to 14 any other way of keeping the build classpath clear of known-
vulnerable artefacts, the dependency inventory out of the shipped artefact, and a runnable scan
within reach.

**Ratifying a row** means recording a decision against its number — accepted as a deviation from
the plan, or accepted as an addition to the plan's file, API, configuration and build inventories
for rows 5 to 14 — with the owner's name and the date, in whatever register governs the plan; the
plan itself is frozen and is not edited to accommodate any of this. The rows are numbered so a
decision can cite one without restating it, and a ratified row's status belongs in
[Decisions recorded against these rows](#decisions-recorded-against-these-rows) beside its number,
so this document and that decision cannot drift apart — the same table that already carries the
keep-as-delivered decisions on rows 1 to 4. Two facts an owner should have in hand when deciding:
no row changes an interface a caller or another service depends on, and every one of them is
exercised by the module's own gate, which is green on the delivered tree.
