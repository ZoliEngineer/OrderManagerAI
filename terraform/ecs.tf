resource "aws_ecs_cluster" "main" {
  name = local.prefix
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

locals {
  ns = aws_service_discovery_private_dns_namespace.main.name

  image = { for s in local.services : s => "${aws_ecr_repository.svc[s].repository_url}:latest" }

  # Identity-provider settings shared by every service that validates JWTs (see JwtSecurityConfig).
  oidc_env = {
    OIDC_ISSUER_URI = local.oidc_issuer
    OIDC_CLIENT_IDS = "${aws_cognito_user_pool_client.web.id},${aws_cognito_user_pool_client.advisor.id}"
  }

  # Behind the ALB the apps must trust X-Forwarded-* (correct https URLs in MCP/OAuth metadata).
  http_env = merge(local.oidc_env, {
    SERVER_FORWARD_HEADERS_STRATEGY = "framework"
    CORS_ALLOWED_ORIGINS            = local.app_origin
  })

  kafka_secrets = {
    KAFKA_CLUSTER_API_KEY    = local.secret_ref["KAFKA_CLUSTER_API_KEY"]
    KAFKA_CLUSTER_API_SECRET = local.secret_ref["KAFKA_CLUSTER_API_SECRET"]
  }

  common = {
    region                 = var.region
    cluster_arn            = aws_ecs_cluster.main.arn
    execution_role_arn     = aws_iam_role.task_execution.arn
    subnet_ids             = aws_subnet.public[*].id
    security_group_ids     = [aws_security_group.services.id]
    vpc_id                 = aws_vpc.main.id
    discovery_namespace_id = aws_service_discovery_private_dns_namespace.main.id
    cpu_architecture       = var.cpu_architecture
    use_spot               = var.use_spot
    desired_count          = var.initial_desired_count
    alb_enabled            = var.alb_enabled
    listener_arn           = one(aws_lb_listener.https[*].arn)
  }
}

module "marketdata" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-marketdata"
  image  = local.image["marketdata"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 8080
  discovery_name = "marketdata"
  environment    = local.http_env
  secrets = merge(
    {
      REDIS_PASSWORD = local.secret_ref["REDIS_PASSWORD"]
    },
    local.kafka_secrets,
    var.finnhub_api_key != "" ? { FINNHUB_API_KEY = local.secret_ref["FINNHUB_API_KEY"] } : {}
  )
  alb_rules = [{
    priority = 10
    hosts    = ["api.${var.domain_name}"]
    paths    = ["/api/marketdata*", "/api/market-status*", "/ws/*"]
  }]
}

module "account" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-account"
  image  = local.image["account"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 8081
  environment    = local.http_env
  secrets = {
    SPRING_DATASOURCE_PASSWORD = local.secret_ref["DB_PASSWORD"]
    REDIS_PASSWORD             = local.secret_ref["REDIS_PASSWORD"]
  }
  alb_rules = [{
    priority = 20
    hosts    = ["api.${var.domain_name}"]
    paths    = ["/api/accounts*"]
  }]
}

module "order" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-order"
  image  = local.image["order"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 8082
  discovery_name = "order"
  environment = merge(local.http_env, {
    APP_SERVICES_MARKET_DATA_SERVICE_BASE_URL = "http://marketdata.${local.ns}:8080"
    GRPC_CLIENT_RISK_SERVICE_ADDRESS          = "dns:///risk.${local.ns}:9090"
    GRPC_CLIENT_RISK_SERVICE_NEGOTIATION_TYPE = "plaintext"
  })
  secrets = merge(
    {
      SPRING_DATASOURCE_PASSWORD = local.secret_ref["DB_PASSWORD"]
      REDIS_PASSWORD             = local.secret_ref["REDIS_PASSWORD"]
    },
    local.kafka_secrets
  )
  alb_rules = [{
    priority = 30
    hosts    = ["api.${var.domain_name}"]
    paths    = ["/api/orders*"]
  }]
}

# gRPC on 9090, reachable only inside the VPC (risk.<ns>:9090); health on the 8083 actuator.
module "risk" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-risk"
  image  = local.image["risk"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 9090
  health_port    = 8083
  discovery_name = "risk"
  environment    = local.oidc_env
  secrets = merge(
    { REDIS_PASSWORD = local.secret_ref["REDIS_PASSWORD"] },
    local.kafka_secrets
  )
}

# Kafka-only; no listener.
module "execution" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-execution"
  image  = local.image["execution"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  secrets = local.kafka_secrets
}

# MCP server for AI agents: stateless, forwards the caller's token to order and market data.
module "mcp" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-mcp"
  image  = local.image["mcp"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 8084
  discovery_name = "mcp"
  environment = merge(local.http_env, {
    ORDER_SERVICE_URL = "http://order.${local.ns}:8082"
    MARKETDATA_URL    = "http://marketdata.${local.ns}:8080"
    MCP_PUBLIC_URL    = local.api_origin
    MCP_SCOPE         = local.mcp_scope
  })
  alb_rules = [{
    priority = 40
    hosts    = ["api.${var.domain_name}"]
    paths    = ["/mcp*", "/.well-known/*"]
  }]
}

# Trading advisor: chat UI + Claude agent loop; calls the MCP service with the user's token.
module "advisor" {
  source = "./modules/ecs-service"
  name   = "${local.prefix}-advisor"
  image  = local.image["advisor"]

  region                 = local.common.region
  cluster_arn            = local.common.cluster_arn
  execution_role_arn     = local.common.execution_role_arn
  subnet_ids             = local.common.subnet_ids
  security_group_ids     = local.common.security_group_ids
  vpc_id                 = local.common.vpc_id
  discovery_namespace_id = local.common.discovery_namespace_id
  cpu_architecture       = local.common.cpu_architecture
  use_spot               = local.common.use_spot
  desired_count          = local.common.desired_count
  alb_enabled            = local.common.alb_enabled
  listener_arn           = local.common.listener_arn

  container_port = 8085
  environment = merge(local.http_env, {
    MCP_SERVER_URL    = "http://mcp.${local.ns}:8084/mcp"
    MCP_SCOPE         = local.mcp_scope
    OIDC_UI_CLIENT_ID = aws_cognito_user_pool_client.advisor.id
    OIDC_LOGOUT_URL   = "https://${aws_cognito_user_pool_domain.main.domain}.auth.${var.region}.amazoncognito.com/logout?client_id=${aws_cognito_user_pool_client.advisor.id}&logout_uri=${local.advisor_origin}/"
  })
  secrets = { ANTHROPIC_API_KEY = local.secret_ref["ANTHROPIC_API_KEY"] }
  alb_rules = [{
    priority = 50
    hosts    = ["advisor.${var.domain_name}"]
  }]
}
