# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# USER-ASSIGNED MANAGED IDENTITY
# ----------------------------------------------------------------------------------
# This resource creates a user-assigned managed identity for secure authentication
# and authorization across Azure services and Kubernetes workloads.
#
# Key Features:
# - Azure AD-based managed identity
# - No secrets or credentials to manage
# - Automatic credential rotation
# - Integration with Azure RBAC
# - Support for Workload Identity
# - Cross-service authentication capabilities
# ----------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "this" {
  location            = var.location
  name                = var.name
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

