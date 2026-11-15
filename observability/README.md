# Local observability stack

See [docs/observability.md](../docs/observability.md) for the plan.

## Start / stop
```bash
cd observability
docker compose up -d      # start
docker compose down       # stop (data kept in a volume); add -v to wipe it
```

| Component | URL |
|---|---|
| Prometheus | http://localhost:9091 (9090 is taken by riskservice's gRPC) |
| Grafana | http://localhost:3001 (no login locally; 3000 is the React dev server) → Dashboards → Service Detail |

Prometheus scrapes services running on the host, so start them first, e.g.
`cd orderservice && export $(grep -v '^#' ../.env | xargs) && mvn spring-boot:run`.
Check http://localhost:9091/targets : every service you started should be **UP** (ports 8080-8086; services you did not start show DOWN, which is fine).

**Linux troubleshooting:** if the target is DOWN with `context deadline exceeded`, a host firewall
(e.g. ufw) is dropping traffic from the Docker bridge to the host. Allow it, e.g.
`sudo ufw allow from 172.16.0.0/12 to any port 8082 proto tcp` (repeat per scraped port: 8080-8086).

## First PromQL queries (Graph tab)
```promql
up                                                         # 1 = last scrape worked
sum by (uri, status) (rate(http_server_requests_seconds_count[1m]))   # request rate (R)
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))      # error rate (E)
sum(rate(http_server_requests_seconds_sum[5m]))
  / sum(rate(http_server_requests_seconds_count[5m]))                 # mean latency (D)
jvm_memory_used_bytes{area="heap"}                         # gauge
```

## Grafana
Data source and dashboards are **provisioned from files** (`grafana/provisioning`, `grafana/dashboards`), so
`docker compose down && up` restores everything. Provisioned dashboards are read-only in the UI: to change one,
edit it, use *Export → Save to file* (JSON), and replace the file in `grafana/dashboards/`.

## Azure
Prometheus and Grafana run as Container Apps next to the services (`terraform/main.tf`, `module.prometheus` and `module.grafana`).
Prometheus gets its config from `prometheus/prometheus.azure.yml.tpl`, rendered by Terraform and mounted as a secret.

Deploy (a branch deploy replaces the running prod images, so use this only for testing):
```bash
# 1. Infrastructure: new apps, execution ingress, min replicas. Needs a Grafana admin password.
cd terraform && export TF_VAR_grafana_admin_password='<choose one>' && terraform apply
# 2. Build and deploy all images from this branch
gh workflow run deploy.yml --ref observability
# 3. First time only: GitHub -> Packages -> ordermanagerai-grafana -> Package settings -> make public
#    (the other images are public; Container Apps pulls without credentials)
terraform output grafana_url      # open it, log in as admin
```
Verify: Grafana -> Explore -> Prometheus -> `up` (all 1 except riskservice, which is not scraped) -> Dashboards -> Service Detail.
To cut cost while not testing: `terraform apply -var observability_enabled=false` (removes both apps, lets services scale to zero).
