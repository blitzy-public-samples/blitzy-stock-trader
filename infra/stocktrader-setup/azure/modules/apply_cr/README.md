# Apply CR Module

This module renders and applies the Stock Trader Custom Resource (CR) into the target namespace and optionally configures Istio resources (Gateway, VirtualService, PeerAuthentication) based on the `enable_istio` toggle.

## Features

- Renders Stock Trader CR YAML from template
- Applies CR with retries and readiness checks
- Optional Istio resources when enabled:
  - PeerAuthentication (STRICT mTLS)
  - Gateway and VirtualService
  - TLS certificate generation and secrets

## Usage

```hcl
module "apply_cr" {
  source = "./modules/apply_cr"

  namespace                           = var.stock_trader_namespace
  cr_template_path                    = "${path.module}/modules/apply_cr/cr.yaml.tmpl"
  redis_url                           = "rediss://:${module.redis.primary_access_key}@${module.redis.hostname}:6380"
  stock_quote_api_connect             = module.function_app.function_app_invoke_url

  # Azure/AKS access
  subscription_id                     = var.subscription_id
  resource_group_name                 = var.resource_group_name
  aks_cluster_name                    = var.aks_cluster_name

  # Istio toggle and settings
  enable_istio                        = var.enable_istio
  istio_ingress_namespace             = var.istio_ingress_namespace
  istio_ingress_external_service_name = var.istio_ingress_external_service_name

  # App config
  credentials_secret_name             = var.credentials_secret_name
  couchdb_user                        = var.couchdb_user
  couchdb_password                    = var.couchdb_password
  couchdb_service_name                = var.couchdb_service_name
  couchdb_namespace                   = var.couchdb_namespace
  couchdb_database_name               = var.couchdb_database_name
  database_host                       = module.postgres.fqdn
}
```

## Toggle (enable_istio)

- When `enable_istio = true` (default):
  - PeerAuthentication, Gateway/VirtualService, TLS certs/secrets are rendered and applied
  - Deployments are restarted to ensure sidecar injection
- When `enable_istio = false`:
  - All Istio-related resources are skipped and not created

## Outputs

This module does not define additional Terraform outputs; use root outputs and postcheck script to access endpoints.


