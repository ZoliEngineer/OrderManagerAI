# Optional Prometheus + Grafana (observability_enabled = true). Both start at desired count 0 like the app services;
# scripts/env.sh up (or the env-scale workflow) starts them. Config and dashboards are baked into the images
# (observability/prometheus/Dockerfile, observability/grafana/Dockerfile) and pushed by deploy.yml.
#
#   Prometheus: private, scrapes <service>.ordermanagerai.local through Cloud Map; data on ephemeral storage.
#   Grafana:    public at <cloudfront url>/grafana/ behind its own login (admin / grafana_admin_password).

# Grafana is reachable from the internet: refuse to create it without a real password.
resource "terraform_data" "observability_guard" {
  lifecycle {
    precondition {
      condition     = !var.observability_enabled || length(var.grafana_admin_password) >= 12
      error_message = "observability_enabled = true needs grafana_admin_password (>= 12 characters), e.g. export TF_VAR_grafana_admin_password or GRAFANA_ADMIN_PASSWORD in .env with tf-env.sh."
    }
  }
}

module "prometheus" {
  count  = var.observability_enabled ? 1 : 0
  source = "./modules/ecs-service"
  name   = "${local.prefix}-prometheus"
  image  = local.image["prometheus"]
  cpu    = 512
  memory = 1024

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

  container_port = 9090 # no ALB route: only Grafana (through Cloud Map) talks to it
  health_path    = "/-/healthy"
  discovery_name = "prometheus"

  depends_on = [terraform_data.observability_guard]
}

module "grafana" {
  count  = var.observability_enabled ? 1 : 0
  source = "./modules/ecs-service"
  name   = "${local.prefix}-grafana"
  image  = local.image["grafana"]
  cpu    = 256
  memory = 512

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

  container_port = 3000
  health_path    = "/grafana/api/health"

  environment = {
    PROMETHEUS_URL                = "http://prometheus.${local.ns}:9090"
    GF_SERVER_ROOT_URL            = "${local.app_origin}/grafana/"
    GF_SERVER_SERVE_FROM_SUB_PATH = "true"
    GF_SECURITY_ADMIN_USER        = "admin"
    GF_AUTH_ANONYMOUS_ENABLED     = "false"
    GF_USERS_ALLOW_SIGN_UP        = "false"
  }
  secrets = { GF_SECURITY_ADMIN_PASSWORD = lookup(local.secret_ref, "GRAFANA_ADMIN_PASSWORD", "") }

  origin_verify = random_password.origin_verify.result
  alb_rules = [{
    priority = 60
    paths    = ["/grafana*"]
  }]

  depends_on = [terraform_data.observability_guard]
}
