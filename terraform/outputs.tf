output "acr_login_server" {
  value = azurerm_container_registry.acr.login_server
}

output "marketdata_url" {
  value = "https://${azurerm_linux_web_app.marketdata.default_hostname}"
}

output "frontend_url" {
  value = "https://${azurerm_linux_web_app.frontend.default_hostname}"
}