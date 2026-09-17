# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# COUCHDB KUBERNETES RESOURCES
# ----------------------------------------------------------------------------------
# These resources deploy CouchDB as a NoSQL document database within the AKS
# cluster, providing persistent storage and high availability for the application.
#
# Key Features:
# - NoSQL document database with JSON storage
# - Persistent volume claims for data persistence
# - Multi-replica deployment capabilities
# - Network policies and security
# - Monitoring and health checks
# - Integration with Kubernetes ecosystem
# ----------------------------------------------------------------------------------

# CouchDB Namespace
resource "kubernetes_namespace" "couchdb" {
  metadata { name = var.couchdb_namespace }
}

# CouchDB Persistent Volume Claim
resource "kubernetes_persistent_volume_claim" "couchdb" {
  metadata {
    name      = var.couchdb_pvc_name
    namespace = var.couchdb_namespace
  }
  spec {
    access_modes = ["ReadWriteOnce"]
    resources { requests = { storage = var.couchdb_storage_size } }
  }
}

# CouchDB Deployment
resource "kubernetes_deployment" "couchdb" {
  metadata {
    name      = var.couchdb_deployment_name
    namespace = var.couchdb_namespace
    labels    = { app = var.couchdb_deployment_name }
  }
  spec {
    replicas = 1
    selector { match_labels = { app = var.couchdb_deployment_name } }
    template {
      metadata { labels = { app = var.couchdb_deployment_name } }
      spec {
        affinity {
          pod_anti_affinity {
            preferred_during_scheduling_ignored_during_execution {
              weight = 100
              pod_affinity_term {
                label_selector {
                  match_expressions {
                    key      = "app"
                    operator = "In"
                    values   = [var.couchdb_deployment_name]
                  }
                }
                topology_key = "kubernetes.io/hostname"
              }
            }
          }
        }
        container {
          name  = var.couchdb_deployment_name
          image = var.couchdb_image
          port { container_port = 5984 }
          env {
            name  = "COUCHDB_USER"
            value = var.couchdb_user
          }
          env {
            name  = "COUCHDB_PASSWORD"
            value = var.couchdb_password
          }
          volume_mount {
            name       = "couchdb-data"
            mount_path = "/opt/couchdb/data"
          }
        }
        volume {
          name = "couchdb-data"
          persistent_volume_claim { claim_name = var.couchdb_pvc_name }
        }
      }
    }
  }
}

# CouchDB Service
resource "kubernetes_service" "couchdb" {
  metadata {
    name      = var.couchdb_service_name
    namespace = var.couchdb_namespace
  }
  spec {
    selector = { app = var.couchdb_deployment_name }
    port {
      port        = 5984
      target_port = 5984
    }
    type = "ClusterIP"
  }
}

# ----------------------------------------------------------------------------------
# COUCHDB OPERATOR RESOURCES
# ----------------------------------------------------------------------------------
# These resources deploy the CouchDB Operator using OLM (Operator Lifecycle Manager)
# for automated database management and lifecycle operations.
#
# Key Features:
# - Operator-based database management
# - Automated lifecycle operations
# - OLM integration for operator deployment
# - Catalog source and subscription management
# - Automated installation and updates
# ----------------------------------------------------------------------------------

# Use terraform_data with kubectl commands instead of kubernetes_manifest
# This approach is more reliable when the Kubernetes provider might not be fully configured

# CouchDB Operator Group
resource "terraform_data" "couchdb_operator_group" {
  provisioner "local-exec" {
    command     = <<EOT
      # Set up kubectl access to AKS cluster
      az account set --subscription ${var.subscription_id}
      az aks get-credentials --resource-group ${var.resource_group_name} --name ${var.aks_cluster_name} --overwrite-existing
      
      # Apply the OperatorGroup
      kubectl apply -f - <<EOF
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: couchdb-operator-group
  namespace: ${var.couchdb_namespace}
spec:
  targetNamespaces:
  - ${var.couchdb_namespace}
EOF
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [kubernetes_namespace.couchdb]
}

# CouchDB Catalog Source
resource "terraform_data" "couchdb_catalog_source" {
  provisioner "local-exec" {
    command     = <<EOT
      kubectl apply -f - <<EOF
apiVersion: operators.coreos.com/v1alpha1
kind: CatalogSource
metadata:
  name: couchdb-operator-catalog
  namespace: ${var.olm_namespace}
spec:
  sourceType: grpc
  image: quay.io/couchdb/couchdb-operator-catalog:latest
  displayName: CouchDB Operator Catalog
  publisher: CouchDB
EOF
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [kubernetes_namespace.couchdb]
}

# CouchDB Operator Subscription
resource "terraform_data" "couchdb_subscription" {
  provisioner "local-exec" {
    command     = <<EOT
      kubectl apply -f - <<EOF
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: couchdb-operator
  namespace: ${var.couchdb_namespace}
spec:
  channel: stable
  name: couchdb-operator
  source: couchdb-operator-catalog
  sourceNamespace: ${var.olm_namespace}
  installPlanApproval: Automatic
EOF
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [terraform_data.couchdb_operator_group, terraform_data.couchdb_catalog_source]
}

