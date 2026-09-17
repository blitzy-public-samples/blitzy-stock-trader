# AKS Module

This module deploys an Azure Kubernetes Service (AKS) cluster with Azure CNI overlay networking, Istio service mesh, and workload identity enabled.

## Features

- **Azure CNI Overlay**: Pods and services use overlay networks independent of VNet
- **Istio Service Mesh**: ASM with configurable revisions and ingress gateways
- **Workload Identity**: OIDC issuer and workload identity for secure pod authentication
- **Auto-scaling**: Default node pool with configurable min/max nodes
- **Monitoring**: Azure Monitor integration enabled
- **Maintenance Windows**: Automated upgrades and OS updates

## Usage

```hcl
module "aks" {
  source = "./modules/aks"
  
  location                    = "eastus"
  resource_group_name         = "rg-stocktrader-prod"
  aks_cluster_name            = "aks-stocktrader-prod"
  aks_node_vm_size            = "Standard_D4ds_v5"
  aks_subnet_id               = module.network.aks_subnet_id
  
  # Overlay networking
  aks_service_cidr            = "10.200.0.0/16"
  aks_pod_cidr                = "10.201.0.0/16"
  aks_dns_service_ip          = "10.200.0.10"
  
  # Istio service mesh
  aks_service_mesh_revisions  = ["asm-1-24"]
  
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
| [azurerm_kubernetes_cluster.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/kubernetes_cluster) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| aks_cluster_name | Name of the AKS cluster | `string` | n/a | yes |
| aks_node_vm_size | VM size for the AKS default node pool | `string` | n/a | yes |
| aks_subnet_id | ID of the AKS subnet | `string` | n/a | yes |
| aks_service_cidr | Service CIDR for AKS overlay network | `string` | n/a | yes |
| aks_pod_cidr | Pod CIDR for AKS overlay network | `string` | n/a | yes |
| aks_dns_service_ip | DNS service IP for AKS overlay network | `string` | n/a | yes |
| aks_service_mesh_revisions | List of revisions for the AKS service mesh profile | `list(string)` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| id | The ID of the AKS cluster |
| name | The name of the AKS cluster |
| host | The Kubernetes cluster server host |
| cluster_ca_certificate | The Kubernetes cluster CA certificate |
| client_certificate | The Kubernetes cluster client certificate |
| client_key | The Kubernetes cluster client key |
| kube_config_raw | The raw kube config |
| oidc_issuer_url | The OIDC issuer URL for workload identity |

## Network Configuration

### Overlay Networking
- **Network Plugin**: Azure CNI with overlay mode
- **Network Policy**: Azure network policy enabled
- **Service CIDR**: Configurable (default: 10.200.0.0/16)
- **Pod CIDR**: Configurable (default: 10.201.0.0/16)
- **DNS Service IP**: Configurable (default: 10.200.0.10)

### Benefits
- Pods receive overlay IPs, not VNet IPs
- Services receive overlay cluster IPs
- Complete isolation between infrastructure and workloads
- Scalable IP space for pods/services

## Istio Service Mesh

### Configuration
- **Mode**: Istio ASM (Azure Service Mesh)
- **Revisions**: Configurable (e.g., ["asm-1-24"])
- **Internal Gateway**: Enabled
- **External Gateway**: Enabled

### Toggle (enable_istio)
- Use the root variable `enable_istio` to conditionally enable the mesh on the AKS cluster.
- When `enable_istio = true` (default), the AKS `service_mesh_profile` is applied using Istio mode and the provided revisions.
- When `enable_istio = false`, no `service_mesh_profile` is configured on the cluster.

### Components
- `istiod`: Control plane for service mesh
- External ingress gateway for HTTPS exposure
- Sidecar proxies injected into app pods

## Security Features

- **Workload Identity**: OIDC issuer enabled for pod authentication
- **System-assigned Identity**: Cluster uses managed identity
- **Network Policies**: Azure network policy enabled
- **mTLS**: Mutual TLS encryption between services (via Istio)

## Maintenance

### Auto-upgrade Channel
- **Channel**: Patch (automatic patch updates)
- **Maintenance Window**: Sunday 00:00 UTC (4 hours)

### Node Pool Configuration
- **Auto-scaling**: Enabled (min: 2, max: 5)
- **OS Disk**: Ephemeral for better performance
- **Upgrade Settings**: Max surge 10%

## Examples

### Development Environment
```hcl
module "aks_dev" {
  source = "./modules/aks"
  
  location                    = "eastus"
  resource_group_name         = "rg-stocktrader-dev"
  aks_cluster_name            = "aks-stocktrader-dev"
  aks_node_vm_size            = "Standard_D2ds_v5"
  aks_subnet_id               = module.network.aks_subnet_id
  
  aks_service_cidr            = "10.200.0.0/16"
  aks_pod_cidr                = "10.201.0.0/16"
  aks_dns_service_ip          = "10.200.0.10"
  aks_service_mesh_revisions  = ["asm-1-24"]
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "aks_prod" {
  source = "./modules/aks"
  
  location                    = "eastus"
  resource_group_name         = "rg-stocktrader-prod"
  aks_cluster_name            = "aks-stocktrader-prod"
  aks_node_vm_size            = "Standard_D8ds_v5"
  aks_subnet_id               = module.network.aks_subnet_id
  
  aks_service_cidr            = "10.200.0.0/16"
  aks_pod_cidr                = "10.201.0.0/16"
  aks_dns_service_ip          = "10.200.0.10"
  aks_service_mesh_revisions  = ["asm-1-24"]
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Notes

- Changing overlay CIDRs after cluster creation requires recreation
- Ensure `dns_service_ip` is within the `service_cidr` range
- Namespaces must be labeled with `istio.io/rev=<revision>` for sidecar injection
- Workload identity requires additional configuration in Kubernetes manifests
