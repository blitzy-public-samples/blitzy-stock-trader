# User-Assigned Identity Module

This module creates Azure User-Assigned Managed Identities for secure authentication and authorization across the Stock Trader application infrastructure.

## Features

- **User-Assigned Identities**: Azure AD-based managed identities
- **Workload Identity**: Kubernetes service account integration
- **Federated Credentials**: OIDC-based authentication
- **RBAC Integration**: Role-based access control
- **Security**: No secrets or credentials to manage
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "uai" {
  source = "./modules/uai"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  identity_name       = "uai-stocktrader-prod"
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

## Providers

| Name | Version |
|------|---------|
| azurerm | ~> 4.0 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [azurerm_user_assigned_identity.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/user_assigned_identity) | resource |
| [azurerm_federated_identity_credential.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/federated_identity_credential) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| identity_name | Name of the user-assigned identity | `string` | n/a | yes |
| aks_cluster_id | ID of the AKS cluster | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| identity_id | The ID of the user-assigned identity |
| identity_principal_id | The principal ID of the user-assigned identity |
| identity_client_id | The client ID of the user-assigned identity |
| identity_tenant_id | The tenant ID of the user-assigned identity |

## Identity Types

### User-Assigned Managed Identity
- **Azure AD Integration**: Fully managed by Azure AD
- **No Credentials**: No secrets or certificates to manage
- **Automatic Rotation**: Credentials automatically rotated
- **RBAC Support**: Full Azure RBAC integration

### Workload Identity
- **Kubernetes Integration**: Service account-based authentication
- **OIDC Federation**: OpenID Connect token exchange
- **Pod Identity**: Secure pod-to-Azure authentication
- **No Sidecar**: No additional containers required

## Integration with Other Modules

### AKS Module
Provides cluster information for Workload Identity:
```hcl
module "uai" {
  # ... other configuration ...
  aks_cluster_id = module.aks.cluster_id
}
```

### External Secrets Module
Uses identity for Key Vault access:
```hcl
module "external_secrets" {
  # ... other configuration ...
  external_secrets_identity_id = module.uai.identity_id
}
```

### Application Pods
Service accounts can use the identity:
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: stocktrader-app
  namespace: stocktrader
  annotations:
    azure.workload.identity/client-id: <identity-client-id>
    azure.workload.identity/tenant-id: <identity-tenant-id>
```

## Examples

### Development Environment
```hcl
module "uai_dev" {
  source = "./modules/uai"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-dev"
  identity_name       = "uai-stocktrader-dev"
  aks_cluster_id      = module.aks.cluster_id
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "uai_prod" {
  source = "./modules/uai"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  identity_name       = "uai-stocktrader-prod"
  aks_cluster_id      = module.aks.cluster_id
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## RBAC Configuration

### Key Vault Access
```hcl
# Grant Key Vault access to the identity
resource "azurerm_role_assignment" "key_vault_secrets_user" {
  scope                = module.key_vault.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = module.uai.identity_principal_id
}
```

### Storage Account Access
```hcl
# Grant Storage access to the identity
resource "azurerm_role_assignment" "storage_blob_data_contributor" {
  scope                = module.storage.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = module.uai.identity_principal_id
}
```

### Container Registry Access
```hcl
# Grant ACR access to the identity
resource "azurerm_role_assignment" "acr_pull" {
  scope                = module.acr.id
  role_definition_name = "AcrPull"
  principal_id         = module.uai.identity_principal_id
}
```

## Usage in Applications

### Service Account Configuration
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: stocktrader-app
  namespace: stocktrader
  annotations:
    azure.workload.identity/client-id: "12345678-1234-1234-1234-123456789012"
    azure.workload.identity/tenant-id: "87654321-4321-4321-4321-210987654321"
```

### Pod Configuration
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stocktrader-app
spec:
  template:
    spec:
      serviceAccountName: stocktrader-app
      containers:
        - name: app
          image: stocktrader:latest
          env:
            - name: AZURE_CLIENT_ID
              value: "12345678-1234-1234-1234-123456789012"
```

### Application Code
```python
from azure.identity import DefaultAzureCredential
from azure.keyvault.secrets import SecretClient

# Use DefaultAzureCredential for Workload Identity
credential = DefaultAzureCredential()
client = SecretClient(vault_url="https://kv-stocktrader.vault.azure.net/", credential=credential)

# Access secrets
secret = client.get_secret("database-password")
```

## Troubleshooting

### Common Issues

#### Authentication Failures
1. **Check Identity Status**: Verify identity exists and is enabled
   ```bash
   az identity show --name <identity-name> --resource-group <rg>
   ```

2. **Check Federated Credential**: Verify OIDC federation
   ```bash
   az identity federated-credential list --id <identity-id>
   ```

3. **Check Service Account**: Verify service account configuration
   ```bash
   kubectl get serviceaccount <sa-name> -n <namespace> -o yaml
   ```

#### Permission Issues
1. **Check RBAC**: Verify role assignments
   ```bash
   az role assignment list --assignee <identity-principal-id>
   ```

2. **Check Resource Access**: Verify resource permissions
   ```bash
   az role assignment list --assignee <identity-principal-id> --scope <resource-id>
   ```

### Debugging Commands

```bash
# Check identity status
az identity show --name <identity-name> --resource-group <rg>

# Check federated credentials
az identity federated-credential list --id <identity-id>

# Check role assignments
az role assignment list --assignee <identity-principal-id>

# Test token acquisition
kubectl exec -it <pod> -n <namespace> -- curl -H "Authorization: Bearer $(cat /var/run/secrets/azure/tokens/azure-identity-token)" "https://management.azure.com/tenants?api-version=2020-01-01"
```

## Security Best Practices

### Identity Management
- Use separate identities for different applications
- Implement least-privilege access principles
- Regularly audit identity permissions
- Monitor identity usage and access patterns

### Workload Identity
- Use Workload Identity instead of pod-managed identities
- Configure proper service account annotations
- Implement proper namespace isolation
- Monitor pod identity usage

### Access Control
- Grant minimal required permissions
- Use custom roles for specific access patterns
- Implement just-in-time access when possible
- Regular access reviews and cleanup

## Monitoring and Alerts

### Key Metrics
- **Authentication Success/Failure**: Identity authentication rates
- **Token Acquisition**: Token request success rates
- **Permission Denials**: Access denied events
- **Identity Usage**: Resource access patterns

### Recommended Alerts
- Authentication failures for managed identities
- High number of permission denied events
- Unusual identity usage patterns
- Identity credential rotation events

## Cost Considerations

### Identity Pricing
- **User-Assigned Identity**: ~$0.01/hour per identity
- **Federated Credentials**: No additional cost
- **RBAC Operations**: No additional cost

### Optimization
- Use single identity for multiple related services
- Implement proper identity lifecycle management
- Monitor and remove unused identities
- Use system-assigned identities when possible

## Notes

- User-assigned identities are billed per hour
- Workload Identity requires AKS 1.24+ with OIDC enabled
- Federated credentials support multiple audiences
- Identity credentials are automatically rotated
- Support for custom token lifetimes
- Integration with Azure AD Conditional Access
- Audit logging for all identity operations
- Support for cross-tenant identity scenarios
