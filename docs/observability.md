# Observability — Micrometer, Prometheus, Grafana

Goal: see whether every service is **up**, **responding**, and, when it is not, **why**. Nothing more. It must be cheap to run and easy to maintain.

> **Hosting note (2026-11):** the application runs on AWS ECS Fargate (see [architecture.md](architecture.md#aws-deployment)). Prometheus and Grafana can run there too as two optional ECS services (Terraform `observability_enabled`, see "Where it runs"). Logs are in CloudWatch Logs (`aws logs tail /ecs/<service>`). ECS tasks do not scale to zero by load (desired count 1/0 for the whole environment), so there are no cold-start effects from scale-to-zero.

> **History.** A larger design (OpenTelemetry traces, Loki logs, Tempo, OTLP push, Grafana Cloud, SLO burn-rate alerts, Alertmanager) was built and then removed as too much for this project. It is preserved at git tag `observability-full` (the commit before the simplification) if you want to look at or revive any of it.

## Architecture

```
 Java services ── Micrometer ── GET /actuator/prometheus ──▶ Prometheus ──▶ Grafana (dashboards)
 (marketdata, account, order,        (pull, every 15-30 s)       │
  mcp, advisor, execution)                                       └─ alert rules (visible in the UI)
 Risk: scraped on its actuator port 8083 (inside the VPC on AWS); also seen through order's metrics.
```

| Piece | Choice | Why |
|---|---|---|
| Instrumentation | Micrometer + `micrometer-registry-prometheus` in every service | Built into Spring Boot; HTTP, JVM, Hikari and Kafka metrics for free |
| Collection | Prometheus **pull** | Simple, standard, one config file |
| Dashboards | Grafana, JSON in `observability/grafana/dashboards/`, provisioned from files | Survives restarts; reviewable in git |
| Alerts | Prometheus rules (`observability/prometheus/rules/ordermanager.yml`), tested with `promtool` in CI | Visible in the Prometheus *Alerts* page and in Grafana; **nothing is sent anywhere** |
| Logs | stdout. `docker logs` locally, CloudWatch Logs (`aws logs tail`) on AWS | No log backend to run or pay for |
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
- **AWS (off by default):** Prometheus and Grafana as two ECS services, behind the Terraform variable `observability_enabled` (default `false`). They start at desired count 0 like the others and cost about $0.04/hour while running. Setup and commands are in [observability/README.md](../observability/README.md#aws-off-by-default).
  - Prometheus scrapes the services inside the VPC by Cloud Map name, with no persistent storage (2-day retention on the task's ephemeral disk). A restart loses history.
  - Grafana is public at `<cloudfront url>/grafana/` behind its own login (admin + the password from `grafana_admin_password`).
  - `/actuator/prometheus` is unauthenticated on the services, but only the VPC can reach it: the load balancer routes no actuator path, and explicitly blocks `/advisor/actuator*`, the one actuator that sits under a routed path. Metrics carry no ids or PII.

## Why did my order fail the risk check?
An order that comes back rejected has one of these reasons. The first column is what the API returns.

| Reason | Meaning | Where to look |
|---|---|---|
| `RISK_UNAVAILABLE` | The order service got no answer from Risk (fail-safe). The message ends with the gRPC status and elapsed ms. | `orders.risk.failsafe{cause}`: `DEADLINE_EXCEEDED` = slow or **just started** (a freshly started Fargate task needs time for the JVM, and the deadline is 800 ms); `UNAVAILABLE` = down or unreachable; `INTERNAL` = Risk threw, see its log |
| `BUYING_POWER_UNAVAILABLE` | Risk answered, but the account's Redis key `account:buying-power:<id>` is missing (24 h TTL, written only by the Account Service) | `risk.buying_power.lookups{result="miss"}`; Risk log `Buying power not found in cache` |
| `PRICE_UNAVAILABLE` | Market order and no cached price for the ticker | `risk.price.lookups{result="miss"}`, `risk.price.cache.size` |
| `INSUFFICIENT_FUNDS` | Order value exceeds the **cached** buying power, which can be stale | The Risk decision log line carries both numbers in `message` |

Every Risk decision is one log line with the `decisionId` that the order service also logs (`Order rejected by risk check: ... decisionId=...`), so the two services' logs can be matched by that id:
```bash
aws logs tail /ecs/ordermanagerai-prod-risk  --since 1h | grep "Risk decision"
aws logs tail /ecs/ordermanagerai-prod-order --since 1h | grep -E "Risk call failed|rejected by risk"
```
Risk is scraped locally and on AWS (its actuator is on 8083, inside the VPC), and the order-side metrics (`orders.risk.*`) show how Risk behaves as the order service sees it. The *Order Flow* dashboard has panels for fail-safe cause, call latency and price lookups.

## Failure diagnostics built into the services

- **Stack traces in logs:** exceptions are passed to the logger as the last argument, never through `e.toString()` / `getMessage()` or a `{}` placeholder, so the frames are kept. The exception is the Finnhub calls with the API key in the URL: those log the exception type and a sanitized message (`sanitizeMessage`), not the raw exception.
- **Log levels:** a risk rejection is INFO (an expected business outcome), the Risk fail-safe is WARN with a stack trace, ERROR means an engineer must act.
- **Health:** `/actuator/health` lists each dependency's status (`show-components=always`: db, redis, ...), so a DOWN dependency is visible without connection details. The liveness/readiness groups are enabled (`management.endpoint.health.probes.enabled=true`). ECS container and load-balancer health checks use `/actuator/health/liveness`, so an outage of Redis, Postgres or Kafka does not get tasks killed and restarted in a loop.
- **JVM:** `-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError` in every Dockerfile. The default heap on a 0.5 GiB container is small, and an OOM now ends the process instead of leaving it half-working.
- **Shutdown:** `server.shutdown=graceful` with a 20 s phase timeout, so in-flight requests finish on scale-down and deploys.

## Known gaps
- Execution only runs while the environment is up (desired count 1): orders accepted while it is stopped are matched once it starts again. There is no load-based scaling for it.
- `OrderEventConsumer` logs and counts failed events (`execution.events.failed`) but does not dead-letter them.
- No Disruptor drain on shutdown.
