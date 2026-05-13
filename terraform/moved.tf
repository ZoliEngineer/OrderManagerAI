# State address migration for the container-app module refactor.
# Safe to delete after the first successful terraform apply.

moved {
  from = azurerm_container_app.marketdata
  to   = module.marketdata.azurerm_container_app.this
}

moved {
  from = azurerm_container_app.frontend
  to   = module.frontend.azurerm_container_app.this
}

moved {
  from = azurerm_container_app.account_service
  to   = module.account_service.azurerm_container_app.this
}

moved {
  from = azurerm_container_app.order_service
  to   = module.order_service.azurerm_container_app.this
}
