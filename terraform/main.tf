terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.100"
    }
  }

  # Store state remotely — create this storage account manually first (see instructions below)
  backend "azurerm" {
    resource_group_name  = "tfstate-rg"
    storage_account_name = "tfstateordermanagerai"
    container_name       = "tfstate"
    key                  = "terraform.tfstate"
  }
}

provider "azurerm" {
  features {}
}

data "azurerm_client_config" "current" {}

locals {
  prefix = "${var.project_name}-${var.environment}"
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
}

resource "azurerm_key_vault_secret" "kafka_api_key" {
  name         = "KAFKA-CLUSTER-API-KEY"
  value        = var.kafka_cluster_api_key
  key_vault_id = azurerm_key_vault.main.id
}

resource "azurerm_key_vault_secret" "kafka_api_secret" {
  name         = "KAFKA-CLUSTER-API-SECRET"
  value        = var.kafka_cluster_api_secret
  key_vault_id = azurerm_key_vault.main.id
}

# ── Container Registry ──────────────────────────────────
resource "azurerm_container_registry" "acr" {
  name                = "${var.project_name}${var.environment}acr"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "Basic"
  admin_enabled       = true
}

# ── App Service Plan (Linux, B1 tier) ───────────────────
resource "azurerm_service_plan" "main" {
  name                = "${local.prefix}-plan"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  os_type             = "Linux"
  sku_name            = "B1"
}

# ── Market Data App Service ──────────────────────────────
resource "azurerm_linux_web_app" "marketdata" {
  name                = "${local.prefix}-marketdata"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  service_plan_id     = azurerm_service_plan.main.id

  identity {
    type = "SystemAssigned"
  }

  site_config {
    websockets_enabled = true
    application_stack {
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
      docker_image_name        = "ordermanagerai-marketdata:latest"
    }
  }

  app_settings = {
    WEBSITES_PORT                   = "8080"
    DOCKER_REGISTRY_SERVER_PASSWORD = azurerm_container_registry.acr.admin_password
    CORS_ALLOWED_ORIGINS            = "https://${local.prefix}-frontend.azurewebsites.net"
    REDIS_PASSWORD                  = "@Microsoft.KeyVault(VaultName=${azurerm_key_vault.main.name};SecretName=${azurerm_key_vault_secret.redis_password.name})"
    KAFKA_CLUSTER_API_KEY           = "@Microsoft.KeyVault(VaultName=${azurerm_key_vault.main.name};SecretName=${azurerm_key_vault_secret.kafka_api_key.name})"
    KAFKA_CLUSTER_API_SECRET        = "@Microsoft.KeyVault(VaultName=${azurerm_key_vault.main.name};SecretName=${azurerm_key_vault_secret.kafka_api_secret.name})"
  }
}

resource "azurerm_key_vault_access_policy" "marketdata" {
  key_vault_id = azurerm_key_vault.main.id
  tenant_id    = data.azurerm_client_config.current.tenant_id
  object_id    = azurerm_linux_web_app.marketdata.identity[0].principal_id

  secret_permissions = ["Get", "List"]
}

# ── Frontend App Service ────────────────────────────────
resource "azurerm_linux_web_app" "frontend" {
  name                = "${local.prefix}-frontend"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  service_plan_id     = azurerm_service_plan.main.id

  site_config {
    websockets_enabled = true
    application_stack {
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
      docker_image_name        = "ordermanagerai-frontend:latest"
    }
  }

  app_settings = {
    BACKEND_URL                    = "http://${azurerm_linux_web_app.marketdata.default_hostname}"
    WEBSITES_PORT                  = "80"
    DOCKER_REGISTRY_SERVER_PASSWORD = azurerm_container_registry.acr.admin_password
  }
}