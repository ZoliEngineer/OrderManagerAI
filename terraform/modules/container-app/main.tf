resource "azurerm_container_app" "this" {
  name                         = var.name
  container_app_environment_id = var.environment_id
  resource_group_name          = var.resource_group
  revision_mode                = "Single"

  # Image is managed by the deploy workflow; ignore it here to avoid drift.
  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }

  registry {
    server               = var.registry_server
    username             = var.registry_username
    password_secret_name = "registry-password"
  }

  secret {
    name  = "registry-password"
    value = var.registry_password
  }

  dynamic "secret" {
    for_each = var.extra_secrets
    content {
      name  = secret.value.name
      value = secret.value.value
    }
  }

  template {
    min_replicas = 0
    max_replicas = var.max_replicas

    container {
      name   = coalesce(var.container_name, var.name)
      image  = "mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"
      cpu    = 0.25
      memory = "0.5Gi"

      dynamic "env" {
        for_each = var.env_vars
        content {
          name        = env.value.name
          value       = env.value.value
          secret_name = env.value.secret_name
        }
      }
    }
  }

  dynamic "ingress" {
    for_each = var.enable_ingress ? [1] : []
    content {
      external_enabled = true
      target_port      = var.target_port
      transport        = var.transport
      traffic_weight {
        percentage      = 100
        latest_revision = true
      }
    }
  }
}
