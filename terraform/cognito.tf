# Cognito replaces Entra ID. Access tokens carry `client_id` (no `aud`) and scopes like `ordermanager/mcp.trade`;
# the services accept them via JwtSecurityConfig (OIDC_CLIENT_IDS, scope prefix stripped).
locals {
  app_origin     = "https://app.${var.domain_name}"
  api_origin     = "https://api.${var.domain_name}"
  advisor_origin = "https://advisor.${var.domain_name}"

  cognito_domain = "${local.prefix}-${data.aws_caller_identity.current.account_id}"
  oidc_issuer    = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
  mcp_scope      = "ordermanager/mcp.trade"
}

resource "aws_cognito_user_pool" "main" {
  name                     = local.prefix
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  deletion_protection      = "INACTIVE"

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = false
  }
}

resource "aws_cognito_user_pool_domain" "main" {
  domain       = local.cognito_domain
  user_pool_id = aws_cognito_user_pool.main.id
}

resource "aws_cognito_resource_server" "api" {
  identifier   = "ordermanager"
  name         = "OrderManagerAI API"
  user_pool_id = aws_cognito_user_pool.main.id

  scope {
    scope_name        = "market.read"
    scope_description = "Access market data"
  }
  scope {
    scope_name        = "internal.read"
    scope_description = "Internal service-to-service calls"
  }
  scope {
    scope_name        = "mcp.trade"
    scope_description = "Allow AI agents to read prices and place or cancel orders on behalf of the signed-in advisor"
  }
}

# React SPA
resource "aws_cognito_user_pool_client" "web" {
  name         = "${local.prefix}-web"
  user_pool_id = aws_cognito_user_pool.main.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "${aws_cognito_resource_server.api.identifier}/market.read"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["http://localhost:3000/", "${local.app_origin}/"]
  logout_urls                          = ["http://localhost:3000/", "${local.app_origin}/"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors        = "ENABLED"

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 30
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

# Advisor chat page (served by advisorservice); it must be allowed to request mcp.trade
resource "aws_cognito_user_pool_client" "advisor" {
  name         = "${local.prefix}-advisor"
  user_pool_id = aws_cognito_user_pool.main.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "${aws_cognito_resource_server.api.identifier}/mcp.trade"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["http://localhost:8085/", "${local.advisor_origin}/"]
  logout_urls                          = ["http://localhost:8085/", "${local.advisor_origin}/"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors        = "ENABLED"

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 30
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user" "advisor" {
  for_each     = toset(var.advisor_emails)
  user_pool_id = aws_cognito_user_pool.main.id
  username     = each.value

  attributes = {
    email          = each.value
    email_verified = "true"
  }
}
