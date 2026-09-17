# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE CACHE FOR REDIS
# ----------------------------------------------------------------------------------
# This resource creates a managed Redis cache service for application caching,
# session storage, and data caching with enterprise-grade security and performance.
#
# Key Features:
# - Managed Redis service with high availability
# - SSL/TLS encryption enabled by default
# - Private network access for enhanced security
# - Configurable SKU tiers (Basic, Standard, Premium)
# - Integration with private endpoints for secure access
# - Automatic backup and recovery capabilities
# ----------------------------------------------------------------------------------
resource "azurerm_redis_cache" "this" {
  family                        = "C"
  location                      = var.location
  name                          = var.redis_cache_name
  public_network_access_enabled = false
  redis_version                 = "6"
  resource_group_name           = var.resource_group_name
  sku_name                      = "Standard"
  capacity                      = 2
  tags                          = var.tags
}

