# SPDX-License-Identifier: Apache-2.0
# Copyright 2025 Kyndryl
# ----------------------------------------------------------------------------------
# FUNCTION APP MODULE - VERSION CONSTRAINTS
# ----------------------------------------------------------------------------------
# Pins Terraform and provider versions used by this module. Keeping these
# explicit improves reproducibility and compatibility across environments.
# ----------------------------------------------------------------------------------

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    archive = {
      source = "hashicorp/archive"
    }
  }
}

