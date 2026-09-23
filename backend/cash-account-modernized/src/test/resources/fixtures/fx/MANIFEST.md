# Fixture manifest — `src/test/resources/fixtures/fx`

This directory holds the module's single recorded exchange-rate API response: the body that
`fx/FrankfurterExchangeRateClient` would receive from the live rate service. Its consumer is
`src/test/java/com/ibm/hybrid/cloud/sample/stocktrader/cashaccount/fx/CurrencyConversionTest`,
which replays it through `MockRestServiceServer` instead of making a network call. One fixture plus
this manifest is the complete and intended contents of the directory — AAP §0.6.1 lists exactly one
JSON here and §0.8.3 does not wildcard the path, so nothing further belongs in it.

The provenance lives in a sidecar because it cannot live in the fixture. AAP §0.10.1 records that a
CSV reserves its first line for the column header and defines no comment syntax, and that a binary
fixture cannot carry a comment at all. A third reason applies here: JSON has no comment syntax, and
this payload is replayed byte-for-byte as an HTTP response body, so a comment would corrupt the very
contract under test. Nothing loads this manifest at build or run time — it is documentation only.

## Fixtures

| File | What it encodes | AAP section it carries | Expected test outcome |
| --- | --- | --- | --- |
| `frankfurter-latest-usd.json` | The recorded `GET {CURRENCY_API_URL}?from=USD&to=EUR` response body — the `amount`, `base`, `date` and `rates.<code>` members of the rate service's `/latest` contract | §0.6.4 (the researched `/latest` request/response contract); §0.8.1 is the row authorising the file | Replayed through `MockRestServiceServer`, the USD→EUR rate parses as `BigDecimal` `0.92` and is applied with a single final truncation |

## What `CurrencyConversionTest` asserts with it

- The cross-currency rate is applied with **one final truncation** — the truncation itself lives in
  `domain/Money.applyRate`, not in this fixture and not in the FX client.
- The recorded rate value parses as `BigDecimal`, never as `double` (AAP §0.7.1).
- A same-currency lookup short-circuits to exactly `1` **with no HTTP call**, so this fixture is not
  consulted on that path at all.
- An unavailable rate raises `ExchangeRateUnavailableException`, which the service layer renders as
  `503 EXCHANGE_RATE_UNAVAILABLE` with `Retry-After: 5`, leaving the balance unchanged and writing
  no `ledger_entry` row.
- The recorded FX request carries **no `Authorization` header** — the caller's JWT is never forwarded
  to the public rate API (AAP §0.7.5).

## Deliberate choices — do not "fix" these

**1. `"amount":1.0` stays exactly as recorded.** It is a literal from a third-party contract, not one
of the module's money values, and the consuming client never parses this field. The AAP §0.7.1
no-float prohibition governs the service's own balance, amount, rate and variance paths and the
module's own CSV fixtures; it does not license rewriting an external API's recorded payload. Beware
the near-collision: the sibling `fixtures/legacy-export/matched/frankfurt1.csv` carries an `amount`
column valued `1.00`, and that is a **different artifact** — a legacy DB2 `NUMERIC(9,2)` export
column subject to the module's fixed-point rules. Do not harmonise the two in either direction.

**2. `rates.EUR` = `0.92` and `date` = `2024-01-15` deliberately mirror** the
`EUR,USD,1.00,0.92,2024-01-15` row of `fixtures/legacy-export/matched/frankfurt1.csv`. AAP §0.12.5
requires parity to be judged on identical inputs: the live source and `fx/LegacyRateTableSource`
must return the *same* USD→EUR rate, so that no `RATE_SOURCE` divergence can originate in the
fixture corpus itself rather than in the code under test. `0.92` carries two decimals and is
therefore representable in the legacy rate column, which is `RATES DECIMAL(3, 2)`
(`backend/cash-account-cobol/COBOL/DCLFRANK.cpy:L12`) and `RATES PIC S9(1)V9(2) USAGE COMP-3`
(`DCLFRANK.cpy:L22`), ceiling 9.99. The `date` member matches that row's `loaddt`, whose legacy form
is `LOADDT DATE NOT NULL` / `PIC X(10)` (`DCLFRANK.cpy:L13, L23`). Changing either value in one file
without the other **breaks the corpus** and will surface as a spurious variance.

**3. The client reads only `rates.<code>`.** `fx/FrankfurterExchangeRateClient` parses through a
private nested `@JsonIgnoreProperties` DTO whose map value type is declared `BigDecimal`, so
`amount`, `base` and `date` are present for contract fidelity but are never read. The endpoint is
never hardcoded either: the chart injects it as `CURRENCY_API_URL` from `cashAccount.exchangeRateUrl`
(`infra/stocktrader-operator/helm-charts/stocktrader/values.yaml:147`, wired at
`templates/cash-account.yaml:156-160`). This file records only the response body — no host, no path,
no query string.

## Synthetic data, and no key material

This payload is **synthetic and hand-recorded**. No live call was ever made to the real rate service
to produce it, and no test in this module makes one; the tooling and fixtures are verified against
synthetic data only (AAP §0.3.2). The guarantee is configuration, not convention: the sibling
`src/test/resources/application-test.yml` points `cashaccount.fx.url` at a refused loopback address
(port 1), so an escaping call fails fast rather than reaching the public endpoint.

This directory contains **no API key, token or key material of any kind**. The rate service requires
no API key (AAP §0.6.4), so none was ever recorded, and AAP §0.7.5 confines the module's only
shipped key material to the public certificate at `src/main/resources/security/jwtsigner.pem`.
