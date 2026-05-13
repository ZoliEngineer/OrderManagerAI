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

  # Predicted frontend origin — used for CORS and Entra ID redirect URIs.
  # Derived from the environment default_domain to avoid a circular dependency.
  frontend_origin = "https://${local.prefix}-frontend.${azurerm_container_app_environment.main.default_domain}"

  # ACR credentials passed to every module call.
  acr = {
    server   = azurerm_container_registry.acr.login_server
    username = azurerm_container_registry.acr.admin_username
    password = azurerm_container_registry.acr.admin_password
  }

  # Env vars shared by every backend service.
  common_env_vars = [
    { name = "AAD_TENANT_ID",        value = data.azurerm_client_config.current.tenant_id },
    { name = "AAD_CLIENT_ID",        value = azuread_application.app.client_id             },
    { name = "CORS_ALLOWED_ORIGINS", value = local.frontend_origin                         },
  ]
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

    oauth2_permission_scope {
      admin_consent_description  = "Allow internal service-to-service calls"
      admin_consent_display_name = "internal.read"
      enabled                    = true
      id                         = "00000000-0000-0000-0000-000000000002"
      type                       = "Admin"
      value                      = "internal.read"
    }
  }

  # SPA platform — Entra ID will issue tokens directly to this origin
  single_page_application {
    redirect_uris = [
      "http://localhost:3000/",
      "https://${local.prefix}-frontend.${azurerm_container_app_environment.main.default_domain}/",
    ]
  }

  lifecycle {
    ignore_changes = [identifier_uris]
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

resource "azurerm_key_vault_secret" "supabase_db_password" {
  name         = "SUPABASE-DB-PASSWORD"
  value        = var.supabase_db_password
  key_vault_id = azurerm_key_vault.main.id
  depends_on   = [azurerm_key_vault_access_policy.deployer]
}

resource "azurerm_key_vault_secret" "neon_db_password" {
  name         = "NEON-DB-PASSWORD"
  value        = var.neon_db_password
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
# Shared networking/logging layer. Consumption plan = pay-per-use.
resource "azurerm_container_app_environment" "main" {
  name                = "${local.prefix}-env"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
}

# ── Container Apps ───────────────────────────────────────

module "marketdata" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-marketdata"
  container_name = "marketdata"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  acr_server     = local.acr.server
  acr_username   = local.acr.username
  acr_password   = local.acr.password
  target_port    = 8080
  max_replicas   = 1

  extra_secrets = concat(
    [
      { name = "redis-password",   value = var.redis_password           },
      { name = "kafka-api-key",    value = var.kafka_cluster_api_key    },
      { name = "kafka-api-secret", value = var.kafka_cluster_api_secret },
    ],
    var.finnhub_api_key != "" ? [{ name = "finnhub-api-key", value = var.finnhub_api_key }] : []
  )

  env_vars = concat(
    [
      { name = "REDIS_PASSWORD",           secret_name = "redis-password"   },
      { name = "KAFKA_CLUSTER_API_KEY",    secret_name = "kafka-api-key"    },
      { name = "KAFKA_CLUSTER_API_SECRET", secret_name = "kafka-api-secret" },
    ],
    local.common_env_vars,
    var.finnhub_api_key != "" ? [{ name = "FINNHUB_API_KEY", secret_name = "finnhub-api-key" }] : []
  )
}

module "frontend" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-frontend"
  container_name = "frontend"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  acr_server     = local.acr.server
  acr_username   = local.acr.username
  acr_password   = local.acr.password
  target_port    = 80
  max_replicas   = 1

  env_vars = [
    { name = "BACKEND_URL", value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}" },
  ]
}

module "account_service" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-acct-svc"
  container_name = "account-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  acr_server     = local.acr.server
  acr_username   = local.acr.username
  acr_password   = local.acr.password
  target_port    = 8081

  extra_secrets = [
    { name = "redis-password",    value = var.redis_password   },
    { name = "neon-db-password",  value = var.neon_db_password },
  ]

  env_vars = concat(
    [
      { name = "SPRING_DATASOURCE_PASSWORD", secret_name = "neon-db-password" },
      { name = "REDIS_PASSWORD",             secret_name = "redis-password"   },
    ],
    local.common_env_vars
  )
}

module "order_service" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-order-svc"
  container_name = "order-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  acr_server     = local.acr.server
  acr_username   = local.acr.username
  acr_password   = local.acr.password
  target_port    = 8082

  extra_secrets = [
    { name = "supabase-db-password", value = var.supabase_db_password      },
    { name = "redis-password",       value = var.redis_password             },
    { name = "kafka-api-key",        value = var.kafka_cluster_api_key      },
    { name = "kafka-api-secret",     value = var.kafka_cluster_api_secret   },
  ]

  env_vars = concat(
    [
      { name = "SPRING_DATASOURCE_PASSWORD", secret_name = "supabase-db-password" },
      { name = "REDIS_PASSWORD",             secret_name = "redis-password"        },
      { name = "KAFKA_CLUSTER_API_KEY",      secret_name = "kafka-api-key"         },
      { name = "KAFKA_CLUSTER_API_SECRET",   secret_name = "kafka-api-secret"      },
    ],
    local.common_env_vars,
    [{ name = "MARKET_DATA_SERVICE_BASE_URL",
       value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}" }]
  )
}

# Stateless — no DB. gRPC port 9090 is internal; only actuator port 8083 via ingress.
module "risk_service" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-risk-svc"
  container_name = "risk-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  acr_server     = local.acr.server
  acr_username   = local.acr.username
  acr_password   = local.acr.password
  target_port    = 8083

  extra_secrets = [
    { name = "redis-password", value = var.redis_password },
  ]

  env_vars = concat(
    [
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
    ],
    local.common_env_vars
  )
}
