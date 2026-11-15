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
| Alertmanager | http://localhost:9093 (alerts, silences) |
| Mailpit | http://localhost:8025 (inbox for alert emails) |
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

## Alert rules
`prometheus/rules/ordermanager.yml` (recording rules + alerts, local and Azure) and `rules/local.yml` (TargetDown, local only).
Alerts show up in Prometheus -> Alerts (http://localhost:9091/alerts): *inactive -> pending (condition true, waiting out `for:`) -> firing*.
In Azure use Grafana -> Explore -> `ALERTS{alertstate="firing"}`. Nothing is sent anywhere until Alertmanager (step 8).

Unit tests use fake input series, no server needed (also run by CI, job `prometheus-rules`):
```bash
cd observability/prometheus
docker run --rm --entrypoint promtool -v "$PWD":/p prom/prometheus:v3.5.0 check rules /p/rules/ordermanager.yml /p/rules/slo.yml /p/rules/local.yml
docker run --rm --entrypoint promtool -v "$PWD":/p prom/prometheus:v3.5.0 test rules /p/tests/ordermanager.test.yml /p/tests/slo.test.yml
```
Alerts go to Alertmanager, which groups them and mails Mailpit (local only; Azure has no Alertmanager yet).
`severity=critical` mails immediately to `oncall@`, everything else goes to a slower `team@` digest. While `TargetDown` fires for a job, `HighErrorRate` for that job is muted (inhibition). Silence an alert in the Alertmanager UI (Silences -> New silence) and the repeat mails stop.
Check the config: `docker run --rm --entrypoint amtool -v "$PWD/alertmanager":/a prom/alertmanager:v0.28.1 check-config /a/alertmanager.yml`.

Try it live: stop riskservice, place an order -> `RiskFailSafeRejects` goes pending, then firing after 1 minute; `TargetDown` fires too.

## SLOs
`rules/slo.yml` defines two order SLOs (99% over 7 days): availability (not a 5xx and not a risk fail-safe reject) and latency (under 800 ms).
Recording rules compute the bad ratio over 5m/30m/1h/6h; the burn rate is that ratio divided by the 1% budget.
`*FastBurn` (critical, 1h and 5m above 14x) pages; `*SlowBurn` (warning, 6h and 30m above 6x) is a ticket-level digest mail.
Both windows must agree, so a short blip (5m high, 1h low) and an already-recovered outage (1h high, 5m low) do not page.
The *System Overview* dashboard shows SLI, error budget left and burn rates. Tests: `tests/slo.test.yml`.

**Native histograms:** not possible with the Micrometer Prometheus registry (1.16 emits classic buckets only), so the SLIs use a
classic bucket ratio with an exact `le="0.8"` bucket (`serviceLevelObjectives(800ms)` in `OrderService`). Native histograms arrive with OTLP in step 14.

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

## Traces (step 10)
Apps send spans over OTLP/HTTP to the **OTel Collector** (:4318), which batches them and forwards to **Tempo**; Grafana reads Tempo (data source *Tempo*).
Only orderservice is instrumented so far (`spring-boot-starter-opentelemetry` + `datasource-micrometer` for SQL spans). Traces are local only; Azure has no Collector/Tempo yet.
```bash
docker compose up -d
cd ../orderservice && export $(grep -v '^#' ../.env | xargs) && mvn spring-boot:run   # place an order via the UI or curl
```
Grafana -> Explore -> Tempo -> Search (service `ordermanager-order`), or TraceQL:
```
{ resource.service.name = "ordermanager-order" && span.http.route = "/api/orders" }
{ resource.service.name = "ordermanager-order" && kind = client }   # SQL / outbound spans
```
Expect one server span for `POST /api/orders` with child spans for the SQL statements. `/actuator/**` calls produce no spans.

## Grafana Cloud (steps 17-18, pulled forward)
Decision 2026-11-15: Grafana Cloud is set up right after step 11 so every later step can be tested in Azure.

**One-time, by hand** (grafana.com, free tier):
1. Create a stack. Region close to your Azure region.
2. Stack -> *Details* -> OpenTelemetry -> note the OTLP endpoint (`https://otlp-gateway-<region>.grafana.net/otlp`) and the **instance ID** (number).
3. *Access policies* -> create a policy with scopes `traces:write`, `metrics:write`, `logs:write`, `profiles:write` -> add a token.
4. Put them in the root `.env` (git-ignored):
   ```
   GRAFANA_CLOUD_OTLP_ENDPOINT=https://otlp-gateway-<region>.grafana.net/otlp
   GRAFANA_CLOUD_INSTANCE_ID=123456
   GRAFANA_CLOUD_OTLP_TOKEN=glc_...
   ```

**Local**: the Collector also forwards to the cloud (Tempo stays too):
```bash
cd observability
docker compose --env-file ../.env -f docker-compose.yml -f docker-compose.cloud.yml up -d
```
Place an order, then Grafana Cloud -> Explore -> Tempo (`grafanacloud-<stack>-traces`).

**Azure**: apps export directly (no Collector). Set, then `terraform apply` and deploy:
```bash
export TF_VAR_grafana_cloud_otlp_endpoint="https://otlp-gateway-<region>.grafana.net/otlp"
export TF_VAR_grafana_cloud_otlp_auth="Basic $(printf '%s:%s' "$GRAFANA_CLOUD_INSTANCE_ID" "$GRAFANA_CLOUD_OTLP_TOKEN" | base64 -w0)"
```
Only orderservice is wired so far; each newly instrumented service gets the same three env vars. Never commit the token.
