# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# PRIVATE ENDPOINTS
# ----------------------------------------------------------------------------------
# These resources create private endpoints for Azure managed services, enabling
# secure private connectivity from the VNet to services without public internet exposure.
#
# Key Features:
# - Private connectivity to Azure services
# - Network isolation and security
# - Automatic DNS resolution via private DNS zones
# - Integration with Azure Private Link service
# - No public internet exposure for enhanced security
# ----------------------------------------------------------------------------------

# PostgreSQL Private Endpoint
resource "azurerm_private_endpoint" "postgres" {
  location            = var.location
  name                = var.postgres_private_endpoint_name
  resource_group_name = var.resource_group_name
  subnet_id           = var.db_private_endpoints_subnet_id
  tags                = var.postgres_tags

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [var.postgres_private_dns_zone_id]
  }

  private_service_connection {
    name                           = "${var.postgres_private_endpoint_name}_psc"
    private_connection_resource_id = var.postgres_server_id
    subresource_names              = ["postgresqlServer"]
    is_manual_connection           = false
  }
}

# Redis Cache Private Endpoint
resource "azurerm_private_endpoint" "redis" {
  location            = var.location
  name                = var.redis_private_endpoint_name
  resource_group_name = var.resource_group_name
  subnet_id           = var.db_private_endpoints_subnet_id
  tags                = var.redis_tags

  private_dns_zone_group {
    name                 = "default"
    private_dns_zone_ids = [var.redis_private_dns_zone_id]
  }

  private_service_connection {
    name                           = "${var.redis_private_endpoint_name}_psc"
    private_connection_resource_id = var.redis_id
    subresource_names              = ["redisCache"]
    is_manual_connection           = false
  }
}

