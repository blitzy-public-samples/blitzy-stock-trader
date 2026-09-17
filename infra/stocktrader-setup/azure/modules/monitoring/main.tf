# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE MONITOR ACTION GROUPS
# ----------------------------------------------------------------------------------
# These resources create action groups for Azure Monitor alerts, enabling
# notification and response mechanisms for monitoring events.
#
# Key Features:
# - Email notifications for alert responses
# - Role-based access control for monitoring
# - Common alert schema integration
# - Integration with Azure Monitor metrics
# - Automated response capabilities
# ----------------------------------------------------------------------------------

# Smart Detection Action Group for Application Insights
resource "azurerm_monitor_action_group" "smart_detection" {
  name                = "Application Insights Smart Detection"
  resource_group_name = var.resource_group_name
  short_name          = "SmartDetect"
  arm_role_receiver {
    name                    = "Monitoring Contributor"
    role_id                 = "749f88d5-cbae-40b8-bcfc-e573ddc772fa"
    use_common_alert_schema = true
  }
  arm_role_receiver {
    name                    = "Monitoring Reader"
    role_id                 = "43d0d8ad-25c7-4714-9337-8ba259a9fe05"
    use_common_alert_schema = true
  }
}

# Recommended Alert Rules Action Group
resource "azurerm_monitor_action_group" "recommended" {
  name                = "RecommendedAlertRules-AG-1"
  resource_group_name = var.resource_group_name
  short_name          = "recalert1"
  tags                = var.tags
  email_receiver {
    email_address           = var.email_receiver_name
    name                    = "Email_-EmailAction-"
    use_common_alert_schema = true
  }
}

# ----------------------------------------------------------------------------------
# AZURE MONITOR METRIC ALERTS
# ----------------------------------------------------------------------------------
# These resources create metric-based alerts for AKS cluster monitoring,
# enabling proactive detection of performance and capacity issues.
#
# Key Features:
# - CPU and memory usage monitoring
# - Configurable thresholds and frequency
# - Integration with action groups for notifications
# - Real-time performance monitoring
# - Automated alerting capabilities
# ----------------------------------------------------------------------------------

# CPU Usage Alert
resource "azurerm_monitor_metric_alert" "cpu_usage" {
  auto_mitigate       = false
  frequency           = "PT5M"
  name                = "CPU Usage Percentage - ${var.aks_cluster_name}"
  resource_group_name = var.resource_group_name
  scopes              = [var.aks_id]
  tags                = var.tags
  action {
    action_group_id = azurerm_monitor_action_group.recommended.id
  }
  criteria {
    aggregation      = "Average"
    metric_name      = "node_cpu_usage_percentage"
    metric_namespace = "Microsoft.ContainerService/managedClusters"
    operator         = "GreaterThan"
    threshold        = 95
  }
}

# Memory Usage Alert
resource "azurerm_monitor_metric_alert" "memory_usage" {
  auto_mitigate       = false
  frequency           = "PT5M"
  name                = "Memory Working Set Percentage - ${var.aks_cluster_name}"
  resource_group_name = var.resource_group_name
  scopes              = [var.aks_id]
  tags                = var.tags
  action {
    action_group_id = azurerm_monitor_action_group.recommended.id
  }
  criteria {
    aggregation      = "Average"
    metric_name      = "node_memory_working_set_percentage"
    metric_namespace = "Microsoft.ContainerService/managedClusters"
    operator         = "GreaterThan"
    threshold        = 100
  }
}

# ----------------------------------------------------------------------------------
# DATA COLLECTION ENDPOINT
# ----------------------------------------------------------------------------------
# This resource creates a data collection endpoint for Prometheus metrics,
# enabling centralized metric collection and monitoring capabilities.
#
# Key Features:
# - Prometheus metrics collection
# - Centralized monitoring endpoint
# - Integration with Azure Monitor
# - Scalable metric collection
# ----------------------------------------------------------------------------------

# Microsoft Prometheus Data Collection Endpoint
resource "azurerm_monitor_data_collection_endpoint" "ms_prometheus" {
  kind                = "Linux"
  location            = var.location
  name                = "MSProm-${var.location}-${var.aks_cluster_name}"
  resource_group_name = var.resource_group_name
}

