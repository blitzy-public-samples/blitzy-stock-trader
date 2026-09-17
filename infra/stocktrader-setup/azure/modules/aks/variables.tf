# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AKS MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Kubernetes Service (AKS) module.
# These variables control the configuration of the managed Kubernetes cluster including
# networking, service mesh, and compute resources.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, cluster name
# - Compute Configuration: VM sizes and node pools
# - Network Configuration: Subnet, CIDR ranges, DNS settings
# - Service Mesh: Istio configuration and revisions
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the AKS cluster will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the AKS cluster
variable "resource_group_name" { type = string }

# =============================================================================
# COMPUTE CONFIGURATION VARIABLES
# =============================================================================

# Name of the AKS cluster (must be unique within the resource group)
variable "aks_cluster_name" { type = string }

# VM size for AKS node pools (e.g., Standard_D2s_v3, Standard_DS2_v2)
variable "aks_node_vm_size" { type = string }

# =============================================================================
# NETWORK CONFIGURATION VARIABLES
# =============================================================================

# ID of the subnet where AKS nodes will be deployed
variable "aks_subnet_id" { type = string }

# CIDR range for Kubernetes services (e.g., 10.0.0.0/16)
variable "aks_service_cidr" { type = string }

# CIDR range for Kubernetes pods (e.g., 10.244.0.0/16)
variable "aks_pod_cidr" { type = string }

# IP address for Kubernetes DNS service (must be within service_cidr)
variable "aks_dns_service_ip" { type = string }

# =============================================================================
# SERVICE MESH VARIABLES
# =============================================================================

# Toggle to enable/disable Istio service mesh deployment
variable "enable_istio" {
  description = "Enable Istio service mesh deployment and configuration"
  type        = bool
  default     = true
}

# List of Istio service mesh revisions to enable (e.g., ["asm-1-24"])
variable "aks_service_mesh_revisions" { type = list(string) }

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to all AKS resources for organization and cost tracking
variable "tags" { type = map(string) }

