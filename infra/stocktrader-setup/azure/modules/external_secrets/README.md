# External Secrets Module

This module deploys External Secrets Operator (ESO) to synchronize secrets from Azure Key Vault to Kubernetes, enabling secure secret management for the Stock Trader application.

## Features

- **External Secrets Operator**: Kubernetes-native secret management
- **Azure Key Vault Integration**: Secure secret synchronization
- **Workload Identity**: Azure AD-based authentication
- **Automatic Synchronization**: Real-time secret updates
- **Security**: No secrets stored in Terraform state
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "external_secrets" {
  source = "./modules/external_secrets"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  key_vault_id        = module.key_vault.id
  aks_cluster_id      = module.aks.cluster_id
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Requirements

| Name | Version |
|------|---------|
| terraform | >= 1.0 |
| azurerm | ~> 4.0 |
| kubernetes | ~> 2.27 |

## Providers

| Name | Version |
|------|---------|
| azurerm | ~> 4.0 |
| kubernetes | ~> 2.27 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [azurerm_user_assigned_identity.external_secrets](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/user_assigned_identity) | resource |
| [azurerm_federated_identity_credential.external_secrets](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/federated_identity_credential) | resource |
| [azurerm_role_assignment.external_secrets_key_vault](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/role_assignment) | resource |
| [kubernetes_namespace.external_secrets](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/namespace) | resource |
| [kubernetes_service_account.external_secrets](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/service_account) | resource |
| [kubernetes_deployment.external_secrets](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/deployment) | resource |
| [kubernetes_cluster_secret_store.external_secrets](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/cluster_secret_store) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| key_vault_id | ID of the Azure Key Vault | `string` | n/a | yes |
| aks_cluster_id | ID of the AKS cluster | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| external_secrets_identity_id | The ID of the External Secrets user-assigned identity |
| external_secrets_namespace | The Kubernetes namespace for External Secrets |

## Architecture

```
Azure Key Vault
    ↓ (RBAC)
User-Assigned Identity
    ↓ (Workload Identity)
Kubernetes Service Account
    ↓ (Pod Identity)
External Secrets Operator
    ↓ (Secret Sync)
Kubernetes Secrets
    ↓ (Application Access)
Application Pods
```

## Integration with Other Modules

### Key Vault Module
Requires Key Vault from the `key_vault` module:
```hcl
module "external_secrets" {
  # ... other configuration ...
  key_vault_id = module.key_vault.id
}
```

### AKS Module
Requires AKS cluster from the `aks` module:
```hcl
module "external_secrets" {
  # ... other configuration ...
  aks_cluster_id = module.aks.cluster_id
}
```

### Workload Identity
Uses Azure Workload Identity for secure authentication:
```hcl
module "uai" {
  # ... other configuration ...
  external_secrets_identity_id = module.external_secrets.external_secrets_identity_id
}
```

## Examples

### Development Environment
```hcl
module "external_secrets_dev" {
  source = "./modules/external_secrets"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-dev"
  key_vault_id        = module.key_vault.id
  aks_cluster_id      = module.aks.cluster_id
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "external_secrets_prod" {
  source = "./modules/external_secrets"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  key_vault_id        = module.key_vault.id
  aks_cluster_id      = module.aks.cluster_id
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Secret Management

### External Secret Example
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: database-credentials
  namespace: stocktrader
spec:
  secretStoreRef:
    name: azure-kv
    kind: ClusterSecretStore
  target:
    name: database-credentials
  data:
    - secretKey: DB_HOST
      remoteRef:
        key: database-host
    - secretKey: DB_USER
      remoteRef:
        key: database-id
    - secretKey: DB_PASSWORD
      remoteRef:
        key: database-password
```

### Cluster Secret Store
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: azure-kv
spec:
  provider:
    azurekv:
      authType: WorkloadIdentity
      vaultUrl: "https://kv-stocktrader-prod.vault.azure.net/"
      tenantId: "your-tenant-id"
```

## Usage in Applications

### Environment Variables
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stocktrader-app
spec:
  template:
    spec:
      containers:
        - name: app
          env:
            - name: DB_HOST
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: DB_HOST
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: DB_USER
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: DB_PASSWORD
```

### Volume Mounts
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stocktrader-app
spec:
  template:
    spec:
      volumes:
        - name: secrets
          secret:
            secretName: database-credentials
      containers:
        - name: app
          volumeMounts:
            - name: secrets
              mountPath: /etc/secrets
              readOnly: true
```

## Troubleshooting

### Common Issues

#### Authentication Failures
1. **Check Workload Identity**: Verify federated identity credential
   ```bash
   az identity federated-credential list --id <identity-id>
   ```

2. **Check RBAC**: Verify Key Vault permissions
   ```bash
   az role assignment list --assignee <identity-id> --scope <key-vault-id>
   ```

3. **Check Service Account**: Verify service account exists
   ```bash
   kubectl get serviceaccount -n external-secrets
   ```

#### Secret Synchronization Issues
1. **Check External Secrets Status**: Verify operator is running
   ```bash
   kubectl get pods -n external-secrets
   kubectl logs -n external-secrets deployment/external-secrets
   ```

2. **Check External Secret Status**: Verify secret sync
   ```bash
   kubectl get externalsecret -A
   kubectl describe externalsecret <name> -n <namespace>
   ```

### Debugging Commands

```bash
# Check External Secrets operator status
kubectl get pods -n external-secrets
kubectl logs -n external-secrets deployment/external-secrets

# Check External Secret resources
kubectl get externalsecret -A
kubectl get secretstore -A
kubectl get clustersecretstore

# Check Key Vault access
kubectl exec -it <pod> -n <namespace> -- curl -H "Authorization: Bearer $(cat /var/run/secrets/azure/tokens/azure-identity-token)" "https://<key-vault>.vault.azure.net/secrets/<secret-name>?api-version=7.3"
```

## Security Best Practices

### Authentication
- Use Workload Identity for secure authentication
- Implement least-privilege access to Key Vault
- Rotate service account tokens regularly
- Monitor authentication failures

### Secret Management
- Store sensitive data only in Key Vault
- Use External Secrets for Kubernetes secret management
- Implement secret rotation policies
- Audit secret access regularly

### Network Security
- Use private endpoints for Key Vault access
- Implement network policies for pod communication
- Monitor network traffic for anomalies

## Monitoring and Alerts

### Key Metrics
- **Secret Sync Status**: Success/failure rates
- **Authentication Failures**: Workload Identity issues
- **Key Vault Access**: API call success rates
- **Pod Health**: External Secrets operator status

### Recommended Alerts
- External Secrets operator pod not running
- Secret synchronization failures
- Authentication failures to Key Vault
- High number of secret access attempts

## Notes

- External Secrets Operator requires Kubernetes 1.19+
- Workload Identity requires AKS 1.24+ with OIDC enabled
- Key Vault must have RBAC enabled for Workload Identity
- External Secrets supports multiple secret stores
- Consider using External Secrets for all Kubernetes secrets
- Monitor External Secrets operator logs for troubleshooting
- Implement secret rotation for enhanced security
- Use External Secrets with GitOps workflows for declarative secret management
