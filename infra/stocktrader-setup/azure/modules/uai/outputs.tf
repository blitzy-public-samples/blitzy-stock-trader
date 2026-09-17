# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "principal_id" {
  description = "The principal ID of the User Assigned Identity"
  value       = azurerm_user_assigned_identity.this.principal_id
}

output "client_id" {
  description = "The client ID of the User Assigned Identity"
  value       = azurerm_user_assigned_identity.this.client_id
}

output "id" {
  description = "The ID of the User Assigned Identity"
  value       = azurerm_user_assigned_identity.this.id
}

output "name" {
  description = "The name of the User Assigned Identity"
  value       = azurerm_user_assigned_identity.this.name
}
