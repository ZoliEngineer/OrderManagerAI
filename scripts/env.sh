#!/usr/bin/env bash
# Switch the whole AWS environment on or off to control cost. Run with admin AWS credentials.
#   scripts/env.sh up     create the ALB, then start every service
#   scripts/env.sh down   stop every service, then remove the ALB (~$22/month saved)
# Costs while "down": Route 53 zone, Secrets Manager, ECR storage — a few dollars a month.
set -euo pipefail

TF_DIR="$(cd "$(dirname "$0")/../terraform" && pwd)"
REGION="${AWS_REGION:-us-east-2}"
CLUSTER="$(terraform -chdir="$TF_DIR" output -raw ecs_cluster)"

scale() {
  local count="$1"
  for svc in $(aws ecs list-services --cluster "$CLUSTER" --region "$REGION" --query 'serviceArns[]' --output text); do
    aws ecs update-service --cluster "$CLUSTER" --region "$REGION" --service "$svc" --desired-count "$count" \
      --query 'service.[serviceName,desiredCount]' --output text
  done
}

wait_stable() {
  # shellcheck disable=SC2046
  aws ecs wait services-stable --cluster "$CLUSTER" --region "$REGION" \
    --services $(aws ecs list-services --cluster "$CLUSTER" --region "$REGION" --query 'serviceArns[]' --output text)
}

case "${1:-}" in
  up)
    # Enabling the ALB re-creates the ECS services (their load balancer attachment changes), so start them afterwards.
    terraform -chdir="$TF_DIR" apply -auto-approve -var alb_enabled=true
    scale 1
    wait_stable
    echo "Environment is up: $(terraform -chdir="$TF_DIR" output -raw frontend_url)"
    ;;
  down)
    scale 0
    wait_stable
    terraform -chdir="$TF_DIR" apply -auto-approve -var alb_enabled=false
    echo "Environment is down (ALB removed, no tasks running)."
    ;;
  *)
    echo "usage: $0 up|down" >&2
    exit 1
    ;;
esac
