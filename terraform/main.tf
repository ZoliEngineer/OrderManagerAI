terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.100"
    }
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 2.50"
    }
  }

  # Store state remotely — create this storage account manually first (see instructions below)
  backend "azurerm" {
    resource_group_name = "tfstate-rg"
    container_name      = "tfstate"
    key                 = "terraform.tfstate"
    # storage_account_name is passed dynamically by bootstrap.sh via -backend-config
  }
}

provider "azurerm" {
  features {}
}

provider "azuread" {}

data "azurerm_client_config" "current" {}

locals {
  prefix     = "${var.project_name}-${var.environment}"
  sub_suffix = substr(replace(data.azurerm_client_config.current.subscription_id, "-", ""), 0, 6)
}

# ── Entra ID App Registration ────────────────────────────
resource "azuread_service_principal" "app" {
  client_id = azuread_application.app.client_id
}

resource "azuread_application_identifier_uri" "app" {
  application_id = azuread_application.app.id
  identifier_uri = "api://${azuread_application.app.client_id}"
}

resource "azuread_application" "app" {
  display_name     = "OrderManagerAI"
  sign_in_audience = "AzureADMyOrg"

  # Exposes the backend API and defines the scope the frontend requests
  api {
    requested_access_token_version = 2

    oauth2_permission_scope {
      admin_consent_description  = "Access market data"
      admin_consent_display_name = "market.read"
      enabled                    = true
      id                         = "00000000-0000-0000-0000-000000000001"
      type                       = "User"
      user_consent_description   = "Access market data on your behalf"
      user_consent_display_name  = "market.read"
      value                      = "market.read"
    }
  }

  # SPA platform — Entra ID will issue tokens directly to this origin
  single_page_application {
    redirect_uris = [
      "http://localhost:3000/",
      "https://${local.prefix}-frontend.${azurerm_container_app_environment.main.default_domain}/",
    ]
  }
}

# ── Resource Group ──────────────────────────────────────
resource "azurerm_resource_group" "main" {
  name     = "${local.prefix}-rg"
  location = var.location
}

# ── Key Vault ───────────────────────────────────────────
resource "azurerm_key_vault" "main" {
  name                = "${var.project_name}${var.environment}kv"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = "standard"
}

resource "azurerm_key_vault_access_policy" "deployer" {
  key_vault_id = azurerm_key_vault.main.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = data.azurerm_client_config.current.object_id

  secret_permissions = ["Get", "List", "Set", "Delete", "Purge"]
}

resource "azurerm_key_vault_secret" "redis_password" {
  name         = "REDIS-PASSWORD"
  value        = var.redis_password
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_key_vault_access_policy.deployer]
}

resource "azurerm_key_vault_secret" "kafka_api_key" {
  name         = "KAFKA-CLUSTER-API-KEY"
  value        = var.kafka_cluster_api_key
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_key_vault_access_policy.deployer]
}

resource "azurerm_key_vault_secret" "kafka_api_secret" {
  name         = "KAFKA-CLUSTER-API-SECRET"
  value        = var.kafka_cluster_api_secret
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_key_vault_access_policy.deployer]
}

resource "azurerm_key_vault_secret" "finnhub_api_key" {
  count        = var.finnhub_api_key != "" ? 1 : 0
  name         = "FINNHUB-API-KEY"
  value        = var.finnhub_api_key
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_key_vault_access_policy.deployer]
}

# ── Container Registry ──────────────────────────────────
resource "azurerm_container_registry" "acr" {
  name                = "${var.project_name}${var.environment}acr${local.sub_suffix}"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Basic"
  admin_enabled       = true
}

# ── Container App Environment ────────────────────────────
# Shared networking/logging layer for both container apps.
# Consumption plan = pay-per-use, well within the free tier for low traffic.
resource "azurerm_container_app_environment" "main" {
  name                = "${local.prefix}-env"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
}

# ── Market Data Container App ────────────────────────────
# Secrets are stored directly in the Container App rather than fetched from
# Key Vault at runtime — this avoids the managed-identity/access-policy
# ordering problem. Key Vault still holds the canonical copies for auditing.
# CORS origin uses the environment's default_domain to predict the frontend
# FQDN without creating a circular resource dependency.
resource "azurerm_container_app" "marketdata" {
  name                         = "${local.prefix}-marketdata"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }

  registry {
    server               = azurerm_container_registry.acr.login_server
    username             = azurerm_container_registry.acr.admin_username
    password_secret_name = "acr-password"
  }

  secret {
    name  = "acr-password"
    value = azurerm_container_registry.acr.admin_password
  }
  secret {
    name  = "redis-password"
    value = var.redis_password
  }
  secret {
    name  = "kafka-api-key"
    value = var.kafka_cluster_api_key
  }
  secret {
    name  = "kafka-api-secret"
    value = var.kafka_cluster_api_secret
  }
  dynamic "secret" {
    for_each = var.finnhub_api_key != "" ? [var.finnhub_api_key] : []
    content {
      name  = "finnhub-api-key"
      value = secret.value
    }
  }

  template {
    min_replicas = 0
    max_replicas = 1

    container {
      name   = "marketdata"
      # Placeholder lets Terraform create the app without images in ACR.
      # The deploy workflow overwrites this on the first push to main.
      image  = "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
      cpu    = 0.25
      memory = "0.5Gi"

      env {
        name        = "REDIS_PASSWORD"
        secret_name = "redis-password"
      }
      env {
        name        = "KAFKA_CLUSTER_API_KEY"
        secret_name = "kafka-api-key"
      }
      env {
        name        = "KAFKA_CLUSTER_API_SECRET"
        secret_name = "kafka-api-secret"
      }
      env {
        name  = "AAD_TENANT_ID"
        value = data.azurerm_client_config.current.tenant_id
      }
      env {
        name  = "AAD_CLIENT_ID"
        value = azuread_application.app.client_id
      }
      env {
        name  = "CORS_ALLOWED_ORIGINS"
        value = "https://${local.prefix}-frontend.${azurerm_container_app_environment.main.default_domain}"
      }
      dynamic "env" {
        for_each = var.finnhub_api_key != "" ? [1] : []
        content {
          name        = "FINNHUB_API_KEY"
          secret_name = "finnhub-api-key"
        }
      }
    }
  }

  ingress {
    external_enabled = true
    target_port      = 80  # placeholder serves on 80; deploy workflow sets real port via image update
    transport        = "http"
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }
}

# ── Frontend Container App ───────────────────────────────
# Serves the nginx+React image. AAD config is baked into the image at build
# time via Docker build-args, so only BACKEND_URL is needed at runtime
# (nginx uses it for proxying if configured).
resource "azurerm_container_app" "frontend" {
  name                         = "${local.prefix}-frontend"
  container_app_environment_id = azurerm_container_app_environment.main.id
  resource_group_name          = azurerm_resource_group.main.name
  revision_mode                = "Single"

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }

  registry {
    server               = azurerm_container_registry.acr.login_server
    username             = azurerm_container_registry.acr.admin_username
    password_secret_name = "acr-password"
  }

  secret {
    name  = "acr-password"
    value = azurerm_container_registry.acr.admin_password
  }

  template {
    min_replicas = 0
    max_replicas = 1

    container {
      name   = "frontend"
      # Placeholder — replaced by deploy workflow on first push to main.
      image  = "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
      cpu    = 0.25
      memory = "0.5Gi"

      env {
        name  = "BACKEND_URL"
        value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}"
      }
    }
  }

  ingress {
    external_enabled = true
    target_port      = 80
    transport        = "http"
    traffic_weight {
      percentage      = 100
      latest_revision = true
    }
  }
}