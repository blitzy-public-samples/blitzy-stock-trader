# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# POSTGRESQL DATABASE INITIALIZATION
# ----------------------------------------------------------------------------------
# This module handles the initialization and setup of PostgreSQL databases, including
# schema creation, user management, and initial data seeding for the Stock Trader application.
#
# Key Features:
# - Database initialization and setup
# - Schema deployment and table creation
# - User management and permission setup
# - Initial data seeding and migration support
# - Temporary firewall access for initialization
# - Template-based schema generation
# ----------------------------------------------------------------------------------

# Get current IP address for temporary firewall access
data "http" "myip" {
  url = "https://api.ipify.org"
}

# Temporary Firewall Rule for Initialization
resource "azurerm_postgresql_flexible_server_firewall_rule" "allow_my_ip" {
  name             = "AllowMyIP"
  server_id        = var.postgres_server_id
  start_ip_address = trim(data.http.myip.response_body, " \n")
  end_ip_address   = trim(data.http.myip.response_body, " \n")
}

# Schema Template File Generation
resource "local_file" "init_schema_sql" {
  content  = templatefile("${path.module}/init_schema.sql.tmpl", {})
  filename = "${path.module}/init_schema.sql"
}

# PostgreSQL Schema Initialization
resource "terraform_data" "init_postgres_schema" {
  provisioner "local-exec" {
    command     = <<EOT
      set -e
      export PGPASSWORD=${var.administrator_login_password}
      
      # Wait for PostgreSQL server to be fully ready and firewall rules to be active
      echo "Waiting for PostgreSQL server to be ready..."
      for i in {1..30}; do
        echo "Checking PostgreSQL connection (attempt $i)..."
        if psql -h ${var.postgres_fqdn} -U ${var.administrator_login} -d postgres -c "SELECT 1;" >/dev/null 2>&1; then
          echo "PostgreSQL connection successful"
          break
        fi
        if [ $i -eq 30 ]; then
          echo "ERROR: Could not connect to PostgreSQL after 30 attempts"
          exit 1
        fi
        echo "Connection failed, retrying in 10 seconds..."
        sleep 10
      done
      
      # Run schema initialization with retry logic
      for i in {1..5}; do
        echo "Attempt $i: Running schema initialization..."
        if psql -v ON_ERROR_STOP=1 -h ${var.postgres_fqdn} -U ${var.administrator_login} -d postgres -f ${local_file.init_schema_sql.filename}; then
          echo "Schema initialization complete."
          break
        else
          if [ $i -eq 5 ]; then
            echo "ERROR: Schema initialization failed after 5 attempts"
            exit 1
          fi
          echo "Schema initialization failed, retrying in 10 seconds..."
          sleep 10
        fi
      done
    EOT
    interpreter = ["/bin/bash", "-c"]
  }
  depends_on = [azurerm_postgresql_flexible_server_firewall_rule.allow_my_ip, local_file.init_schema_sql]
}

