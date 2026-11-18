# One JSON secret (~$0.40/month) holding every credential; tasks pick keys with arn:key::.
locals {
  secret_values = merge(
    {
      REDIS_PASSWORD           = var.redis_password
      KAFKA_CLUSTER_API_KEY    = var.kafka_cluster_api_key
      KAFKA_CLUSTER_API_SECRET = var.kafka_cluster_api_secret
      DB_PASSWORD              = var.db_password
      ANTHROPIC_API_KEY        = var.anthropic_api_key
    },
    # A present-but-empty key would switch marketdata to the live Finnhub feed, so only add it when set.
    var.finnhub_api_key != "" ? { FINNHUB_API_KEY = var.finnhub_api_key } : {},
    var.observability_enabled ? { GRAFANA_ADMIN_PASSWORD = var.grafana_admin_password } : {}
  )

  secret_ref = { for k, _ in local.secret_values : k => "${aws_secretsmanager_secret.app.arn}:${k}::" }
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.prefix}/app"
  recovery_window_in_days = 0 # learning project: allow immediate delete and re-create
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id     = aws_secretsmanager_secret.app.id
  secret_string = jsonencode(local.secret_values)
}
