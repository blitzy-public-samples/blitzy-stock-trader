SHELL := /bin/bash

.PHONY: init fmt validate plan apply destroy precheck postcheck lint app-url func-url

init:
	terraform init

fmt:
	terraform fmt -recursive

validate:
	terraform init -backend=false
	terraform validate

plan:
	terraform plan -out=plan.tfplan

apply:
	terraform apply plan.tfplan

destroy:
	terraform plan -destroy -out=destroy.tfplan
	terraform apply destroy.tfplan

precheck:
	bash precheck.sh

postcheck:
	bash postcheck.sh

lint:
	terraform fmt -recursive -check
	terraform init -backend=false >/dev/null 2>&1 || true
	terraform validate
	command -v tflint >/dev/null 2>&1 || (curl -s https://raw.githubusercontent.com/terraform-linters/tflint/master/install_linux.sh | bash)
	tflint --recursive
	command -v shellcheck >/dev/null 2>&1 && find . -type f -name "*.sh" -print0 | xargs -0 -I{} bash -lc 'shellcheck {}' || true

# Print application URL (Istio external or LoadBalancer) with /trader suffix
app-url:
	@ENABLE_ISTIO=$$(grep '^enable_istio' terraform.tfvars 2>/dev/null | awk -F= '{print $$2}' | cut -d'#' -f1 | tr -d ' "' || echo "true"); \
	STOCK_TRADER_NAMESPACE=$$(grep '^stock_trader_namespace' terraform.tfvars 2>/dev/null | awk -F= '{print $$2}' | cut -d'#' -f1 | tr -d ' "' || echo "stock-trader"); \
	if [ "$$ENABLE_ISTIO" = "false" ]; then \
	  echo "Istio is disabled. Checking LoadBalancer service..."; \
	  EXTERNAL_IP=$$(kubectl -n $$STOCK_TRADER_NAMESPACE get svc gitops-stocktrader-trader-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true); \
	  if [ -n "$$EXTERNAL_IP" ] && [ "$$EXTERNAL_IP" != "null" ]; then \
	    echo "https://$$EXTERNAL_IP:9443/trader"; \
	  else \
	    echo "LoadBalancer external IP not available yet"; \
	  fi; \
	else \
	  URL=$$(terraform output -raw istio_ingress_external_url_https 2>/dev/null || true); \
	  if [ -n "$$URL" ] && [ "$$URL" != "null" ]; then \
	    echo "$$URL/trader"; \
	  else \
	    IP=$$(kubectl -n aks-istio-ingress get svc aks-istio-ingressgateway-external -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true); \
	    if [ -n "$$IP" ]; then echo "https://$$IP/trader"; else echo "Application URL not available yet"; fi; \
	  fi; \
	fi

# Print Function App invoke URL with default key (from Terraform output if present)
func-url:
	@RG=$$(grep '^resource_group_name' terraform.tfvars | awk -F= '{print $$2}' | cut -d'#' -f1 | tr -d ' "'); \
	APP=$$(grep '^function_app_name' terraform.tfvars | awk -F= '{print $$2}' | cut -d'#' -f1 | tr -d ' "'); \
	if [ -z "$$RG" ] || [ -z "$$APP" ]; then echo "Missing resource_group_name or function_app_name in terraform.tfvars"; exit 1; fi; \
	KEY=$$(az functionapp function keys list --name "$$APP" --resource-group "$$RG" --function-name stock_quote -o tsv --query default 2>/dev/null | tr -d '\n' || true); \
	if [ -z "$$KEY" ]; then echo "Could not fetch function key. Ensure the app exists and you are logged into Azure (az login)."; exit 1; fi; \
	echo "https://$$APP.azurewebsites.net/api/stock_quote?code=$$KEY"

