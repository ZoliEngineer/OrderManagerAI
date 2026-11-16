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

  # Env vars shared by every backend service.
  # Services that Prometheus scrapes stay at 1 replica; otherwise every scrape would wake them anyway.
  scraped_min_replicas = var.observability_enabled ? 1 : 0

  common_env_vars = [
    { name = "AAD_TENANT_ID", value = data.azurerm_client_config.current.tenant_id },
    { name = "AAD_CLIENT_ID", value = azuread_application.app.client_id },
    { name = "CORS_ALLOWED_ORIGINS", value = local.frontend_origin },
  ]

  # Spans go straight to Grafana Cloud (no Collector in Azure). Empty until the grafana_cloud_otlp_* variables are set.
  # The auth header comes from the secret "grafana-otlp-auth", so every instrumented service must also have it in extra_secrets.
  otel_enabled = var.grafana_cloud_otlp_auth != ""
  otel_secrets = local.otel_enabled ? [{ name = "grafana-otlp-auth", value = var.grafana_cloud_otlp_auth }] : []
  otel_env_vars = local.otel_enabled ? [
    { name = "OTEL_TRACES_ENDPOINT", value = "${var.grafana_cloud_otlp_endpoint}/v1/traces" },
    { name = "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_HEADERS_AUTHORIZATION", secret_name = "grafana-otlp-auth" },
    { name = "OTEL_LOGS_ENDPOINT", value = "${var.grafana_cloud_otlp_endpoint}/v1/logs" },
    { name = "OTEL_METRICS_ENDPOINT", value = "${var.grafana_cloud_otlp_endpoint}/v1/metrics" },
    { name = "MANAGEMENT_OTLP_METRICS_EXPORT_HEADERS_AUTHORIZATION", secret_name = "grafana-otlp-auth" },
    { name = "MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_HEADERS_AUTHORIZATION", secret_name = "grafana-otlp-auth" },
    { name = "MANAGEMENT_OPENTELEMETRY_RESOURCE_ATTRIBUTES_DEPLOYMENT_ENVIRONMENT_NAME", value = "azure-prod" },
  ] : []
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

    # Required by the MCP server: lets an AI agent trade on behalf of the signed-in advisor.
    # Admin consent is required because the agent can place and cancel orders.
    oauth2_permission_scope {
      admin_consent_description  = "Allow AI agents to read prices and place or cancel orders on behalf of the signed-in advisor"
      admin_consent_display_name = "mcp.trade"
      enabled                    = true
      id                         = "00000000-0000-0000-0000-000000000003"
      type                       = "Admin"
      value                      = "mcp.trade"
    }
  }

  # SPA platform — Entra ID will issue tokens directly to this origin
  single_page_application {
    redirect_uris = [
      "http://localhost:3000/",
      "https://${local.prefix}-frontend.${azurerm_container_app_environment.main.default_domain}/",
      # Trading advisor chat page (served by advisorservice itself)
      "http://localhost:8085/",
      "https://${local.prefix}-advisor-svc.${azurerm_container_app_environment.main.default_domain}/",
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

resource "azurerm_key_vault_secret" "anthropic_api_key" {
  count        = var.anthropic_api_key != "" ? 1 : 0
  name         = "ANTHROPIC-API-KEY"
  value        = var.anthropic_api_key
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

# ── Container App Environment ────────────────────────────
# Shared networking/logging layer. Consumption plan = pay-per-use.
resource "azurerm_container_app_environment" "main" {
  name                = "${local.prefix}-env"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
}

# ── Container Apps ───────────────────────────────────────

module "marketdata" {
  min_replicas   = local.scraped_min_replicas
  source         = "./modules/container-app"
  name           = "${local.prefix}-marketdata"
  container_name = "marketdata"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 8080
  max_replicas   = 1

  extra_secrets = concat(
    [
      { name = "redis-password", value = var.redis_password },
      { name = "kafka-api-key", value = var.kafka_cluster_api_key },
      { name = "kafka-api-secret", value = var.kafka_cluster_api_secret },
    ],
    var.finnhub_api_key != "" ? [{ name = "finnhub-api-key", value = var.finnhub_api_key }] : []
  )

  env_vars = concat(
    [
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
      { name = "KAFKA_CLUSTER_API_KEY", secret_name = "kafka-api-key" },
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
  target_port    = 80
  max_replicas   = 1

  env_vars = [
    { name = "BACKEND_URL", value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}" },
  ]
}

module "account_service" {
  min_replicas   = local.scraped_min_replicas
  source         = "./modules/container-app"
  name           = "${local.prefix}-acct-svc"
  container_name = "account-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 8081

  extra_secrets = [
    { name = "redis-password", value = var.redis_password },
    { name = "neon-db-password", value = var.neon_db_password },
  ]

  env_vars = concat(
    [
      { name = "SPRING_DATASOURCE_PASSWORD", secret_name = "neon-db-password" },
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
    ],
    local.common_env_vars
  )
}

module "order_service" {
  min_replicas   = local.scraped_min_replicas
  source         = "./modules/container-app"
  name           = "${local.prefix}-order-svc"
  container_name = "order-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 8082

  extra_secrets = concat([
    { name = "supabase-db-password", value = var.supabase_db_password },
    { name = "redis-password", value = var.redis_password },
    { name = "kafka-api-key", value = var.kafka_cluster_api_key },
    { name = "kafka-api-secret", value = var.kafka_cluster_api_secret },
  ], local.otel_secrets)

  env_vars = concat(
    local.otel_env_vars,
    [
      { name = "SPRING_DATASOURCE_PASSWORD", secret_name = "supabase-db-password" },
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
      { name = "KAFKA_CLUSTER_API_KEY", secret_name = "kafka-api-key" },
      { name = "KAFKA_CLUSTER_API_SECRET", secret_name = "kafka-api-secret" },
    ],
    local.common_env_vars,
    [
      { name = "APP_SERVICES_MARKET_DATA_SERVICE_BASE_URL",
      value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}" },
      { name = "GRPC_CLIENT_RISK_SERVICE_ADDRESS",
      value = "dns:///${local.prefix}-risk-svc.${azurerm_container_app_environment.main.default_domain}:443" },
      { name = "GRPC_CLIENT_RISK_SERVICE_NEGOTIATION_TYPE",
      value = "tls" },
    ]
  )
}

# gRPC on 9090 (http2); actuator health on 8083 is only reachable internally.
module "risk_service" {
  source         = "./modules/container-app"
  name           = "${local.prefix}-risk-svc"
  container_name = "risk-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 9090
  transport      = "http2"

  extra_secrets = concat([
    { name = "redis-password", value = var.redis_password },
    { name = "kafka-api-key", value = var.kafka_cluster_api_key },
    { name = "kafka-api-secret", value = var.kafka_cluster_api_secret },
  ], local.otel_secrets)

  env_vars = concat(
    local.otel_env_vars,
    [
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
      { name = "KAFKA_CLUSTER_API_KEY", secret_name = "kafka-api-key" },
      { name = "KAFKA_CLUSTER_API_SECRET", secret_name = "kafka-api-secret" },
    ],
    local.common_env_vars
  )
}

# Kafka-driven; its only HTTP endpoint is actuator (port 8086), so ingress is internal-only (Prometheus scrapes it).
module "execution_service" {
  min_replicas     = local.scraped_min_replicas
  source           = "./modules/container-app"
  name             = "${local.prefix}-exec-svc"
  container_name   = "execution-service"
  environment_id   = azurerm_container_app_environment.main.id
  resource_group   = azurerm_resource_group.main.name
  target_port      = 8086
  external_ingress = false

  extra_secrets = concat([
    { name = "redis-password", value = var.redis_password },
    { name = "kafka-api-key", value = var.kafka_cluster_api_key },
    { name = "kafka-api-secret", value = var.kafka_cluster_api_secret },
  ], local.otel_secrets)

  env_vars = concat(
    local.otel_env_vars,
    [
      { name = "REDIS_PASSWORD", secret_name = "redis-password" },
      { name = "KAFKA_CLUSTER_API_KEY", secret_name = "kafka-api-key" },
      { name = "KAFKA_CLUSTER_API_SECRET", secret_name = "kafka-api-secret" },
    ],
    local.common_env_vars
  )
}

# MCP server for AI trading agents — stateless; forwards the caller's token to order and market data services.
module "mcp_service" {
  min_replicas   = local.scraped_min_replicas
  source         = "./modules/container-app"
  name           = "${local.prefix}-mcp-svc"
  container_name = "mcp-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 8084

  extra_secrets = local.otel_secrets

  env_vars = concat(
    local.otel_env_vars,
    local.common_env_vars,
    [
      { name = "ORDER_SERVICE_URL",
      value = "https://${local.prefix}-order-svc.${azurerm_container_app_environment.main.default_domain}" },
      { name = "MARKETDATA_URL",
      value = "https://${local.prefix}-marketdata.${azurerm_container_app_environment.main.default_domain}" },
      { name = "MCP_PUBLIC_URL",
      value = "https://${local.prefix}-mcp-svc.${azurerm_container_app_environment.main.default_domain}" },
    ]
  )
}

# Trading advisor: chat UI + Claude agent loop; trades through the MCP service with the user's token.
module "advisor_service" {
  min_replicas   = local.scraped_min_replicas
  source         = "./modules/container-app"
  name           = "${local.prefix}-advisor-svc"
  container_name = "advisor-service"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 8085

  extra_secrets = concat(var.anthropic_api_key != "" ? [
    { name = "anthropic-api-key", value = var.anthropic_api_key },
  ] : [], local.otel_secrets)

  env_vars = concat(
    local.otel_env_vars,
    local.common_env_vars,
    [
      { name = "MCP_SERVER_URL",
      value = "https://${local.prefix}-mcp-svc.${azurerm_container_app_environment.main.default_domain}/mcp" },
    ],
    var.anthropic_api_key != "" ? [{ name = "ANTHROPIC_API_KEY", secret_name = "anthropic-api-key" }] : []
  )
}

# ── Observability (Prometheus + Grafana) ─────────────────
# Prometheus scrapes the services' /actuator/prometheus over HTTPS. Its config is rendered by Terraform
# (service FQDNs are only known here) and mounted from a Container App secret.
# The risk service is not scraped: its only ingress is gRPC, and its actuator port (8083) has no ingress.
module "prometheus" {
  count          = var.observability_enabled ? 1 : 0
  source         = "./modules/container-app"
  name           = "${local.prefix}-prometheus"
  container_name = "prometheus"
  image          = "docker.io/prom/prometheus:v3.5.0"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 9090
  max_replicas   = 1
  min_replicas   = 1
  cpu            = 0.5
  memory         = "1Gi"

  external_ingress   = false
  secret_volume_path = "/etc/prometheus-config"
  args = [
    "--config.file=/etc/prometheus-config/prometheus-config", # file name = secret name
    "--storage.tsdb.retention.time=7d",
  ]

  extra_secrets = [
    {
      name = "prometheus-config"
      value = templatefile("${path.module}/../observability/prometheus/prometheus.azure.yml.tpl", {
        prefix = local.prefix
        domain = azurerm_container_app_environment.main.default_domain
      })
    },
    {
      name  = "prometheus-rules"
      value = file("${path.module}/../observability/prometheus/rules/ordermanager.yml")
    },
    {
      name  = "prometheus-rules-slo"
      value = file("${path.module}/../observability/prometheus/rules/slo.yml")
    },
  ]
}

# Image (config + dashboards baked in) is built and deployed by deploy.yml.
module "grafana" {
  count          = var.observability_enabled ? 1 : 0
  source         = "./modules/container-app"
  name           = "${local.prefix}-grafana"
  container_name = "grafana"
  environment_id = azurerm_container_app_environment.main.id
  resource_group = azurerm_resource_group.main.name
  target_port    = 3000
  max_replicas   = 1
  min_replicas   = 1
  cpu            = 0.5
  memory         = "1Gi"

  extra_secrets = [
    { name = "grafana-admin-password", value = var.grafana_admin_password },
  ]

  env_vars = [
    { name = "GF_SECURITY_ADMIN_PASSWORD", secret_name = "grafana-admin-password" },
    { name = "GF_AUTH_ANONYMOUS_ENABLED", value = "false" },
    { name = "PROMETHEUS_URL",
    value = "https://${local.prefix}-prometheus.internal.${azurerm_container_app_environment.main.default_domain}" },
  ]
}
