#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl

set -e

# ------------------------------------------------------------------------------
# Enhanced Precheck Script for Terraform Azure Deployments
# This script validates prerequisites and environment before running Terraform.
# It checks for required tools, Azure login, permissions, and resource uniqueness.
# Updated for Terraform best practices implementation.
# ------------------------------------------------------------------------------

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${BLUE}🔍 Terraform Azure Deployment Precheck${NC}"
echo "=================================================="

# ------------------------------------------------------------------------------
# Check if Azure CLI is installed
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📋 Checking Azure CLI...${NC}"
if ! command -v az > /dev/null 2>&1; then
  echo -e "${RED}❌ Azure CLI (az) is not installed. Please install it:${NC}"
  echo "   https://docs.microsoft.com/en-us/cli/azure/install-azure-cli"
  exit 1
fi
AZ_VERSION=$(az version | grep '"azure-cli"' | awk -F'"' '{print $4}')
echo -e "${GREEN}✅ Azure CLI version: $AZ_VERSION${NC}"

# ------------------------------------------------------------------------------
# Check Azure Functions Core Tools (func)
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}⚙️  Checking Azure Functions Core Tools...${NC}"
if ! command -v func > /dev/null 2>&1; then
  echo -e "${RED}❌ Azure Functions Core Tools (func) is not installed.${NC}"
  echo "   Install with: npm install -g azure-functions-core-tools@4 --unsafe-perm true"
  exit 1
fi
FUNC_VERSION=$(func --version 2>/dev/null || echo "unknown")
echo -e "${GREEN}✅ Functions Core Tools version: $FUNC_VERSION${NC}"

# ------------------------------------------------------------------------------
# Check if the user is logged in to Azure and the token is valid
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔑 Checking Azure login status...${NC}"
if ! az group list --query [].name -o tsv > /dev/null 2>&1; then
  echo -e "${RED}❌ Your Azure login session is invalid or expired.${NC}"
  echo "   Please run 'az login' or 'az login --use-device-code' to refresh your credentials."
  exit 1
fi

SUBSCRIPTION_ID=$(az account show --query id -o tsv)
USER=$(az account show --query user.name -o tsv)
echo -e "${GREEN}✅ Logged in as: $USER${NC}"
echo -e "${GREEN}✅ Subscription ID: $SUBSCRIPTION_ID${NC}"

# ------------------------------------------------------------------------------
# Check if Operator SDK is installed
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📦 Checking Operator SDK...${NC}"
if ! command -v operator-sdk > /dev/null 2>&1; then
  echo -e "${RED}❌ Operator SDK is not installed. Please install it:${NC}"
  echo "   https://sdk.operatorframework.io/docs/installation/"
  exit 1
fi
OPSDK_VERSION=$(operator-sdk version | grep 'operator-sdk version' | awk '{print $3}')
echo -e "${GREEN}✅ Operator SDK version: $OPSDK_VERSION${NC}"

# ------------------------------------------------------------------------------
# Check if Terraform is installed and meets version requirements
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🏗️  Checking Terraform...${NC}"
REQUIRED_TF_VERSION="1.0.0"
if ! command -v terraform > /dev/null 2>&1; then
  echo -e "${RED}❌ Terraform is not installed. Please install Terraform version $REQUIRED_TF_VERSION or later:${NC}"
  echo "   https://www.terraform.io/downloads.html"
  exit 1
fi
TF_VERSION=$(terraform version -json | grep 'terraform_version' | awk -F '"' '{print $4}')
echo -e "${GREEN}✅ Terraform version: $TF_VERSION${NC}"

# Check version against requirements in versions.tf
if [ -f "versions.tf" ]; then
  REQUIRED_VERSION=$(grep 'required_version' versions.tf | sed 's/.*"\(.*\)".*/\1/')
  if [ -n "$REQUIRED_VERSION" ]; then
    echo -e "${CYAN}📋 Required Terraform version from versions.tf: $REQUIRED_VERSION${NC}"
    # Simple version check - you might want to implement more sophisticated version comparison
    if [[ "$TF_VERSION" < "1.0" ]]; then
      echo -e "${RED}❌ Terraform version $TF_VERSION is too old. Please upgrade to 1.0 or later.${NC}"
      exit 1
    fi
  fi
fi

# ------------------------------------------------------------------------------
# Check for additional tools (optional but recommended)
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🛠️  Checking additional tools...${NC}"

# Check for kubectl
if command -v kubectl > /dev/null 2>&1; then
  KUBECTL_VERSION=$(kubectl version --client --short 2>/dev/null | cut -d' ' -f3 | tr -d 'v')
  if [ -n "$KUBECTL_VERSION" ]; then
    echo -e "${GREEN}✅ kubectl version: $KUBECTL_VERSION${NC}"
  else
    echo -e "${GREEN}✅ kubectl is installed${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  kubectl not found (optional for post-deployment operations)${NC}"
fi

# Check for terraform-docs
if command -v terraform-docs > /dev/null 2>&1; then
  echo -e "${GREEN}✅ terraform-docs is installed${NC}"
else
  echo -e "${YELLOW}⚠️  terraform-docs not found (optional for documentation generation)${NC}"
fi

# Check for tflint
if command -v tflint > /dev/null 2>&1; then
  TFLINT_VERSION=$(tflint --version | head -n1 | awk '{print $2}' | tr -d 'v')
  if [ -n "$TFLINT_VERSION" ]; then
    echo -e "${GREEN}✅ tflint version: $TFLINT_VERSION${NC}"
  else
    echo -e "${GREEN}✅ tflint is installed${NC}"
  fi
else
  echo -e "${YELLOW}⚠️  tflint not found (optional for linting)${NC}"
fi

# Check for pre-commit
if command -v pre-commit > /dev/null 2>&1; then
  echo -e "${GREEN}✅ pre-commit is installed${NC}"
else
  echo -e "${YELLOW}⚠️  pre-commit not found (optional for git hooks)${NC}"
fi

# Check for OpenSSL (required for HTTPS certificate generation)
if command -v openssl > /dev/null 2>&1; then
  OPENSSL_VERSION=$(openssl version | awk '{print $2}')
  echo -e "${GREEN}✅ OpenSSL version: $OPENSSL_VERSION${NC}"
else
  echo -e "${RED}❌ OpenSSL is not installed. Please install it for HTTPS certificate generation:${NC}"
  echo "   MacOS: brew install openssl"
  echo "   Ubuntu/Debian: sudo apt-get install openssl"
  echo "   RedHat/CentOS: sudo yum install openssl"
  exit 1
fi

# Check for jq (required for JSON parsing in scripts)
if command -v jq > /dev/null 2>&1; then
  JQ_VERSION=$(jq --version | cut -d'-' -f2)
  echo -e "${GREEN}✅ jq version: $JQ_VERSION${NC}"
else
  echo -e "${YELLOW}⚠️  jq not found (optional but recommended for JSON parsing)${NC}"
  echo "   MacOS: brew install jq"
  echo "   Ubuntu/Debian: sudo apt-get install jq"
  echo "   RedHat/CentOS: sudo yum install jq"
fi

# ------------------------------------------------------------------------------
# Check if PostgreSQL client (psql) is installed
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🗄️  Checking PostgreSQL client...${NC}"
if ! command -v psql > /dev/null 2>&1; then
  echo -e "${RED}❌ PostgreSQL client (psql) is not installed. Please install it to enable automated DDL execution.${NC}"
  echo "   MacOS: brew install libpq && brew link --force libpq"
  echo "   Ubuntu/Debian: sudo apt-get install postgresql-client"
  echo "   RedHat/CentOS: sudo yum install postgresql"
  echo "   Windows (Installer): https://www.postgresql.org/download/windows/"
  echo "   Windows (Chocolatey): choco install postgresql"
  echo "   WSL: sudo apt-get install postgresql-client"
  exit 1
fi
echo -e "${GREEN}✅ PostgreSQL client (psql) is installed.${NC}"

# ------------------------------------------------------------------------------
# Check if terraform.tfvars file exists
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📄 Checking configuration files...${NC}"
if [ ! -f terraform.tfvars ]; then
  echo -e "${RED}❌ terraform.tfvars file not found! Please create it with your variable values.${NC}"
  echo "   You can copy terraform.tfvars.example if available."
  exit 1
fi
echo -e "${GREEN}✅ terraform.tfvars found${NC}"

# ------------------------------------------------------------------------------
# Check if cr.yaml.tmpl file exists (now in modules/apply_cr/)
# ------------------------------------------------------------------------------
if [ ! -f "modules/apply_cr/cr.yaml.tmpl" ]; then
  echo -e "${RED}❌ cr.yaml.tmpl file not found in modules/apply_cr/!${NC}"
  echo "   This file should be located at: modules/apply_cr/cr.yaml.tmpl"
  exit 1
fi
echo -e "${GREEN}✅ cr.yaml.tmpl found in modules/apply_cr/${NC}"

# ------------------------------------------------------------------------------
# Check if init_schema.sql.tmpl file exists (now in modules/postgres_init/)
# ------------------------------------------------------------------------------
if [ ! -f "modules/postgres_init/init_schema.sql.tmpl" ]; then
  echo -e "${RED}❌ init_schema.sql.tmpl file not found in modules/postgres_init/!${NC}"
  echo "   This file should be located at: modules/postgres_init/init_schema.sql.tmpl"
  exit 1
fi
echo -e "${GREEN}✅ init_schema.sql.tmpl found in modules/postgres_init/${NC}"

# ------------------------------------------------------------------------------
# Check if istio-gateway.yaml.tmpl file exists (now in modules/apply_cr/)
# ------------------------------------------------------------------------------
if [ ! -f "modules/apply_cr/istio-gateway.yaml.tmpl" ]; then
  echo -e "${RED}❌ istio-gateway.yaml.tmpl file not found in modules/apply_cr/!${NC}"
  echo "   This file should be located at: modules/apply_cr/istio-gateway.yaml.tmpl"
  exit 1
fi
echo -e "${GREEN}✅ istio-gateway.yaml.tmpl found in modules/apply_cr/${NC}"

# ------------------------------------------------------------------------------
# Read resource group and location from terraform.tfvars
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔍 Reading configuration values...${NC}"
RG=$(grep '^resource_group_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
LOCATION=$(grep '^location' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')

if [ -z "$RG" ]; then
  echo -e "${RED}❌ Could not read resource_group_name from terraform.tfvars${NC}"
  exit 1
fi

if [ -z "$LOCATION" ]; then
  echo -e "${RED}❌ Could not read location from terraform.tfvars${NC}"
  exit 1
fi

echo -e "${GREEN}✅ Resource Group: $RG${NC}"
echo -e "${GREEN}✅ Location: $LOCATION${NC}"

# ------------------------------------------------------------------------------
# Check if the specified resource group exists in Azure
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔎 Checking if resource group '$RG' exists...${NC}"
if ! az group show --name "$RG" > /dev/null 2>&1; then
  echo -e "${RED}❌ Resource group '$RG' does not exist. Please create it before running Terraform.${NC}"
  echo "   You can create it with: az group create --name '$RG' --location '$LOCATION'"
  exit 1
fi
echo -e "${GREEN}✅ Resource group '$RG' exists.${NC}"

# ------------------------------------------------------------------------------
# Check if the current user has Owner or Contributor permissions on the resource group
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔐 Checking permissions...${NC}"
HAS_RG_ROLE=$(az role assignment list --assignee "$USER" --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RG" --query "[?roleDefinitionName=='Owner'||roleDefinitionName=='Contributor']" -o tsv)
HAS_SUB_ROLE=$(az role assignment list --assignee "$USER" --scope "/subscriptions/$SUBSCRIPTION_ID" --query "[?roleDefinitionName=='Owner'||roleDefinitionName=='Contributor']" -o tsv)

if [ -z "$HAS_RG_ROLE" ] && [ -z "$HAS_SUB_ROLE" ]; then
  echo -e "${YELLOW}⚠️  WARNING: Could not verify Owner or Contributor role for $USER on resource group '$RG' or the subscription.${NC}"
  echo "   This check only detects direct assignments. Inherited or group-based permissions (which are common in Azure) may not be shown here."
  echo "   If you have previously created resources in this subscription or resource group, you likely have the required permissions."
  echo "   You may proceed, but if you encounter authorization errors during deployment, please contact your Azure administrator."
else
  echo -e "${GREEN}✅ Permissions verified for resource group access.${NC}"
fi

# ------------------------------------------------------------------------------
# Show all variable values from terraform.tfvars in a pretty format
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}📋 Configuration values from terraform.tfvars:${NC}"
echo "--------------------------------------------------"
grep -v '^ *#' terraform.tfvars | grep -v '^ *$' | while IFS='=' read -r key value; do
  # Remove comments after value, trim spaces/quotes
  clean_value=$(echo "$value" | cut -d'#' -f1 | tr -d ' "')
  clean_key=$(echo "$key" | tr -d ' ')
  if [ -n "$clean_key" ] && [ -n "$clean_value" ]; then
    # Mask sensitive values
    if [[ "$clean_key" == *"password"* ]] || [[ "$clean_key" == *"secret"* ]]; then
      masked_value="***MASKED***"
      printf "  ${CYAN}%-35s${NC} : ${YELLOW}%s${NC}\n" "$clean_key" "$masked_value"
    else
      printf "  ${CYAN}%-35s${NC} : %s\n" "$clean_key" "$clean_value"
    fi
  fi
done

# ------------------------------------------------------------------------------
# Check for resource name uniqueness in the subscription
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔍 Checking resource name uniqueness...${NC}"
REDIS_NAME=$(grep '^redis_cache_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
POSTGRES_NAME=$(grep '^postgres_server_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
AKS_NAME=$(grep '^aks_cluster_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
FUNCTION_APP_NAME=$(grep '^function_app_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
KEY_VAULT_NAME=$(grep '^key_vault_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
REDIS_PE_NAME=$(grep '^redis_private_endpoint_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')
POSTGRES_PE_NAME=$(grep '^postgres_private_endpoint_name' terraform.tfvars | awk -F= '{print $2}' | cut -d'#' -f1 | tr -d ' "')

DUPLICATE_MSG=""

# Check if Redis Cache name is unique in the subscription
if [ -n "$REDIS_NAME" ]; then
  if az redis list --query "[?name=='$REDIS_NAME']" -o tsv | grep -q "$REDIS_NAME"; then
    DUPLICATE_MSG+="${RED}❌ Redis Cache with name '$REDIS_NAME' already exists in your subscription.${NC}\n   Please edit your terraform.tfvars file to use a unique name and try again.\n"
  else
    echo -e "${GREEN}✅ Redis Cache name '$REDIS_NAME' is available${NC}"
  fi
fi

# Check if PostgreSQL Flexible Server name is unique in the subscription
if [ -n "$POSTGRES_NAME" ]; then
  if az postgres flexible-server list --query "[?name=='$POSTGRES_NAME']" -o tsv | grep -q "$POSTGRES_NAME"; then
    DUPLICATE_MSG+="${RED}❌ PostgreSQL Flexible Server with name '$POSTGRES_NAME' already exists in your subscription.${NC}\n   Please edit your terraform.tfvars file to use a unique name and try again.\n"
  else
    echo -e "${GREEN}✅ PostgreSQL Server name '$POSTGRES_NAME' is available${NC}"
  fi
fi

# Check if AKS cluster name is unique in the subscription
if [ -n "$AKS_NAME" ]; then
  if az aks list --query "[?name=='$AKS_NAME']" -o tsv | grep -q "$AKS_NAME"; then
    DUPLICATE_MSG+="${RED}❌ AKS cluster with name '$AKS_NAME' already exists in your subscription.${NC}\n   Please edit your terraform.tfvars file to use a unique name and try again.\n"
  else
    echo -e "${GREEN}✅ AKS cluster name '$AKS_NAME' is available${NC}"
  fi
fi

# Check if Key Vault name is globally unique (Key Vault names are global across Azure)
if [ -n "$KEY_VAULT_NAME" ]; then
  if az keyvault list --query "[?name=='$KEY_VAULT_NAME']" -o tsv | grep -q "$KEY_VAULT_NAME"; then
    DUPLICATE_MSG+="${RED}❌ Key Vault with name '$KEY_VAULT_NAME' already exists (Key Vault names are global).${NC}\n   Please edit your terraform.tfvars file to use a globally unique name and try again.\n"
  else
    echo -e "${GREEN}✅ Key Vault name '$KEY_VAULT_NAME' is available${NC}"
  fi
fi

# Check if Redis Private Endpoint name is unique in the subscription
if [ -n "$REDIS_PE_NAME" ]; then
  if az network private-endpoint list --query "[?name=='$REDIS_PE_NAME']" -o tsv | grep -q "$REDIS_PE_NAME"; then
    DUPLICATE_MSG+="${RED}❌ Private Endpoint with name '$REDIS_PE_NAME' already exists in your subscription.${NC}\n   Please edit your terraform.tfvars file to use a unique name and try again.\n"
  else
    echo -e "${GREEN}✅ Redis Private Endpoint name '$REDIS_PE_NAME' is available${NC}"
  fi
fi

# Check if PostgreSQL Private Endpoint name is unique in the subscription
if [ -n "$POSTGRES_PE_NAME" ]; then
  if az network private-endpoint list --query "[?name=='$POSTGRES_PE_NAME']" -o tsv | grep -q "$POSTGRES_PE_NAME"; then
    DUPLICATE_MSG+="${RED}❌ Private Endpoint with name '$POSTGRES_PE_NAME' already exists in your subscription.${NC}\n   Please edit your terraform.tfvars file to use a unique name and try again.\n"
  else
    echo -e "${GREEN}✅ PostgreSQL Private Endpoint name '$POSTGRES_PE_NAME' is available${NC}"
  fi
fi

# Check if Function App name is globally unique
if [ -n "$FUNCTION_APP_NAME" ]; then
  if az resource list --resource-type Microsoft.Web/sites --query "[?name=='$FUNCTION_APP_NAME']" -o tsv | grep -q "$FUNCTION_APP_NAME"; then
    DUPLICATE_MSG+="${RED}❌ Function App with name '$FUNCTION_APP_NAME' already exists (Function App names are global).${NC}\n   Please set 'function_app_name' in terraform.tfvars to a globally unique value and try again.\n"
  else
    echo -e "${GREEN}✅ Function App name '$FUNCTION_APP_NAME' is available${NC}"
  fi
fi

if [ -n "$DUPLICATE_MSG" ]; then
  echo -e "$DUPLICATE_MSG"
  exit 1
fi

echo -e "${GREEN}✅ All resource names are unique in the subscription.${NC}"

# ------------------------------------------------------------------------------
# Check Terraform configuration
# ------------------------------------------------------------------------------
echo -e "\n${CYAN}🔧 Checking Terraform configuration...${NC}"
if [ -f "terraform.tfstate" ]; then
  echo -e "${YELLOW}⚠️  Terraform state file found. This will be a modification of existing resources.${NC}"
elif [ -f ".terraform/terraform.tfstate" ]; then
  echo -e "${YELLOW}⚠️  Terraform state file found in .terraform directory. This will be a modification of existing resources.${NC}"
else
  echo -e "${GREEN}✅ No existing state found. This will be a fresh deployment.${NC}"
fi

# ------------------------------------------------------------------------------
# Final user confirmation before running Terraform
# ------------------------------------------------------------------------------
echo -e "\n${BLUE}=================================================="
echo -e "🎯 Precheck Summary:${NC}"
echo -e "${GREEN}✅ All prerequisites are met${NC}"
echo -e "${GREEN}✅ Azure authentication is valid${NC}"
echo -e "${GREEN}✅ Resource group exists${NC}"
echo -e "${GREEN}✅ Resource names are unique${NC}"
echo -e "${GREEN}✅ Configuration files are present${NC}"
echo -e "${BLUE}==================================================${NC}"

echo ""
read -p '⚠️  Do you want to continue with these values? (y/n): ' CONFIRM
if [[ "$CONFIRM" != "y" ]]; then
  echo -e "${YELLOW}Aborting.${NC}"
  exit 1
fi

echo -e "\n${GREEN}🚀 Ready to run Terraform!${NC}"
echo ""
echo -e "${CYAN}Next steps:${NC}"
echo "  1. Initialize Terraform:" 
echo "     make init"
echo "  2. Create an execution plan:" 
echo "     make plan"
echo "  3. Apply the plan to deploy resources:" 
echo "     make apply"
echo ""