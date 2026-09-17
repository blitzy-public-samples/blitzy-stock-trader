# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# POSTGRESQL INIT MODULE VARIABLES
# ----------------------------------------------------------------------------------
# This file defines all input variables for the PostgreSQL Initialization module.
# These variables control the database setup, schema deployment, and initial
# configuration for the Stock Trader application database.
#
# Variable Categories:
# - Database Connection: Server ID, FQDN, and connectivity settings
# - Authentication: Administrator credentials for database access
# - Schema Configuration: Database initialization and setup
# ----------------------------------------------------------------------------------

# =============================================================================
# DATABASE CONNECTION VARIABLES
# =============================================================================

# Resource ID of the PostgreSQL Flexible Server
variable "postgres_server_id" { type = string }
# Fully qualified domain name of the PostgreSQL server
variable "postgres_fqdn" { type = string }

# =============================================================================
# AUTHENTICATION VARIABLES
# =============================================================================

# Administrator username for PostgreSQL server access
variable "administrator_login" { type = string }

# Administrator password for PostgreSQL server access (sensitive)
variable "administrator_login_password" {
  type      = string
  sensitive = true
}

