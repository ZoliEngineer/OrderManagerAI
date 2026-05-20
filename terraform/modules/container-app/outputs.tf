output "fqdn" {
  value = length(azurerm_container_app.this.ingress) > 0 ? azurerm_container_app.this.ingress[0].fqdn : null
}
