output "acr_login_server" {
  value = azurerm_container_registry.acr.login_server
}

output "marketdata_url" {
  value = "https://${azurerm_container_app.marketdata.latest_revision_fqdn}"
}

output "frontend_url" {
  value = "https://${azurerm_container_app.frontend.latest_revision_fqdn}"
}

output "key_vault_uri" {
  value = azurerm_key_vault.main.vault_uri
}

output "aad_client_id" {
  value = azuread_application.app.client_id
}

output "aad_tenant_id" {
  value = data.azurerm_client_config.current.tenant_id
}

output "aad_scope" {
  value = "api://${azuread_application.app.client_id}/market.read"
}