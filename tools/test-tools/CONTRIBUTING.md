## Contributing to IBM Stock Trader Test Tools

Thanks for your interest in improving the Stock Trader test tooling! The goal of this repository is to make it easy for anyone to exercise the IBM Stock Trader sample application with confidence. Contributions of any size are welcome.

### Ways to Contribute

- Improve existing automation suites (Selenium, Gatling, CLI harnesses).
- Add new test modules that target un-covered microservices or APIs.
- Enhance docs, configuration examples, or developer experience.
- File issues that describe bugs, gaps, or feature ideas.

### Ground Rules

- Follow the [Code of Conduct](./CODE_OF_CONDUCT.md).
- Discuss significant changes in an issue before opening a pull request.
- Keep discussions respectful and focus on collaborative solutions.

### Development Workflow

1. Fork the repository and create a feature branch.
2. Install language-specific prerequisites listed in the module README.
3. Run the relevant test suite(s) locally and ensure they pass.
4. Update documentation or configuration samples when behavior changes.
5. Submit a pull request that references the issue you are addressing.
6. Respond to reviewer feedback and keep the branch up to date with `main`.

### Coding Guidelines

- Match the style of the module you are editing (Java, Scala, shell, etc.).
- Prefer descriptive commit messages; squash commits before merging if asked.
- Add tests for new functionality whenever practical.
- Avoid committing secrets or credentials; use environment variables or sample placeholders.

### Issue Reporting

When filing an issue, provide:

- A concise title describing the problem or enhancement.
- Steps to reproduce (for bugs) and expected vs actual behavior.
- Environment details (OS, Java/Maven versions, browser, etc.).
- Logs, screenshots, or stack traces when available.

### Code Review Expectations

Maintainers review pull requests for correctness, clarity, and maintainability. Please allow time for review—especially for larger changes—and be prepared to iterate. Automated checks (lint, tests) must pass before merge.

### Release Notes

If your contribution changes user-facing behavior, add a summary to the relevant module README or changelog (if present) so downstream users understand the impact.

### Questions?

Start a discussion or reach out via an issue if you need guidance getting started. We are happy to help.

