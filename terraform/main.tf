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

locals {
  prefix = "${var.project_name}-${var.environment}"
}

# ── Resource Group ──────────────────────────────────────
resource "azurerm_resource_group" "main" {
  name     = "${local.prefix}-rg"
  location = var.location
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

# ── Backend App Service ─────────────────────────────────
resource "azurerm_linux_web_app" "backend" {
  name                = "${local.prefix}-backend"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  service_plan_id     = azurerm_service_plan.main.id

  site_config {
    application_stack {
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
      docker_image_name        = "ordermanagerai-backend:latest"
    }
  }

  app_settings = {
    WEBSITES_PORT = "8080"
  }
}

# ── Frontend App Service ────────────────────────────────
resource "azurerm_linux_web_app" "frontend" {
  name                = "${local.prefix}-frontend"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  service_plan_id     = azurerm_service_plan.main.id

  site_config {
    application_stack {
      docker_registry_url      = "https://${azurerm_container_registry.acr.login_server}"
      docker_registry_username = azurerm_container_registry.acr.admin_username
      docker_registry_password = azurerm_container_registry.acr.admin_password
      docker_image_name        = "ordermanagerai-frontend:latest"
    }
  }

  app_settings = {
    BACKEND_URL   = "https://${azurerm_linux_web_app.backend.default_hostname}"
    WEBSITES_PORT = "80"
  }
}