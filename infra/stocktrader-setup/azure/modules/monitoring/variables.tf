# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# MONITORING MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the Azure Monitor module.
# These variables control the configuration of monitoring, alerting, and
# observability for the Stock Trader application infrastructure.
#
# Variable Categories:
# - Core Infrastructure: Location, resource group configuration
# - Alert Configuration: Action groups and notification settings
# - AKS Monitoring: Cluster-specific monitoring and metrics
# - Resource Management: Tags and metadata
# ----------------------------------------------------------------------------------

# =============================================================================
# CORE INFRASTRUCTURE VARIABLES
# =============================================================================

# Name of the Azure resource group containing monitoring resources
variable "resource_group_name" { type = string }
# Azure region where monitoring resources will be deployed
variable "location" { type = string }

# =============================================================================
# ALERT CONFIGURATION VARIABLES
# =============================================================================

# Email address for receiving monitoring alerts and notifications
variable "email_receiver_name" { type = string }

# =============================================================================
# AKS MONITORING VARIABLES
# =============================================================================

# Resource ID of the AKS cluster for monitoring and alerting
variable "aks_id" { type = string }

# Name of the AKS cluster for alert naming and identification
variable "aks_cluster_name" { type = string }

# =============================================================================
# RESOURCE MANAGEMENT VARIABLES
# =============================================================================

# Tags to apply to monitoring resources for organization and cost tracking
variable "tags" { type = map(string) }

