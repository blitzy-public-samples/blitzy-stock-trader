# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "postgres_private_dns_zone_id" {
  description = "The ID of the PostgreSQL private DNS zone"
  value       = azurerm_private_dns_zone.privatelink_postgres_database_azure_com.id
}

output "redis_private_dns_zone_id" {
  description = "The ID of the Redis private DNS zone"
  value       = azurerm_private_dns_zone.privatelink_redis_cache_windows_net.id
}

output "postgres_private_dns_zone_name" {
  description = "The name of the PostgreSQL private DNS zone"
  value       = azurerm_private_dns_zone.privatelink_postgres_database_azure_com.name
}

output "redis_private_dns_zone_name" {
  description = "The name of the Redis private DNS zone"
  value       = azurerm_private_dns_zone.privatelink_redis_cache_windows_net.name
}
