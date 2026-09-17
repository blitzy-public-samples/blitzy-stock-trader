# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# EXTERNAL SECRETS OPERATOR (ESO) DEPLOYMENT
# ----------------------------------------------------------------------------------
# This module deploys External Secrets Operator to synchronize secrets from
# Azure Key Vault to Kubernetes using Azure Workload Identity for authentication.
#
# Key Features:
# - Kubernetes-native secret management
# - Secure secret synchronization with Workload Identity
# - Automatic secret updates and rotation
# - Integration with Azure Key Vault for centralized secret management
# - RBAC and access control for secret access
# - Helm-based deployment with CRD installation
# ----------------------------------------------------------------------------------

# Install External Secrets Operator and Workload Identity Webhook
resource "terraform_data" "helm_install_eso" {
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      helm repo add azure-workload-identity https://azure.github.io/azure-workload-identity/charts || true
      helm repo update
      helm upgrade --install workload-identity-webhook azure-workload-identity/azure-workload-identity \
        --namespace kube-system | cat
      helm repo add external-secrets https://charts.external-secrets.io || true
      helm repo update
      kubectl get ns ${var.namespace} >/dev/null 2>&1 || kubectl create ns ${var.namespace}
      helm upgrade --install external-secrets external-secrets/external-secrets \
        --namespace ${var.namespace} \
        --set installCRDs=true \
        --set webhook.networkPolicy.enabled=false \
        --set certController.enabled=true | cat
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
}

# ----------------------------------------------------------------------------------
# CRD AVAILABILITY WAIT
# ----------------------------------------------------------------------------------
# This resource waits for External Secrets Operator CRDs to be available
# and ensures the operator pods are ready before proceeding with configuration.
#
# Key Features:
# - Ensures CRDs are established before use
# - Validates operator pod readiness
# - Webhook endpoint availability check
# - Proper deployment sequencing
# ----------------------------------------------------------------------------------

# Wait for CRDs to be available
resource "terraform_data" "wait_for_crds" {
  depends_on = [terraform_data.helm_install_eso]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Wait for CRDs to be available
      echo "Waiting for External Secrets CRDs to be available..."
      kubectl wait --for=condition=established --timeout=300s crd/clustersecretstores.external-secrets.io
      kubectl wait --for=condition=established --timeout=300s crd/externalsecrets.external-secrets.io
      echo "CRDs are now available"
      
      echo "Waiting for External Secrets Operator pods to be ready..."
      kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=external-secrets -n external-secrets --timeout=300s
      kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=external-secrets-webhook -n external-secrets --timeout=300s
      echo "External Secrets Operator pods are ready"
      
      # Additional wait to ensure webhook endpoints are available
      echo "Waiting for webhook endpoints to be available..."
      for i in {1..30}; do
        if kubectl get endpoints -n external-secrets external-secrets-webhook --no-headers 2>/dev/null | grep -q ":"; then
          echo "Webhook endpoints are available"
          break
        fi
        if [ $i -eq 30 ]; then
          echo "ERROR: Webhook endpoints not available after 2.5 minutes"
          exit 1
        fi
        echo "Waiting for webhook endpoints... (attempt $i/30)"
        sleep 5
      done
    EOT
  }
}

# ----------------------------------------------------------------------------------
# WORKLOAD IDENTITY SERVICE ACCOUNT
# ----------------------------------------------------------------------------------
# This resource creates a Kubernetes ServiceAccount configured for Azure
# Workload Identity, enabling secure pod-to-Azure authentication.
#
# Key Features:
# - ServiceAccount with Workload Identity annotations
# - Azure AD integration for authentication
# - Secure token exchange mechanism
# - Integration with External Secrets Operator
# ----------------------------------------------------------------------------------

# Create ServiceAccount for Workload Identity
resource "terraform_data" "service_account" {
  depends_on = [terraform_data.wait_for_crds]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      kubectl -n ${var.namespace} apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${var.service_account_name}
  namespace: ${var.namespace}
  annotations:
    azure.workload.identity/client-id: ${var.uai_client_id}
EOF
    EOT
  }
}

# ----------------------------------------------------------------------------------
# FEDERATED IDENTITY CREDENTIAL
# ----------------------------------------------------------------------------------
# This resource creates a federated identity credential on the User-Assigned
# Identity, enabling trust between Kubernetes ServiceAccounts and Azure AD.
#
# Key Features:
# - OIDC-based trust relationship
# - Secure token exchange mechanism
# - Integration with Azure Workload Identity
# - ServiceAccount-to-Identity mapping
# ----------------------------------------------------------------------------------

# Federated Identity Credential on the UAI to trust this SA
resource "azurerm_federated_identity_credential" "eso" {
  name                = "eso-fic"
  resource_group_name = var.resource_group_name
  audience            = ["api://AzureADTokenExchange"]
  issuer              = var.oidc_issuer_url
  subject             = "system:serviceaccount:${var.namespace}:${var.service_account_name}"
  parent_id           = var.uai_id
}

# ----------------------------------------------------------------------------------
# CLUSTER SECRET STORE
# ----------------------------------------------------------------------------------
# This resource creates a ClusterSecretStore that defines how External Secrets
# Operator connects to Azure Key Vault for secret retrieval.
#
# Key Features:
# - Azure Key Vault integration
# - Workload Identity authentication
# - Cluster-wide secret store configuration
# - Automatic secret synchronization
# ----------------------------------------------------------------------------------

# Create ClusterSecretStore
resource "terraform_data" "cluster_secret_store" {
  depends_on = [terraform_data.service_account]

  provisioner "local-exec" {
    command = <<-EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Wait for External Secrets Operator webhook to be ready
      echo "Waiting for External Secrets Operator webhook to be ready..."
      for i in {1..60}; do
        if kubectl get pods -n external-secrets -l app.kubernetes.io/name=external-secrets-webhook --no-headers | grep -q "Running" && \
           kubectl get endpoints -n external-secrets external-secrets-webhook --no-headers | grep -q ":"; then
          echo "External Secrets Operator webhook is ready"
          break
        fi
        if [ $i -eq 60 ]; then
          echo "ERROR: External Secrets Operator webhook not ready after 5 minutes"
          exit 1
        fi
        echo "Waiting for webhook... (attempt $i/60)"
        sleep 5
      done
      
      # Wait a bit more to ensure webhook is fully operational
      sleep 10
      
      # Create ClusterSecretStore YAML file
      cat > /tmp/clustersecretstore.yaml << 'YAML_EOF'
apiVersion: external-secrets.io/v1
kind: ClusterSecretStore
metadata:
  name: azure-kv
spec:
  provider:
    azurekv:
      tenantId: ${var.tenant_id}
      vaultUrl: ${var.key_vault_uri}
      authType: WorkloadIdentity
      serviceAccountRef:
        name: ${var.service_account_name}
        namespace: ${var.namespace}
YAML_EOF
      
      # Apply ClusterSecretStore with retry logic
      for attempt in {1..3}; do
        echo "Applying ClusterSecretStore (attempt $attempt/3)..."
        if kubectl apply -f /tmp/clustersecretstore.yaml; then
          echo "ClusterSecretStore applied successfully"
          break
        else
          if [ $attempt -eq 3 ]; then
            echo "ERROR: Failed to apply ClusterSecretStore after 3 attempts"
            exit 1
          fi
          echo "Attempt $attempt failed, retrying in 10 seconds..."
          sleep 10
        fi
      done
      
      # Clean up
      rm -f /tmp/clustersecretstore.yaml
    EOT
  }
}

# ----------------------------------------------------------------------------------
# EXTERNAL SECRET
# ----------------------------------------------------------------------------------
# This resource creates an ExternalSecret that defines which secrets to
# synchronize from Azure Key Vault to Kubernetes namespaces.
#
# Key Features:
# - Secret synchronization from Key Vault to Kubernetes
# - Application-specific secret mapping
# - Automatic secret updates and rotation
# - Integration with application deployments
# ----------------------------------------------------------------------------------

# ExternalSecret to create stock-trader-secret-credentials in the app namespace
resource "terraform_data" "external_secret" {
  depends_on = [terraform_data.cluster_secret_store]

  provisioner "local-exec" {
    command     = <<EOT
      set -e
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Ensure namespace exists
      kubectl get ns ${var.app_namespace} >/dev/null 2>&1 || kubectl create ns ${var.app_namespace}
      
      # Wait for ClusterSecretStore to be ready
      echo "Waiting for ClusterSecretStore to be ready..."
      for i in {1..30}; do
        if kubectl get clustersecretstore azure-kv --no-headers 2>/dev/null | grep -q "azure-kv"; then
          echo "ClusterSecretStore is ready"
          break
        fi
        if [ $i -eq 30 ]; then
          echo "ERROR: ClusterSecretStore not ready after 2.5 minutes"
          exit 1
        fi
        echo "Waiting for ClusterSecretStore... (attempt $i/30)"
        sleep 5
      done
      
      # Create ExternalSecret YAML file
      cat > /tmp/externalsecret.yaml << 'YAML_EOF'
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: ${var.credentials_secret_name}
  namespace: ${var.app_namespace}
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: ${var.cluster_secret_store_name}
  target:
    name: ${var.credentials_secret_name}
    creationPolicy: Owner
    template:
      type: Opaque
  data:
  - secretKey: cloudant.id
    remoteRef:
      key: cloudant-id
  - secretKey: cloudant.password
    remoteRef:
      key: cloudant-password
  - secretKey: database.id
    remoteRef:
      key: database-id
  - secretKey: database.password
    remoteRef:
      key: database-password
  - secretKey: database.host
    remoteRef:
      key: database-host
  - secretKey: redis.url
    remoteRef:
      key: redis-url
  - secretKey: oidc.clientId
    remoteRef:
      key: oidc-clientId
  - secretKey: oidc.clientSecret
    remoteRef:
      key: oidc-clientSecret
YAML_EOF
      
      # Apply ExternalSecret with retry logic
      for attempt in {1..3}; do
        echo "Applying ExternalSecret (attempt $attempt/3)..."
        if kubectl apply -f /tmp/externalsecret.yaml; then
          echo "ExternalSecret applied successfully"
          break
        else
          if [ $attempt -eq 3 ]; then
            echo "ERROR: Failed to apply ExternalSecret after 3 attempts"
            exit 1
          fi
          echo "Attempt $attempt failed, retrying in 10 seconds..."
          sleep 10
        fi
      done
      
      # Clean up
      rm -f /tmp/externalsecret.yaml
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
}


