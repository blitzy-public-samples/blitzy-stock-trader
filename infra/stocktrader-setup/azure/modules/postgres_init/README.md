# PostgreSQL Initialization Module

This module handles the initialization and setup of PostgreSQL databases, including schema creation, user management, and initial data seeding for the Stock Trader application.

## Features

- **Database Initialization**: Automated database setup
- **Schema Creation**: SQL schema deployment
- **User Management**: Database user creation and permissions
- **Data Seeding**: Initial data population
- **Templates**: Configurable SQL templates
- **Security**: Secure credential management

## Usage

```hcl
module "postgres_init" {
  source = "./modules/postgres_init"
  
  postgres_server_id           = module.postgres.id
  postgres_fqdn                = module.postgres.fqdn
  administrator_login          = var.administrator_login
  administrator_login_password = var.administrator_login_password
  
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
| postgresql | ~> 1.0 |

## Providers

| Name | Version |
|------|---------|
| postgresql | ~> 1.0 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [postgresql_database.this](https://registry.terraform.io/providers/cyrilgdn/postgresql/latest/docs/resources/database) | resource |
| [postgresql_role.this](https://registry.terraform.io/providers/cyrilgdn/postgresql/latest/docs/resources/role) | resource |
| [postgresql_grant.this](https://registry.terraform.io/providers/cyrilgdn/postgresql/latest/docs/resources/grant) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| postgres_server_id | ID of the PostgreSQL Flexible Server | `string` | n/a | yes |
| postgres_fqdn | FQDN of the PostgreSQL server | `string` | n/a | yes |
| administrator_login | Administrator login for PostgreSQL | `string` | n/a | yes |
| administrator_login_password | Administrator password for PostgreSQL | `string` | n/a | yes |
| database_name | Name of the database to create | `string` | `"stocktrader"` | no |
| tags | Tags to apply to resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| database_name | The name of the created database |
| database_user | The name of the created database user |

## Initialization Process

### Database Creation
- **Database Setup**: Creates application database
- **Character Set**: UTF-8 encoding
- **Collation**: Proper collation settings
- **Owner**: Sets appropriate database owner

### User Management
- **Application User**: Creates dedicated application user
- **Permissions**: Grants appropriate permissions
- **Security**: Implements least-privilege access
- **Password Management**: Secure password handling

### Schema Deployment
- **Table Creation**: Creates application tables
- **Indexes**: Optimizes database performance
- **Constraints**: Implements data integrity
- **Views**: Creates application views

## Integration with Other Modules

### PostgreSQL Module
Uses PostgreSQL server from the `postgres` module:
```hcl
module "postgres_init" {
  # ... other configuration ...
  postgres_server_id = module.postgres.id
  postgres_fqdn      = module.postgres.fqdn
}
```

### Key Vault Module
Database credentials can be stored in Key Vault:
```hcl
module "key_vault" {
  # ... other configuration ...
  secrets_map = {
    "database-name"     = module.postgres_init.database_name
    "database-user"     = module.postgres_init.database_user
    "database-password" = var.database_password
  }
}
```

## Examples

### Development Environment
```hcl
module "postgres_init_dev" {
  source = "./modules/postgres_init"
  
  postgres_server_id           = module.postgres.id
  postgres_fqdn                = module.postgres.fqdn
  administrator_login          = var.administrator_login
  administrator_login_password = var.administrator_login_password
  database_name                = "stocktrader_dev"
  
  tags = {
    Environment = "Development"
    Project     = "StockTrader"
  }
}
```

### Production Environment
```hcl
module "postgres_init_prod" {
  source = "./modules/postgres_init"
  
  postgres_server_id           = module.postgres.id
  postgres_fqdn                = module.postgres.fqdn
  administrator_login          = var.administrator_login
  administrator_login_password = var.administrator_login_password
  database_name                = "stocktrader"
  
  tags = {
    Environment = "Production"
    Project     = "StockTrader"
  }
}
```

## Schema Examples

### Database Schema
```sql
-- Create users table
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create portfolios table
CREATE TABLE portfolios (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create trades table
CREATE TABLE trades (
    id SERIAL PRIMARY KEY,
    portfolio_id INTEGER REFERENCES portfolios(id),
    symbol VARCHAR(10) NOT NULL,
    quantity INTEGER NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    trade_type VARCHAR(4) CHECK (trade_type IN ('BUY', 'SELL')),
    trade_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_trades_portfolio_id ON trades(portfolio_id);
CREATE INDEX idx_trades_symbol ON trades(symbol);
CREATE INDEX idx_trades_date ON trades(trade_date);
```

### User Permissions
```sql
-- Grant permissions to application user
GRANT CONNECT ON DATABASE stocktrader TO stocktrader_user;
GRANT USAGE ON SCHEMA public TO stocktrader_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO stocktrader_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO stocktrader_user;
```

## Connection Examples

### From Application
```python
import psycopg2
import os

# Connect to database
conn = psycopg2.connect(
    host=os.getenv('DB_HOST'),
    database=os.getenv('DB_NAME'),
    user=os.getenv('DB_USER'),
    password=os.getenv('DB_PASSWORD'),
    sslmode='require'
)

# Execute queries
cursor = conn.cursor()
cursor.execute("SELECT * FROM users")
users = cursor.fetchall()
```

### From Kubernetes (via External Secrets)
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
    - secretKey: DB_NAME
      remoteRef:
        key: database-name
    - secretKey: DB_USER
      remoteRef:
        key: database-user
    - secretKey: DB_PASSWORD
      remoteRef:
        key: database-password
```

## Troubleshooting

### Common Issues

#### Connection Failures
1. **Check Server Status**: Verify PostgreSQL server is running
   ```bash
   az postgres flexible-server show --name <server-name> --resource-group <rg>
   ```

2. **Check Network**: Verify network connectivity
   ```bash
   telnet <fqdn> 5432
   ```

3. **Check Credentials**: Verify login credentials
   ```bash
   psql -h <fqdn> -U <admin-user> -d postgres
   ```

#### Database Creation Issues
1. **Check Permissions**: Verify admin user permissions
   ```sql
   SELECT rolname, rolsuper, rolcreatedb FROM pg_roles WHERE rolname = 'admin';
   ```

2. **Check Database**: Verify database exists
   ```sql
   \l
   ```

3. **Check Schema**: Verify schema creation
   ```sql
   \dt
   ```

### Debugging Commands

```bash
# Test database connection
psql -h <fqdn> -U <admin-user> -d postgres

# Check database status
psql -h <fqdn> -U <admin-user> -d stocktrader -c "\l"

# Check tables
psql -h <fqdn> -U <admin-user> -d stocktrader -c "\dt"

# Check user permissions
psql -h <fqdn> -U <admin-user> -d stocktrader -c "\du"
```

## Security Best Practices

### Database Security
- **Strong Passwords**: Use complex passwords for all users
- **SSL/TLS**: Enable SSL connections
- **Network Security**: Use private endpoints
- **Access Control**: Implement proper user permissions

### Credential Management
- **Key Vault**: Store credentials in Azure Key Vault
- **Rotation**: Regular password rotation
- **Least Privilege**: Grant minimal required permissions
- **Audit Logging**: Monitor database access

### Data Protection
- **Encryption**: Enable encryption at rest and in transit
- **Backup**: Regular database backups
- **Access Control**: Implement row-level security
- **Monitoring**: Monitor database activity

## Monitoring and Alerts

### Key Metrics
- **Connection Count**: Active database connections
- **Query Performance**: Slow query detection
- **Storage Usage**: Database size and growth
- **Error Rates**: Database error rates

### Recommended Alerts
- Database connection failures
- High query response times (>1 second)
- Storage usage >80%
- Authentication failures

## Backup and Recovery

### Backup Strategy
```bash
# Create database backup
pg_dump -h <fqdn> -U <admin-user> -d stocktrader > backup.sql

# Restore database
psql -h <fqdn> -U <admin-user> -d stocktrader < backup.sql
```

### Recovery Procedures
- **Point-in-Time Recovery**: Use PostgreSQL point-in-time recovery
- **Schema Recovery**: Restore database schema
- **Data Recovery**: Restore specific tables or data
- **Testing**: Regular recovery testing

## Notes

- Database initialization runs only once
- Schema changes require manual migration
- User permissions follow least-privilege principles
- Database credentials should be stored securely
- Regular backups are essential for data protection
- Monitor database performance and growth
- Implement proper indexing for performance
- Use connection pooling for application connections
