# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# NETWORK MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Virtual Network module.
# These variables control the configuration of the network infrastructure including
# VNet, subnets, and network segmentation for the Stock Trader application.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, VNet name
# - Network Configuration: Address spaces and CIDR ranges
# - Subnet Configuration: AKS and private endpoints subnets
# - Network Segmentation: Security and isolation boundaries
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the virtual network will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the virtual network
variable "resource_group_name" { type = string }

# =============================================================================
# NETWORK CONFIGURATION VARIABLES
# =============================================================================

# Name of the virtual network (must be unique within the resource group)
variable "vnet_name" { type = string }

# Address space for the virtual network (e.g., ["172.16.0.0/26"])
variable "vnet_address_space" { type = list(string) }

# =============================================================================
# SUBNET CONFIGURATION VARIABLES
# =============================================================================

# Name of the subnet for database private endpoints
variable "db_private_endpoints_subnet_name" { type = string }

# CIDR prefix for database private endpoints subnet (e.g., "172.16.0.32/28")
variable "db_private_endpoints_subnet_prefix" { type = string }

# Name of the subnet for AKS cluster nodes
variable "aks_subnet_name" { type = string }

# CIDR prefix for AKS subnet (e.g., "172.16.0.0/27")
variable "aks_subnet_prefix" { type = string }

