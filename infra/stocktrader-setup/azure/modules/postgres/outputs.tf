# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "id" {
  description = "The ID of the PostgreSQL Flexible Server"
  value       = azurerm_postgresql_flexible_server.this.id
}

output "fqdn" {
  description = "The FQDN of the PostgreSQL Flexible Server"
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "name" {
  description = "The name of the PostgreSQL Flexible Server"
  value       = azurerm_postgresql_flexible_server.this.name
}

output "administrator_login" {
  description = "The administrator login for the PostgreSQL server"
  value       = azurerm_postgresql_flexible_server.this.administrator_login
}

output "administrator_login_password" {
  description = "The administrator login password for the PostgreSQL server"
  value       = var.administrator_login_password
  sensitive   = true
}
