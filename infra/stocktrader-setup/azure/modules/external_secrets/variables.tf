# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# EXTERNAL SECRETS MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the External Secrets Operator module.
# These variables control the configuration of Kubernetes-native secret management
# with Azure Key Vault integration using Workload Identity authentication.
#
# Variable Categories:
# - Azure Configuration: Subscription, resource group, cluster details
# - Kubernetes Configuration: Namespaces, service accounts, secret stores
# - Workload Identity: OIDC, tenant, and identity configuration
# - Key Vault Integration: Vault URI and secret management
# - Application Configuration: Namespaces and secret naming
# ----------------------------------------------------------------------------------

# =============================================================================
# AZURE CONFIGURATION VARIABLES
# =============================================================================

# Azure subscription ID for resource deployment
variable "subscription_id" { type = string }
# Name of the Azure resource group containing the AKS cluster
variable "resource_group_name" { type = string }

# Name of the AKS cluster for credential configuration
variable "aks_cluster_name" { type = string }

# =============================================================================
# KUBERNETES CONFIGURATION VARIABLES
# =============================================================================

# Kubernetes namespace for External Secrets Operator deployment
variable "namespace" { type = string }

# Name of the Kubernetes ServiceAccount for Workload Identity
variable "service_account_name" { type = string }

# =============================================================================
# WORKLOAD IDENTITY VARIABLES
# =============================================================================

# Client ID of the User-Assigned Identity for authentication
variable "uai_client_id" { type = string }

# ID of the User-Assigned Identity for federated credential
variable "uai_id" { type = string }

# OIDC issuer URL for the AKS cluster
variable "oidc_issuer_url" { type = string }

# Azure AD tenant ID for authentication
variable "tenant_id" { type = string }

# =============================================================================
# KEY VAULT INTEGRATION VARIABLES
# =============================================================================

# Name of the ClusterSecretStore for Key Vault connection
variable "cluster_secret_store_name" { type = string }

# URI of the Azure Key Vault for secret retrieval
variable "key_vault_uri" { type = string }

# =============================================================================
# APPLICATION CONFIGURATION VARIABLES
# =============================================================================

# Kubernetes namespace where application secrets will be created
variable "app_namespace" { type = string }

# Name of the Kubernetes secret containing application credentials
variable "credentials_secret_name" { type = string }

