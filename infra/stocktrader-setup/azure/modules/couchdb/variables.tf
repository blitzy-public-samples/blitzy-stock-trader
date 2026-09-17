# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# COUCHDB MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the CouchDB module.
# These variables control the configuration of the NoSQL document database
# deployment within the AKS cluster for the Stock Trader application.
#
# Variable Categories:
# - Kubernetes Access: AKS cluster credentials and configuration
# - Azure Configuration: Subscription, resource group, cluster details
# - CouchDB Configuration: Database settings, storage, and deployment
# - Authentication: User credentials and access control
# - Operator Configuration: OLM and operator management
# ----------------------------------------------------------------------------------

# =============================================================================
# KUBERNETES ACCESS VARIABLES
# =============================================================================

# Kubernetes API server host URL
variable "kube_host" { type = string }
# Kubernetes cluster CA certificate for secure API communication
variable "kube_cluster_ca_certificate" { type = string }

# Kubernetes client certificate for authentication
variable "kube_client_certificate" { type = string }

# Kubernetes client key for authentication
variable "kube_client_key" { type = string }

# =============================================================================
# AZURE CONFIGURATION VARIABLES
# =============================================================================

# Azure subscription ID for AKS cluster access
variable "subscription_id" { type = string }

# Name of the Azure resource group containing the AKS cluster
variable "resource_group_name" { type = string }

# Name of the AKS cluster for credential configuration
variable "aks_cluster_name" { type = string }

# =============================================================================
# COUCHDB CONFIGURATION VARIABLES
# =============================================================================

# Kubernetes namespace for CouchDB deployment
variable "couchdb_namespace" { type = string }

# Name of the Persistent Volume Claim for CouchDB data storage
variable "couchdb_pvc_name" { type = string }

# Storage size for CouchDB data persistence (e.g., "10Gi")
variable "couchdb_storage_size" { type = string }

# Name of the CouchDB deployment in Kubernetes
variable "couchdb_deployment_name" { type = string }

# Docker image for CouchDB container (e.g., "couchdb:3.3.2")
variable "couchdb_image" { type = string }

# =============================================================================
# AUTHENTICATION VARIABLES
# =============================================================================

# CouchDB administrator username
variable "couchdb_user" { type = string }

# CouchDB administrator password (sensitive)
variable "couchdb_password" {
  type      = string
  sensitive = true
}

# =============================================================================
# SERVICE CONFIGURATION VARIABLES
# =============================================================================

# Name of the CouchDB Kubernetes service
variable "couchdb_service_name" { type = string }

# =============================================================================
# OPERATOR CONFIGURATION VARIABLES
# =============================================================================

# Kubernetes namespace for OLM (Operator Lifecycle Manager)
variable "olm_namespace" { type = string }

