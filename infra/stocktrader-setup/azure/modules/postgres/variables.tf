# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# POSTGRESQL MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Database for PostgreSQL
# Flexible Server module. These variables control the configuration of the managed
# PostgreSQL database service for the Stock Trader application.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, server name
# - Database Configuration: Compute tier, version, and storage
# - Authentication: Administrator credentials and access control
# - Network Configuration: Firewall rules and connectivity
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the PostgreSQL server will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the PostgreSQL server
variable "resource_group_name" { type = string }

# =============================================================================
# DATABASE CONFIGURATION VARIABLES
# =============================================================================

# Name of the PostgreSQL Flexible Server (must be globally unique)
variable "postgres_server_name" { type = string }

# =============================================================================
# AUTHENTICATION VARIABLES
# =============================================================================

# Administrator username for PostgreSQL server access
variable "administrator_login" { type = string }

# Administrator password for PostgreSQL server access (sensitive)
variable "administrator_login_password" {
  type      = string
  sensitive = true
}

# =============================================================================
# NETWORK CONFIGURATION VARIABLES
# =============================================================================

# Optional client IP address for firewall access (null for no client access)
variable "client_ip" {
  type    = string
  default = null
}

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to PostgreSQL resources for organization and cost tracking
variable "tags" { type = map(string) }

