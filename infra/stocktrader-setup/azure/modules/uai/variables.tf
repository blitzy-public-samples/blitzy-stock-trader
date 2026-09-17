# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# USER-ASSIGNED IDENTITY MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure User-Assigned Managed Identity
# module. These variables control the configuration of managed identities for
# secure authentication and authorization across Azure services.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, identity name
# - Identity Configuration: Name and metadata settings
# - Resource Management: Tags and organization
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the managed identity will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the managed identity
variable "resource_group_name" { type = string }

# =============================================================================
# IDENTITY CONFIGURATION VARIABLES
# =============================================================================

# Name of the User-Assigned Managed Identity
variable "name" { type = string }

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to managed identity resources for organization and cost tracking
variable "tags" { type = map(string) }

