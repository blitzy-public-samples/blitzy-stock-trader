# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE DATABASE FOR POSTGRESQL FLEXIBLE SERVER
# ----------------------------------------------------------------------------------
# This resource creates a managed PostgreSQL database service with flexible
# compute and storage options for the Stock Trader application.
#
# Key Features:
# - Managed PostgreSQL service with high availability
# - Flexible compute tiers (Burstable, General Purpose, Memory Optimized)
# - Automated backups and point-in-time recovery
# - SSL/TLS encryption and Azure AD authentication
# - Private network access for enhanced security
# - Integration with private endpoints for secure access
# ----------------------------------------------------------------------------------
resource "azurerm_postgresql_flexible_server" "this" {
  administrator_login    = var.administrator_login
  administrator_password = var.administrator_login_password
  location               = var.location
  name                   = var.postgres_server_name
  sku_name               = "B_Standard_B1ms"
  version                = "12"
  resource_group_name    = var.resource_group_name
  tags                   = var.tags
  zone                   = "3"
}

# ----------------------------------------------------------------------------------
# POSTGRESQL FIREWALL RULES
# ----------------------------------------------------------------------------------
# These resources configure network access controls for the PostgreSQL server,
# allowing secure access from Azure services and optionally from specific client IPs.
#
# Key Features:
# - Azure services access for managed operations
# - Optional client IP access for development/debugging
# - Network security and access control
# - Integration with private endpoints for production access
# ----------------------------------------------------------------------------------

# Wait for PostgreSQL server to be fully ready before applying firewall rules
resource "time_sleep" "wait_for_postgres_ready" {
  depends_on = [azurerm_postgresql_flexible_server.this]
  
  create_duration = "240s"
  
  triggers = {
    server_id = azurerm_postgresql_flexible_server.this.id
  }
}

# Allow Azure services
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_azure" {
  depends_on = [time_sleep.wait_for_postgres_ready]
  
  end_ip_address   = "0.0.0.0"
  name             = "AllowAllAzureServicesAndResourcesWithinAzureIps"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = "0.0.0.0"
}

# Optional: allow a specific client IP if provided
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_client_ip" {
  depends_on = [time_sleep.wait_for_postgres_ready]
  
  count            = var.client_ip == null ? 0 : 1
  end_ip_address   = var.client_ip
  name             = "ClientIPAddress"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = var.client_ip
}

