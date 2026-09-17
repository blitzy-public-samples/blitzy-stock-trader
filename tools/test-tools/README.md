# IBM Stock Trader – Test Tools

High-level tools and test suites used with the IBM/Kyndryl Stock Trader sample application. This repo aggregates end-to-end UI tests and performance/stress tools that complement the microservices in Stock Trader.

Learn more about the Stock Trader project in the IBMStockTrader GitHub organization: https://github.com/IBMStockTrader

## What’s in this repository

- loopr/ — Gatling-based load and stress testing (Scala/sbt). See `loopr/README.md` for scenarios and usage.
- stock-trader-selenium-v2/ — Selenium + TestNG UI tests (Java/Maven). See `stock-trader-selenium-v2/README.md` for setup and running.

Each tool is self-contained with its own README and build instructions. Use this root README as a high-level guide; consult subfolder READMEs for details.

## Quick start

Run UI tests (Selenium/TestNG):

```
cd stock-trader-selenium-v2
mvn test
```

Run performance tests (Gatling):

```
cd loopr
./runLoopr.sh
```

## Repository structure

- loopr/ — Scala/sbt Gatling scenarios and helpers
- stock-trader-selenium-v2/ — Java/Maven Selenium tests and test data
- LICENSE — Apache-2.0 license
- CONTRIBUTING.md — Guidelines for contributing
- CODE_OF_CONDUCT.md — Community standards
- SECURITY.md — How to report security issues
- SUPPORT.md — Where to get help

## Contributing

We welcome issues, discussions, and pull requests. Please read `CONTRIBUTING.md` before submitting changes.

## Code of Conduct

This project adheres to `CODE_OF_CONDUCT.md`. By participating, you agree to uphold this code.

## Security

Please review `SECURITY.md` for our security policy and how to responsibly report vulnerabilities.

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for full text.

## Support

See `SUPPORT.md` for ways to get help and ask questions.
