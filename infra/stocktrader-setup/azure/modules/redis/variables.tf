# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# REDIS MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Cache for Redis module.
# These variables control the configuration of the managed Redis cache service
# for application caching, session storage, and data caching.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group, cache name
# - Cache Configuration: SKU, capacity, and performance settings
# - Security Configuration: Network access and authentication
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Azure region where the Redis cache will be deployed
variable "location" { type = string }
# Name of the Azure resource group containing the Redis cache
variable "resource_group_name" { type = string }

# =============================================================================
# CACHE CONFIGURATION VARIABLES
# =============================================================================

# Name of the Redis cache instance (must be globally unique)
variable "redis_cache_name" { type = string }

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to Redis cache resources for organization and cost tracking
variable "tags" { type = map(string) }

