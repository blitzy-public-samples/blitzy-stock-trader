# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

provider "azurerm" {
  features {
  }
  subscription_id                 = var.subscription_id
  environment                     = "public"
  use_msi                         = false
  use_cli                         = true
  use_oidc                        = false
  resource_provider_registrations = "none"
}

# Kubernetes provider configured from AKS outputs; used by modules via provider inheritance
provider "kubernetes" {
  host                   = module.aks.host
  cluster_ca_certificate = module.aks.cluster_ca_certificate
  client_certificate     = module.aks.client_certificate
  client_key             = module.aks.client_key
}

data "azurerm_client_config" "current" {}

provider "helm" {
  kubernetes {
    host                   = module.aks.host
    cluster_ca_certificate = module.aks.cluster_ca_certificate
    client_certificate     = module.aks.client_certificate
    client_key             = module.aks.client_key
  }
}
