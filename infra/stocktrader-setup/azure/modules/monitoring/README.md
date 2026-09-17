# Monitoring Module

This module deploys Azure Monitor and Log Analytics workspace for comprehensive monitoring, logging, and observability of the Stock Trader application infrastructure.

## Features

- **Log Analytics Workspace**: Centralized logging and monitoring
- **Azure Monitor**: Infrastructure and application monitoring
- **Container Insights**: Kubernetes cluster monitoring
- **Network Monitoring**: Network performance and connectivity
- **Security Monitoring**: Security event collection
- **Tags**: Consistent resource tagging

## Usage

```hcl
module "monitoring" {
  source = "./modules/monitoring"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  workspace_name      = "law-stocktrader-prod"
  
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
| [azurerm_log_analytics_workspace.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/log_analytics_workspace) | resource |
| [azurerm_log_analytics_solution.this](https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/log_analytics_solution) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| location | Azure region for resources | `string` | n/a | yes |
| resource_group_name | Name of the resource group | `string` | n/a | yes |
| workspace_name | Name of the Log Analytics workspace | `string` | n/a | yes |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| workspace_id | The ID of the Log Analytics workspace |
| workspace_key | The primary shared key for the Log Analytics workspace |
| workspace_customer_id | The workspace customer ID (GUID) |

## Monitoring Components

### Log Analytics Workspace
- **Centralized Logging**: Collect logs from all Azure resources
- **Custom Queries**: KQL-based log analysis
- **Data Retention**: Configurable retention policies
- **Workspace Insights**: Built-in monitoring solutions

### Container Insights
- **Kubernetes Monitoring**: Pod, node, and cluster metrics
- **Performance Metrics**: CPU, memory, network usage
- **Log Collection**: Container and application logs
- **Health Monitoring**: Pod and service health status

### Network Monitoring
- **Network Performance**: Latency and throughput metrics
- **Connectivity Monitoring**: End-to-end connectivity tests
- **Traffic Analysis**: Network flow and packet analysis
- **Security Monitoring**: Network security events

## Integration with Other Modules

### AKS Module
Enable Container Insights for Kubernetes monitoring:
```hcl
module "aks" {
  # ... other configuration ...
  log_analytics_workspace_id = module.monitoring.workspace_id
}
```

### Application Monitoring
Configure application logging and metrics:
```hcl
# Application Insights (if needed)
resource "azurerm_application_insights" "app" {
  name                = "appi-stocktrader-prod"
  location            = var.location
  resource_group_name = var.resource_group_name
  workspace_id        = module.monitoring.workspace_id
  application_type    = "web"
}
```

## Examples

### Development Environment
```hcl
module "monitoring_dev" {
  source = "./modules/monitoring"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-dev"
  workspace_name      = "law-stocktrader-dev"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "monitoring_prod" {
  source = "./modules/monitoring"
  
  location            = "eastus"
  resource_group_name = "rg-stocktrader-prod"
  workspace_name      = "law-stocktrader-prod"
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Monitoring Queries

### Kubernetes Cluster Health
```kusto
// Pod status overview
KubePodInventory
| where TimeGenerated > ago(1h)
| summarize PodCount = count() by PodStatus
| render piechart

// Node resource usage
Perf
| where ObjectName == "K8SNode"
| where CounterName in ("cpuUsageNanoCores", "memoryWorkingSetBytes")
| summarize avg(CounterValue) by CounterName, bin(TimeGenerated, 5m)
| render timechart
```

### Application Performance
```kusto
// Application logs
ContainerLog
| where ContainerName contains "stocktrader"
| where TimeGenerated > ago(1h)
| summarize LogCount = count() by LogLevel
| render piechart

// Response times
requests
| where cloud_RoleName == "StockTrader"
| summarize avg(duration) by bin(timestamp, 5m)
| render timechart
```

### Network Monitoring
```kusto
// Network connectivity
NetworkMonitoring
| where TimeGenerated > ago(1h)
| summarize avg(LossRate) by Source, Destination
| render table

// Security events
SecurityEvent
| where TimeGenerated > ago(24h)
| where EventID in (4624, 4625, 4634, 4647, 4648)
| summarize EventCount = count() by EventID
| render table
```

## Alerting

### Recommended Alerts

#### Infrastructure Alerts
```kusto
// High CPU usage
Perf
| where ObjectName == "K8SNode"
| where CounterName == "cpuUsageNanoCores"
| where CounterValue > 80
| summarize count() by Computer

// High memory usage
Perf
| where ObjectName == "K8SNode"
| where CounterName == "memoryWorkingSetBytes"
| where CounterValue > 85
| summarize count() by Computer
```

#### Application Alerts
```kusto
// High error rate
requests
| where cloud_RoleName == "StockTrader"
| where success == false
| summarize ErrorRate = count() * 100.0 / count() by bin(timestamp, 5m)
| where ErrorRate > 5

// Slow response times
requests
| where cloud_RoleName == "StockTrader"
| where duration > 5000
| summarize count() by bin(timestamp, 5m)
```

## Dashboard Examples

### Infrastructure Dashboard
- **Cluster Overview**: Node count, pod count, resource usage
- **Network Performance**: Latency, throughput, connectivity
- **Storage Metrics**: Disk usage, I/O performance
- **Security Events**: Authentication, authorization events

### Application Dashboard
- **Response Times**: Average, 95th percentile response times
- **Error Rates**: HTTP error rates by endpoint
- **Throughput**: Requests per second
- **Dependencies**: External service dependencies

## Troubleshooting

### Common Issues

#### Data Collection Issues
1. **Check Agent Status**: Verify monitoring agents are running
   ```bash
   kubectl get pods -n kube-system | grep omsagent
   ```

2. **Check Workspace Connection**: Verify workspace configuration
   ```bash
   kubectl get configmap -n kube-system omsagent-rs -o yaml
   ```

3. **Check Log Collection**: Verify log collection is enabled
   ```bash
   kubectl logs -n kube-system deployment/omsagent-rs
   ```

#### Performance Issues
1. **Check Data Volume**: Monitor data ingestion rates
2. **Check Query Performance**: Optimize KQL queries
3. **Check Retention Policies**: Adjust data retention as needed

### Debugging Commands

```bash
# Check monitoring agent status
kubectl get pods -n kube-system | grep omsagent
kubectl logs -n kube-system deployment/omsagent-rs

# Check workspace configuration
az monitor log-analytics workspace show --workspace-name <workspace-name>

# Check data collection
az monitor log-analytics workspace get-usage --workspace-name <workspace-name>
```

## Security Considerations

### Data Protection
- **Encryption**: Data encrypted at rest and in transit
- **Access Control**: RBAC for workspace access
- **Audit Logging**: Monitor workspace access
- **Data Residency**: Configure data location

### Compliance
- **GDPR**: Data retention and privacy controls
- **SOC 2**: Security and availability controls
- **ISO 27001**: Information security management
- **HIPAA**: Healthcare data protection (if applicable)

## Cost Optimization

### Data Retention
- **Hot Data**: Recent data (7-30 days) for active monitoring
- **Warm Data**: Historical data (30-90 days) for analysis
- **Cold Data**: Archived data (90+ days) for compliance

### Query Optimization
- **Time Range**: Limit queries to necessary time periods
- **Filtering**: Use specific filters to reduce data volume
- **Summarization**: Use summarize operators for aggregation
- **Scheduled Queries**: Use scheduled queries for regular reports

## Notes

- Log Analytics workspace name must be globally unique
- Container Insights requires monitoring agent deployment
- Data retention can be configured up to 2 years
- Workspace supports up to 100 GB/day data ingestion
- Custom solutions can be added for specific monitoring needs
- Integration with Azure Sentinel for security monitoring
- Support for custom dashboards and workbooks
- Real-time monitoring with near real-time data ingestion
