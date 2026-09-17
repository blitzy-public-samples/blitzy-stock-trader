# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "vnet_id" {
  description = "The ID of the Virtual Network"
  value       = azurerm_virtual_network.db_vnet.id
}

output "aks_subnet_id" {
  description = "The ID of the AKS subnet"
  value       = azurerm_subnet.aks_subnet.id
}

output "db_private_endpoints_subnet_id" {
  description = "The ID of the database private endpoints subnet"
  value       = azurerm_subnet.db_private_endpoints_subnet.id
}

output "vnet_name" {
  description = "The name of the Virtual Network"
  value       = azurerm_virtual_network.db_vnet.name
}

output "resource_group_name" {
  description = "The name of the resource group"
  value       = var.resource_group_name
}
