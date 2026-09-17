# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# DNS MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Private DNS zones module.
# These variables control the configuration of private DNS zones for service
# discovery and name resolution within the virtual network.
#
# Variable Categories:
# - Core Infrastructure: Resource group and VNet configuration
# - DNS Configuration: Private zones and resolution settings
# - Network Integration: VNet links and connectivity
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Name of the Azure resource group containing the DNS zones
variable "resource_group_name" { type = string }
# =============================================================================
# NETWORK INTEGRATION VARIABLES
# =============================================================================

# ID of the virtual network to link with private DNS zones
variable "vnet_id" { type = string }

