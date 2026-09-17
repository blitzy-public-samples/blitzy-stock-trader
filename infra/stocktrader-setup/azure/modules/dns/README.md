# DNS Module

This module creates private DNS zones and VNet links for Azure managed services, enabling seamless service discovery for private endpoints.

## Features

- **Private DNS Zones**: For Redis and PostgreSQL service discovery
- **VNet Links**: Connect DNS zones to the VNet for automatic resolution
- **Service Discovery**: Automatic DNS resolution for private endpoints
- **Security**: Private DNS zones for internal name resolution
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "dns" {
  source = "./modules/dns"
  
  resource_group_name = "rg-stocktrader-prod"
  vnet_id             = module.network.vnet_id
  
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
| [azurerm_private_dns_zone.redis](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_zone) | resource |
| [azurerm_private_dns_zone.postgres](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_zone) | resource |
| [azurerm_private_dns_zone_virtual_network_link.redis](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_zone_virtual_network_link) | resource |
| [azurerm_private_dns_zone_virtual_network_link.postgres](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/private_dns_zone_virtual_network_link) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| vnet_id | ID of the virtual network | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| redis_private_dns_zone_id | The ID of the Redis private DNS zone |
| postgres_private_dns_zone_id | The ID of the PostgreSQL private DNS zone |

## DNS Zones Created

### Redis Cache
- **Zone Name**: `privatelink.redis.cache.windows.net`
- **Purpose**: DNS resolution for Redis Cache private endpoints
- **Records**: Automatically created by private endpoint

### PostgreSQL Flexible Server
- **Zone Name**: `privatelink.postgres.database.azure.com`
- **Purpose**: DNS resolution for PostgreSQL private endpoints
- **Records**: Automatically created by private endpoint

## DNS Resolution Flow

```
Application Pod
    ↓
Kubernetes DNS (10.200.0.10)
    ↓
Azure DNS
    ↓
Private DNS Zone
    ↓
Private Endpoint
    ↓
Azure Managed Service
```

## Integration with Other Modules

### Network Module
This module requires the VNet from the `network` module:
```hcl
module "dns" {
  source = "./modules/dns"
  
  resource_group_name = var.resource_group_name
  vnet_id             = module.network.vnet_id
}
```

### Private Endpoints Module
The DNS zones are used by the `private_endpoints` module:
```hcl
module "private_endpoints" {
  # ... other configuration ...
  postgres_private_dns_zone_id = module.dns.postgres_private_dns_zone_id
  redis_private_dns_zone_id    = module.dns.redis_private_dns_zone_id
}
```

## Examples

### Development Environment
```hcl
module "dns_dev" {
  source = "./modules/dns"
  
  resource_group_name = "rg-stocktrader-dev"
  vnet_id             = module.network.vnet_id
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "dns_prod" {
  source = "./modules/dns"
  
  resource_group_name = "rg-stocktrader-prod"
  vnet_id             = module.network.vnet_id
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## DNS Testing

### From Kubernetes Pod
```bash
# Test Redis DNS resolution
nslookup redis-stocktrader-prod.privatelink.redis.cache.windows.net

# Test PostgreSQL DNS resolution
nslookup pgflex-stocktrader-prod.privatelink.postgres.database.azure.com
```

### From Azure VM in VNet
```bash
# Test Redis connection
redis-cli -h redis-stocktrader-prod.privatelink.redis.cache.windows.net -p 6380 -a <password>

# Test PostgreSQL connection
psql -h pgflex-stocktrader-prod.privatelink.postgres.database.azure.com -U <user> -d postgres
```

## Troubleshooting

### Common Issues

#### DNS Resolution Fails
1. **Check VNet Link**: Ensure DNS zone is linked to VNet
   ```bash
   az network private-dns link vnet list --zone-name privatelink.redis.cache.windows.net
   ```

2. **Check Private Endpoint**: Verify private endpoint exists and is approved
   ```bash
   az network private-endpoint show --name <pe-name> --resource-group <rg>
   ```

3. **Check DNS Records**: Verify A records exist in private DNS zone
   ```bash
   az network private-dns record-set a list --zone-name privatelink.redis.cache.windows.net
   ```

#### Connection Timeouts
1. **Network Security Groups**: Check NSG rules allow traffic
2. **Private Endpoint Status**: Ensure private endpoint is approved
3. **Service Status**: Verify managed service is running

### Debugging Commands

```bash
# Check DNS zone status
az network private-dns zone show --name privatelink.redis.cache.windows.net

# Check VNet links
az network private-dns link vnet list --zone-name privatelink.redis.cache.windows.net

# Check DNS records
az network private-dns record-set a list --zone-name privatelink.redis.cache.windows.net

# Test DNS resolution
nslookup <service-name>.privatelink.<service>.windows.net
```

## Security Considerations

### DNS Security
- **Private DNS Zones**: Only accessible within the VNet
- **No Public Resolution**: Private endpoints are not publicly resolvable
- **VNet Isolation**: DNS zones are scoped to specific VNets

### Best Practices
- Use private DNS zones for all private endpoints
- Link DNS zones to the correct VNet
- Monitor DNS resolution for troubleshooting
- Consider DNS policies for advanced scenarios

## Notes

- Private DNS zones are automatically created when private endpoints are configured
- DNS resolution works seamlessly within the VNet
- No additional configuration required for applications
- Private DNS zones support standard DNS record types
- VNet links enable automatic DNS resolution for all resources in the VNet
- DNS zones can be shared across multiple VNets if needed
