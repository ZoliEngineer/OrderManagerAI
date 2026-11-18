output "frontend_url" {
  value = local.app_origin
}

output "api_url" {
  value = local.api_origin
}

output "mcp_server_url" {
  value = "${local.api_origin}/mcp"
}

output "advisor_url" {
  value = "${local.advisor_origin}/"
}

output "oidc_issuer_uri" {
  description = "Value for OIDC_ISSUER_URI / the properties files"
  value       = local.oidc_issuer
}

output "cognito_client_ids" {
  value = {
    web     = aws_cognito_user_pool_client.web.id
    advisor = aws_cognito_user_pool_client.advisor.id
  }
}

output "mcp_scope" {
  value = local.mcp_scope
}

output "advisor_user_subs" {
  description = "Cognito `sub` per advisor — put these in the accounts seed (ADVISOR_SUB_1)."
  value       = { for k, u in aws_cognito_user.advisor : k => u.sub }
}

output "ecr_repositories" {
  value = { for k, r in aws_ecr_repository.svc : k => r.repository_url }
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_services" {
  value = {
    marketdata = module.marketdata.service_name
    account    = module.account.service_name
    order      = module.order.service_name
    risk       = module.risk.service_name
    execution  = module.execution.service_name
    mcp        = module.mcp.service_name
    advisor    = module.advisor.service_name
  }
}

output "frontend_bucket" {
  value = aws_s3_bucket.frontend.id
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.main.id
}

output "github_deploy_user" {
  description = "Create an access key for this user and store it as the GitHub secrets AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY."
  value       = aws_iam_user.github_deploy.name
}
