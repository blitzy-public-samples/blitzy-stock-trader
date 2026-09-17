# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

locals {
  # Common tags that should be applied to all resources
  common_tags = {
    Environment        = var.environment
    Project            = var.project_name
    ManagedBy          = "Terraform"
    CostCenter         = var.cost_center
    Owner              = var.owner
    Service            = var.service_name
    Compliance         = var.compliance_requirements
    DataClassification = var.data_classification
    BackupRetention    = var.backup_retention
    Criticality        = var.criticality
    Customer           = var.customer_name
  }

  # Service-specific tags
  service_tags = {
    "api-management" = {
      ServiceType = "API Gateway"
      ServiceTier = var.service_tier
    }
    "couchdb" = {
      ServiceType = "Database"
      ServiceTier = var.service_tier
    }
    "postgresql" = {
      ServiceType = "Database"
      ServiceTier = var.service_tier
    }
    "redis" = {
      ServiceType = "Cache"
      ServiceTier = var.service_tier
    }
    "kubernetes" = {
      ServiceType = "Container Platform"
      ServiceTier = var.service_tier
    }
  }
}

# Variables for tag values
variable "environment" {
  description = "Environment (e.g., dev, test, prod)"
  type        = string
  default     = "dev"
}

variable "project_name" {
  description = "Name of the project"
  type        = string
  default     = "StockTrader"
}

variable "cost_center" {
  description = "Cost center for billing purposes"
  type        = string
  default     = "IT-1234"
}

variable "owner" {
  description = "Team or individual responsible for the resource"
  type        = string
  default     = "Platform Team"
}

variable "service_name" {
  description = "Name of the service"
  type        = string
  default     = "StockTrader Platform"
}

variable "compliance_requirements" {
  description = "Compliance requirements (e.g., PCI, HIPAA, None)"
  type        = string
  default     = "None"
}

variable "data_classification" {
  description = "Data classification level"
  type        = string
  default     = "Internal"
}

variable "backup_retention" {
  description = "Backup retention period"
  type        = string
  default     = "30d"
}

variable "criticality" {
  description = "Criticality level of the service"
  type        = string
  default     = "Medium"
}

variable "service_tier" {
  description = "Service tier (e.g., Basic, Standard, Premium)"
  type        = string
  default     = "Standard"
}

variable "customer_name" {
  description = "Customer name"
  type        = string
  default     = "Kyndryl"
}