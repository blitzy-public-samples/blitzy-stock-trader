# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# KUBERNETES BOOTSTRAP OPERATIONS
# ----------------------------------------------------------------------------------
# This module performs day-2 operations on the AKS cluster, including service mesh
# configuration, operator installation, and namespace setup for the Stock Trader application.
#
# Key Features:
# - Istio external ingress gateway configuration
# - OLM (Operator Lifecycle Manager) installation
# - Stock Trader operator deployment
# - Application namespace creation and labeling
# - Service mesh integration setup
# - Operator-based application management
# ----------------------------------------------------------------------------------

# Enable Istio External Ingress Gateway (only when Istio is enabled)
resource "terraform_data" "enable_istio_external_ingress" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az extension add --name aks-preview --yes >/dev/null 2>&1 || az extension update --name aks-preview >/dev/null 2>&1 || true
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      if ! kubectl -n ${var.istio_ingress_namespace} get svc ${var.istio_ingress_external_service_name} >/dev/null 2>&1; then
        az aks mesh enable-ingress-gateway \
          --resource-group ${var.resource_group_name} \
          --name ${var.aks_cluster_name} \
          --ingress-gateway-type external
      fi
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
}

# Install OLM (Operator Lifecycle Manager)
resource "terraform_data" "install_olm" {
  provisioner "local-exec" {
    command     = <<EOT
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      if ! operator-sdk version > /dev/null 2>&1; then
        echo "operator-sdk not found in PATH. Please install it before running this step. See: https://sdk.operatorframework.io/docs/installation/" >&2
        exit 1
      fi
      operator-sdk olm install
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
}

# Install Stock Trader Operator
resource "terraform_data" "install_stocktrader_operator" {
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Wait for OLM to be ready
      echo "Waiting for OLM to be ready..."
      kubectl wait --for=condition=ready pod -l app=olm-operator -n olm --timeout=300s
      kubectl wait --for=condition=ready pod -l app=catalog-operator -n olm --timeout=300s
      
      # Install operator with increased timeout and retry logic
      echo "Installing Stock Trader Operator..."
      for i in {1..3}; do
        echo "Attempt $i of 3"
        if operator-sdk run bundle docker.io/ibmstocktrader/stocktrader-operator-bundle:v1.0.0 --timeout=600s; then
          echo "Operator installation successful"
          break
        else
          echo "Attempt $i failed, waiting 30 seconds before retry..."
          sleep 30
        fi
      done
      
      # Verify operator installation
      echo "Verifying operator installation..."
      kubectl get subscription -n olm
      kubectl get csv -n olm
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.install_olm]
}

# Create Stock Trader Namespace
resource "terraform_data" "create_stock_trader_namespace" {
  provisioner "local-exec" {
    command     = <<EOT
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      kubectl get namespace ${var.stock_trader_namespace} >/dev/null 2>&1 || kubectl create namespace ${var.stock_trader_namespace}
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.install_stocktrader_operator]
}

# Label Stock Trader Namespace for Istio (only when Istio is enabled)
resource "terraform_data" "label_stock_trader_namespace" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      kubectl label namespace ${var.stock_trader_namespace} istio.io/rev=${var.istio_revision} --overwrite
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.create_stock_trader_namespace]
}

