# Redis Module

This module deploys Azure Cache for Redis with configurable SKU, networking, and security settings for the Stock Trader application.

## Features

- **Azure Cache for Redis**: Managed Redis service with high availability
- **Configurable SKU**: Basic, Standard, or Premium tiers
- **Private Networking**: Designed for private endpoint integration
- **Security**: SSL/TLS encryption enabled by default
- **Monitoring**: Azure Monitor integration
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "redis" {
  source = "./modules/redis"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  redis_cache_name    = "redis-stocktrader-prod"
  redis_cache_sku     = "Standard"
  
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
| [azurerm_redis_cache.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/redis_cache) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| redis_cache_name | Name of the Redis Cache instance | `string` | n/a | yes |
| redis_cache_sku | The SKU of Redis cache (Basic, Standard, Premium) | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| id | The ID of the Redis Cache |
| hostname | The hostname of the Redis Cache |
| primary_access_key | The primary access key for the Redis Cache |
| primary_connection_string | The primary connection string for the Redis Cache |

## SKU Comparison

| Feature | Basic | Standard | Premium |
|---------|-------|----------|---------|
| SLA | 99.9% | 99.9% | 99.9% |
| Data Persistence | ❌ | ❌ | ✅ |
| Geo-Replication | ❌ | ❌ | ✅ |
| Virtual Network | ❌ | ❌ | ✅ |
| Clustering | ❌ | ❌ | ✅ |
| Redis Modules | ❌ | ❌ | ✅ |

## Networking

### Connection Options
- **Public Endpoint**: Available for Basic/Standard SKUs
- **Private Endpoint**: Recommended for production (Premium SKU)
- **VNet Integration**: Available with Premium SKU

### Security Features
- **SSL/TLS**: Enabled by default (port 6380)
- **Authentication**: Access keys for authentication
- **Network Isolation**: Private endpoints for secure access

## Integration with Other Modules

### Private Endpoints
This Redis instance is designed to work with the `private_endpoints` module:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  redis_id = module.redis.id
}
```

### Key Vault Integration
Redis connection strings can be stored in Key Vault:
```hcl
module "key_vault" {
  # ... other configuration ...
  secrets_map = {
    "redis-url" = "rediss://:${module.redis.primary_access_key}@${module.redis.hostname}:6380"
  }
}
```

## Examples

### Development Environment
```hcl
module "redis_dev" {
  source = "./modules/redis"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-dev"
  redis_cache_name    = "redis-stocktrader-dev"
  redis_cache_sku     = "Basic"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "redis_prod" {
  source = "./modules/redis"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  redis_cache_name    = "redis-stocktrader-prod"
  redis_cache_sku     = "Premium"
  
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
  name: redis-connection
spec:
  secretStoreRef:
    name: azure-kv
    kind: ClusterSecretStore
  target:
    name: redis-connection
  data:
    - secretKey: REDIS_URL
      remoteRef:
        key: redis-url
```

### From Application Code
```python
import redis
import os

redis_url = os.getenv('REDIS_URL')
r = redis.from_url(redis_url, decode_responses=True)
```

## Monitoring and Alerts

### Key Metrics
- **Connected Clients**: Number of connected clients
- **Cache Hits/Misses**: Performance metrics
- **Memory Usage**: Available memory
- **Network I/O**: Network throughput

### Recommended Alerts
- High memory usage (>80%)
- Low cache hit ratio (<90%)
- Connection failures
- High latency (>100ms)

## Notes

- Redis Cache name must be globally unique across Azure
- Basic SKU is suitable for development/testing only
- Premium SKU required for private endpoints and VNet integration
- SSL/TLS is enabled by default (port 6380)
- Access keys should be rotated regularly
- Consider using Azure Key Vault to store connection strings
- Private endpoints provide the most secure access method
