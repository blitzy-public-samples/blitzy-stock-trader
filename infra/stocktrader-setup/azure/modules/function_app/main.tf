# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# AZURE FUNCTION APP (Stock Quote)
# ----------------------------------------------------------------------------------
# Provisions a minimal Azure Function App to host the `stock_quote` HTTP function.
# Includes storage account, packaging, and output of an invocation URL with key.
#
# Key Notes:
# - Creates a unique storage account for the Functions runtime
# - Packages code in `app/` into a ZIP for deployment
# - Exposes outputs for function name, default host key, and invoke URL
# ----------------------------------------------------------------------------------
locals {
	app_dir         = "${path.module}/app"
	package_path    = "${path.module}/build/package.zip"
	sa_prefix       = substr(join("", regexall("[a-z0-9]", lower(var.function_app_name))), 0, 12)
	storage_account_name = "${length(local.sa_prefix) > 2 ? local.sa_prefix : "fa"}${random_string.sa_suffix.result}"
}

# Random string used to ensure globally unique storage account naming
resource "random_string" "sa_suffix" {
	length  = 8
	special = false
	upper   = false
}

# Storage account for the Functions runtime and package
resource "azurerm_storage_account" "fa" {
	name                     = local.storage_account_name
	resource_group_name      = var.resource_group_name
	location                 = var.location
	account_tier             = "Standard"
	account_replication_type = "LRS"
}

resource "azurerm_storage_container" "package" {
	name                  = "function-packages"
	storage_account_id    = azurerm_storage_account.fa.id
	container_access_type = "private"
}

# Zip the function app source
data "archive_file" "function_package" {
	type        = "zip"
	source_dir  = local.app_dir
	output_path = local.package_path
}

# Upload the package to blob storage
resource "azurerm_storage_blob" "package" {
	name                   = "${var.function_app_name}-${data.archive_file.function_package.output_sha}.zip"
	storage_account_name   = azurerm_storage_account.fa.name
	storage_container_name = azurerm_storage_container.package.name
	type                   = "Block"
	source                 = data.archive_file.function_package.output_path
}

# Generate a read-only SAS URL for the uploaded package
data "azurerm_storage_account_sas" "package" {
	connection_string = azurerm_storage_account.fa.primary_connection_string
	https_only        = true

	resource_types {
		service   = false
		container = false
		object    = true
	}

	services {
		blob  = true
		queue = false
		table = false
		file  = false
	}

	start  = timestamp()
	expiry = timeadd(timestamp(), "240h")

	permissions {
		read    = true
		write   = false
		delete  = false
		list    = false
		add     = false
		create  = false
		update  = false
		process = false
		tag     = false
		filter  = false
	}
}

locals {
	package_sas_url = "${azurerm_storage_account.fa.primary_blob_endpoint}${azurerm_storage_container.package.name}/${azurerm_storage_blob.package.name}${data.azurerm_storage_account_sas.package.sas}"
}

# App Service plan - Linux Consumption (Y1)
resource "azurerm_service_plan" "fa" {
	name                = "${var.function_app_name}-plan"
	resource_group_name = var.resource_group_name
	location            = var.location
	os_type             = "Linux"
	sku_name            = "FC1"
}

# Flex Consumption Function App configured for Python 3.11
resource "azurerm_function_app_flex_consumption" "fa" {
	name                = var.function_app_name
	resource_group_name = var.resource_group_name
	location            = var.location
	service_plan_id     = azurerm_service_plan.fa.id

	storage_container_type      = "blobContainer"
	storage_container_endpoint  = "${azurerm_storage_account.fa.primary_blob_endpoint}${azurerm_storage_container.package.name}"
	storage_authentication_type = "StorageAccountConnectionString"
	storage_access_key          = azurerm_storage_account.fa.primary_access_key

	runtime_name    = "python"
	runtime_version = "3.11"

	maximum_instance_count = 50
	instance_memory_in_mb  = 2048

	site_config {}
}

# Deploy the packaged code using Azure CLI OneDeploy after the app exists.
resource "null_resource" "deploy_code" {
	triggers = {
		function_app_name = var.function_app_name
		resource_group    = var.resource_group_name
		package_sha       = data.archive_file.function_package.output_sha
	}

	provisioner "local-exec" {
		interpreter = ["/bin/bash", "-c"]
		command     = <<-EOT
			set -e
			ZIP="${data.archive_file.function_package.output_path}"
			APP="${var.function_app_name}"
			RG="${var.resource_group_name}"
			echo "Deploying package to $APP in $RG via zip deploy..."
			az functionapp deployment source config-zip --resource-group "$RG" --name "$APP" --src "$ZIP" >/dev/null
			# Best-effort trigger sync
			az functionapp sync-triggers --resource-group "$RG" --name "$APP" >/dev/null || true
		EOT
	}

	depends_on = [
		azurerm_function_app_flex_consumption.fa,
		azurerm_storage_blob.package
	]
}

# Expose default host function key for invoking HTTP-triggered functions
data "azurerm_function_app_host_keys" "fa" {
	name                = azurerm_function_app_flex_consumption.fa.name
	resource_group_name = var.resource_group_name
}