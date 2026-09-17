# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# PRIVATE ENDPOINTS MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Private Endpoints module.
# These variables control the configuration of private endpoints for secure
# connectivity to Azure managed services without public internet exposure.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, subnet configuration
# - PostgreSQL Configuration: Server, DNS zone, and endpoint settings
# - Redis Configuration: Cache, DNS zone, and endpoint settings
# - Network Security: Private connectivity and isolation
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the private endpoints will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the private endpoints
variable "resource_group_name" { type = string }

# =============================================================================
# NETWORK CONFIGURATION VARIABLES
# =============================================================================

# ID of the subnet where private endpoints will be deployed
variable "db_private_endpoints_subnet_id" { type = string }

# =============================================================================
# POSTGRESQL PRIVATE ENDPOINT VARIABLES
# =============================================================================

# Name of the PostgreSQL private endpoint
variable "postgres_private_endpoint_name" { type = string }

# ID of the PostgreSQL private DNS zone for name resolution
variable "postgres_private_dns_zone_id" { type = string }

# ID of the PostgreSQL Flexible Server to connect
variable "postgres_server_id" { type = string }

# Tags to apply to PostgreSQL private endpoint resources
variable "postgres_tags" { type = map(string) }

# =============================================================================
# REDIS PRIVATE ENDPOINT VARIABLES
# =============================================================================

# Name of the Redis private endpoint
variable "redis_private_endpoint_name" { type = string }

# ID of the Redis private DNS zone for name resolution
variable "redis_private_dns_zone_id" { type = string }

# ID of the Redis cache to connect
variable "redis_id" { type = string }

# Tags to apply to Redis private endpoint resources
variable "redis_tags" { type = map(string) }

