# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# IMPORTANT: All variables below are required. You must provide values for each one.
# Ensure this file is created and filled out before running 'terraform plan' or 'terraform apply'.
# NOTE: The Azure Resource Group specified must already exist before running Terraform.
# NOTE: The 'redis_cache_name' and 'postgres_server_name' values must be unique within your Azure subscription.
# NOTE: The 'location' variable should not contain spaces (e.g., use 'eastus' not 'east us').
#
# VALIDATION STRATEGY:
# ===================
# All variables include validation rules to catch configuration errors early:
# - Resource names: Enforce Azure naming conventions and length limits
# - Network CIDRs: Validate proper CIDR format
# - Email addresses: Ensure valid email format
# - Kubernetes names: Enforce DNS-1123 naming standards
# - Secrets: Enforce minimum security requirements
# - Azure regions: Validate against supported regions list
# ----------------------------------------------------------------------------------
#
# OIDC CLIENT SECRET GENERATION INSTRUCTIONS:
# ============================================
# 
# To generate a secure OIDC client secret, use one of these methods:
#
# Method 1: Using OpenSSL (Recommended)
# -------------------------------------
# openssl rand -base64 32
# This generates a 32-byte random string encoded in base64 (43 characters)
#
# Method 2: Using Python
# ----------------------
# python3 -c "import secrets; print(secrets.token_urlsafe(32))"
# This generates a URL-safe base64-encoded random string
#
# Method 3: Using Node.js
# -----------------------
# node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"
# This generates a base64-encoded random string
#
# Method 4: Using PowerShell
# --------------------------
# [System.Web.Security.Membership]::GeneratePassword(32, 10)
# This generates a 32-character password with 10 special characters
#
# SECURITY NOTES:
# ===============
# - Never commit the actual client secret to version control
# - Store the secret securely (e.g., in Azure Key Vault, environment variables)
# - Rotate the secret regularly (every 90-180 days)
# - Use different secrets for different environments (dev, staging, prod)
# - The client secret should be at least 32 characters long
#
# EXAMPLE USAGE:
# ==============
# In your terraform.tfvars file:
# oidc_client_id     = "stock-trader-prod"
# oidc_client_secret = "your-generated-secret-from-above-methods"
#
# ----------------------------------------------------------------------------------
# ----------------------------------------------------------------------------------
# CORE INFRASTRUCTURE VARIABLES
# ----------------------------------------------------------------------------------
# These variables define the foundational infrastructure components including
# resource groups, networking, and basic Azure configuration.
# ----------------------------------------------------------------------------------

# The name of the Azure Resource Group where all resources will be created.
# This resource group must already exist before running Terraform.
# Example: "rg-stocktrader-prod" or "rg-stocktrader-dev"
variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
  nullable    = false

  validation {
    condition     = length(var.resource_group_name) > 0 && length(var.resource_group_name) <= 80
    error_message = "Resource group name must be between 1 and 80 characters."
  }
}

# ----------------------------------------------------------------------------------
# DATA SERVICES VARIABLES
# ----------------------------------------------------------------------------------
# These variables configure the data storage and caching services including
# Redis Cache, PostgreSQL, and CouchDB for the Stock Trader application.
# ----------------------------------------------------------------------------------

# The name to assign to the Azure Redis Cache instance.
# NOTE: This must be unique within your Azure subscription.
# Used for application caching, session storage, and data caching.
# Example: "redis-stocktrader-prod" or "redis-stocktrader-dev"
variable "redis_cache_name" {
  description = "Name of the Redis Cache instance for application caching and session storage"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]{3,63}$", var.redis_cache_name))
    error_message = "Redis cache name must be 3-63 characters long and contain only lowercase letters, numbers, and hyphens."
  }
}

# The name to assign to the Azure PostgreSQL Flexible Server instance.
# NOTE: This must be unique within your Azure subscription.
# Used for primary application data storage and transaction processing.
# Example: "pgflex-stocktrader-prod" or "pgflex-stocktrader-dev"
variable "postgres_server_name" {
  description = "Name of the PostgreSQL Flexible Server for application data storage"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]{3,63}$", var.postgres_server_name))
    error_message = "PostgreSQL server name must be 3-63 characters long and contain only lowercase letters, numbers, and hyphens."
  }
}

# Postgres private endpoint name
variable "postgres_private_endpoint_name" {
  description = "Name of the PostgreSQL private endpoint"
  type        = string
  nullable    = false

  validation {
    condition     = length(var.postgres_private_endpoint_name) > 0 && length(var.postgres_private_endpoint_name) <= 80
    error_message = "Private endpoint name must be between 1 and 80 characters."
  }
}

# The Azure Subscription ID where resources will be deployed.
variable "subscription_id" {
  description = "Azure Subscription ID"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.subscription_id))
    error_message = "Subscription ID must be a valid UUID format."
  }
}

# The Azure region (location) where resources will be deployed.
variable "location" {
  description = "Azure region for resources"
  type        = string
  nullable    = false

  validation {
    condition = contains([
      # Americas
      "eastus", "eastus2", "southcentralus", "westus", "westus2", "westus3", "centralus", "northcentralus", "westcentralus", "canadacentral", "canadaeast", "brazilsouth", "brazilsoutheast",
      # Europe
      "northeurope", "westeurope", "francecentral", "francesouth", "germanywestcentral", "germanynorth", "switzerlandnorth", "switzerlandwest", "uksouth", "ukwest", "norwayeast", "norwaywest", "swedensouth",
      # Asia Pacific
      "eastasia", "southeastasia", "japaneast", "japanwest", "australiaeast", "australiasoutheast", "australiacentral", "australiacentral2", "koreacentral", "koreasouth", "centralindia", "southindia", "westindia", "jioindiawest",
      # Middle East & Africa
      "uaeorth", "southafricanorth", "southafricawest"
    ], var.location)
    error_message = "Location must be a valid Azure region. See https://azure.microsoft.com/en-us/global-infrastructure/geographies/ for available regions."
  }
}

# The password for the PostgreSQL administrator login (sensitive).
variable "administrator_login_password" {
  description = "Password for the PostgreSQL administrator login"
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition     = length(var.administrator_login_password) >= 8
    error_message = "Password must be at least 8 characters long."
  }
}

# The username for the PostgreSQL administrator login.
variable "administrator_login" {
  description = "Username for the PostgreSQL administrator login"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]{2,62}$", var.administrator_login))
    error_message = "Administrator login must start with a letter and contain only letters, numbers, and underscores (3-63 characters)."
  }
}

# The SKU (pricing tier) for the Redis cache instance.
variable "redis_cache_sku" {
  description = "The SKU of Redis cache"
  type        = string
  nullable    = false

  validation {
    condition     = contains(["Basic", "Standard", "Premium"], var.redis_cache_sku)
    error_message = "Redis cache SKU must be one of: Basic, Standard, Premium."
  }
}

# ----------------------------------------------------------------------------------
# KUBERNETES & SERVICE MESH VARIABLES
# ----------------------------------------------------------------------------------
# These variables configure the Azure Kubernetes Service (AKS) cluster and
# Istio service mesh components for the Stock Trader application.
# ----------------------------------------------------------------------------------

# Toggle to enable/disable Istio service mesh deployment
variable "enable_istio" {
  description = "Enable Istio service mesh deployment and configuration"
  type        = bool
  default     = true
  nullable    = false
}

# The name to assign to the Azure Kubernetes Service (AKS) cluster.
# NOTE: This must be unique within your Azure subscription.
# Used for container orchestration, service mesh, and application deployment.
# Example: "aks-stocktrader-prod" or "aks-stocktrader-dev"
variable "aks_cluster_name" {
  description = "Name of the AKS cluster for container orchestration and application deployment"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]{3,63}$", var.aks_cluster_name))
    error_message = "AKS cluster name must be 3-63 characters long and contain only lowercase letters, numbers, and hyphens."
  }
}

# The value to use for the 'created-by' tag on resources.
variable "created_by" {
  description = "Tag for resource creator"
  type        = string
  nullable    = false

  validation {
    condition     = length(var.created_by) > 0 && length(var.created_by) <= 512
    error_message = "Created by tag must be between 1 and 512 characters."
  }
}

# The name to assign to the Redis private endpoint.
variable "redis_private_endpoint_name" {
  description = "Name of the Redis private endpoint"
  type        = string
  nullable    = false

  validation {
    condition     = length(var.redis_private_endpoint_name) > 0 && length(var.redis_private_endpoint_name) <= 80
    error_message = "Private endpoint name must be between 1 and 80 characters."
  }
}

# The email address to use for alert notifications or receivers.
variable "email_receiver_name" {
  description = "Email receiver name"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", var.email_receiver_name))
    error_message = "Email address must be in a valid format."
  }
}

# Azure Function App deployment variables
variable "function_app_name" {
  description = "Azure Function App name to create/publish"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]{2,60}$", var.function_app_name))
    error_message = "Function App name must be 2-60 chars, lowercase letters, numbers, and hyphens."
  }
}

# The VM size for the AKS default node pool.
variable "aks_node_vm_size" {
  description = "VM size for the AKS default node pool (e.g., Standard_D4ds_v5)"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^Standard_[A-Z][0-9]+[a-z]*s?_v[0-9]+$", var.aks_node_vm_size))
    error_message = "VM size must be in the format Standard_[Type][Size]s?_v[Version] (e.g., Standard_D4ds_v5)."
  }
}

# The list of revisions for the AKS service mesh profile.
# This revision must match the namespace label for sidecar injection to work.
# The webhook looks for: istio.io/rev=asm-1-24
variable "aks_service_mesh_revisions" {
  description = "List of revisions for the AKS service mesh profile (e.g., [\"asm-1-24\"])"
  type        = list(string)
  nullable    = false

  validation {
    condition     = length(var.aks_service_mesh_revisions) > 0
    error_message = "At least one service mesh revision must be specified."
  }
}

# Istio revision label to apply to namespaces for sidecar injection
variable "istio_revision" {
  description = "Istio revision label value to use for sidecar injection (e.g., asm-1-24)"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^asm-[0-9]+-[0-9]+$", var.istio_revision))
    error_message = "Istio revision must be in the format asm-[major]-[minor] (e.g., asm-1-24)."
  }
}

# Namespace where AKS creates Istio ingress resources
variable "istio_ingress_namespace" {
  description = "Namespace for Istio ingress components (AKS default: aks-istio-ingress)"
  type        = string
  default     = "aks-istio-ingress"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.istio_ingress_namespace))
    error_message = "Namespace name must contain only lowercase letters, numbers, and hyphens."
  }
}

# Service name for the external Istio ingress gateway
variable "istio_ingress_external_service_name" {
  description = "Service name of the external Istio ingress gateway (AKS default: aks-istio-ingressgateway-external)"
  type        = string
  default     = "aks-istio-ingressgateway-external"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.istio_ingress_external_service_name))
    error_message = "Service name must contain only lowercase letters, numbers, and hyphens."
  }
}

# AKS Overlay Network Configuration Variables
variable "aks_service_cidr" {
  description = "Service CIDR for AKS overlay network"
  type        = string
  default     = "10.200.0.0/16"
  nullable    = false

  validation {
    condition     = can(cidrhost(var.aks_service_cidr, 0))
    error_message = "Service CIDR must be a valid CIDR block."
  }
}

variable "aks_pod_cidr" {
  description = "Pod CIDR for AKS overlay network"
  type        = string
  default     = "10.201.0.0/16"
  nullable    = false

  validation {
    condition     = can(cidrhost(var.aks_pod_cidr, 0))
    error_message = "Pod CIDR must be a valid CIDR block."
  }
}

variable "aks_dns_service_ip" {
  description = "DNS service IP for AKS overlay network"
  type        = string
  default     = "10.200.0.10"
  nullable    = false

  validation {
    condition     = can(regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$", var.aks_dns_service_ip))
    error_message = "DNS service IP must be a valid IPv4 address."
  }
}

# The name to assign to the Stock Trader namespace.
variable "stock_trader_namespace" {
  description = "Namespace for Stock Trader deployment"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.stock_trader_namespace))
    error_message = "Namespace name must contain only lowercase letters, numbers, and hyphens."
  }
}

# ----------------------------------------------------------------------------------
# APPLICATION & NAMESPACE VARIABLES
# ----------------------------------------------------------------------------------
# These variables configure the Stock Trader application namespaces, CouchDB
# deployment, and related Kubernetes resources.
# ----------------------------------------------------------------------------------

# CouchDB inputs for automated deployment post-AKS
variable "couchdb_namespace" {
  description = "Namespace for CouchDB deployment"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.couchdb_namespace))
    error_message = "Namespace name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "couchdb_pvc_name" {
  description = "PersistentVolumeClaim name for CouchDB"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.couchdb_pvc_name))
    error_message = "PVC name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "couchdb_storage_size" {
  description = "Storage size for CouchDB PVC"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[0-9]+[KMGTPEZYkmgtpezy]?i?$", var.couchdb_storage_size))
    error_message = "Storage size must be in Kubernetes format (e.g., 10Gi, 100Mi)."
  }
}

variable "couchdb_deployment_name" {
  description = "Deployment name for CouchDB"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.couchdb_deployment_name))
    error_message = "Deployment name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "couchdb_image" {
  description = "CouchDB Docker image"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-zA-Z0-9.-]+(/[a-zA-Z0-9.-]+)*:[a-zA-Z0-9.-]+$", var.couchdb_image))
    error_message = "Docker image must be in format registry/repository:tag."
  }
}

variable "couchdb_user" {
  description = "CouchDB admin username"
  type        = string
  nullable    = false

  validation {
    condition     = length(var.couchdb_user) >= 3 && length(var.couchdb_user) <= 63
    error_message = "CouchDB username must be between 3 and 63 characters."
  }
}

variable "couchdb_password" {
  description = "CouchDB admin password"
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition     = length(var.couchdb_password) >= 8
    error_message = "CouchDB password must be at least 8 characters long."
  }
}

variable "couchdb_service_name" {
  description = "Service name for CouchDB"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.couchdb_service_name))
    error_message = "Service name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "olm_namespace" {
  description = "Namespace where OLM is installed"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.olm_namespace))
    error_message = "Namespace name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "couchdb_database_name" {
  description = "CouchDB database name for the Stock Trader application"
  type        = string
  default     = "account"
  nullable    = false

  validation {
    condition     = can(regex("^[a-z0-9_-]+$", var.couchdb_database_name))
    error_message = "Database name must contain only lowercase letters, numbers, hyphens, and underscores."
  }
}

variable "oidc_client_id" {
  description = "OIDC client ID for authentication. This should be a unique identifier for your application."
  type        = string
  default     = "stock-trader"
  nullable    = false

  validation {
    condition     = can(regex("^[a-zA-Z0-9-]{3,63}$", var.oidc_client_id))
    error_message = "OIDC client ID must be 3-63 characters long and contain only alphanumeric characters and hyphens."
  }
}

variable "oidc_client_secret" {
  description = "OIDC client secret for authentication. This should be a cryptographically secure random string."
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition     = length(var.oidc_client_secret) >= 32
    error_message = "OIDC client secret must be at least 32 characters long."
  }
}

# ----------------------------------------------------------------------------------
# SECRETS & EXTERNAL SECRETS VARIABLES
# ----------------------------------------------------------------------------------
# These variables configure Azure Key Vault and External Secrets Operator
# for secure secret management and synchronization.
# ----------------------------------------------------------------------------------

# Secrets and External Secrets configuration
variable "key_vault_name" {
  description = "Azure Key Vault name for storing application secrets and certificates"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[a-zA-Z0-9-]{3,24}$", var.key_vault_name))
    error_message = "Key Vault name must be 3-24 characters long and contain only alphanumeric characters and hyphens."
  }
}

variable "external_secrets_namespace" {
  description = "Namespace to install External Secrets Operator"
  type        = string
  default     = "external-secrets"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.external_secrets_namespace))
    error_message = "Namespace name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "external_secrets_service_account" {
  description = "ServiceAccount name used by ESO for Azure Workload Identity"
  type        = string
  default     = "eso-wi"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.external_secrets_service_account))
    error_message = "ServiceAccount name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "cluster_secret_store_name" {
  description = "ClusterSecretStore name for Key Vault"
  type        = string
  default     = "azure-kv"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.cluster_secret_store_name))
    error_message = "ClusterSecretStore name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "credentials_secret_name" {
  description = "Kubernetes secret name for app credentials"
  type        = string
  default     = "stock-trader-secret-credentials"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.credentials_secret_name))
    error_message = "Secret name must contain only lowercase letters, numbers, and hyphens."
  }
}