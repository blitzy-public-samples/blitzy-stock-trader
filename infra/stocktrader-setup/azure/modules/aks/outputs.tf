output "cluster_id" {
  description = "The ID of the AKS cluster"
  value       = azurerm_kubernetes_cluster.this.id
}

output "cluster_name" {
  description = "The name of the AKS cluster"
  value       = azurerm_kubernetes_cluster.this.name
}

output "kube_config" {
  description = "The kubeconfig for the AKS cluster"
  value       = azurerm_kubernetes_cluster.this.kube_config_raw
  sensitive   = true
}

output "host" {
  description = "The Kubernetes cluster server host"
  value       = azurerm_kubernetes_cluster.this.kube_config.0.host
}

output "client_certificate" {
  description = "The Kubernetes cluster client certificate"
  value       = base64decode(azurerm_kubernetes_cluster.this.kube_config.0.client_certificate)
  sensitive   = true
}

output "client_key" {
  description = "The Kubernetes cluster client key"
  value       = base64decode(azurerm_kubernetes_cluster.this.kube_config.0.client_key)
  sensitive   = true
}

output "cluster_ca_certificate" {
  description = "The Kubernetes cluster CA certificate"
  value       = base64decode(azurerm_kubernetes_cluster.this.kube_config.0.cluster_ca_certificate)
  sensitive   = true
}

# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

output "id" {
  description = "The ID of the AKS cluster"
  value       = azurerm_kubernetes_cluster.this.id
}

output "oidc_issuer_url" {
  description = "The OIDC issuer URL for the AKS cluster"
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}
