# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE VIRTUAL NETWORK
# ----------------------------------------------------------------------------------
# This resource creates the foundational networking infrastructure for the
# Stock Trader application, providing network isolation and connectivity.
#
# Key Features:
# - Virtual network with configurable address space
# - Subnet segmentation for different service types
# - Integration with Azure services and private endpoints
# - Network security and routing capabilities
# ----------------------------------------------------------------------------------
resource "azurerm_virtual_network" "db_vnet" {
  name                = var.vnet_name
  address_space       = var.vnet_address_space
  location            = var.location
  resource_group_name = var.resource_group_name
}

# ----------------------------------------------------------------------------------
# PRIVATE ENDPOINTS SUBNET
# ----------------------------------------------------------------------------------
# This subnet hosts private endpoints for Azure managed services, enabling
# secure private connectivity from the VNet to services like PostgreSQL and Redis.
#
# Key Features:
# - Dedicated subnet for private endpoint resources
# - Network isolation for secure service access
# - Integration with Azure Private Link service
# - Automatic DNS resolution via private DNS zones
# ----------------------------------------------------------------------------------
resource "azurerm_subnet" "db_private_endpoints_subnet" {
  name                 = var.db_private_endpoints_subnet_name
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.db_vnet.name
  address_prefixes     = [var.db_private_endpoints_subnet_prefix]
}

# ----------------------------------------------------------------------------------
# AKS SUBNET
# ----------------------------------------------------------------------------------
# This subnet hosts the Azure Kubernetes Service (AKS) node pools, providing
# network connectivity for container workloads and service mesh components.
#
# Key Features:
# - Dedicated subnet for AKS node pools
# - Integration with Azure CNI Overlay networking
# - Network policies and security group support
# - Service mesh traffic management
# ----------------------------------------------------------------------------------
resource "azurerm_subnet" "aks_subnet" {
  name                 = var.aks_subnet_name
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.db_vnet.name
  address_prefixes     = [var.aks_subnet_prefix]
}

