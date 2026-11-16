resource "azurerm_container_app" "this" {
  name                         = var.name
  container_app_environment_id = var.environment_id
  resource_group_name          = var.resource_group
  revision_mode                = "Single"

  # Image is managed by the deploy workflow; ignore it here to avoid drift.
  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }

  # Images are public on GHCR, so no pull credentials are needed. Set
  # registry_username to a non-empty value to pull from a private registry.
  dynamic "registry" {
    for_each = var.registry_username != "" ? [1] : []
    content {
      server               = var.registry_server
      username             = var.registry_username
      password_secret_name = "registry-password"
    }
  }

  dynamic "secret" {
    for_each = var.registry_username != "" ? [1] : []
    content {
      name  = "registry-password"
      value = var.registry_password
    }
  }

  dynamic "secret" {
    for_each = var.extra_secrets
    content {
      name  = secret.value.name
      value = secret.value.value
    }
  }

  template {
    dynamic "volume" {
      for_each = var.secret_volume_path != null ? [1] : []
      content {
        name         = "secrets"
        storage_type = "Secret"
      }
    }

    min_replicas = var.min_replicas
    max_replicas = var.max_replicas

    container {
      name   = coalesce(var.container_name, var.name)
      image  = var.image
      cpu    = var.cpu
      memory = var.memory
      args   = var.args

      dynamic "volume_mounts" {
        for_each = var.secret_volume_path != null ? [1] : []
        content {
          name = "secrets"
          path = var.secret_volume_path
        }
      }

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
      external_enabled = var.external_ingress
      target_port      = var.target_port
      transport        = var.transport
      traffic_weight {
        percentage      = 100
        latest_revision = true
      }
    }
  }
}
