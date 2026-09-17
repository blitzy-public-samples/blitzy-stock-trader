# CouchDB Module

This module deploys CouchDB as a NoSQL database within the AKS cluster for the Stock Trader application, providing document-based data storage and synchronization capabilities.

## Features

- **CouchDB Deployment**: NoSQL document database
- **Kubernetes Native**: Deployed within AKS cluster
- **Persistent Storage**: PVC-based data persistence
- **High Availability**: Multi-replica deployment
- **Security**: Network policies and RBAC
- **Monitoring**: Health checks and metrics

## Usage

```hcl
module "couchdb" {
  source = "./modules/couchdb"
  
  namespace = "stocktrader"
  replicas  = 3
  
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
| [kubernetes_persistent_volume_claim.couchdb](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/persistent_volume_claim) | resource |
| [kubernetes_deployment.couchdb](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/deployment) | resource |
| [kubernetes_service.couchdb](https://registry.terraform.io/providers/hashicorp/kubernetes/latest/docs/resources/service) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| namespace | Kubernetes namespace for CouchDB | `string` | `"couchdb"` | no |
| replicas | Number of CouchDB replicas | `number` | `1` | no |
| storage_size | Storage size for CouchDB PVC | `string` | `"10Gi"` | no |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| namespace | The Kubernetes namespace for CouchDB |
| service_name | The name of the CouchDB service |
| service_port | The port of the CouchDB service |

## CouchDB Features

### Document Database
- **JSON Documents**: Native JSON document storage
- **ACID Compliance**: Full ACID transaction support
- **RESTful API**: HTTP-based API for data access
- **MapReduce**: Built-in MapReduce for data processing

### Replication
- **Multi-Master**: Bidirectional replication
- **Conflict Resolution**: Automatic conflict detection
- **Offline Support**: Offline-first architecture
- **Sync**: Real-time data synchronization

### Security
- **Authentication**: Basic and cookie-based auth
- **Authorization**: Role-based access control
- **SSL/TLS**: Encrypted communication
- **Network Policies**: Pod-to-pod communication control

## Integration with Other Modules

### AKS Module
Deployed within the AKS cluster:
```hcl
module "couchdb" {
  # ... other configuration ...
  namespace = "stocktrader"
}
```

### Network Policies
Can be protected with network policies:
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: couchdb-network-policy
  namespace: stocktrader
spec:
  podSelector:
    matchLabels:
      app: couchdb
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: stocktrader
      ports:
        - protocol: TCP
          port: 5984
```

## Examples

### Development Environment
```hcl
module "couchdb_dev" {
  source = "./modules/couchdb"
  
  namespace    = "stocktrader-dev"
  replicas     = 1
  storage_size = "5Gi"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "couchdb_prod" {
  source = "./modules/couchdb"
  
  namespace    = "stocktrader"
  replicas     = 3
  storage_size = "50Gi"
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Connection Examples

### From Application Pod
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
          image: stocktrader:latest
          env:
            - name: COUCHDB_URL
              value: "http://couchdb.stocktrader.svc.cluster.local:5984"
            - name: COUCHDB_USER
              valueFrom:
                secretKeyRef:
                  name: couchdb-credentials
                  key: username
            - name: COUCHDB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: couchdb-credentials
                  key: password
```

### From Application Code
```python
import couchdb

# Connect to CouchDB
server = couchdb.Server('http://couchdb.stocktrader.svc.cluster.local:5984/')
server.resource.credentials = ('admin', 'password')

# Create or get database
db = server['stocktrader']

# Create document
doc = {
    'type': 'trade',
    'symbol': 'AAPL',
    'quantity': 100,
    'price': 150.50,
    'timestamp': '2024-01-01T10:00:00Z'
}
db.save(doc)
```

## Database Operations

### Create Database
```bash
# Create database
curl -X PUT http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader

# Create user
curl -X PUT http://couchdb.stocktrader.svc.cluster.local:5984/_users/org.couchdb.user:trader \
  -H "Content-Type: application/json" \
  -d '{"name": "trader", "password": "password", "roles": ["trader"], "type": "user"}'
```

### Document Operations
```bash
# Create document
curl -X POST http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader \
  -H "Content-Type: application/json" \
  -d '{"type": "trade", "symbol": "AAPL", "quantity": 100}'

# Get document
curl -X GET http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader/<doc-id>

# Update document
curl -X PUT http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader/<doc-id> \
  -H "Content-Type: application/json" \
  -d '{"_id": "<doc-id>", "_rev": "<rev>", "type": "trade", "symbol": "AAPL", "quantity": 200}'
```

## Troubleshooting

### Common Issues

#### Pod Not Starting
1. **Check Pod Status**: Verify pod is running
   ```bash
   kubectl get pods -n stocktrader -l app=couchdb
   kubectl describe pod <pod-name> -n stocktrader
   ```

2. **Check PVC**: Verify storage is available
   ```bash
   kubectl get pvc -n stocktrader
   kubectl describe pvc couchdb-pvc -n stocktrader
   ```

3. **Check Logs**: Review pod logs
   ```bash
   kubectl logs <pod-name> -n stocktrader
   ```

#### Connection Issues
1. **Check Service**: Verify service is running
   ```bash
   kubectl get svc -n stocktrader
   kubectl describe svc couchdb -n stocktrader
   ```

2. **Check Network**: Test connectivity
   ```bash
   kubectl exec -it <pod-name> -n stocktrader -- curl couchdb:5984
   ```

### Debugging Commands

```bash
# Check CouchDB status
kubectl get pods,svc,pvc -n stocktrader

# Check CouchDB logs
kubectl logs -l app=couchdb -n stocktrader

# Test CouchDB connectivity
kubectl exec -it <pod-name> -n stocktrader -- curl couchdb:5984

# Check CouchDB info
kubectl exec -it <pod-name> -n stocktrader -- curl couchdb:5984/_utils
```

## Security Considerations

### Network Security
- **Network Policies**: Restrict pod-to-pod communication
- **Service Mesh**: Use Istio for traffic management
- **TLS**: Enable SSL/TLS for encrypted communication
- **Firewall Rules**: Configure ingress/egress rules

### Authentication
- **Admin User**: Secure admin credentials
- **User Management**: Implement proper user roles
- **API Keys**: Use API keys for application access
- **Session Management**: Secure session handling

### Data Protection
- **Encryption**: Encrypt data at rest and in transit
- **Backup**: Regular database backups
- **Access Control**: Implement proper access controls
- **Audit Logging**: Monitor database access

## Monitoring and Alerts

### Key Metrics
- **Database Size**: Document count and storage usage
- **Request Rate**: HTTP requests per second
- **Response Time**: Average response times
- **Error Rate**: HTTP error rates

### Recommended Alerts
- CouchDB pod not running
- High response times (>1 second)
- High error rates (>5%)
- Storage usage >80%

## Backup and Recovery

### Backup Strategy
```bash
# Create backup
curl -X GET http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader/_all_docs > backup.json

# Restore backup
curl -X POST http://couchdb.stocktrader.svc.cluster.local:5984/stocktrader/_bulk_docs \
  -H "Content-Type: application/json" \
  -d @backup.json
```

### Disaster Recovery
- **Regular Backups**: Automated backup scheduling
- **Cross-Region**: Backup to secondary region
- **Point-in-Time**: Document-level recovery
- **Testing**: Regular recovery testing

## Notes

- CouchDB requires persistent storage for data persistence
- Multi-replica deployment requires cluster coordination
- CouchDB supports offline-first applications
- Built-in replication for high availability
- RESTful API for easy integration
- MapReduce for complex data processing
- Conflict resolution for distributed scenarios
- Support for attachments and binary data
