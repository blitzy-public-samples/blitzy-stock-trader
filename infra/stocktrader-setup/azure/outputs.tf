# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "istio_ingress_external_ip" {
  description = "External IP address of the Istio ingress gateway (only available when Istio is enabled)"
  value       = var.enable_istio ? data.kubernetes_service.istio_ingress_external[0].status.0.load_balancer.0.ingress.0.ip : null
}

output "istio_ingress_external_url" {
  description = "External URL for the Istio ingress gateway (only available when Istio is enabled)"
  value       = var.enable_istio ? "http://${data.kubernetes_service.istio_ingress_external[0].status.0.load_balancer.0.ingress.0.ip}" : null
}

output "istio_ingress_external_url_https" {
  description = "External HTTPS URL for the Istio ingress gateway (only available when Istio is enabled)"
  value       = var.enable_istio ? "https://${data.kubernetes_service.istio_ingress_external[0].status.0.load_balancer.0.ingress.0.ip}" : null
}

output "aks_cluster_name" {
  description = "Name of the AKS cluster"
  value       = module.aks.cluster_name
}

output "aks_cluster_id" {
  description = "ID of the AKS cluster"
  value       = module.aks.cluster_id
}

output "postgres_fqdn" {
  description = "FQDN of the PostgreSQL server"
  value       = module.postgres.fqdn
}

output "redis_hostname" {
  description = "Hostname of the Redis cache"
  value       = module.redis.hostname
}

output "key_vault_name" {
  description = "Name of the Key Vault"
  value       = module.key_vault.key_vault_name
}

output "key_vault_uri" {
  description = "URI of the Key Vault"
  value       = module.key_vault.key_vault_uri
}

output "stock_trader_namespace" {
  description = "Namespace where Stock Trader application is deployed"
  value       = "stock-trader"
}

output "couchdb_namespace" {
  description = "Namespace where CouchDB is deployed"
  value       = "couchdb"
}

output "external_secrets_namespace" {
  description = "Namespace where External Secrets Operator is deployed"
  value       = "external-secrets"
} 

output "function_app_name" {
  value       = module.function_app.function_app_name
  description = "Function App name"
}

output "function_app_invoke_url" {
  value       = module.function_app.function_app_invoke_url
  description = "Function invoke URL with default key"
  sensitive   = true
}