# Security Policy

## Supported Versions
We aim to keep main and the latest tagged release secure. Older releases may not receive security updates.

## Reporting a Vulnerability
Please report suspected vulnerabilities privately.
- Email: john.alcorn@kyndryl.com
- Subject: [Security - Stock Trader] <short description>
- Include: affected versions/commit, reproduction steps, impact, and any logs (redact secrets)

We will acknowledge receipt within 3 business days and will work to address the issue, in a timeframe commensurate with its severity.

## Scope
- Terraform code in this repository
- Provided scripts (e.g., precheck.sh)
- Kubernetes and Istio manifests/templates included here

## Out of Scope
- Third-party providers, Azure platform services, and external dependencies
- Misconfigurations outside the documented usage

## Disclosure
Once a fix is available, we will open an issue explaining the problem and the fix, and will associate the commit with the fix to that issue.


