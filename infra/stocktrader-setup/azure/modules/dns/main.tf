# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# PRIVATE DNS ZONES
# ----------------------------------------------------------------------------------
# These resources create private DNS zones for Azure managed services, enabling
# seamless service discovery for private endpoints within the VNet.
#
# Key Features:
# - Private DNS zones for PostgreSQL and Redis services
# - Automatic DNS resolution for private endpoints
# - VNet integration for internal name resolution
# - Service discovery without public DNS exposure
# - Integration with Azure Private Link service
# ----------------------------------------------------------------------------------

# PostgreSQL Private DNS Zone
resource "azurerm_private_dns_zone" "privatelink_postgres_database_azure_com" {
  name                = "privatelink.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
  soa_record {
    email        = "azureprivatedns-host.microsoft.com"
    expire_time  = 2419200
    minimum_ttl  = 10
    refresh_time = 3600
    retry_time   = 300
    tags         = {}
    ttl          = 3600
  }
}

# Redis Cache Private DNS Zone
resource "azurerm_private_dns_zone" "privatelink_redis_cache_windows_net" {
  name                = "privatelink.redis.cache.windows.net"
  resource_group_name = var.resource_group_name
  soa_record {
    email        = "azureprivatedns-host.microsoft.com"
    expire_time  = 2419200
    minimum_ttl  = 10
    refresh_time = 3600
    retry_time   = 300
    tags         = {}
    ttl          = 3600
  }
}

# ----------------------------------------------------------------------------------
# VNET DNS LINKS
# ----------------------------------------------------------------------------------
# These resources link private DNS zones to the VNet, enabling automatic
# DNS resolution for private endpoints within the network.
#
# Key Features:
# - VNet integration for DNS resolution
# - Automatic service discovery
# - Network isolation and security
# - Seamless connectivity for applications
# ----------------------------------------------------------------------------------

# Link Redis private zone to the VNet so AKS can resolve
resource "azurerm_private_dns_zone_virtual_network_link" "redis_aks_link" {
  name                  = "redis-aks-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.privatelink_redis_cache_windows_net.name
  virtual_network_id    = var.vnet_id
  registration_enabled  = false
}

