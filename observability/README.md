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
`prometheus/rules/ordermanager.yml` has recording rules and alerts (TargetDown, HighErrorRate, RiskFailSafeRejects, RingBufferPressure, StalePrices). The same file is used locally and in Azure.
Alerts show up in Prometheus -> Alerts (http://localhost:9091/alerts): *inactive -> pending (condition true, waiting out `for:`) -> firing*. In Azure use Grafana -> Explore -> `ALERTS{alertstate="firing"}`. Nothing is sent anywhere.

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

## Azure (off by default)
Prometheus and Grafana run as Container Apps next to the services (`terraform/main.tf`, `module.prometheus` and `module.grafana`), only when `observability_enabled=true`. That also keeps the scraped services at one replica (always-on cost), so turn it on for testing and off again afterwards.
Prometheus gets its config from `prometheus/prometheus.azure.yml.tpl`, rendered by Terraform and mounted as a secret. Risk is not scraped (gRPC-only ingress).

```bash
# 1. Infrastructure: Prometheus + Grafana apps, scraped services at min 1 replica. Needs a Grafana admin password.
cd terraform && export TF_VAR_grafana_admin_password='<choose one>' && terraform apply -var observability_enabled=true
# 2. Build and deploy all images (a branch deploy replaces the running prod images, so use this only for testing)
gh workflow run deploy.yml --ref <branch>
# 3. First time only: GitHub -> Packages -> ordermanagerai-grafana -> Package settings -> make public
terraform output grafana_url      # open it, log in as admin
```
Verify: Grafana -> Explore -> Prometheus -> `up` (all 1 except riskservice, which is not scraped) -> Dashboards -> Service Detail.
Turn it off again: `terraform apply` (default `observability_enabled=false` removes both apps and lets services scale to zero).

**Why failures happen, in Azure:** `az containerapp logs show -n <app> -g <resource-group> --follow` (or `--type system` for platform events such as image pull or restarts).
