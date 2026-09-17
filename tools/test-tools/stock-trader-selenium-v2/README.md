# Stock Trader Selenium V2

End-to-end Selenium tests for the new Stock Trader UI (Bootstrap 5). Covers login and portfolio CRUD (create, view, buy, sell, delete) using JUnit 5 and Page Object Model.

## Prerequisites

- Java 11+ (Java 17 recommended)
- Maven 3.8+
- A desktop browser (Chrome recommended)

Notes:
- Self‑signed certificates are auto‑accepted (no manual interstitial click required).
- Selenium Manager downloads a matching driver automatically. If your environment blocks downloads, put a compatible driver on PATH.

## Project Structure

```
src/test/java/com/stocktrader/selenium/
  BaseTest.java            # Common setup/teardown and config loading
  LoginPage.java           # Page Object for Login
  LoginTest.java           # JUnit tests for login flows
  PortfolioPage.java       # Page Object for portfolio screens
  PortfolioCrudIT.java     # JUnit integration test for full CRUD flow
  utils/
    WebDriverManager.java  # Browser options (SSL bypass, etc.)
    TestDataProvider.java  # Config helper
src/test/resources/config/test-config.properties
pom.xml
```

## Configuration

Edit `src/test/resources/config/test-config.properties`:

- `app.base.url=https://128.203.77.14/trader`
- `test.browser=chrome` (chrome|firefox|edge)
- Timeouts, window size, test users

The suite derives `login` and `summary` URLs from `app.base.url`.

## Run Tests

- Run all tests (login + portfolio CRUD):
```bash
mvn -q test
```

- Run a specific test class:
```bash
mvn -q -Dtest=LoginTest test
mvn -q -Dtest=PortfolioCrudIT test
```

## Best Practices Used

- Page Object Model with explicit waits and resilient locators
- Attribute-based selectors + JS click fallback for dynamic UI
- SSL interstitial bypass enabled in `WebDriverManager`
- Maven Surefire configured to include `*Test.java` and `*IT.java`

## Failure Artifacts

When a test fails, the framework automatically saves:
- `screenshot.png`
- `pageSource.html`

Location:
```
target/artifacts/<TestClass>/<testMethod>_<timestamp>/
```

## Troubleshooting

- Chrome opens on privacy warning: handled automatically. If it persists, ensure `acceptInsecureCerts` isn’t overridden.
- Driver mismatch: update browser or let Selenium Manager fetch a compatible driver. Alternatively place chromedriver/geckodriver on PATH.
- Element not found: the UI may have changed; update locators in the relevant Page Object.

## Extending

Create a new Page Object under `selenium/` and a JUnit test extending `BaseTest`. Prefer attribute-based selectors, add explicit waits, and keep assertions focused and meaningful.