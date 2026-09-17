# Network Module

This module creates the foundational networking infrastructure for the Stock Trader application, including VNet, subnets for AKS nodes and private endpoints, and private DNS zones.

## Features

- **VNet with /26 CIDR**: Small VNet (64 IPs) for infrastructure components only
- **AKS Subnet**: /27 subnet (32 IPs) for AKS node pool
- **Private Endpoints Subnet**: /28 subnet (16 IPs) for data service private endpoints
- **Private DNS Zones**: For Redis and PostgreSQL service discovery
- **VNet Links**: Connect private DNS zones to the VNet

## Usage

```hcl
module "network" {
  source = "./modules/network"
  
  location                           = "eastus"
  resource_group_name                = "rg-stocktrader-prod"
  vnet_name                          = "vnet-stocktrader-prod"
  vnet_address_space                 = ["172.16.0.0/26"]
  
  # Subnets
  aks_subnet_name                    = "aks-subnet"
  aks_subnet_prefix                  = "172.16.0.0/27"
  
  db_private_endpoints_subnet_name   = "pe-subnet"
  db_private_endpoints_subnet_prefix = "172.16.0.32/28"
  
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
| [azurerm_virtual_network.db_vnet](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/virtual_network) | resource |
| [azurerm_subnet.aks_subnet](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/subnet) | resource |
| [azurerm_subnet.db_private_endpoints_subnet](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/subnet) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| vnet_name | Name of the virtual network | `string` | n/a | yes |
| vnet_address_space | Address space for the virtual network | `list(string)` | n/a | yes |
| aks_subnet_name | Name of the AKS subnet | `string` | n/a | yes |
| aks_subnet_prefix | Address prefix for the AKS subnet | `string` | n/a | yes |
| db_private_endpoints_subnet_name | Name of the private endpoints subnet | `string` | n/a | yes |
| db_private_endpoints_subnet_prefix | Address prefix for the private endpoints subnet | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| vnet_id | The ID of the virtual network |
| aks_subnet_id | The ID of the AKS subnet |
| db_private_endpoints_subnet_id | The ID of the private endpoints subnet |

## Network Architecture

### VNet Design
```
VNet: 172.16.0.0/26 (64 IPs total)
├── AKS Subnet: 172.16.0.0/27 (32 IPs)
│   └── AKS nodes, load balancers
└── Private Endpoints Subnet: 172.16.0.32/28 (16 IPs)
    └── Redis PE, PostgreSQL PE
```

### Subnet Allocation
- **AKS Subnet** (`172.16.0.0/27`): Hosts AKS node pool and associated load balancers
- **Private Endpoints Subnet** (`172.16.0.32/28`): Hosts private endpoints for managed services

### Benefits of Small VNet
- **Multi-affiliate Support**: Multiple affiliates can use the same overlay CIDRs
- **IP Conservation**: Minimal VNet IP consumption
- **Clear Separation**: Infrastructure vs. workload networks
- **Scalability**: Overlay networks handle application scaling

## Private DNS Integration

This module works with the `dns` module to provide:
- Private DNS zones for Redis and PostgreSQL
- VNet links for automatic DNS resolution
- Seamless service discovery for private endpoints

## Examples

### Development Environment
```hcl
module "network_dev" {
  source = "./modules/network"
  
  location                           = "eastus"
  resource_group_name                = "rg-stocktrader-dev"
  vnet_name                          = "vnet-stocktrader-dev"
  vnet_address_space                 = ["172.16.0.0/26"]
  
  aks_subnet_name                    = "aks-subnet"
  aks_subnet_prefix                  = "172.16.0.0/27"
  
  db_private_endpoints_subnet_name   = "pe-subnet"
  db_private_endpoints_subnet_prefix = "172.16.0.32/28"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "network_prod" {
  source = "./modules/network"
  
  location                           = "eastus"
  resource_group_name                = "rg-stocktrader-prod"
  vnet_name                          = "vnet-stocktrader-prod"
  vnet_address_space                 = ["172.16.0.0/26"]
  
  aks_subnet_name                    = "aks-subnet"
  aks_subnet_prefix                  = "172.16.0.0/27"
  
  db_private_endpoints_subnet_name   = "pe-subnet"
  db_private_endpoints_subnet_prefix = "172.16.0.32/28"
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Notes

- VNet CIDR should be /26 to support multi-affiliate deployments
- Subnet allocation follows the pattern: /27 for AKS, /28 for private endpoints
- This VNet is for infrastructure only; Kubernetes workloads use overlay networks
- Private endpoints require the corresponding private DNS zones (see `dns` module)
