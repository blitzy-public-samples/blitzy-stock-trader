# Private Endpoints Module

This module creates private endpoints for Azure managed services (Redis Cache and PostgreSQL Flexible Server), enabling secure private connectivity from the VNet to these services.

## Features

- **Private Endpoints**: Secure private connectivity to Azure services
- **Redis Cache Integration**: Private endpoint for Redis Cache
- **PostgreSQL Integration**: Private endpoint for PostgreSQL Flexible Server
- **DNS Integration**: Automatic DNS record creation in private DNS zones
- **Security**: Network isolation and private connectivity
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "private_endpoints" {
  source = "./modules/private_endpoints"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-prod"
  vnet_id                      = module.network.vnet_id
  subnet_id                    = module.network.db_private_endpoints_subnet_id
  redis_id                     = module.redis.id
  postgres_server_id           = module.postgres.id
  redis_private_dns_zone_id    = module.dns.redis_private_dns_zone_id
  postgres_private_dns_zone_id = module.dns.postgres_private_dns_zone_id
  
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
| [azurerm_private_endpoint.redis](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_endpoint) | resource |
| [azurerm_private_endpoint.postgres](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_endpoint) | resource |
| [azurerm_private_dns_a_record.redis](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_a_record) | resource |
| [azurerm_private_dns_a_record.postgres](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_a_record) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| vnet_id | ID of the virtual network | `string` | n/a | yes |
| subnet_id | ID of the subnet for private endpoints | `string` | n/a | yes |
| redis_id | ID of the Redis Cache | `string` | n/a | yes |
| postgres_server_id | ID of the PostgreSQL Flexible Server | `string` | n/a | yes |
| redis_private_dns_zone_id | ID of the Redis private DNS zone | `string` | n/a | yes |
| postgres_private_dns_zone_id | ID of the PostgreSQL private DNS zone | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| redis_private_endpoint_id | The ID of the Redis private endpoint |
| postgres_private_endpoint_id | The ID of the PostgreSQL private endpoint |
| redis_private_endpoint_ip | The private IP address of the Redis private endpoint |
| postgres_private_endpoint_ip | The private IP address of the PostgreSQL private endpoint |

## Private Endpoints Created

### Redis Cache Private Endpoint
- **Service**: Redis Cache
- **Subnet**: Dedicated subnet for private endpoints
- **DNS Zone**: `privatelink.redis.cache.windows.net`
- **Purpose**: Secure private access to Redis Cache

### PostgreSQL Flexible Server Private Endpoint
- **Service**: PostgreSQL Flexible Server
- **Subnet**: Dedicated subnet for private endpoints
- **DNS Zone**: `privatelink.postgres.database.azure.com`
- **Purpose**: Secure private access to PostgreSQL database

## Network Architecture

```
VNet (172.16.0.0/26)
├── AKS Subnet (172.16.0.0/27)
│   └── Application Pods
└── Private Endpoints Subnet (172.16.0.32/28)
    ├── Redis Private Endpoint
    └── PostgreSQL Private Endpoint
```

## Integration with Other Modules

### Network Module
Requires VNet and subnet from the `network` module:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  vnet_id   = module.network.vnet_id
  subnet_id = module.network.db_private_endpoints_subnet_id
}
```

### DNS Module
Uses private DNS zones from the `dns` module:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  redis_private_dns_zone_id    = module.dns.redis_private_dns_zone_id
  postgres_private_dns_zone_id = module.dns.postgres_private_dns_zone_id
}
```

### Data Services Modules
Connects to Redis and PostgreSQL services:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  redis_id           = module.redis.id
  postgres_server_id = module.postgres.id
}
```

## Examples

### Development Environment
```hcl
module "private_endpoints_dev" {
  source = "./modules/private_endpoints"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-dev"
  vnet_id                      = module.network.vnet_id
  subnet_id                    = module.network.db_private_endpoints_subnet_id
  redis_id                     = module.redis.id
  postgres_server_id           = module.postgres.id
  redis_private_dns_zone_id    = module.dns.redis_private_dns_zone_id
  postgres_private_dns_zone_id = module.dns.postgres_private_dns_zone_id
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "private_endpoints_prod" {
  source = "./modules/private_endpoints"
  
  location                     = "eastus"
  resource_group_name          = "rg-stocktrader-prod"
  vnet_id                      = module.network.vnet_id
  subnet_id                    = module.network.db_private_endpoints_subnet_id
  redis_id                     = module.redis.id
  postgres_server_id           = module.postgres.id
  redis_private_dns_zone_id    = module.dns.redis_private_dns_zone_id
  postgres_private_dns_zone_id = module.dns.postgres_private_dns_zone_id
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Connection Testing

### From Kubernetes Pod
```bash
# Test Redis connection via private endpoint
redis-cli -h redis-stocktrader-prod.privatelink.redis.cache.windows.net -p 6380 -a <password>

# Test PostgreSQL connection via private endpoint
psql -h pgflex-stocktrader-prod.privatelink.postgres.database.azure.com -U <user> -d postgres
```

### From Azure VM in VNet
```bash
# Test DNS resolution
nslookup redis-stocktrader-prod.privatelink.redis.cache.windows.net
nslookup pgflex-stocktrader-prod.privatelink.postgres.database.azure.com

# Test connectivity
telnet redis-stocktrader-prod.privatelink.redis.cache.windows.net 6380
telnet pgflex-stocktrader-prod.privatelink.postgres.database.azure.com 5432
```

## Troubleshooting

### Common Issues

#### Private Endpoint Not Working
1. **Check Private Endpoint Status**: Ensure it's approved
   ```bash
   az network private-endpoint show --name <pe-name> --resource-group <rg>
   ```

2. **Check DNS Resolution**: Verify A records exist
   ```bash
   az network private-dns record-set a list --zone-name privatelink.redis.cache.windows.net
   ```

3. **Check Subnet Configuration**: Ensure subnet has proper delegation
   ```bash
   az network vnet subnet show --name <subnet-name> --vnet-name <vnet-name> --resource-group <rg>
   ```

#### Connection Timeouts
1. **Network Security Groups**: Check NSG rules
2. **Service Status**: Verify managed service is running
3. **Private Endpoint Approval**: Ensure endpoint is approved

### Debugging Commands

```bash
# Check private endpoint status
az network private-endpoint show --name <pe-name> --resource-group <rg>

# Check private endpoint connections
az network private-endpoint-connection list --id <pe-id>

# Check DNS records
az network private-dns record-set a list --zone-name privatelink.redis.cache.windows.net

# Test connectivity
telnet <service-name>.privatelink.<service>.windows.net <port>
```

## Security Benefits

### Network Isolation
- **Private Connectivity**: Traffic stays within Azure backbone
- **No Public Exposure**: Services not accessible from internet
- **VNet Scoped**: Access limited to specific VNet

### Compliance
- **Data Residency**: Traffic doesn't traverse public internet
- **Audit Trails**: Private endpoint connections are logged
- **Network Policies**: Can apply additional network policies

## Cost Considerations

### Private Endpoint Pricing
- **Private Endpoint**: ~$0.10/hour per endpoint
- **DNS Zones**: ~$0.50/month per zone
- **Data Processing**: No additional charges for data transfer

### Optimization
- Use private endpoints only for production workloads
- Consider shared private endpoints for multiple services
- Monitor usage and costs regularly

## Notes

- Private endpoints require Premium SKU for Redis Cache
- PostgreSQL Flexible Server supports private endpoints on all tiers
- Private endpoints provide the most secure access method
- DNS resolution is automatic within the VNet
- Private endpoints can be shared across multiple VNets
- Consider using Azure Private Link service for custom applications
- Private endpoints support both TCP and UDP protocols
- Monitor private endpoint health and connectivity
