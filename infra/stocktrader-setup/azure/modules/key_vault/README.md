# Key Vault Module

This module creates an Azure Key Vault with access policies for managed identity and populates it with application secrets for the Stock Trader application.

## Features

- **Azure Key Vault**: Secure secret storage with soft delete and purge protection
- **Access Policies**: Configured for user-assigned managed identity
- **Secret Population**: Pre-populated with application secrets and computed values
- **Tags**: Consistent tagging for resource management
- **Soft Delete**: Enabled with 7-day retention
- **Purge Protection**: Enabled for compliance

## Usage

```hcl
module "key_vault" {
  source = "./modules/key_vault"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  key_vault_name      = "kv-stocktrader-prod"
  uai_principal_id    = module.uai.principal_id
  
  secrets_map = {
    # Application secrets
    "cloudant-id"               = "couchdb_user"
    "cloudant-password"         = "couchdb_password"
    "database-id"               = "postgres_user"
    "database-password"         = "postgres_password"
    "database-host"             = "postgres_fqdn"
    
    # Computed secrets
    "redis-url"                 = "rediss://:key@host:6380"
    
    # Placeholder secrets (user must update)
    "mq-id"                     = "app"
    "mq-password"               = ""
    "odm-id"                    = "odmAdmin"
    "odm-password"              = "odmAdmin"
    "openwhisk-id"              = "<your id>"
    "openwhisk-password"        = "<your password>"
    "watson-id"                 = "apikey"
    "watson-password"           = "<your API key>"
    "oidc-clientId"             = "stock-trader"
    "oidc-clientSecret"         = "6cc295a0-e786-4943-b0f2-e6036d0c0c6c"
    "kafka-user"                = "token"
    "kafka-apiKey"              = "<your API key>"
    "twitter-accessToken"       = "<your access token>"
    "twitter-accessTokenSecret" = "<your access token secret>"
    "twitter-consumerKey"       = "<your consumer key>"
    "twitter-consumerSecret"    = "<your consumer secret>"
    "mongo-user"                = "<your Mongo user>"
    "mongo-password"            = "<your Mongo password>"
    "iex-apiKey"                = "<your IEX API key>"
    "encryption-password"       = "<encryption password>"
    "encryption-saltBytes"      = "<salt bytes generated>"
    "s3-apiKey"                 = "<your S3 API Key>"
  }
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Requirements

| Name | Version |
|------|---------|
| terraform | >= 1.0 |
| azurerm | ~> 4.0 |

## Providers

| Name | Version |
|------|---------|
| azurerm | ~> 4.0 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [azurerm_key_vault.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/key_vault) | resource |
| [azurerm_key_vault_access_policy.uai](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/key_vault_access_policy) | resource |
| [azurerm_key_vault_secret.secrets](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/key_vault_secret) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| key_vault_name | Name of the Azure Key Vault | `string` | n/a | yes |
| uai_principal_id | Principal ID of the user-assigned managed identity | `string` | n/a | yes |
| secrets_map | Map of secret names to values | `map(string)` | `{}` | no |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| vault_uri | The URI of the Key Vault |
| vault_id | The ID of the Key Vault |

## Security Features

### Access Control
- **Access Policies**: Configured for user-assigned managed identity
- **Permissions**: Get, List, Set, Delete secrets
- **No Public Access**: Private endpoints recommended for production

### Data Protection
- **Soft Delete**: Enabled with 7-day retention
- **Purge Protection**: Enabled to prevent permanent deletion
- **Encryption**: Azure-managed keys for data at rest

### Compliance
- **Audit Logging**: Enabled by default
- **RBAC**: Role-based access control
- **Tags**: Consistent resource tagging

## Secret Management

### Pre-populated Secrets
The module creates secrets for:
- **Database Credentials**: PostgreSQL username, password, host
- **CouchDB Credentials**: Username and password
- **Redis Connection**: Computed Redis URL with authentication
- **Application Secrets**: OIDC, Kafka, Twitter, Watson, etc.

### Secret Naming Convention
- Use lowercase with hyphens: `database-password`
- Group related secrets: `cloudant-id`, `cloudant-password`
- Include environment context in secret names

### External Secrets Integration
This Key Vault works with the `external_secrets` module to:
- Sync secrets to Kubernetes
- Provide workload identity authentication
- Enable secure secret consumption by pods

## Examples

### Development Environment
```hcl
module "key_vault_dev" {
  source = "./modules/key_vault"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-dev"
  key_vault_name      = "kv-stocktrader-dev"
  uai_principal_id    = module.uai.principal_id
  
  secrets_map = {
    "cloudant-id"               = var.couchdb_user
    "cloudant-password"         = var.couchdb_password
    "database-id"               = var.administrator_login
    "database-password"         = var.administrator_login_password
    "database-host"             = module.postgres.fqdn
    "redis-url"                 = "rediss://:${module.redis.primary_access_key}@${module.redis.hostname}:6380"
  }
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "key_vault_prod" {
  source = "./modules/key_vault"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  key_vault_name      = "kv-stocktrader-prod"
  uai_principal_id    = module.uai.principal_id
  
  secrets_map = merge(
    {
      # Core application secrets
      "cloudant-id"               = var.couchdb_user
      "cloudant-password"         = var.couchdb_password
      "database-id"               = var.administrator_login
      "database-password"         = var.administrator_login_password
      "database-host"             = module.postgres.fqdn
      "redis-url"                 = "rediss://:${module.redis.primary_access_key}@${module.redis.hostname}:6380"
    },
    var.production_secrets  # Additional production secrets
  )
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Notes

- Key Vault name must be globally unique across Azure
- Soft delete and purge protection cannot be disabled after creation
- Access policies are additive; multiple policies can grant access
- Consider using private endpoints for production deployments
- Secrets are stored as strings; binary data should be base64 encoded
- External Secrets Operator requires specific permissions to read secrets
