# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "id" {
  description = "The ID of the Redis Cache"
  value       = azurerm_redis_cache.this.id
}

output "hostname" {
  description = "The hostname of the Redis Cache"
  value       = azurerm_redis_cache.this.hostname
}

output "primary_access_key" {
  description = "The primary access key for the Redis Cache"
  value       = azurerm_redis_cache.this.primary_access_key
  sensitive   = true
}

output "secondary_access_key" {
  description = "The secondary access key for the Redis Cache"
  value       = azurerm_redis_cache.this.secondary_access_key
  sensitive   = true
}

output "name" {
  description = "The name of the Redis Cache"
  value       = azurerm_redis_cache.this.name
}
