# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# KEY VAULT MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Key Vault module.
# These variables control the configuration of the managed secret storage service
# for secure storage and management of application secrets and credentials.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, vault name
# - Security Configuration: Access policies and permissions
# - Secret Management: Secret population and naming conventions
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the Key Vault will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the Key Vault
variable "resource_group_name" { type = string }

# =============================================================================
# VAULT CONFIGURATION VARIABLES
# =============================================================================

# Name of the Key Vault (must be globally unique)
variable "key_vault_name" { type = string }

# =============================================================================
# SECURITY CONFIGURATION VARIABLES
# =============================================================================

# Principal ID of the User-Assigned Identity for access policies
variable "uai_principal_id" { type = string }

# =============================================================================
# SECRET MANAGEMENT VARIABLES
# =============================================================================

# Map of secret names to values for initial vault population
variable "secrets_map" {
  type = map(string)
}

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to Key Vault resources for organization and cost tracking
variable "tags" { type = map(string) }

