# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# FUNCTION APP MODULE VARIABLES
# ----------------------------------------------------------------------------------
# Defines minimal inputs required to deploy the Function App used for
# the `stock_quote` HTTP-triggered function.
# - `resource_group_name`: Target Azure Resource Group
# - `function_app_name`:   Name of the Function App resource
# - `location`:            Azure region where resources will be deployed
# ----------------------------------------------------------------------------------

variable "resource_group_name" {
  description = "Azure Resource Group name"
  type        = string
}

variable "function_app_name" {
  description = "Azure Function App name"
  type        = string
}

variable "location" {
  description = "Azure location"
  type        = string
}
