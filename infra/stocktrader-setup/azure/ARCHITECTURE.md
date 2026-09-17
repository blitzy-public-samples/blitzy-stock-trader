# Architecture Overview

## Purpose
This repository provisions a complete, production-ready Azure environment for the Stock Trader application using Terraform. It includes Azure infrastructure, an AKS cluster with Azure CNI overlay networking, Istio service mesh, managed data services (PostgreSQL, Redis), secure secret management via Azure Key Vault, and Kubernetes bootstrap for the application and operators.

## High-Level Diagram
See `public/diagram.png` for the deployment flow and relationships. This document expands on those components and the networking model.

---

## Core Design Principles
- Clear separation between Azure infrastructure (VNet) and Kubernetes overlay networks (pods/services)
- Secure-by-default: private endpoints, Workload Identity, and mTLS via Istio
- Reproducible IaC with opinionated defaults and variable validation
- Minimize VNet address consumption to support multi-affiliate topologies

---

## Networking Model

### Azure VNet (Infrastructure Plane)
- VNet: `172.16.0.0/26`
  - AKS nodes: `172.16.0.0/27`
  - Private endpoints: `172.16.0.32/28`
- Purpose: Host node NICs, load balancers, and private endpoints only
- Rationale: Keep small to avoid overlap and scale affiliates easily

### Kubernetes Overlay (Workload Plane)
- Configured via Azure CNI overlay in AKS (Kubernetes-managed)
- Independent of the VNet ranges
- Addressing:
  - Services: `10.200.0.0/16`
  - Pods: `10.201.0.0/16`
  - DNS service IP: `10.200.0.10`
- Benefits:
  - Isolation between infrastructure and workloads
  - Scalable IP space for pods/services
  - Easier multi-environment/multi-affiliate coexistence

#### How CNI Overlay Works (in this repo)
- Overlay IP management is handled by Kubernetes; Azure VNet does not allocate pod/service IPs.
- Nodes have VNet IPs (172.16.0.0/27), while pods/services get overlay IPs (10.201.0.0/16, 10.200.0.0/16).
- Pod-to-pod traffic stays in the overlay and is transparent to the VNet.
- Egress from pods to external networks exits via node networking and Azure load balancers/NAT as configured.
- Because overlay ranges are not part of the VNet, the same overlay CIDRs can be reused across affiliates without conflict.

#### Why Overlay (vs. traditional Azure CNI)
- Conserves scarce VNet IP space (only nodes consume VNet IPs).
- Simplifies multi-tenant/multi-affiliate deployments with standardized overlay CIDRs.
- Decouples Kubernetes workload scaling from VNet sizing.

#### Constraints and Notes
- Ensure `dns_service_ip` is within `service_cidr`.
- Changing overlay CIDRs after cluster creation requires recreation.
- Network policies use `network_policy = "azure"` and operate on overlay IPs.
- Some network integrations that expect routable pod IPs in the VNet should instead route through gateways or node-level egress.

#### Verification (summary)
- Nodes show VNet IPs: `kubectl get nodes -o wide` → 172.16.x.x
- Pods show overlay IPs: `kubectl get pods -o wide` → 10.201.x.x
- Services show overlay IPs: `kubectl get services -o wide` → 10.200.x.x
- AKS confirms overlay mode:
  - `az aks show -g <rg> -n <cluster> --query "networkProfile"` → `networkPluginMode: overlay`

For step-by-step checks and example outputs, see `mdfiles/CNI_OVERLAY_IMPLEMENTATION.md`.

---

## Istio (Azure Service Mesh)
- Enabled through `service_mesh_profile` on AKS
- Revisioned (e.g., `asm-1-24`) to enable side-by-side upgrades
- Components:
  - `istiod` control plane
  - External ingress gateway for HTTPS exposure
  - Sidecar proxies injected into app pods
- Traffic and Security:
  - mTLS between services
  - Gateway + VirtualService for routing to Stock Trader services
  - Centralized ingress; policy enforcement at the mesh layer

#### Revisioning and Injection
- The enabled revision (e.g., `asm-1-24`) is listed under `service_mesh_profile.revisions`.
- Namespaces must be labeled with `istio.io/rev=asm-1-24` to receive sidecars.
- After labeling, existing deployments should be restarted to pick up sidecars.

#### Gateways
- Both internal and external ingress gateways are enabled at the AKS layer; this repo uses the external gateway for app access.
- The external gateway obtains an Azure LoadBalancer IP. Terraform templates apply Gateway and VirtualService to route `/trader` and related paths.

#### Common Pitfalls (and fixes implemented)
- Wrong injection label (`istio-injection=enabled`) replaced with `istio.io/rev=asm-1-24`.
- Ensured external ingress gateway existence and readiness before app access checks.
- Added rollout restarts for workloads after labeling to ensure sidecars are injected.

#### Verification (summary)
- Check mesh profile: `az aks show -g <rg> -n <cluster> --query "serviceMeshProfile"`
- Confirm namespace label: `kubectl get namespace <ns> --show-labels`
- Pods READY 2/2 with `istio-proxy`: `kubectl -n <ns> get pods -o wide`
- External gateway IP assigned: `kubectl -n aks-istio-ingress get svc aks-istio-ingressgateway-external -o wide`

For detailed steps, commands, and troubleshooting, see `mdfiles/ISTIO_SETUP_FIXES.md`.

---

## Secrets and Workload Identity
- AKS OIDC issuer enabled; Workload Identity binds Kubernetes ServiceAccounts to Azure AD
- User-assigned managed identity (UAI) used by workloads
- Azure Key Vault stores secrets; External Secrets Operator (ESO) syncs to Kubernetes

---

## Resource Inventory by Module

### Root (`main.tf` orchestrates modules)
- Calls all modules in dependency order
- Passes overlay CIDRs, Istio revision, names, and tags

### `modules/network`
- Resources:
  - `azurerm_virtual_network.db_vnet`
    - `address_space = ["172.16.0.0/26"]`
    - `name = vnet_name`, `location`, `resource_group_name`
  - `azurerm_subnet.aks_subnet`
    - `address_prefixes = ["172.16.0.0/27"]`
    - Hosts AKS node pool
  - `azurerm_subnet.db_private_endpoints_subnet`
    - `address_prefixes = ["172.16.0.32/28"]`
    - Hosts Private Endpoints (PE) for data services
- Outputs used elsewhere:
  - `vnet_id`, `aks_subnet_id`, `db_private_endpoints_subnet_id`

### `modules/aks`
- Resource:
  - `azurerm_kubernetes_cluster.this`
    - Cluster:
      - `name`, `location`, `resource_group_name`, `tags`
      - `oidc_issuer_enabled = true`, `workload_identity_enabled = true`
      - `automatic_upgrade_channel = "patch"`
      - `image_cleaner_enabled = true`
      - `monitor_metrics {}`
      - `identity { type = "SystemAssigned" }`
    - Default node pool:
      - Autoscaling `min_count = 2`, `max_count = 5`
      - `vm_size = var.aks_node_vm_size`
      - `os_disk_type = "Ephemeral"`
      - `vnet_subnet_id = var.aks_subnet_id`
    - Network profile (Azure CNI overlay):
      - `network_plugin = "azure"`
      - `network_policy = "azure"`
      - `network_plugin_mode = "overlay"`
      - `service_cidr = var.aks_service_cidr` (default `10.200.0.0/16`)
      - `pod_cidr = var.aks_pod_cidr` (default `10.201.0.0/16`)
      - `dns_service_ip = var.aks_dns_service_ip` (default `10.200.0.10`)
      - `load_balancer_sku = "standard"`
    - Service mesh profile (Istio ASM):
      - `mode = "Istio"`
      - `revisions = var.aks_service_mesh_revisions` (e.g., `["asm-1-24"]`)
      - `internal_ingress_gateway_enabled = true`
      - `external_ingress_gateway_enabled = true`
- Outputs used elsewhere:
  - `id`, `name`, `host`, `cluster_ca_certificate`, `client_certificate`, `client_key`, `kube_config_raw`, `oidc_issuer_url`

### `modules/dns`
- Typical resources (as referenced by consumers):
  - Private DNS zones for Redis (`privatelink.redis.cache.windows.net`) and PostgreSQL (`privatelink.postgres.database.azure.com`)
  - VNet links binding the above zones to `db_vnet`
- Outputs:
  - `postgres_private_dns_zone_id`, `redis_private_dns_zone_id`

### `modules/postgres`
- Resources:
  - `azurerm_postgresql_flexible_server`
    - Server with admin login/password
    - Private networking (paired with PE module)
  - Optionally: databases per application needs (initialized in `postgres_init`)
- Outputs:
  - `id`, `fqdn`

### `modules/redis`
- Resources:
  - `azurerm_redis_cache`
    - SKU from `redis_cache_sku` (Basic/Standard/Premium)
- Outputs:
  - `id`, `hostname`, `primary_access_key`

### `modules/private_endpoints`
- Resources:
  - `azurerm_private_endpoint` for PostgreSQL
    - Subnet: `db_private_endpoints_subnet_id`
    - Connection to `postgres_server_id`
    - Links to `postgres_private_dns_zone_id`
  - `azurerm_private_endpoint` for Redis
    - Subnet: `db_private_endpoints_subnet_id`
    - Connection to `redis_id`
    - Links to `redis_private_dns_zone_id`

### `modules/uai`
- Resources:
  - `azurerm_user_assigned_identity`
- Outputs:
  - `id`, `client_id`, `principal_id`

### `modules/key_vault`
- Resources:
  - `azurerm_key_vault`
  - `azurerm_key_vault_secret` entries
    - Includes computed `redis-url` and application secrets
- Inputs/Outputs:
  - Accepts `uai_principal_id` for access policies/role assignments
  - Outputs `vault_uri`

### `modules/monitoring`
- Resources:
  - Azure Monitor alert rules, action groups, Data Collection Endpoint (as applicable)
- Purpose:
  - Basic operational visibility and alerting hooks

### `modules/k8s_bootstrap`
- Resources (via Kubernetes provider and CLI where applicable):
  - OLM installation
  - Namespace creation and labeling for Istio injection: `istio.io/rev=asm-1-24`
  - Operator subscriptions required by the app

### `modules/postgres_init`
- Resources/Actions:
  - Runs SQL template to create schema (`init_schema.sql.tmpl`)

### `modules/couchdb`
- Resources:
  - Kubernetes manifests to deploy CouchDB in-cluster
  - PVC, Service, and related objects

### `modules/apply_cr`
- Resources:
  - Renders and applies Stock Trader Custom Resource
  - Applies Istio Gateway and VirtualService from templates

---

## Lifecycle and Flow
1. Network and data services are provisioned
2. AKS cluster is created with overlay networking and Istio ASM
3. Istio ingress is exposed externally (HTTPS)
4. Kubernetes bootstrap installs OLM/operators and labels namespaces for sidecar injection
5. Secrets are synchronized from Key Vault into Kubernetes via ESO
6. Application CR and Istio routing are applied; app becomes reachable via HTTPS

---

## Verification
- AKS network profile:
  - `az aks show -g <rg> -n <cluster> --query "networkProfile"`
- Overlay IPs for pods/services:
  - `kubectl get pods -o wide`
  - `kubectl get services -o wide`
- Istio ASM config and sidecars:
  - `az aks show -g <rg> -n <cluster> --query "serviceMeshProfile"`
  - `kubectl -n <ns> get pods -o wide` (READY should be 2/2)
- Key Vault + ESO:
  - Confirm ESO `ClusterSecretStore` and synced Secrets in app namespace

---

## Security Considerations
- mTLS inside the mesh; optional Istio authorization policies
- Private endpoints for data planes; no public exposure for DB/Redis
- Workload identity avoids long-lived credentials in pods
- Validations on variables to reduce misconfiguration risk

---

## Extensibility and Multi-Affiliate Guidance
- Keep each affiliate VNet at `/26` with consistent subnetting (`/27` nodes, `/28` private endpoints)
- Reuse the same overlay CIDRs across affiliates safely (independent of VNet)
- Customize Istio revisions per environment to rollback/advance independently
- Add additional managed services behind private endpoints and private DNS as needed

---

## References
- `mdfiles/CNI_OVERLAY_IMPLEMENTATION.md` for step-by-step overlay details and checks
- AzureRM provider `~> 4.x` with AKS overlay support
- Istio ASM revisioning (`asm-1-24`) guidance
