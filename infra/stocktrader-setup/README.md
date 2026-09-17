# stocktrader-setup

Infrastructure-as-code for provisioning and configuring the Stock Trader application across multiple cloud providers. This repository contains Terraform/OpenTofu modules and workflows to deploy core prerequisites (Kubernetes, databases, messaging/cache, networking, identity, and observability) and Stock Trader itself.

While the initial implementation targets Microsoft Azure, the repository is structured to support additional hyperscalers alongside Azure over time.

## Repository layout

- `azure/`: Complete Terraform for Microsoft Azure (AKS, PostgreSQL, Azure Cache, Istio add-on for AKS, plus app deployment).
- Additional providers will appear at the root (for example, `aws/`, `gcp/`). Each provider has its own README with prerequisites and steps.

## Cloud providers

- Azure: see `azure/README.md` for prerequisites, configuration, variables, and step-by-step apply/destroy instructions.
- Others (coming soon): `aws/`, `gcp/`.

## Contributing

Contributions are welcome. Please open an issue or a pull request with proposed changes. When contributing new cloud providers, place their IaC under a top-level directory (for example, `aws/`) and include a dedicated `README.md` with instructions.

## License

This project is licensed under the Apache License, Version 2.0. See `LICENSE` for details.
