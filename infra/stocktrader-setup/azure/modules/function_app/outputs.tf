# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# FUNCTION APP MODULE OUTPUTS
# ----------------------------------------------------------------------------------
# Exposes useful information for invoking and integrating with the deployed
# Azure Function App instance.
# - `function_app_name`: Name of the Function App
# - `default_host_key`:  Default key for authorizing HTTP-trigger calls
# - `function_app_invoke_url`: Full invoke URL for the `stock_quote` function
# ----------------------------------------------------------------------------------

output "function_app_name" {
  value       = var.function_app_name
  description = "Deployed Azure Function App name"
}

output "default_host_key" {
  value     = try(data.azurerm_function_app_host_keys.fa.default_function_key, "")
  sensitive = true
}

output "function_app_invoke_url" {
  value       = "https://${var.function_app_name}.azurewebsites.net/api/stock_quote?code=${try(data.azurerm_function_app_host_keys.fa.default_function_key, "")}"
  description = "Invoke URL for the stock_quote function including default key"
}

