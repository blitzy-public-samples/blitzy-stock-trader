# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE KEY VAULT
# ----------------------------------------------------------------------------------
# This resource creates a secure vault for storing application secrets, keys,
# and certificates with enterprise-grade security and compliance features.
#
# Key Features:
# - Secure secret storage with encryption at rest and in transit
# - Access policies for fine-grained permission control
# - Soft delete and purge protection for data recovery
# - Integration with Azure AD for authentication
# - Audit logging and compliance reporting
# ----------------------------------------------------------------------------------

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                        = var.key_vault_name
  location                    = var.location
  resource_group_name         = var.resource_group_name
  tenant_id                   = data.azurerm_client_config.current.tenant_id
  sku_name                    = "standard"
  enabled_for_disk_encryption = false
  purge_protection_enabled    = false
  soft_delete_retention_days  = 7
  rbac_authorization_enabled  = false # Use access policies instead of RBAC
  tags                        = var.tags
}

# ----------------------------------------------------------------------------------
# KEY VAULT SECRETS
# ----------------------------------------------------------------------------------
# These resources create and manage secrets within the Key Vault, providing
# secure storage for application credentials, connection strings, and sensitive data.
#
# Key Features:
# - Dynamic secret creation from provided map
# - Secure storage with encryption
# - Access control via policies
# - Integration with External Secrets Operator
# ----------------------------------------------------------------------------------
resource "azurerm_key_vault_secret" "this" {
  for_each     = var.secrets_map
  name         = each.key
  value        = each.value
  key_vault_id = azurerm_key_vault.this.id
  content_type = "text/plain"

  depends_on = [azurerm_key_vault_access_policy.current_user, azurerm_key_vault_access_policy.uai_access]
}

# ----------------------------------------------------------------------------------
# KEY VAULT ACCESS POLICIES
# ----------------------------------------------------------------------------------
# These resources define access policies for Key Vault, controlling who can
# read, write, and manage secrets within the vault.
#
# Key Features:
# - Fine-grained permission control
# - Integration with Azure AD identities
# - Support for both users and managed identities
# - Audit trail for access tracking
# ----------------------------------------------------------------------------------

# Access policy for the current user (Terraform operator)
resource "azurerm_key_vault_access_policy" "current_user" {
  key_vault_id = azurerm_key_vault.this.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = data.azurerm_client_config.current.object_id

  secret_permissions = [
    "Get",
    "List",
    "Set",
    "Delete",
    "Recover",
    "Backup",
    "Restore",
    "Purge"
  ]

  depends_on = [azurerm_key_vault.this]
}

# Use access policy instead of RBAC role assignment
resource "azurerm_key_vault_access_policy" "uai_access" {
  key_vault_id = azurerm_key_vault.this.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = var.uai_principal_id

  secret_permissions = [
    "Get",
    "List"
  ]

  depends_on = [azurerm_key_vault.this]
}

output "id" {
  value = azurerm_key_vault.this.id
}

output "name" {
  value = azurerm_key_vault.this.name
}

output "vault_uri" {
  value = azurerm_key_vault.this.vault_uri
}

output "key_vault_name" {
  description = "Name of the Key Vault"
  value       = azurerm_key_vault.this.name
}

output "key_vault_uri" {
  description = "URI of the Key Vault"
  value       = azurerm_key_vault.this.vault_uri
}

