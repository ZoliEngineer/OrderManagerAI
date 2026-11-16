# Observability — Micrometer, Prometheus, Grafana

Goal: see whether every service is **up**, **responding**, and, when it is not, **why**. Nothing more. It must be cheap to run and easy to maintain.

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
| order | `orders.placed{side,type,outcome=accepted\|rejected\|failsafe}`, `orders.place.latency`, `orders.cancelled{outcome}`, `grpc.client` timers (Risk calls) |
| risk | `risk.checks{decision,reason}`, `risk.evaluate.latency`, `risk.buying_power.lookups{result}`, `risk.price.cache.size`, `risk.price.kafka.delay`, `grpc.server` timers |
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

## Remaining work

| Item | Why it matters for "why is it failing" |
|---|---|
| Stack traces kept in logs. A Throwable passed into a `{}` placeholder, or logged via `e.toString()` / `getMessage()`, loses its frames (e.g. `RiskGrpcClient`, `FinnhubMarketStatusService`, `AdvisorAgent`, `HttpMcpToolGateway`, `FinnhubPriceSource`). | The log is the only place the reason lives |
| Log levels: a risk rejection is INFO, a fail-safe is WARN, WS disconnects are DEBUG. `OrderEventConsumer` should not swallow failures silently (it counts them in `execution.events.failed`). | ERROR then means "an engineer must act" |
| Health groups: readiness includes db / redis / kafka, liveness includes no external dependencies. | `/actuator/health` tells you which dependency is down |
| JVM flags in the Dockerfiles: `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`. | Default heap on 0.5 GiB containers is about 128 MB, so OOMs look like random failures |
| Graceful shutdown (`server.shutdown=graceful`) and Disruptor drain. | No lost requests on scale-down |
| Execution never wakes in Azure (`min_replicas = 0`, no ingress, no scale rule). A Kafka scale rule on `order.created` would fix it. | Otherwise orders are accepted and never matched |
