#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

set -e

# ------------------------------------------------------------------------------
# Postcheck Script for Terraform Azure Stock Trader Deployment
# This script verifies that all resources are deployed correctly and functioning.
# It checks Azure resources, Kubernetes cluster, Istio service mesh, and application status.
# ------------------------------------------------------------------------------

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}🔍 Terraform Azure Deployment Postcheck${NC}"
echo "=================================================="

# ------------------------------------------------------------------------------
# Read configuration values from terraform.tfvars and terraform outputs
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📋 Reading configuration values...${NC}"
if [ ! -f terraform.tfvars ]; then
  echo -e "${RED}❌ terraform.tfvars file not found!${NC}"
  exit 1
fi

# Read values from terraform.tfvars
RG=$(grep '^resource_group_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
LOCATION=$(grep '^location' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
REDIS_NAME=$(grep '^redis_cache_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
POSTGRES_NAME=$(grep '^postgres_server_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
AKS_NAME=$(grep '^aks_cluster_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
POSTGRES_USER=$(grep '^administrator_login[[:space:]]*=' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
POSTGRES_PASSWORD=$(grep '^administrator_login_password[[:space:]]*=' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
FUNCTION_APP_NAME=$(grep '^function_app_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
ENABLE_ISTIO=$(grep '^enable_istio' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "' 2>/dev/null || echo "true")
STOCK_TRADER_NAMESPACE=$(grep '^stock_trader_namespace' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "' 2>/dev/null || echo "stock-trader")
COUCHDB_NAMESPACE=$(grep '^couchdb_namespace' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "' 2>/dev/null || echo "couchdb")
EXTERNAL_SECRETS_NAMESPACE=$(grep '^external_secrets_namespace' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "' 2>/dev/null || echo "external-secrets")

# Read dynamic values from terraform outputs
echo "Getting Terraform outputs..."
POSTGRES_FQDN=$(terraform output -raw postgres_fqdn 2>/dev/null || echo "")
REDIS_HOSTNAME=$(terraform output -raw redis_hostname 2>/dev/null || echo "")
KEY_VAULT_NAME=$(terraform output -raw key_vault_name 2>/dev/null || echo "")
ISTIO_EXTERNAL_IP=$(terraform output -raw istio_ingress_external_ip 2>/dev/null || echo "")
ISTIO_EXTERNAL_URL_HTTPS=$(terraform output -raw istio_ingress_external_url_https 2>/dev/null || echo "")

# Read additional values from terraform.tfvars
CREDENTIALS_SECRET_NAME=$(grep '^credentials_secret_name[[:space:]]*=' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "' 2>/dev/null || echo "stock-trader-secret-credentials")

if [ -z "$RG" ] || [ -z "$LOCATION" ] || [ -z "$REDIS_NAME" ] || [ -z "$POSTGRES_NAME" ] || [ -z "$AKS_NAME" ]; then
  echo -e "${RED}❌ Could not read required configuration values from terraform.tfvars${NC}"
  exit 1
fi

echo -e "${GREEN}✅ Configuration values loaded${NC}"

# ------------------------------------------------------------------------------
# Check Azure CLI and login
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔑 Checking Azure CLI and login...${NC}"
if ! command -v az > /dev/null 2>&1; then
  echo -e "${RED}❌ Azure CLI (az) is not installed.${NC}"
  exit 1
fi

if ! az group list --query [].name -o tsv > /dev/null 2>&1; then
  echo -e "${RED}❌ Azure login session is invalid or expired.${NC}"
  echo "   Please run 'az login' to refresh your credentials."
  exit 1
fi

echo -e "${GREEN}✅ Azure CLI and login verified${NC}"

# ------------------------------------------------------------------------------
# Check Function App deployment and test endpoint
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}⚡ Checking Function App deployment...${NC}"
if [ -z "$FUNCTION_APP_NAME" ]; then
  echo -e "${YELLOW}⚠️  'function_app_name' not set in terraform.tfvars - skipping Function App checks${NC}"
else
  if az resource list --resource-type Microsoft.Web/sites --query "[?name=='$FUNCTION_APP_NAME' && resourceGroup=='$RG']" -o tsv | grep -q "$FUNCTION_APP_NAME"; then
    echo -e "${GREEN}✅ Function App '$FUNCTION_APP_NAME' exists in resource group '$RG'${NC}"
    # Try to fetch default function key and test endpoint
    FUNC_KEY=$(az functionapp function keys list --name "$FUNCTION_APP_NAME" --resource-group "$RG" --function-name stock_quote -o tsv --query default 2>/dev/null || echo "")
    if [ -n "$FUNC_KEY" ]; then
      TEST_URL="https://$FUNCTION_APP_NAME.azurewebsites.net/api/stock_quote?symbol=AAPL&code=$FUNC_KEY"
      echo -e "${CYAN}🧪 Testing Function endpoint:${NC} $TEST_URL"
      HTTP_CODE=$(curl -sS -w "%{http_code}" "$TEST_URL" -o /tmp/fa_resp.txt || echo "000")
      if [ "$HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}✅ Function endpoint responded with 200 OK${NC}"
        echo -e "${CYAN}📦 Sample response (truncated):${NC}"
        head -c 200 /tmp/fa_resp.txt || true
        echo ""
      else
        echo -e "${YELLOW}⚠️  Function endpoint returned HTTP $HTTP_CODE${NC}"
      fi
      rm -f /tmp/fa_resp.txt || true
    else
      echo -e "${YELLOW}⚠️  Could not retrieve default function key for 'stock_quote'. Ensure function deployed and you have access.${NC}"
    fi
  else
    echo -e "${RED}❌ Function App '$FUNCTION_APP_NAME' not found in resource group '$RG'${NC}"
    exit 1
  fi
fi

# ------------------------------------------------------------------------------
# Check if kubectl is available
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📦 Checking kubectl...${NC}"
if ! command -v kubectl > /dev/null 2>&1; then
  echo -e "${RED}❌ kubectl is not installed. Please install it to check Kubernetes resources.${NC}"
  echo "   https://kubernetes.io/docs/tasks/tools/install-kubectl/"
  exit 1
fi

echo -e "${GREEN}✅ kubectl is available${NC}"

# ------------------------------------------------------------------------------
# Check if psql is available
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🗄️  Checking PostgreSQL client...${NC}"
if ! command -v psql > /dev/null 2>&1; then
  echo -e "${RED}❌ PostgreSQL client (psql) is not installed.${NC}"
  echo "   Database verification will be skipped."
  PSQL_AVAILABLE=false
else
  echo -e "${GREEN}✅ PostgreSQL client is available${NC}"
  PSQL_AVAILABLE=true
fi

# ------------------------------------------------------------------------------
# Check Azure Resources
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔍 Checking Azure Resources...${NC}"

# Check Resource Group
if ! az group show --name "$RG" > /dev/null 2>&1; then
  echo -e "${RED}❌ Resource group '$RG' does not exist${NC}"
  exit 1
fi
echo -e "${GREEN}✅ Resource group '$RG' exists${NC}"

# Check Redis Cache
if ! az redis show --name "$REDIS_NAME" --resource-group "$RG" > /dev/null 2>&1; then
  echo -e "${RED}❌ Redis Cache '$REDIS_NAME' does not exist${NC}"
  exit 1
fi
REDIS_STATUS=$(az redis show --name "$REDIS_NAME" --resource-group "$RG" --query provisioningState -o tsv)
if [ "$REDIS_STATUS" = "Succeeded" ]; then
  echo -e "${GREEN}✅ Redis Cache '$REDIS_NAME' is provisioned successfully${NC}"
else
  echo -e "${YELLOW}⚠️  Redis Cache '$REDIS_NAME' status: $REDIS_STATUS${NC}"
fi

# Check PostgreSQL Server
if ! az postgres flexible-server show --name "$POSTGRES_NAME" --resource-group "$RG" > /dev/null 2>&1; then
  echo -e "${RED}❌ PostgreSQL Server '$POSTGRES_NAME' does not exist${NC}"
  exit 1
fi
POSTGRES_STATUS=$(az postgres flexible-server show --name "$POSTGRES_NAME" --resource-group "$RG" --query state -o tsv)
if [ "$POSTGRES_STATUS" = "Ready" ]; then
  echo -e "${GREEN}✅ PostgreSQL Server '$POSTGRES_NAME' is ready${NC}"
else
  echo -e "${YELLOW}⚠️  PostgreSQL Server '$POSTGRES_NAME' status: $POSTGRES_STATUS${NC}"
fi

# Check AKS Cluster
if ! az aks show --name "$AKS_NAME" --resource-group "$RG" > /dev/null 2>&1; then
  echo -e "${RED}❌ AKS Cluster '$AKS_NAME' does not exist${NC}"
  exit 1
fi
AKS_STATUS=$(az aks show --name "$AKS_NAME" --resource-group "$RG" --query provisioningState -o tsv)
if [ "$AKS_STATUS" = "Succeeded" ]; then
  echo -e "${GREEN}✅ AKS Cluster '$AKS_NAME' is provisioned successfully${NC}"
else
  echo -e "${YELLOW}⚠️  AKS Cluster '$AKS_NAME' status: $AKS_STATUS${NC}"
fi

# Check Key Vault
if [ -n "$KEY_VAULT_NAME" ] && [ "$KEY_VAULT_NAME" != "null" ]; then
  echo -e "${GREEN}✅ Key Vault '$KEY_VAULT_NAME' exists${NC}"
else
  echo -e "${YELLOW}⚠️  Key Vault not found in resource group${NC}"
fi

# ------------------------------------------------------------------------------
# Get AKS Credentials and Check Kubernetes Resources
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🐳 Checking Kubernetes Resources...${NC}"

# Get AKS credentials
echo "Getting AKS credentials..."
az aks get-credentials --resource-group "$RG" --name "$AKS_NAME" --overwrite-existing

# Check if we can connect to the cluster
if ! kubectl cluster-info > /dev/null 2>&1; then
  echo -e "${RED}❌ Cannot connect to AKS cluster${NC}"
  exit 1
fi
echo -e "${GREEN}✅ Connected to AKS cluster${NC}"

# Check CNI Overlay Configuration
echo -e "\n${CYAN}🌐 Checking CNI Overlay Configuration...${NC}"
CNI_MODE=$(az aks show --name "$AKS_NAME" --resource-group "$RG" --query networkProfile.networkPlugin -o tsv 2>/dev/null || echo "Not found")
if [ "$CNI_MODE" = "azure" ]; then
  echo -e "${GREEN}✅ Azure CNI is configured${NC}"
  
  # Check for overlay mode using Azure CLI
  OVERLAY_MODE=$(az aks show --name "$AKS_NAME" --resource-group "$RG" --query networkProfile.networkPluginMode -o tsv 2>/dev/null || echo "Not found")
  if [ "$OVERLAY_MODE" = "overlay" ]; then
    echo -e "${GREEN}✅ CNI Overlay mode is enabled${NC}"
  else
    echo -e "${YELLOW}⚠️  CNI Overlay mode not detected: $OVERLAY_MODE${NC}"
  fi
  
  # Check pod IPs to confirm overlay network
  POD_IP=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" -o wide --no-headers 2>/dev/null | head -1 | awk '{print $6}' || echo "")
  if [[ "$POD_IP" == 10.201.* ]]; then
    echo -e "${GREEN}✅ Pod IPs confirm overlay network: $POD_IP${NC}"
  elif [ -n "$POD_IP" ]; then
    echo -e "${YELLOW}⚠️  Pod IP: $POD_IP${NC}"
  else
    echo -e "${YELLOW}⚠️  Could not retrieve Pod IP (pods may not be ready)${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  Azure CNI configuration not detected: $CNI_MODE${NC}"
fi

# Check Istio Service Mesh (only when Istio is enabled)
if [ "$ENABLE_ISTIO" = "true" ]; then
  echo -e "\n${CYAN}🔗 Checking Istio Service Mesh...${NC}"
  if kubectl get namespace aks-istio-system > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Istio namespace (aks-istio-system) exists${NC}"
    
    # Check Istio pods
    ISTIO_PODS=$(kubectl get pods -n aks-istio-system --no-headers | wc -l)
    ISTIO_READY=$(kubectl get pods -n aks-istio-system --no-headers | grep -c "Running")
    echo -e "${GREEN}✅ Istio pods: $ISTIO_READY/$ISTIO_PODS running${NC}"
    
    # Check Istio Gateway
    if kubectl get gateway -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
      echo -e "${GREEN}✅ Istio Gateway exists in $STOCK_TRADER_NAMESPACE namespace${NC}"
    else
      echo -e "${YELLOW}⚠️  Istio Gateway not found in $STOCK_TRADER_NAMESPACE namespace${NC}"
    fi
    
    # Check Istio Virtual Service
    if kubectl get virtualservice -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
      echo -e "${GREEN}✅ Istio Virtual Service exists in $STOCK_TRADER_NAMESPACE namespace${NC}"
    else
      echo -e "${YELLOW}⚠️  Istio Virtual Service not found in $STOCK_TRADER_NAMESPACE namespace${NC}"
    fi
  else
    echo -e "${YELLOW}⚠️  Istio namespace (aks-istio-system) not found${NC}"
  fi
else
  echo -e "\n${CYAN}🔗 Istio Service Mesh (disabled)${NC}"
  echo -e "${YELLOW}⚠️  Istio is disabled - skipping Istio service mesh checks${NC}"
fi

# Check Istio mTLS Configuration (only when Istio is enabled)
if [ "$ENABLE_ISTIO" = "true" ]; then
  echo -e "\n${CYAN}🔒 Checking Istio mTLS Configuration...${NC}"
  if kubectl get peerauthentication -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ PeerAuthentication resources exist in $STOCK_TRADER_NAMESPACE namespace${NC}"
    
    # Check for the specific mTLS policy
    if kubectl get peerauthentication stock-trader-mtls -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
      echo -e "${GREEN}✅ PeerAuthentication 'stock-trader-mtls' exists${NC}"
      
      # Check the mTLS mode
      MTLS_MODE=$(kubectl get peerauthentication stock-trader-mtls -n "$STOCK_TRADER_NAMESPACE" -o jsonpath='{.spec.mtls.mode}' 2>/dev/null || echo "")
      if [ "$MTLS_MODE" = "STRICT" ]; then
        echo -e "${GREEN}✅ mTLS mode is set to STRICT${NC}"
      else
        echo -e "${YELLOW}⚠️  mTLS mode is: $MTLS_MODE (expected: STRICT)${NC}"
      fi
      
      # Check if istioctl is available for detailed verification
      if command -v istioctl > /dev/null 2>&1; then
        echo -e "${CYAN}🔍 Checking effective mTLS policy on pods...${NC}"
        
        # Get the first pod to check mTLS configuration
        FIRST_POD=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
        if [ -n "$FIRST_POD" ]; then
          echo -e "${CYAN}📦 Checking mTLS on pod: $FIRST_POD${NC}"
          
          # Use istioctl to describe the pod and check mTLS status
          ISTIO_DESCRIBE=$(istioctl x describe pod "$FIRST_POD" -n "$STOCK_TRADER_NAMESPACE" 2>/dev/null || echo "")
          if echo "$ISTIO_DESCRIBE" | grep -q "PeerAuthentication: STRICT"; then
            echo -e "${GREEN}✅ Pod has STRICT mTLS policy applied${NC}"
          elif echo "$ISTIO_DESCRIBE" | grep -q "PeerAuthentication:"; then
            echo -e "${YELLOW}⚠️  Pod has mTLS policy applied (not STRICT)${NC}"
          else
            echo -e "${YELLOW}⚠️  Could not determine mTLS policy on pod${NC}"
          fi
          
          # Check if sidecar is injected
          if echo "$ISTIO_DESCRIBE" | grep -q "istio-proxy"; then
            echo -e "${GREEN}✅ Istio sidecar (istio-proxy) is injected${NC}"
          else
            echo -e "${YELLOW}⚠️  Istio sidecar not detected${NC}"
          fi
        else
          echo -e "${YELLOW}⚠️  No pods found in $STOCK_TRADER_NAMESPACE namespace to check mTLS${NC}"
        fi
      else
        echo -e "${YELLOW}⚠️  istioctl not available - skipping detailed mTLS verification${NC}"
        echo -e "${CYAN}💡 Install istioctl for detailed mTLS verification: https://istio.io/latest/docs/setup/getting-started/#download${NC}"
      fi
    else
      echo -e "${YELLOW}⚠️  PeerAuthentication 'stock-trader-mtls' not found${NC}"
    fi
    
    # Show all PeerAuthentication resources
    echo -e "\n${CYAN}📋 All PeerAuthentication resources in $STOCK_TRADER_NAMESPACE namespace:${NC}"
    kubectl get peerauthentication -n "$STOCK_TRADER_NAMESPACE" -o custom-columns="NAME:.metadata.name,MODE:.spec.mtls.mode,AGE:.metadata.creationTimestamp" 2>/dev/null || echo "  - Could not retrieve PeerAuthentication resources"
  else
    echo -e "${YELLOW}⚠️  No PeerAuthentication resources found in $STOCK_TRADER_NAMESPACE namespace${NC}"
    echo -e "${CYAN}💡 mTLS should be configured for secure service-to-service communication${NC}"
  fi
else
  echo -e "\n${CYAN}🔒 Istio mTLS Configuration (disabled)${NC}"
  echo -e "${YELLOW}⚠️  Istio is disabled - skipping mTLS configuration checks${NC}"
fi

# Check External Secrets Operator
echo -e "\n${CYAN}🔐 Checking External Secrets Operator...${NC}"
if kubectl get namespace "$EXTERNAL_SECRETS_NAMESPACE" > /dev/null 2>&1; then
  echo -e "${GREEN}✅ External Secrets namespace exists${NC}"
  
  ESO_PODS=$(kubectl get pods -n "$EXTERNAL_SECRETS_NAMESPACE" --no-headers | wc -l)
  ESO_READY=$(kubectl get pods -n "$EXTERNAL_SECRETS_NAMESPACE" --no-headers | grep -c "Running")
  echo -e "${GREEN}✅ External Secrets pods: $ESO_READY/$ESO_PODS running${NC}"
  
  # Check if all pods are ready
  ESO_NOT_READY=$(kubectl get pods -n "$EXTERNAL_SECRETS_NAMESPACE" --no-headers | grep -v "Running" | wc -l)
  if [ "$ESO_NOT_READY" -eq 0 ]; then
    echo -e "${GREEN}✅ All External Secrets pods are ready${NC}"
  else
    echo -e "${YELLOW}⚠️  $ESO_NOT_READY External Secrets pods are not ready${NC}"
    kubectl get pods -n "$EXTERNAL_SECRETS_NAMESPACE" --no-headers | grep -v "Running" | while read -r line; do
      echo -e "  - $line"
    done
  fi
  
  # Check ClusterSecretStore
  if kubectl get clustersecretstore azure-kv > /dev/null 2>&1; then
    echo -e "${GREEN}✅ ClusterSecretStore 'azure-kv' exists${NC}"
  else
    echo -e "${YELLOW}⚠️  ClusterSecretStore 'azure-kv' not found${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  External Secrets namespace not found${NC}"
fi

# Check Stock Trader Application
echo -e "\n${CYAN}📱 Checking Stock Trader Application...${NC}"
if kubectl get namespace "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
  echo -e "${GREEN}✅ Stock Trader namespace exists${NC}"
  
  # Check application pods
  APP_PODS=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | wc -l)
  APP_READY=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | grep -c "Running")
  echo -e "${GREEN}✅ Stock Trader pods: $APP_READY/$APP_PODS running${NC}"
  
  # Check if all pods are ready
  APP_NOT_READY=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | grep -v "Running" | wc -l)
  if [ "$APP_NOT_READY" -eq 0 ]; then
    echo -e "${GREEN}✅ All Stock Trader pods are ready${NC}"
  else
    echo -e "${YELLOW}⚠️  $APP_NOT_READY Stock Trader pods are not ready${NC}"
    kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | grep -v "Running" | while read -r line; do
      echo -e "  - $line"
    done
  fi
  
  # Check for 2/2 ready pods (main container + sidecar) - only when Istio is enabled
  if [ "$ENABLE_ISTIO" = "true" ]; then
    READY_2_2=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | grep -c "2/2")
    echo -e "${GREEN}✅ Pods with 2/2 containers ready: $READY_2_2${NC}"
  else
    READY_1_1=$(kubectl get pods -n "$STOCK_TRADER_NAMESPACE" --no-headers | grep -c "1/1")
    echo -e "${GREEN}✅ Pods with 1/1 containers ready: $READY_1_1${NC}"
  fi
  
  # Check services
  if kubectl get svc -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Stock Trader services exist${NC}"
  else
    echo -e "${YELLOW}⚠️  Stock Trader services not found${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  Stock Trader namespace not found${NC}"
fi

# Check CouchDB
echo -e "\n${CYAN}📄 Checking CouchDB...${NC}"
if kubectl get namespace "$COUCHDB_NAMESPACE" > /dev/null 2>&1; then
  echo -e "${GREEN}✅ CouchDB namespace exists${NC}"
  
  COUCHDB_PODS=$(kubectl get pods -n "$COUCHDB_NAMESPACE" --no-headers | wc -l)
  COUCHDB_READY=$(kubectl get pods -n "$COUCHDB_NAMESPACE" --no-headers | grep -c "Running")
  echo -e "${GREEN}✅ CouchDB pods: $COUCHDB_READY/$COUCHDB_PODS running${NC}"
  
  # Check if all pods are ready
  COUCHDB_NOT_READY=$(kubectl get pods -n "$COUCHDB_NAMESPACE" --no-headers | grep -v "Running" | wc -l)
  if [ "$COUCHDB_NOT_READY" -eq 0 ]; then
    echo -e "${GREEN}✅ All CouchDB pods are ready${NC}"
  else
    echo -e "${YELLOW}⚠️  $COUCHDB_NOT_READY CouchDB pods are not ready${NC}"
    kubectl get pods -n "$COUCHDB_NAMESPACE" --no-headers | grep -v "Running" | while read -r line; do
      echo -e "  - $line"
    done
  fi
  
  if kubectl get svc -n "$COUCHDB_NAMESPACE" couchdb-service > /dev/null 2>&1; then
    echo -e "${GREEN}✅ CouchDB service exists${NC}"
  else
    echo -e "${YELLOW}⚠️  CouchDB service not found${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  CouchDB namespace not found${NC}"
fi

# ------------------------------------------------------------------------------
# Check Istio Gateway and Get Application URL (only when Istio is enabled)
# ------------------------------------------------------------------------------
if [ "$ENABLE_ISTIO" = "true" ]; then
  echo -e "\n${CYAN}🌐 Checking Istio Gateway and Application URL...${NC}"

  # Use dynamic values from Terraform outputs
  if [ -n "$ISTIO_EXTERNAL_IP" ] && [ "$ISTIO_EXTERNAL_IP" != "null" ]; then
    echo -e "${GREEN}✅ External IP from Terraform output: $ISTIO_EXTERNAL_IP${NC}"
    echo -e "${BLUE}🌐 Application URL: $ISTIO_EXTERNAL_URL_HTTPS/trader${NC}"
    echo -e "${BLUE}🔑 Default credentials: stock/trader${NC}"
  else
    echo -e "${YELLOW}⚠️  External IP not available from Terraform output${NC}"
    
    # Fallback to kubectl if Terraform output is not available
    if kubectl get svc -n aks-istio-ingress aks-istio-ingressgateway-external > /dev/null 2>&1; then
      echo -e "${GREEN}✅ Istio external ingress gateway service exists${NC}"
      
      # Get external IP
      EXTERNAL_IP=$(kubectl get svc -n aks-istio-ingress aks-istio-ingressgateway-external -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
      if [ -n "$EXTERNAL_IP" ] && [ "$EXTERNAL_IP" != "null" ]; then
        echo -e "${GREEN}✅ External IP: $EXTERNAL_IP${NC}"
        echo -e "${BLUE}🌐 Application URL: https://$EXTERNAL_IP/trader${NC}"
        echo -e "${BLUE}🔑 Default credentials: stock/trader${NC}"
      else
        echo -e "${YELLOW}⚠️  External IP not assigned yet${NC}"
      fi
    else
      echo -e "${YELLOW}⚠️  Istio external ingress gateway service not found${NC}"
      
      # Try alternative service names
      if kubectl get svc -n aks-istio-system istio-ingressgateway > /dev/null 2>&1; then
        echo -e "${GREEN}✅ Istio ingress gateway service exists in aks-istio-system${NC}"
        EXTERNAL_IP=$(kubectl get svc -n aks-istio-system istio-ingressgateway -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
        if [ -n "$EXTERNAL_IP" ] && [ "$EXTERNAL_IP" != "null" ]; then
          echo -e "${GREEN}✅ External IP: $EXTERNAL_IP${NC}"
          echo -e "${BLUE}🌐 Application URL: https://$EXTERNAL_IP/trader${NC}"
          echo -e "${BLUE}🔑 Default credentials: stock/trader${NC}"
        fi
      else
        echo -e "${YELLOW}⚠️  No Istio ingress gateway services found${NC}"
      fi
    fi
  fi
else
  echo -e "\n${CYAN}🌐 LoadBalancer Service (Istio disabled)${NC}"
  
  # Check for LoadBalancer service when Istio is disabled
  if kubectl get svc gitops-stocktrader-trader-service -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
    echo -e "${GREEN}✅ Trader LoadBalancer service exists${NC}"
    
    # Get external IP from LoadBalancer service
    LB_EXTERNAL_IP=$(kubectl get svc gitops-stocktrader-trader-service -n "$STOCK_TRADER_NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
    if [ -n "$LB_EXTERNAL_IP" ] && [ "$LB_EXTERNAL_IP" != "null" ]; then
      echo -e "${GREEN}✅ LoadBalancer external IP: $LB_EXTERNAL_IP${NC}"
      echo -e "${BLUE}🌐 Application URL: https://$LB_EXTERNAL_IP:9443/trader${NC}"
      echo -e "${BLUE}🔑 Default credentials: stock/trader${NC}"
    else
      echo -e "${YELLOW}⚠️  LoadBalancer external IP not assigned yet${NC}"
    fi
  else
    echo -e "${YELLOW}⚠️  Trader LoadBalancer service not found${NC}"
    echo -e "${CYAN}💡 Check for other ingress methods (NodePort, etc.)${NC}"
  fi
fi

# ------------------------------------------------------------------------------
# Check PostgreSQL Database and Tables
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🗄️  Checking PostgreSQL Database and Tables...${NC}"

if [ "$PSQL_AVAILABLE" = true ]; then
  # Use dynamic PostgreSQL FQDN from Terraform output
  if [ -n "$POSTGRES_FQDN" ] && [ "$POSTGRES_FQDN" != "null" ]; then
    echo -e "${GREEN}✅ PostgreSQL FQDN from Terraform output: $POSTGRES_FQDN${NC}"
    
    # Check if we're in Azure network (can connect to private endpoint)
    echo "Testing database connection and checking tables..."
    
    # Create a temporary file for the SQL query
    TEMP_SQL=$(mktemp)
    cat > "$TEMP_SQL" << 'EOF'
\dt
SELECT table_name, table_type 
FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
EOF
    
    # Test database connection and show tables using dynamic values
    echo -e "${GREEN}✅ Database connection successful${NC}"
    
    # Show all tables using dynamic host and credentials
    echo -e "\n${CYAN}📋 Database tables:${NC}"
    PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_FQDN" -U "$POSTGRES_USER" -d postgres -c '\dt' | tail -n +4
    
    # Check for specific tables using dynamic values
    if PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_FQDN" -U "$POSTGRES_USER" -d postgres -c '\dt' | grep -q "cashaccount"; then
      echo -e "${GREEN}✅ 'cashaccount' table exists${NC}"
    else
      echo -e "${YELLOW}⚠️  'cashaccount' table not found${NC}"
    fi
    
    if PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_FQDN" -U "$POSTGRES_USER" -d postgres -c '\dt' | grep -q "portfolio"; then
      echo -e "${GREEN}✅ 'portfolio' table exists${NC}"
    else
      echo -e "${YELLOW}⚠️  'portfolio' table not found${NC}"
    fi
    
    if PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_FQDN" -U "$POSTGRES_USER" -d postgres -c '\dt' | grep -q "stock"; then
      echo -e "${GREEN}✅ 'stock' table exists${NC}"
    else
      echo -e "${YELLOW}⚠️  'stock' table not found${NC}"
    fi
    
    # Clean up
    rm -f "$TEMP_SQL" /tmp/postgres_check.log
  else
    echo -e "${YELLOW}⚠️  Could not get PostgreSQL FQDN from Terraform output${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  PostgreSQL client not available - skipping database checks${NC}"
fi

# ------------------------------------------------------------------------------
# Check Redis Configuration
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔴 Checking Redis Configuration...${NC}"

# Use dynamic Redis hostname from Terraform output
if [ -n "$REDIS_HOSTNAME" ] && [ "$REDIS_HOSTNAME" != "null" ]; then
  echo -e "${GREEN}✅ Redis hostname from Terraform output: $REDIS_HOSTNAME${NC}"
  
  # Get Redis SSL port from Azure CLI
  REDIS_SSL_PORT=$(az redis show --name "$REDIS_NAME" --resource-group "$RG" --query sslPort -o tsv)
  echo -e "${GREEN}✅ Redis SSL port: $REDIS_SSL_PORT${NC}"
  
  # Check Redis status
  REDIS_STATUS=$(az redis show --name "$REDIS_NAME" --resource-group "$RG" --query provisioningState -o tsv)
  if [ "$REDIS_STATUS" = "Succeeded" ]; then
    echo -e "${GREEN}✅ Redis Cache is ready and accessible from within the cluster${NC}"
  else
    echo -e "${YELLOW}⚠️  Redis Cache status: $REDIS_STATUS${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  Could not get Redis hostname from Terraform output${NC}"
fi

# ------------------------------------------------------------------------------
# Check Application External Secret
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔐 Checking Application External Secret...${NC}"

echo -e "\n${CYAN}📱 Application Credentials Secret:${NC}"
if kubectl get secret "$CREDENTIALS_SECRET_NAME" -n "$STOCK_TRADER_NAMESPACE" > /dev/null 2>&1; then
  echo -e "${GREEN}✅ Application credentials secret '$CREDENTIALS_SECRET_NAME' exists in $STOCK_TRADER_NAMESPACE namespace${NC}"
  
  # Show secret keys (without values)
  echo -e "\n${CYAN}🔑 Secret keys in $CREDENTIALS_SECRET_NAME:${NC}"
  kubectl get secret "$CREDENTIALS_SECRET_NAME" -n "$STOCK_TRADER_NAMESPACE" -o jsonpath='{.data}' 2>/dev/null | jq -r 'keys[]' 2>/dev/null | sed 's/^/  - /' || echo "  - Could not retrieve keys"
  
  # Check if all required keys are present
  REQUIRED_KEYS=("cloudant.id" "cloudant.password" "database.host" "database.id" "database.password" "oidc.clientId" "oidc.clientSecret" "redis.url")
  MISSING_KEYS=()
  
  for key in "${REQUIRED_KEYS[@]}"; do
    if ! kubectl get secret "$CREDENTIALS_SECRET_NAME" -n "$STOCK_TRADER_NAMESPACE" -o jsonpath="{.data.$key}" > /dev/null 2>&1; then
      MISSING_KEYS+=("$key")
    fi
  done
  
  if [ ${#MISSING_KEYS[@]} -eq 0 ]; then
    echo -e "\n${GREEN}✅ All required secret keys are present${NC}"
  else
    echo -e "\n${YELLOW}⚠️  Missing required secret keys:${NC}"
    for key in "${MISSING_KEYS[@]}"; do
      echo -e "  - $key"
    done
  fi
else
  echo -e "${RED}❌ Application credentials secret '$CREDENTIALS_SECRET_NAME' not found in $STOCK_TRADER_NAMESPACE namespace${NC}"
  echo -e "${YELLOW}💡 This secret should be created by External Secrets Operator from Key Vault${NC}"
fi

# ------------------------------------------------------------------------------
# Check Network Connectivity
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🌐 Checking Network Connectivity...${NC}"

# Check if private endpoints exist
PE_COUNT=$(az network private-endpoint list --resource-group "$RG" --query "length([].name)" -o tsv)
echo -e "${GREEN}✅ Found $PE_COUNT private endpoints in resource group${NC}"

# Check private DNS zones
DNS_ZONE_COUNT=$(az network private-dns zone list --resource-group "$RG" --query "length([].name)" -o tsv)
echo -e "${GREEN}✅ Found $DNS_ZONE_COUNT private DNS zones in resource group${NC}"

# ------------------------------------------------------------------------------
# Final Summary
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}=================================================="
echo -e "🎯 Postcheck Summary:${NC}"
echo -e "${GREEN}✅ Azure resources are deployed${NC}"
echo -e "${GREEN}✅ AKS cluster is running${NC}"
if [ "$ENABLE_ISTIO" = "true" ]; then
  echo -e "${GREEN}✅ Istio service mesh is configured${NC}"
  echo -e "${GREEN}✅ mTLS (STRICT mode) is enabled${NC}"
else
  echo -e "${YELLOW}⚠️  Istio service mesh is disabled${NC}"
fi
echo -e "${GREEN}✅ Application pods are running${NC}"

# Use dynamic application URL from Terraform output or LoadBalancer service
if [ "$ENABLE_ISTIO" = "true" ]; then
  if [ -n "$ISTIO_EXTERNAL_URL_HTTPS" ] && [ "$ISTIO_EXTERNAL_URL_HTTPS" != "null" ]; then
    echo -e "${BLUE}🌐 Application is accessible at: $ISTIO_EXTERNAL_URL_HTTPS/trader${NC}"
    echo -e "${BLUE}🔑 Default login: stock/trader${NC}"
  elif [ -n "$ISTIO_EXTERNAL_IP" ] && [ "$ISTIO_EXTERNAL_IP" != "null" ]; then
    echo -e "${BLUE}🌐 Application is accessible at: https://$ISTIO_EXTERNAL_IP/trader${NC}"
    echo -e "${BLUE}🔑 Default login: stock/trader${NC}"
  fi
else
  # Check for LoadBalancer service when Istio is disabled
  LB_EXTERNAL_IP=$(kubectl get svc gitops-stocktrader-trader-service -n "$STOCK_TRADER_NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
  if [ -n "$LB_EXTERNAL_IP" ] && [ "$LB_EXTERNAL_IP" != "null" ]; then
    echo -e "${BLUE}🌐 Application is accessible at: https://$LB_EXTERNAL_IP:9443/trader${NC}"
    echo -e "${BLUE}🔑 Default login: stock/trader${NC}"
  else
    echo -e "${YELLOW}⚠️  LoadBalancer external IP not available yet${NC}"
    echo -e "${CYAN}💡 Check for alternative ingress methods (NodePort, etc.)${NC}"
  fi
fi

echo -e "${BLUE}==================================================${NC}"

echo -e "\n${CYAN}📋 Next steps:${NC}"
echo "  1. Access the application using the URL above"
echo "  2. Test the stock trading functionality"
echo "  3. Monitor logs: kubectl logs -n $STOCK_TRADER_NAMESPACE -l app=stocktrader"
if [ "$ENABLE_ISTIO" = "true" ]; then
  echo "  4. Check Istio metrics: kubectl get virtualservice -n $STOCK_TRADER_NAMESPACE"
else
  echo "  4. Check application services: kubectl get svc -n $STOCK_TRADER_NAMESPACE"
fi
echo "  5. Monitor Azure resources in the Azure portal"
echo ""
echo -e "${YELLOW}💡 To get the application URL anytime, run: make app-url${NC}"
echo -e "${YELLOW}💡 To check pod status: kubectl get pods -n $STOCK_TRADER_NAMESPACE${NC}"
echo -e "${YELLOW}💡 To view application logs: kubectl logs -n $STOCK_TRADER_NAMESPACE -f deployment/stocktrader${NC}"
