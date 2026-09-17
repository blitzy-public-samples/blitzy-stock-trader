# Kubernetes Bootstrap Module

This module provides essential Kubernetes resources and configurations for bootstrapping the Stock Trader application, including namespaces, service accounts, and initial configurations.

## Features

- **Namespace Management**: Application-specific namespaces
- **Service Accounts**: Kubernetes service accounts for applications
- **RBAC Configuration**: Role-based access control setup
- **Initial Configurations**: Default settings and policies
- **Security**: Network policies and security contexts
- **Monitoring**: Basic monitoring configurations
 - **Istio Integration (Toggle)**: Labels namespace for sidecar injection and enables external ingress gateway when `enable_istio = true` in root

## Usage

```hcl
module "k8s_bootstrap" {
  source = "./modules/k8s_bootstrap"
  
  # Controlled from root via variables: subscription/resource group/AKS name
  # Istio integration controlled by root variable `enable_istio`
  namespace = "stocktrader"
  
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
| kubernetes | ~> 2.27 |

## Providers

| Name | Version |
|------|---------|
| kubernetes | ~> 2.27 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [kubernetes_namespace.this](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/namespace) | resource |
| [kubernetes_service_account.app](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/service_account) | resource |
| [kubernetes_role.app](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/role) | resource |
| [kubernetes_role_binding.app](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/role_binding) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| namespace | Kubernetes namespace for the application | `string` | `"stocktrader"` | no |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| namespace | The Kubernetes namespace |
| service_account_name | The name of the application service account |

## Bootstrap Components

### Namespace
- **Application Isolation**: Separate namespace for application resources
- **Resource Quotas**: Optional resource limits and requests
- **Network Policies**: Default network isolation
- **Labels**: Consistent labeling for resource management

### Service Account
- **Application Identity**: Service account for application pods
- **RBAC Integration**: Role-based access control
- **Workload Identity**: Azure Workload Identity support
- **Security Context**: Secure default settings

### RBAC Configuration
- **Application Roles**: Custom roles for application needs
- **Role Bindings**: Service account to role mappings
- **Least Privilege**: Minimal required permissions
- **Audit Trail**: Access logging and monitoring

## Integration with Other Modules

### AKS Module
Provides the Kubernetes cluster context:
```hcl
module "k8s_bootstrap" {
  # ... other configuration ...
  namespace = "stocktrader"
}
```

### External Secrets Module
Uses service account for secret access:
```hcl
module "external_secrets" {
  # ... other configuration ...
  service_account_name = module.k8s_bootstrap.service_account_name
}
```

### Application Deployments
Applications use the bootstrap resources:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: stocktrader-app
  namespace: stocktrader
spec:
  template:
    spec:
      serviceAccountName: stocktrader-app
      containers:
        - name: app
          image: stocktrader:latest
```

## Examples

### Development Environment
```hcl
module "k8s_bootstrap_dev" {
  source = "./modules/k8s_bootstrap"
  
  namespace = "stocktrader-dev"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "k8s_bootstrap_prod" {
  source = "./modules/k8s_bootstrap"
  
  namespace = "stocktrader"
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## RBAC Examples

### Application Role
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: stocktrader-app-role
  namespace: stocktrader
rules:
  - apiGroups: [""]
    resources: ["pods", "services", "configmaps", "secrets"]
    verbs: ["get", "list", "watch"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets"]
    verbs: ["get", "list", "watch"]
```

### Role Binding
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: stocktrader-app-rolebinding
  namespace: stocktrader
subjects:
  - kind: ServiceAccount
    name: stocktrader-app
    namespace: stocktrader
roleRef:
  kind: Role
  name: stocktrader-app-role
  apiGroup: rbac.authorization.k8s.io
```

## Network Policies

### Default Network Policy
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny
  namespace: stocktrader
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
```

### Application Network Policy
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: stocktrader-app-policy
  namespace: stocktrader
spec:
  podSelector:
    matchLabels:
      app: stocktrader
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: istio-system
      ports:
        - protocol: TCP
          port: 8080
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: stocktrader
      ports:
        - protocol: TCP
          port: 5432  # PostgreSQL
        - protocol: TCP
          port: 6380  # Redis
```

## Resource Quotas

### Namespace Quota
```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: stocktrader-quota
  namespace: stocktrader
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi
    pods: "10"
    services: "5"
```

## Troubleshooting

### Common Issues

#### Namespace Creation
1. **Check Namespace Status**: Verify namespace exists
   ```bash
   kubectl get namespace stocktrader
   kubectl describe namespace stocktrader
   ```

2. **Check RBAC**: Verify service account and roles
   ```bash
   kubectl get serviceaccount -n stocktrader
   kubectl get role,rolebinding -n stocktrader
   ```

#### Permission Issues
1. **Check Service Account**: Verify service account configuration
   ```bash
   kubectl get serviceaccount stocktrader-app -n stocktrader -o yaml
   ```

2. **Check Role Bindings**: Verify role assignments
   ```bash
   kubectl get rolebinding -n stocktrader -o yaml
   ```

### Debugging Commands

```bash
# Check namespace resources
kubectl get all -n stocktrader

# Check RBAC resources
kubectl get serviceaccount,role,rolebinding -n stocktrader

# Check network policies
kubectl get networkpolicy -n stocktrader

# Check resource quotas
kubectl get resourcequota -n stocktrader
```

## Security Best Practices

### Namespace Security
- **Resource Isolation**: Separate namespaces for different applications
- **Network Policies**: Implement default deny policies
- **Resource Quotas**: Set limits to prevent resource exhaustion
- **Labels**: Use consistent labeling for security policies

### RBAC Security
- **Least Privilege**: Grant minimal required permissions
- **Role Separation**: Separate roles for different functions
- **Regular Audits**: Review permissions regularly
- **Service Accounts**: Use dedicated service accounts per application

### Network Security
- **Default Deny**: Implement default deny network policies
- **Explicit Allow**: Only allow necessary traffic
- **Namespace Isolation**: Restrict cross-namespace communication
- **Pod Security**: Use security contexts and policies

## Monitoring and Alerts

### Key Metrics
- **Namespace Usage**: Resource consumption per namespace
- **RBAC Activity**: Permission usage and changes
- **Network Traffic**: Pod-to-pod communication patterns
- **Security Events**: Policy violations and security incidents

### Recommended Alerts
- Namespace resource usage >80%
- RBAC permission changes
- Network policy violations
- Service account token usage

## Notes

- Bootstrap resources should be created before application deployment
- When Istio is disabled (root `enable_istio = false`), this module skips labeling for sidecar injection and enabling the external ingress gateway.
- Network policies provide default security posture
- RBAC configurations follow least-privilege principles
- Resource quotas prevent resource exhaustion
- Service accounts support Workload Identity integration
- Namespace labels enable policy enforcement
- Regular audits of bootstrap configurations recommended
- Bootstrap resources can be extended for specific application needs
