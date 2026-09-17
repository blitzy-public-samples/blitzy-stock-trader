# PostgreSQL Module

This module deploys Azure Database for PostgreSQL Flexible Server with configurable compute, storage, and networking settings for the Stock Trader application.

## Features

- **PostgreSQL Flexible Server**: Managed PostgreSQL service with high availability
- **Configurable Compute**: Burstable or General Purpose compute tiers
- **Private Networking**: Designed for private endpoint integration
- **Security**: SSL/TLS encryption, Azure AD authentication
- **Backup**: Automated backups with configurable retention
- **Monitoring**: Azure Monitor integration
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "postgres" {
  source = "./modules/postgres"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-prod"
  postgres_server_name         = "pgflex-stocktrader-prod"
  administrator_login          = "pgadmin"
  administrator_login_password = "SecurePassword123!"
  
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
| [azurerm_postgresql_flexible_server.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/postgresql_flexible_server) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| postgres_server_name | Name of the PostgreSQL Flexible Server | `string` | n/a | yes |
| administrator_login | Administrator login for PostgreSQL | `string` | n/a | yes |
| administrator_login_password | Administrator password for PostgreSQL | `string` | n/a | yes |
| client_ip | Client IP address for firewall rules (null for private access) | `string` | `null` | no |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| id | The ID of the PostgreSQL Flexible Server |
| fqdn | The fully qualified domain name of the PostgreSQL server |
| administrator_login | The administrator login for the PostgreSQL server |

## Compute Tiers

| Tier | Use Case | vCores | Memory | Storage |
|------|----------|--------|--------|---------|
| Burstable | Development/Testing | 1-2 | 2-4 GB | 20-32 GB |
| General Purpose | Production | 2-32 | 4-128 GB | 32-16 TB |
| Memory Optimized | High Performance | 2-32 | 8-128 GB | 32-16 TB |

## Networking

### Connection Options
- **Public Access**: Configurable via firewall rules
- **Private Endpoint**: Recommended for production
- **VNet Integration**: Available with private endpoints

### Security Features
- **SSL/TLS**: Enabled by default
- **Azure AD Authentication**: Supported
- **Network Isolation**: Private endpoints for secure access
- **Firewall Rules**: IP-based access control

## Integration with Other Modules

### Private Endpoints
This PostgreSQL server is designed to work with the `private_endpoints` module:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  postgres_server_id = module.postgres.id
}
```

### Database Initialization
Use the `postgres_init` module to create databases and schemas:
```hcl
module "postgres_init" {
  source                       = "./modules/postgres_init"
  postgres_server_id           = module.postgres.id
  postgres_fqdn                = module.postgres.fqdn
  administrator_login          = var.administrator_login
  administrator_login_password = var.administrator_login_password
}
```

### Key Vault Integration
Database credentials can be stored in Key Vault:
```hcl
module "key_vault" {
  # ... other configuration ...
  secrets_map = {
    "database-id"       = var.administrator_login
    "database-password" = var.administrator_login_password
    "database-host"     = module.postgres.fqdn
  }
}
```

## Examples

### Development Environment
```hcl
module "postgres_dev" {
  source = "./modules/postgres"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-dev"
  postgres_server_name         = "pgflex-stocktrader-dev"
  administrator_login          = "pgadmin"
  administrator_login_password = "DevPassword123!"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "postgres_prod" {
  source = "./modules/postgres"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-prod"
  postgres_server_name         = "pgflex-stocktrader-prod"
  administrator_login          = "pgadmin"
  administrator_login_password = var.postgres_password
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Connection Examples

### From Kubernetes (via External Secrets)
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: postgres-connection
spec:
  secretStoreRef:
    name: azure-kv
    kind: ClusterSecretStore
  target:
    name: postgres-connection
  data:
    - secretKey: DB_HOST
      remoteRef:
        key: database-host
    - secretKey: DB_USER
      remoteRef:
        key: database-id
    - secretKey: DB_PASSWORD
      remoteRef:
        key: database-password
```

### From Application Code
```python
import psycopg2
import os

conn = psycopg2.connect(
    host=os.getenv('DB_HOST'),
    database="postgres",
    user=os.getenv('DB_USER'),
    password=os.getenv('DB_PASSWORD'),
    sslmode='require'
)
```

## Backup and Recovery

### Automated Backups
- **Backup Retention**: 7 days (configurable)
- **Backup Frequency**: Daily
- **Point-in-Time Recovery**: Available
- **Geo-Redundant Storage**: Optional

### Manual Backups
```bash
# Create a backup
pg_dump -h <fqdn> -U <admin_user> -d postgres > backup.sql

# Restore from backup
psql -h <fqdn> -U <admin_user> -d postgres < backup.sql
```

## Monitoring and Alerts

### Key Metrics
- **CPU Usage**: Server CPU utilization
- **Memory Usage**: Available memory
- **Storage Usage**: Database storage consumption
- **Active Connections**: Number of active connections
- **Query Performance**: Slow query detection

### Recommended Alerts
- High CPU usage (>80%)
- High memory usage (>80%)
- High storage usage (>85%)
- Connection failures
- Long-running queries (>30 seconds)

## Security Best Practices

### Network Security
- Use private endpoints for production
- Disable public access when possible
- Configure firewall rules for specific IPs
- Enable SSL/TLS connections

### Authentication
- Use strong passwords
- Consider Azure AD authentication
- Rotate credentials regularly
- Store credentials in Key Vault

### Data Protection
- Enable encryption at rest
- Use SSL/TLS for connections
- Implement row-level security
- Regular security audits

## Notes

- PostgreSQL Flexible Server name must be globally unique across Azure
- Burstable tier is suitable for development/testing
- General Purpose tier recommended for production workloads
- Private endpoints provide the most secure access method
- SSL/TLS is enabled by default
- Consider using Azure AD authentication for enhanced security
- Automated backups are enabled by default
- Point-in-time recovery is available for disaster recovery
