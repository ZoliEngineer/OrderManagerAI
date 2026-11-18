# Observability stack

Prometheus scrapes every service's `/actuator/prometheus`; Grafana shows dashboards on top. See [docs/observability.md](../docs/observability.md) for the design.

## Start / stop (local)
```bash
cd observability
docker compose up -d      # start
docker compose down       # stop (data kept in a volume); add -v to wipe it
```

| Component | URL |
|---|---|
| Prometheus | http://localhost:9091 (9090 is taken by riskservice's gRPC) |
| Grafana | http://localhost:3001 (no login locally; 3000 is the React dev server) → Dashboards |

Prometheus scrapes services running on the host, so start them first, e.g.
`cd orderservice && export $(grep -v '^#' ../.env | xargs) && mvn spring-boot:run`.
Check http://localhost:9091/targets: every service you started should be **UP** (ports 8080-8086). Services you did not start show DOWN and `TargetDown` fires for them, which is expected.

**Linux troubleshooting:** if a target is DOWN with `context deadline exceeded`, a host firewall (e.g. ufw) is dropping traffic from the Docker bridge to the host. Allow it, e.g.
`sudo ufw allow from 172.16.0.0/12 to any port 8082 proto tcp` (repeat per scraped port: 8080-8086).

## First PromQL queries (Graph tab)
```promql
up                                                                    # 1 = last scrape worked
sum by (uri, status) (rate(http_server_requests_seconds_count[1m]))   # request rate
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))      # error rate
sum(rate(http_server_requests_seconds_sum[5m]))
  / sum(rate(http_server_requests_seconds_count[5m]))                 # mean latency
jvm_memory_used_bytes{area="heap"}                                    # gauge
```

## Alert rules
`prometheus/rules/ordermanager.yml` has recording rules and alerts (TargetDown, HighErrorRate, RiskFailSafeRejects, RingBufferPressure, StalePrices). The same file is used locally and on AWS.
Alerts show up in Prometheus -> Alerts (http://localhost:9091/alerts): *inactive -> pending (condition true, waiting out `for:`) -> firing*. On AWS use Grafana -> Explore -> `ALERTS{alertstate="firing"}`. Nothing is sent anywhere.

Unit tests use fake input series, no server needed (also run by CI, job `prometheus-rules`):
```bash
cd observability/prometheus
docker run --rm --entrypoint promtool -v "$PWD":/p prom/prometheus:v3.5.0 check rules /p/rules/ordermanager.yml
docker run --rm --entrypoint promtool -v "$PWD":/p prom/prometheus:v3.5.0 test rules /p/tests/ordermanager.test.yml
```
Try it live: stop riskservice and place an order -> `RiskFailSafeRejects` goes pending, then firing after 1 minute; `TargetDown` fires for riskservice too.

## Grafana
Data source and dashboards are **provisioned from files** (`grafana/provisioning`, `grafana/dashboards`), so
`docker compose down && up` restores everything. Provisioned dashboards are read-only in the UI: to change one,
edit it, use *Export → Save to file* (JSON), and replace the file in `grafana/dashboards/`.

## AWS (off by default)
Prometheus and Grafana run as two more ECS services next to the app (`terraform/observability.tf`), only when `observability_enabled = true`. Like every service they start at desired count 0 and cost about $0.04/hour while running.

- **Prometheus** is private. It scrapes `<service>.ordermanagerai.local` through Cloud Map, using `prometheus/prometheus.aws.yml` (same job names as the local file) baked into its image by `prometheus/Dockerfile` together with the rules. Data is on the task's ephemeral storage and is lost on restart.
- **Grafana** is public at `<cloudfront url>/grafana/`, behind its own login (user `admin`, the password you set). Provisioning and dashboards are baked into its image; `PROMETHEUS_URL` points at `http://prometheus.ordermanagerai.local:9090`.
- **Risk is scraped** here (its actuator is on 8083, inside the VPC). The advisor's metrics are at `/advisor/actuator/prometheus` because it runs under the `/advisor` context path; that path is blocked at the load balancer for the public.

```bash
# 1. Infrastructure. Needs GRAFANA_ADMIN_PASSWORD (at least 12 characters) in .env; tf-env.sh exports it for Terraform.
cd terraform && source ./tf-env.sh && terraform apply -var observability_enabled=true
# 2. Images: deploy.yml builds and pushes the prometheus and grafana images on every deploy (run it once after step 1)
gh workflow run deploy.yml
# 3. Start everything (tasks and ALB), then open Grafana and log in as admin
cd .. && ./scripts/env.sh up
terraform -chdir=terraform output grafana_url
```
Verify: Grafana -> Explore -> Prometheus -> `up` (all 1) -> Dashboards -> Service Detail.
Turn it off again: `scripts/env.sh down` stops everything; `terraform apply` with `observability_enabled=false` removes the two services.

**Why failures happen, on AWS:** `aws logs tail /ecs/ordermanagerai-prod-<service> --follow` (service = order, risk, ...). Platform events such as image pull failures, restarts and failed health checks: `aws ecs describe-services --cluster ordermanagerai-prod --services ordermanagerai-prod-<service> --query 'services[0].events[:10]'`.
