# Observability — Micrometer, Prometheus, Grafana

Goal: see whether every service is **up**, **responding**, and, when it is not, **why**. Nothing more. It must be cheap to run and easy to maintain.

> **Hosting note (2026-11):** the "Azure" parts below were written for Azure Container Apps. The application now runs on AWS ECS Fargate (see [architecture.md](architecture.md#aws-deployment)). The in-service instrumentation (Micrometer, `/actuator/prometheus`, business metrics, health, graceful shutdown) and the local Prometheus + Grafana stack are cloud-neutral and still apply. The cloud-hosted Prometheus/Grafana (Terraform `observability_enabled`) has **not** been ported to AWS yet, logs are in CloudWatch Logs (`aws logs tail /ecs/<service>`) instead of `az containerapp logs`, and ECS tasks do not scale to zero (desired count 1/0), so the cold-start notes do not apply.

> **History.** A larger design (OpenTelemetry traces, Loki logs, Tempo, OTLP push, Grafana Cloud, SLO burn-rate alerts, Alertmanager) was built and then removed as too much for this project. It is preserved at git tag `observability-full` (the commit before the simplification) if you want to look at or revive any of it.

## Architecture

```
 Java services ── Micrometer ── GET /actuator/prometheus ──▶ Prometheus ──▶ Grafana (dashboards)
 (marketdata, account, order,        (pull, every 15-30 s)       │
  mcp, advisor, execution)                                       └─ alert rules (visible in the UI)
 Risk: not scraped in Azure (gRPC-only ingress); seen through order's metrics instead.
```

| Piece | Choice | Why |
|---|---|---|
| Instrumentation | Micrometer + `micrometer-registry-prometheus` in every service | Built into Spring Boot; HTTP, JVM, Hikari and Kafka metrics for free |
| Collection | Prometheus **pull** | Simple, standard, one config file |
| Dashboards | Grafana, JSON in `observability/grafana/dashboards/`, provisioned from files | Survives restarts; reviewable in git |
| Alerts | Prometheus rules (`observability/prometheus/rules/ordermanager.yml`), tested with `promtool` in CI | Visible in the Prometheus *Alerts* page and in Grafana; **nothing is sent anywhere** |
| Logs | stdout. `docker logs` locally, `az containerapp logs show` in Azure | No log backend to run or pay for |
| Tracing / OTel | not used | Not needed to answer "up / responding / why" |

Common rules, in `application.properties` of every service:
- `management.endpoints.web.exposure.include=health,prometheus`
- `management.metrics.tags.application=${spring.application.name}`
- `/actuator/**` requests are dropped from HTTP metrics by a Prometheus `metric_relabel_configs` rule (one place), so scrapes and probes do not skew rates and latency.
- No ids (account, order, user) as metric tags.

## How to answer the three questions

| Question | Where to look |
|---|---|
| Is it up? | `up` / **Target up** panel on *System Overview*; `TargetDown` alert |
| Is it responding? | *System Overview* (request rate, 5xx ratio, mean latency per service), *Service Detail* (per endpoint, JVM, Hikari, Kafka); `HighErrorRate` alert |
| Why is it failing? | 1. Business metrics with outcome and reason tags (below). 2. `/actuator/health` shows which dependency is down. 3. The service log, which carries the full stack trace. |

## Business metrics

| Service | Metrics |
|---|---|
| order | `orders.placed{side,type,outcome=accepted\|rejected\|failsafe}`, `orders.place.latency`, `orders.cancelled{outcome}`, `orders.risk.failsafe{cause}` (gRPC status), `orders.risk.call.latency{outcome}`, `grpc.client` timers |
| risk | `risk.checks{decision,reason}`, `risk.evaluate.latency`, `risk.buying_power.lookups{result}`, `risk.price.lookups{result}`, `risk.price.cache.size`, `risk.price.kafka.delay`, `grpc.server` timers |
| marketdata | `marketdata.ticks`, `marketdata.stocks.tracked`, `marketdata.websocket.sessions`, `marketdata.publish{target,outcome}` |
| execution | `execution.events.consumed{type}`, `execution.events.failed`, `execution.ringbuffer.remaining`, `execution.ringbuffer.wait`, `execution.kafka.delay` |
| account | `account.buying_power.cache{operation,result}`, `account.cache.warmed.accounts` |
| mcp | `mcp.tool.calls{tool,outcome}` |
| advisor | `gen_ai.client.token.usage{model,type}` (Claude API spend; no prompt text) |

## Alerts

| Alert | Condition | Severity |
|---|---|---|
| TargetDown | `up == 0` for 1 m | critical |
| HighErrorRate | per service, 5xx > 5 % for 5 m **and** > 0.1 req/s | critical |
| RiskFailSafeRejects | `orders.placed{outcome="failsafe"}` > 0 for 1 m. This is also how a **Risk outage** is detected. | critical |
| RingBufferPressure | execution ring buffer < 20 % free | warning |
| StalePrices | no ticks for 5 m (false positive with Finnhub while the US market is closed) | warning |

## Where it runs

- **Locally (the main way, free):** `cd observability && docker compose up -d`. Prometheus on :9091 and Grafana on :3001. Details in [observability/README.md](../observability/README.md).
- **Azure (off by default):** Prometheus and Grafana as two Container Apps, behind the Terraform variable `observability_enabled` (default `false`). Turning it on also pins the scraped services to `min_replicas = 1`, because a scrape would wake a scale-to-zero app anyway. That is always-on cost, so enable it only while testing, then turn it off.
  - Prometheus has no persistent storage (7-day retention on the container filesystem). A restart loses history.
  - `/actuator/prometheus` is unauthenticated on the public services. Metrics carry no ids or PII, but add a scrape token before this is more than a test environment.

## Why did my order fail the risk check?
An order that comes back rejected has one of these reasons. The first column is what the API returns.

| Reason | Meaning | Where to look |
|---|---|---|
| `RISK_UNAVAILABLE` | The order service got no answer from Risk (fail-safe). The message ends with the gRPC status and elapsed ms. | `orders.risk.failsafe{cause}`: `DEADLINE_EXCEEDED` = slow or **cold start** (Risk runs at `min_replicas = 0`, and the deadline is 800 ms); `UNAVAILABLE` = down or unreachable; `INTERNAL` = Risk threw, see its log |
| `BUYING_POWER_UNAVAILABLE` | Risk answered, but the account's Redis key `account:buying-power:<id>` is missing (24 h TTL, written only by the Account Service) | `risk.buying_power.lookups{result="miss"}`; Risk log `Buying power not found in cache` |
| `PRICE_UNAVAILABLE` | Market order and no cached price for the ticker | `risk.price.lookups{result="miss"}`, `risk.price.cache.size` |
| `INSUFFICIENT_FUNDS` | Order value exceeds the **cached** buying power, which can be stale | The Risk decision log line carries both numbers in `message` |

Every Risk decision is one log line with the `decisionId` that the order service also logs (`Order rejected by risk check: ... decisionId=...`), so the two services' logs can be matched by that id:
```bash
az containerapp logs show -n ordermanagerai-prod-risk-svc  -g <resource-group> --tail 200 | grep "Risk decision"
az containerapp logs show -n ordermanagerai-prod-order-svc -g <resource-group> --tail 200 | grep -E "Risk call failed|rejected by risk"
```
Risk is not scraped in Azure, so there the order-side metrics (`orders.risk.*`) and the logs are what you have. Locally, all of them are in Prometheus. The *Order Flow* dashboard has panels for fail-safe cause, call latency and price lookups.

## Failure diagnostics built into the services

- **Stack traces in logs:** exceptions are passed to the logger as the last argument, never through `e.toString()` / `getMessage()` or a `{}` placeholder, so the frames are kept. The exception is the Finnhub calls with the API key in the URL: those log the exception type and a sanitized message (`sanitizeMessage`), not the raw exception.
- **Log levels:** a risk rejection is INFO (an expected business outcome), the Risk fail-safe is WARN with a stack trace, ERROR means an engineer must act.
- **Health:** `/actuator/health` lists each dependency's status (`show-components=always`: db, redis, ...), so a DOWN dependency is visible without connection details. There are no liveness/readiness groups because nothing in Azure consumes them (no container probes).
- **JVM:** `-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError` in every Dockerfile. The default heap on a 0.5 GiB container is small, and an OOM now ends the process instead of leaving it half-working.
- **Shutdown:** `server.shutdown=graceful` with a 20 s phase timeout, so in-flight requests finish on scale-down and deploys.

## Known gaps
- Execution never wakes in Azure (`min_replicas = 0`, no ingress, no scale rule): orders are accepted and not matched until it is running. A Kafka (KEDA) scale rule on `order.created` would fix it.
- `OrderEventConsumer` logs and counts failed events (`execution.events.failed`) but does not dead-letter them.
- No Disruptor drain on shutdown.
