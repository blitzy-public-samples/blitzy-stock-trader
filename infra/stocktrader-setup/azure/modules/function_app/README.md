### Function App Module (Terraform-native)

This module provisions and deploys a Python Azure Function App using Terraform resources only (no Core Tools publish in steady state):

1) Creates a Storage Account and blob container for packages.
2) Creates a Linux Flex Consumption plan (`FC1`) and a Function App (`python` 3.11, Functions v4).
3) Packages the code under `app/` into a zip (via `archive_file`).
4) Deploys the zip to the app using Azure CLI zip deploy (OneDeploy) after the app exists.

Inputs:
- `resource_group_name` – Resource group to deploy into
- `function_app_name` – Globally-unique Function App name
- `location` – Azure region

Behavior:
- Deployment is Terraform-driven; no `func publish` required.
- Code is re-deployed automatically when files under `app/` change (content hash).
- Outputs include a default host key and a ready-to-use invoke URL.

Outputs:
- `function_app_name` – The Function App name
- `default_host_key` – Default host key (sensitive)
- `function_app_invoke_url` – Invoke URL for `stock_quote`

Notes:
- The app runs on Flex Consumption; settings like `FUNCTIONS_WORKER_RUNTIME` are not used here.
- The zipped package is deployed via `az functionapp deployment source config-zip` executed by Terraform; Azure CLI must be available on the runner.
- The storage account name is derived from the Function App name with a random suffix to keep it unique and compliant.

Usage (from root):
- Set `function_app_name` in `terraform.tfvars`.
- Deploy just this module for testing:
  ```bash
  terraform plan -target=module.function_app -out fa.tfplan
  terraform apply fa.tfplan
  terraform output function_app_invoke_url
  ```

