# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# STOCK TRADER APPLICATION DEPLOYMENT
# ----------------------------------------------------------------------------------
# This module deploys the Stock Trader application using Kubernetes Custom Resources
# and configures Istio service mesh for traffic management and security.
#
# Key Features:
# - Application deployment via Custom Resources
# - Configuration management and template rendering
# - Integration with external services (Redis, PostgreSQL, CouchDB)
# - Istio service mesh integration for traffic management
# - SSL/TLS certificate generation and management
# - Gateway and VirtualService configuration
# ----------------------------------------------------------------------------------

# Custom Resource YAML Template Rendering
resource "local_file" "cr_yaml" {
  content = templatefile(var.cr_template_path, {
    namespace               = var.namespace
    redis_url               = var.redis_url
    stock_quote_api_connect = var.stock_quote_api_connect
    couchdb_user            = var.couchdb_user
    couchdb_password        = var.couchdb_password
    couchdb_service_name    = var.couchdb_service_name
    couchdb_namespace       = var.couchdb_namespace
    couchdb_database_name   = var.couchdb_database_name
    credentials_secret_name = var.credentials_secret_name
    database_host           = var.database_host
  })
  filename = "${path.module}/cr_${var.namespace}.yaml"
}

# Apply Custom Resource to Kubernetes with retry logic
resource "terraform_data" "apply_cr_yaml" {
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Wait for StockTrader CRD to be available with retry logic
      echo "Waiting for StockTrader CRD to be available..."
      for attempt in {1..10}; do
        if kubectl get crd stocktraders.operators.ibm.com >/dev/null 2>&1; then
          echo "StockTrader CRD is available"
          break
        fi
        if [ $attempt -eq 10 ]; then
          echo "ERROR: StockTrader CRD not available after 10 attempts"
          exit 1
        fi
        echo "Waiting for StockTrader CRD... (attempt $attempt/10)"
        sleep 30
      done
      
      # Apply the Custom Resource with retry logic
      echo "Applying StockTrader Custom Resource..."
      for attempt in {1..5}; do
        if kubectl apply -f ${local_file.cr_yaml.filename} -n ${var.namespace}; then
          echo "StockTrader Custom Resource applied successfully"
          break
        else
          if [ $attempt -eq 5 ]; then
            echo "ERROR: Failed to apply StockTrader Custom Resource after 5 attempts"
            exit 1
          fi
          echo "Attempt $attempt failed, retrying in 30 seconds..."
          sleep 30
        fi
      done
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [local_file.cr_yaml]
}



# Rollout Restart for Sidecar Injection (only when Istio is enabled)
resource "terraform_data" "rollout_restart" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      for i in {1..30}; do
        if kubectl -n ${var.istio_ingress_namespace} get svc ${var.istio_ingress_external_service_name} >/dev/null 2>&1; then
          break
        fi
        sleep 10
      done
      for d in $(kubectl -n ${var.namespace} get deploy -o name); do kubectl -n ${var.namespace} rollout restart $d || true; done
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.apply_cr_yaml]
}

# Render Istio PeerAuthentication (STRICT mTLS) for the app namespace (only when Istio is enabled)
resource "local_file" "peer_auth_yaml" {
  count    = var.enable_istio ? 1 : 0
  content  = templatefile("${path.module}/peer-auth.yaml.tmpl", { namespace = var.namespace })
  filename = "${path.module}/peer-auth_${var.namespace}.yaml"
}

# Apply Istio PeerAuthentication (only when Istio is enabled)
resource "terraform_data" "apply_peer_auth" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      kubectl apply -f ${local_file.peer_auth_yaml[0].filename}
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [local_file.peer_auth_yaml, terraform_data.rollout_restart]
}

# Istio Gateway and VirtualService Configuration (only when Istio is enabled)
resource "local_file" "istio_gateway_yaml" {
  count = var.enable_istio ? 1 : 0
  content = templatefile("${path.module}/istio-gateway.yaml.tmpl", {
    stock_trader_namespace              = var.namespace
    istio_ingress_external_service_name = var.istio_ingress_external_service_name
  })
  filename = "${path.module}/istio_gateway_${var.namespace}.yaml"
}

# SSL Certificate Generation (only when Istio is enabled)
resource "terraform_data" "generate_ssl_certificates" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Wait for external IP to be assigned
      for i in {1..30}; do
        EXTERNAL_IP=$(kubectl -n ${var.istio_ingress_namespace} get svc ${var.istio_ingress_external_service_name} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        if [ -n "$EXTERNAL_IP" ] && [ "$EXTERNAL_IP" != "null" ]; then
          echo "External IP found: $EXTERNAL_IP"
          break
        fi
        echo "Waiting for external IP... (attempt $i/30)"
        sleep 10
      done
      
      # Generate certificates with the external IP
      mkdir -p ${path.module}/certs
      openssl req -x509 -sha256 -nodes -days 365 -newkey rsa:2048 \
        -subj "/O=Stock Trader/CN=$EXTERNAL_IP" \
        -keyout ${path.module}/certs/stock-trader.key \
        -out ${path.module}/certs/stock-trader.crt
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.rollout_restart]
}

# TLS Secret Creation (only when Istio is enabled)
resource "terraform_data" "create_tls_secret" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Create TLS secret in stock-trader namespace (where the Gateway is deployed)
      kubectl create secret tls stock-trader-tls \
        --key=${path.module}/certs/stock-trader.key \
        --cert=${path.module}/certs/stock-trader.crt \
        -n ${var.namespace} \
        --dry-run=client -o yaml | kubectl apply -f -
      
      # Create TLS secret in Istio ingress namespace (where the ingress gateway looks for it)
      kubectl create secret tls stock-trader-tls \
        --key=${path.module}/certs/stock-trader.key \
        --cert=${path.module}/certs/stock-trader.crt \
        -n ${var.istio_ingress_namespace} \
        --dry-run=client -o yaml | kubectl apply -f -
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.generate_ssl_certificates]
}

# Apply Istio Gateway Configuration (only when Istio is enabled)
resource "terraform_data" "apply_istio_gateway" {
  count = var.enable_istio ? 1 : 0
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      kubectl apply -f ${local_file.istio_gateway_yaml[0].filename}
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [local_file.istio_gateway_yaml, terraform_data.create_tls_secret, terraform_data.apply_peer_auth]
}

