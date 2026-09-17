# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE KUBERNETES SERVICE (AKS) CLUSTER
# ----------------------------------------------------------------------------------
# This resource creates a managed Kubernetes cluster with advanced networking,
# service mesh, and security features for the Stock Trader application.
#
# Key Features:
# - Azure CNI Overlay networking for pod IP independence
# - Istio service mesh (Azure Service Mesh) for traffic management
# - Workload Identity for secure pod-to-Azure authentication
# - Auto-scaling node pools with maintenance windows
# - System-assigned managed identity for cluster operations
# - Container Insights monitoring integration
# ----------------------------------------------------------------------------------
resource "azurerm_kubernetes_cluster" "this" {
  automatic_upgrade_channel    = "patch"
  dns_prefix                   = "${var.aks_cluster_name}-dns"
  image_cleaner_enabled        = true
  image_cleaner_interval_hours = 168
  location                     = var.location
  name                         = var.aks_cluster_name
  oidc_issuer_enabled          = true
  resource_group_name          = var.resource_group_name
  tags                         = var.tags
  workload_identity_enabled    = true

  default_node_pool {
    auto_scaling_enabled = true
    max_count            = 5
    min_count            = 2
    name                 = "agentpool"
    os_disk_type         = "Ephemeral"
    vm_size              = var.aks_node_vm_size
    upgrade_settings {
      max_surge = "10%"
    }
    vnet_subnet_id = var.aks_subnet_id
  }

  network_profile {
    network_plugin      = "azure"
    network_policy      = "azure"
    network_plugin_mode = "overlay"
    service_cidr        = var.aks_service_cidr
    dns_service_ip      = var.aks_dns_service_ip
    pod_cidr            = var.aks_pod_cidr
    load_balancer_sku   = "standard"
  }

  identity {
    type = "SystemAssigned"
  }

  maintenance_window_auto_upgrade {
    day_of_week = "Sunday"
    duration    = 4
    frequency   = "Weekly"
    interval    = 1
    start_time  = "00:00"
    utc_offset  = "+00:00"
  }

  maintenance_window_node_os {
    day_of_week = "Sunday"
    duration    = 4
    frequency   = "Weekly"
    interval    = 1
    start_time  = "00:00"
    utc_offset  = "+00:00"
  }

  monitor_metrics {}

  dynamic "service_mesh_profile" {
    for_each = var.enable_istio ? [1] : []
    content {
      internal_ingress_gateway_enabled = true
      external_ingress_gateway_enabled = true
      mode                             = "Istio"
      revisions                        = var.aks_service_mesh_revisions
    }
  }
}

