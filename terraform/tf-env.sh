#!/usr/bin/env bash
# Source this (don't execute it) before terraform plan/apply:
#   cd terraform && source ./tf-env.sh && terraform plan
# Maps the repo-root .env names onto Terraform's TF_VAR_* names. Prints no values.

_env="$(dirname "${BASH_SOURCE[0]}")/../.env"
if [ ! -f "$_env" ]; then echo "tf-env.sh: $_env not found" >&2; return 1; fi

# Read values into this script's own variables only: nothing from .env is exported except the TF_VAR_* below
# (exporting everything would also leak GITHUB_TOKEN etc. into this shell and break `gh`).
_get() { grep -E "^$1=" "$_env" | tail -n1 | cut -d= -f2- | sed -E "s/^['\"]//; s/['\"]\$//"; }
REDIS_PASSWORD=$(_get REDIS_PASSWORD)
KAFKA_CLUSTER_API_KEY=$(_get KAFKA_CLUSTER_API_KEY)
KAFKA_CLUSTER_API_SECRET=$(_get KAFKA_CLUSTER_API_SECRET)
SPRING_DATASOURCE_PASSWORD=$(_get SPRING_DATASOURCE_PASSWORD)
FINNHUB_API_KEY=$(_get FINNHUB_API_KEY)
ANTHROPIC_API_KEY=$(_get ANTHROPIC_API_KEY)
GRAFANA_ADMIN_PASSWORD=$(_get GRAFANA_ADMIN_PASSWORD)

export TF_VAR_redis_password="$REDIS_PASSWORD"
export TF_VAR_kafka_cluster_api_key="$KAFKA_CLUSTER_API_KEY"
export TF_VAR_kafka_cluster_api_secret="$KAFKA_CLUSTER_API_SECRET"
export TF_VAR_supabase_db_password="$SPRING_DATASOURCE_PASSWORD"   # orderservice's DB (Supabase)
# Despite the name, accountservice also connects to Supabase as ordermanager_user: same password as orderservice.
export TF_VAR_neon_db_password="$SPRING_DATASOURCE_PASSWORD"
export TF_VAR_finnhub_api_key="${FINNHUB_API_KEY:-}"
export TF_VAR_anthropic_api_key="${ANTHROPIC_API_KEY:-}"
# Only needed with observability_enabled=true
export TF_VAR_grafana_admin_password="${GRAFANA_ADMIN_PASSWORD:-}"
unset _env REDIS_PASSWORD KAFKA_CLUSTER_API_KEY KAFKA_CLUSTER_API_SECRET SPRING_DATASOURCE_PASSWORD FINNHUB_API_KEY ANTHROPIC_API_KEY GRAFANA_ADMIN_PASSWORD
unset -f _get
