# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# K8S BOOTSTRAP MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Kubernetes Bootstrap module.
# These variables control the day-2 operations on the AKS cluster including
# service mesh configuration, operator installation, and namespace setup.
#
# Variable Categories:
# - Azure Configuration: Subscription, resource group, cluster details
# - Istio Configuration: Service mesh and ingress gateway settings
# - Application Configuration: Namespace and revision management
# - Operator Configuration: OLM and application operator setup
# ----------------------------------------------------------------------------------

# =============================================================================
# AZURE CONFIGURATION VARIABLES
# =============================================================================

# Azure subscription ID for AKS cluster access
variable "subscription_id" { type = string }
# Name of the Azure resource group containing the AKS cluster
variable "resource_group_name" { type = string }

# Name of the AKS cluster for credential configuration
variable "aks_cluster_name" { type = string }

# =============================================================================
# ISTIO CONFIGURATION VARIABLES
# =============================================================================

# Toggle to enable/disable Istio service mesh deployment
variable "enable_istio" {
  description = "Enable Istio service mesh deployment and configuration"
  type        = bool
  default     = true
}

# Kubernetes namespace for Istio ingress gateway deployment
variable "istio_ingress_namespace" { type = string }

# Name of the Istio external ingress gateway service
variable "istio_ingress_external_service_name" { type = string }

# =============================================================================
# APPLICATION CONFIGURATION VARIABLES
# =============================================================================

# Kubernetes namespace for Stock Trader application deployment
variable "stock_trader_namespace" { type = string }

# Istio service mesh revision for namespace labeling (e.g., "asm-1-24")
variable "istio_revision" { type = string }

