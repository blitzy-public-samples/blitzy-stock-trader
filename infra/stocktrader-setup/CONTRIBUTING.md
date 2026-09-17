# Contributing

Thanks for your interest in contributing! Please follow these guidelines to help us review your changes quickly and keep the project healthy.

## Development Setup
1. Install prerequisites (see README).
2. Fork and clone the repository.
3. Create a topic branch from `main`.

## Before You Commit
- Run formatting and validation:
```bash
terraform fmt -recursive
terraform validate
bash precheck.sh || true  # Precheck is interactive; run to verify environment
```
- Keep changes scoped and explain the rationale in your commit messages.
- Avoid committing secrets; use placeholders in examples.

## Pull Request Process
1. Ensure your branch is up to date with `main`.
2. Open a PR with:
   - Problem statement
   - Summary of changes
   - Testing/validation evidence (plan/apply output snippets if relevant)
   - Any breaking changes or migration notes
3. CI must pass (lint/validate).
4. A maintainer will review. Please respond to feedback promptly.

## Coding Standards
- Terraform: idiomatic HCL, variables validated, avoid hard-coded values
- Shell: POSIX/bash compatible, `set -e`, error handling, pass `shellcheck`
- Docs: concise, actionable; update README/ARCHITECTURE when changing behavior

## Issue Reporting
- Use GitHub Issues and include reproduction steps, expected vs actual behavior, and environment details (Terraform, providers, Azure CLI versions).

## License
By contributing, you agree that your contributions will be licensed under the repository's license.


