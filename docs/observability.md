# Observability — OpenTelemetry, Prometheus, Grafana

## Context
Today nobody can see what the system is doing. Services expose only `/actuator/health`. There are no metrics and no tracing, logs are plain text on stdout, and the only "alert" is the post-deploy smoke test. One order crosses Browser → Order (REST) → Risk (gRPC) → Kafka → Execution (Disruptor), so when it fails nobody can tell *where*.

Goals:
- **Alerts** on errors and downtime (email, plus phone push for critical alerts).
- **Exact error location**: service, operation, exception, stack frame and the full trace across services.
- **Performance and usage metrics**, with dashboards, KPIs and SLOs.

This is also a **training project**. It is implemented bottom-up, one technology at a time (see [Roadmap](#roadmap)).

## Architecture

```
 React SPA ──Faro (errors, web vitals, traceparent)─────────────────────────┐
    │ traceparent                                                            │
    ▼                                                                        ▼
 Java services (Micrometer Observation API → OTel bridge)          Grafana Cloud
   metrics  ── Micrometer → OTLP (exponential histograms) ────────▶  Mimir (PromQL)
   traces   ── Micrometer Tracing → OTel SDK → OTLP ──────────────▶  Tempo
   logs     ── Logback → OTel appender (trace_id, code.*) → OTLP ─▶  Loki
   profiles ── Pyroscope Java agent (execution, risk, order) ─────▶  Pyroscope
                                                                     Grafana: dashboards, SLOs, alerting → Email / IRM
 Confluent Cloud Metrics API ── Grafana Cloud integration ────────▶  broker-side consumer lag
 Synthetic Monitoring ── HTTPS /actuator/health/readiness ────────▶  downtime
 Azure Monitor (platform metrics, Container Apps system logs) ◀── Grafana data source (read-only pull)
```

**Local stack** (`observability/docker-compose.yml`) uses separate containers, so each component can be seen and learned on its own: Prometheus, Grafana, Alertmanager + Mailpit, OTel Collector, Tempo, Loki, Pyroscope, Alloy (Faro receiver). Locally, apps send OTLP to the Collector, which fans out to the backends. In prod, apps export OTLP directly to Grafana Cloud.

### Layering
```
┌─ Core (cloud-agnostic) ──────────────────────────────────────────┐
│ OTel/Micrometer in the apps, Faro in the browser, OTLP → Grafana, │
│ dashboards, SLOs, alert rules, synthetic checks                   │
├─ Hosting adaptations (Azure config, no code) ─────────────────────┤
│ Key Vault → env vars, KEDA scale rule, termination grace,         │
│ cloud.* resource attributes                                       │
├─ Azure platform add-on (read-only) ───────────────────────────────┤
│ Azure Monitor metrics + Container Apps system logs into Grafana   │
└───────────────────────────────────────────────────────────────────┘
```
The apps don't know which cloud they run on; the only exception is two resource labels. Core signals never depend on the Azure add-on: downtime comes from synthetic checks, and Execution health from Kafka lag. The add-on covers what the JVM can't see: image pull failures, crashes before OTel starts, OOM kills, restart loops, failed revisions and replica scaling.

### Key decisions
| Decision | Choice | Rationale / revisit when |
|---|---|---|
| Backend | Grafana Cloud free tier (Mimir, Loki, Tempo, Pyroscope, SLO, IRM, Synthetics, Faro) | One place for all signals, free at this volume |
| Transport | **OTLP push** in prod | Container Apps scale to zero, so a scraper can't reach replicas that don't exist |
| Prometheus pull | Locally only (`/actuator/prometheus`) | Learning the classic model; ad-hoc debugging |
| Instrumentation | Spring Boot native (`spring-boot-starter-opentelemetry` + Micrometer), **not** the OTel Java agent | The agent adds 2–5 s cold start and memory on 0.5 GiB scale-to-zero containers |
| Export path | Apps → Grafana Cloud OTLP gateway directly | Simplest. Move to the Container Apps managed OTel agent or Grafana Alloy when tail sampling is needed or token rotation gets painful |
| Rules | Prometheus-format rule files in git, tested with `promtool` | Portable between local Prometheus and Mimir (`mimirtool rules sync`) |
| Azure integration | Read-only Azure Monitor data source; **no Application Insights, no Azure Monitor alerts** | Keeps alerting in one notification policy; avoids duplicating Tempo/Loki at higher cost. The apps emit standard OTLP, so switching later only changes endpoint and token |
| Dashboards | JSON in `observability/grafana/dashboards/`, pushed by Terraform | UI edits must be exported back to the repo |
| Semantic conventions | Current OTel semconv (resource attrs, GenAI, MCP); Micrometer names for HTTP/JVM | Grafana's built-in views work without remapping |
| Splunk / Tableau | Not used | Grafana covers dashboards. Splunk duplicates it at higher cost; Tableau is BI, not operational |

## Design rules

### Instrumentation (shared `observability-common` module)
Spring Boot auto-configuration jar used by every service. It contains:
- **Shared config** in `application-observability.properties`: OTLP endpoints, 30 s metric step, `base2_expo` histograms, sampling, Kafka observation on.
- **Resource attributes:** `service.name`, `service.namespace=ordermanager`, `service.version=${GIT_SHA}`, `deployment.environment.name`, `cloud.provider=azure`, `cloud.platform=azure_container_apps`.
- **Cardinality guards** (`MeterFilter`):
  - no `accountId`/`orderId`/`sub` tags, capped `uri`;
  - `/actuator/**` excluded from HTTP metrics, so probes and cold starts stay out of SLIs;
  - histograms only on KPI timers.
- **`ObservationPredicate`:** no spans for actuator calls or price ticks.
- **`SensitiveDataObservationFilter`:** strips `authorization`, `cookie` and `*token*` keys.
- **Logging:**
  - OTel logback appender with `captureCodeAttributes` (class/method/line on each record);
  - MDC **allowlist**;
  - console pattern with `%X{traceId}`.
- **Export failures never affect trading.** Exporters are async and batched; export is disabled in tests.
- **Telemetry is flushed on shutdown** inside the graceful-shutdown budget, because scale-to-zero means frequent SIGTERMs.

### Trace propagation
| Hop | Mechanism |
|---|---|
| Browser → REST services | Faro adds `traceparent` (CORS allows `traceparent`, `tracestate`) |
| Order → Risk (gRPC) | Micrometer gRPC client/server observation interceptors |
| Order → Kafka → Execution | Spring Kafka observation (headers) |
| Kafka consumer → Disruptor → matching | Manual. The trace context *reference* is stored in the pre-allocated ring-buffer event, restored in `MatchingEngineDispatcher` as an `execution.match` span, then cleared |
| Advisor → MCP → Order/MarketData | Auto-configured `RestClient.Builder` (observation interceptor) |
| Advisor agent loop | GenAI semconv spans: `invoke_agent` → `chat` / `approval.wait` / `execute_tool`. **No prompt or completion text** |
| JDBC / Redis | `datasource-micrometer` (SQL spans); Lettuce built-in tracing |
| Price ticks | **Not traced** (metrics only) |

### Error location
Path: alert → log line (`trace_id`, `code.*`) or metric exemplar → trace → failing span with its exception event → stack frame. Loki ⇄ Tempo derived fields go in both directions. `@ControllerAdvice` hooks mark handled 5xx errors on the span. Rules for log levels:
- **ERROR** means an engineer must fix something.
- **Expected business outcomes** (risk rejection) are INFO.
- **Degraded-but-handled cases** (Risk fail-safe) are WARN and counted as a metric.

### Business metrics (low-cardinality; ticker is the only domain tag)
| Service | Metrics |
|---|---|
| order | `orders.placed{side,type,outcome=accepted\|rejected\|failsafe}`, `orders.place.latency` (histogram), `orders.cancelled{outcome}`, gRPC client timer |
| risk | `risk.decisions{decision,reason_code}`, gRPC server timer (histogram), `risk.price.cache.age{ticker}`, `risk.buying_power.cache{result}` |
| marketdata | `marketdata.ticks{ticker,source}`, `marketdata.tick.age{ticker}`, `marketdata.market.open`, `marketdata.ws.sessions`, `marketdata.ws.auth.failures`, `marketdata.finnhub.reconnects` |
| execution | `execution.events.consumed{type}`, `execution.events.failed{type}`, `execution.ringbuffer.remaining`, `execution.matches{ticker}`, `execution.book.depth{side,ticker}`, `execution.match.latency` |
| account | `account.buying_power.cache.writes{outcome}` |
| mcp | `mcp.tool.calls{tool,outcome}` (+ timer), `mcp.guardrail.rejections{rule}` |
| advisor | `gen_ai.client.token.usage`, `gen_ai.client.operation.duration`, `advisor.llm.cost.usd{model}`, `advisor.approvals{decision}`, `advisor.conversations.active` |

These come built in: HTTP RED, JVM, HikariCP, Kafka client, Lettuce, process CPU.

### SLOs, KPIs and alerts
**SLOs** are good/total ratios with multi-window burn-rate alerts: page at 14× burn (1h and 5m windows), ticket at 6× burn (6h and 30m).
| SLO | SLI | Objective |
|---|---|---|
| Order availability | non-5xx & non-failsafe `POST /api/orders` / all | 99% over 7d |
| Order latency | `histogram_fraction(0, 0.8, orders.place.latency)` | 99% over 7d |

**KPIs** (dashboards only):
- Risk check < 50 ms for ≥ 99% of calls.
- Acceptance rate.
- Price freshness while the market is open: < 30 s with Finnhub, < 5 s with the simulator.
- Order → match p95 < 2 s.
- Detection time ≤ 30 min.
- Usage trends.

**Alerts.** Each carries a `service` and `severity` label, a `runbook_url` (`docs/runbooks/`) and an Explore link. **Every rule uses `no_data = OK`**, because a scaled-to-zero service is healthy. Synthetic checks are the exception.
| Alert | Condition | Severity |
|---|---|---|
| ServiceDown | Synthetic readiness check fails 2× in a row (15 min interval) | critical |
| HighErrorRate | Per service, 5xx > 5% for 5m **and** > 0.1 req/s | critical |
| RiskFailSafeRejects | `orders.placed{outcome="failsafe"}` > 0 in 5m | critical |
| KafkaConsumerLag | Confluent lag on `order.created` > 100 for 10m (the only signal for the HTTP-less Execution service) | critical |
| ContainerRestarts | Azure `RestartCount` increase, or system log `OOMKilled`/`BackOff` | critical |
| RevisionProvisioningFailed | Container Apps system log after a deploy | critical |
| NewErrorLogs | Loki ERROR count > 0, by service + logging class (digest routing) | warning |
| ExecutionEventFailures, RingBufferPressure (< 10%), StalePrices (> 60 s **and** market open), LLMCostSpike, FrontendErrors | — | warning |
| SeriesBudget | > 80% of free-tier active series | info |

**Routing:**
- Group alerts by service + alertname.
- Critical alerts go to email + IRM push; warnings go out as a digest.
- ServiceDown inhibits HighErrorRate for the same service.
- No CPU alerts on Execution (its wait strategy keeps the CPU busy by design).

### Cost guardrails (free tier: 10k active series, 50 GB logs/traces/profiles, 14d retention)
- **Cardinality:** no ids in metric tags. Exponential histograms only on KPI timers.
- **Volume:** 30 s export step. No tick or actuator spans. Logs at INFO, `org.apache.kafka` at WARN.
- **Series churn:** each scale-up creates a new `service.instance.id`, i.e. new series. Disable unused binders, aggregate `instance` with Adaptive Metrics, and watch the Telemetry Health dashboard.
- **Sampling:** 100% head sampling at first. Move to tail sampling (collector) before lowering head sampling.

### Azure platform add-on (`terraform/observability-azure.tf`)
- **Grafana access:** service principal with `Monitoring Reader` (resource group) and `Log Analytics Reader`; Grafana Azure Monitor data source.
- **Log Analytics workspace:** 30-day retention, `daily_quota_gb = 0.5`. It receives **only `ContainerAppSystemLogs`** through a diagnostic setting; console logs already go to Loki. The environment logs destination is set to `azure-monitor`. Run `terraform plan` first: this change may recreate the environment.
- **Metrics used:** `Replicas`, `RestartCount`, `UsageNanoCores`, `WorkingSetBytes`, ingress `Requests` by status category.
- **Scope limits:** no Application Insights, no Azure Monitor alert rules, no security logs.

### Dashboards
1. **System Overview:** up/down, replicas, RED per service, SLOs and error budget, KPIs, deploy annotations.
2. **Order Flow:** outcomes, rejection reasons, latency heatmap with exemplars, risk gRPC, Kafka lag, ring buffer, matches, slow traces.
3. **Market Data.**
4. **AI Agents.**
5. **Service Detail** (`$service`): endpoints, JVM, Hikari, Kafka, ERROR logs, profiles, Azure platform row.
6. **Frontend** (Faro).
7. **Telemetry Health:** series and churn, ingest vs quota, exporter failures.

---

## Roadmap
Each step adds **one technology or concept**, ends in a verifiable state and is one PR. Format: **Learn** · **Build** · **Verify**.

### Phase 0 — Prerequisite app fixes (separate PRs, no new technology)
| Step | Fix | Verify |
|---|---|---|
| 0.1 | Exception logging keeps stack traces. A Throwable passed into a `{}` placeholder, or logged via `e.toString()` / `getMessage()`, loses its frames: `RiskGrpcClient:40`, `FinnhubMarketStatusService:30`, `AdvisorAgent`, `HttpMcpToolGateway`, `FinnhubPriceSource`. Add a CI check against the pattern | Stop riskservice, place an order → full stack trace |
| 0.2 | Log levels: risk rejection → INFO, WS disconnects → DEBUG, fail-safe → WARN. `OrderEventConsumer` stops silently swallowing failures (dead-letter topic or explicit skip) | `mvn clean verify -B` |
| 0.3 | JVM heap: Dockerfiles run with defaults, so ≈128 MB heap on 0.5 GiB. Add `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` | `java -XX:+PrintFlagsFinal -version \| grep MaxHeapSize` in the container |
| 0.4 | Graceful shutdown: `server.shutdown=graceful`, shutdown-phase timeout 20 s, Disruptor drain | `docker stop` during a request → it completes |
| 0.5 | Health liveness/readiness groups. Readiness includes db/redis/kafka; liveness includes no external dependencies | `/actuator/health/readiness` |
| 0.6 | Sensitive data: `audit.mcp`/`audit.advisor` and MDC never contain tokens or prompt text | Review checklist |
| 0.7 | Execution never wakes in prod (`min_replicas = 0`, no ingress, no scale rule). Add a KEDA Kafka scale rule on `order.created` | Prod order → replica starts → match logged |
| 0.8 | *(optional)* Migrate `net.devh` gRPC → Spring gRPC, only if net.devh causes trouble with Boot 4 | — |

### Phase 1 — Metrics: Micrometer and Prometheus (pull)
1. **Micrometer in one service (orderservice).**
   - *Learn:* meter types, tags, exposition format, built-in binders.
   - *Build:* `micrometer-registry-prometheus`, expose `prometheus`.
   - *Verify:* `curl :8082/actuator/prometheus` shows HTTP/JVM/Hikari metrics.
2. **Prometheus server.**
   - *Learn:* pull model, `scrape_configs`, `up`, PromQL (`rate`, `sum by`, `histogram_quantile`).
   - *Build:* `observability/docker-compose.yml` with Prometheus scraping orderservice; README; CLAUDE.md command.
   - *Verify:* target UP; RED query works.
3. **Custom business metrics (order).**
   - *Learn:* naming, cardinality, `MeterRegistry` vs `@Observed`, testing metrics.
   - *Build:* `orders.placed`, `orders.place.latency`, `orders.cancelled` + `SimpleMeterRegistry` tests.
   - *Verify:* a rejected order shows up under `outcome="rejected"`.
4. **Shared module + all services.**
   - *Learn:* auto-config jars, `MeterFilter`, common tags.
   - *Build:* `observability-common` with filters; every service depends on it; actuator in executionservice; all targets scraped; `ci.yml` paths filter.
   - *Verify:* all targets UP, no `/actuator` URIs in HTTP metrics.
5. **Grafana.**
   - *Learn:* data sources, panels, variables, provisioning, dashboards as JSON.
   - *Build:* Grafana on :3001 with provisioning; Service Detail dashboard exported to the repo.
   - *Verify:* survives `docker compose down && up`.
6. **Remaining business metrics** (one service per commit).
   - *Learn:* gauges on live state, timers from Kafka timestamps.
   - *Build:* metrics table for risk, marketdata, execution, account, mcp; Order Flow and Market Data dashboards.
   - *Verify:* live data with the simulator.
7. **Rule files + promtool.**
   - *Learn:* recording rules, alert rules, `for:`, rule unit tests.
   - *Build:* `observability/prometheus/rules/` (HighErrorRate with min volume, RiskFailSafeRejects, RingBufferPressure, StalePrices, local TargetDown) + tests + CI `promtool test rules`.
   - *Verify:* a 1-in-2 failure does not fire; stopping Risk fires.
8. **Alertmanager + Mailpit.**
   - *Learn:* routing tree, grouping, inhibition, silences.
   - *Build:* `alertmanager.yml` (critical vs digest, ServiceDown/TargetDown inhibits HighErrorRate).
   - *Verify:* email in Mailpit (:8025); a silence suppresses repeats.
9. **Histograms + SLOs.**
   - *Learn:* classic vs native histograms, SLIs, error budgets, burn rates.
   - *Build:* native histograms on KPI timers; SLO recording rules and burn-rate alerts with tests; System Overview SLO row.
   - *Verify:* a simulated fast burn fires the page alert.

### Phase 2 — OpenTelemetry
10. **Collector + Tempo, first traces (order).**
    - *Learn:* spans, `traceparent`, SDK vs API, OTLP, Collector pipelines, resource attributes.
    - *Build:* Collector + Tempo in compose; `spring-boot-starter-opentelemetry` via observability-common; JDBC spans; actuator spans dropped.
    - *Verify:* an order trace with SQL child spans; TraceQL query.
11. **Context propagation, one hop each.**
    - *Learn:* propagators, carriers, thread hand-offs, parent vs link.
    - 11a gRPC Order → Risk.
    - 11b Kafka Order → Execution (tick template not traced).
    - 11c Disruptor manual hand-off + `TestObservationRegistry` test.
    - *Verify:* one trace from order → `execution.match`.
12. **AI agent tracing (GenAI semconv).**
    - *Learn:* `invoke_agent`/`chat`/`execute_tool`, `gen_ai.*` metrics, keeping prompts out.
    - *Build:* `AdvisorAgent` observations + GenAI metrics; `RestClient` builders in `HttpMcpToolGateway`/mcpservice; `SensitiveDataObservationFilter`; AI Agents dashboard.
    - *Verify:* one chat turn = one trace advisor → mcp → order → risk → execution, with no prompt text or auth headers.
13. **Logs → Loki.**
    - *Learn:* OTel log model, `code.*`, MDC allowlist, Loki labels vs structured metadata, LogQL, derived fields.
    - *Build:* Loki + Collector logs pipeline; appender installer + logback include; Loki ⇄ Tempo links; `@ControllerAdvice` hooks; NewErrorLogs rule.
    - *Verify:* the fail-safe WARN log links to the `UNAVAILABLE` span; NewErrorLogs stays silent.
14. **Metrics via OTLP push.**
    - *Learn:* push vs pull, temporality, OTLP → Prometheus name translation, `target_info`, exponential → native histograms, exemplars.
    - *Build:* Micrometer OTLP registry next to Prometheus; Collector → Prometheus OTLP receiver; compare the two; move dashboards and rules to OTLP series and stop scraping.
    - *Verify:* rules and tests still pass; `docker stop` right after a request still delivers the last span and metric step.
15. **Pyroscope.**
    - *Learn:* sampling profilers, flame graphs, allocation profiles, span profiles.
    - *Build:* Pyroscope + Java agent on execution/risk/order; profile panel.
    - *Verify:* allocation profile of the matching hot path under load; measure startup cost.
16. **Faro + Alloy.**
    - *Learn:* RUM, Core Web Vitals, browser-rooted traces, CORS, source maps.
    - *Build:* Alloy `faro.receiver`; Faro SDK in `frontend/src/index.js` (skipped without `REACT_APP_FARO_URL`); CORS headers; advisor page via CDN.
    - *Verify:* a UI order trace starts in the browser; a JS error shows a readable stack.

### Phase 3 — Grafana Cloud and production (Azure)
17. **Grafana Cloud stack.**
    - *Learn:* stack endpoints, OTLP gateway, access policies, the usage page.
    - *Build:* create the stack by hand; point the *local Collector* at Grafana Cloud.
    - *Verify:* local traffic appears in the cloud; series count is visible.
18. **Prod wiring.**
    - *Learn:* Key Vault → container secrets → env, direct export, series churn.
    - *Build:* `grafana-otlp-token` secret; `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_AUTH_HEADER`, `APP_ENV`, `GIT_SHA` in `local.common_env_vars`; deploy annotation in `deploy.yml`.
    - *Verify:* a prod order shows a full trace; series count after a few scale cycles.
19. **Alerting and dashboards as code.**
    - *Learn:* `mimirtool`, `grafana/grafana` Terraform provider, data-source-managed vs Grafana-managed rules, `no_data_state`, IRM.
    - *Build:* sync rule files to Mimir, or convert them to Grafana-managed rules. **Decide here** so every alert lands in one notification policy. Add `terraform/observability.tf` (dashboards, contact points, policy with digest and inhibition) and runbooks.
    - *Verify:* a test notification arrives; a scaled-to-zero service raises nothing; stopping Risk alerts.
20. **Synthetic monitoring.**
    - *Learn:* blackbox probes, probe cost vs cold start, detection time.
    - *Build:* readiness checks every 15 min; ServiceDown (no data = alerting); up/down tiles.
    - *Verify:* a broken revision fires within ~30 min.
21. **Confluent Cloud integration.**
    - *Learn:* broker-side vs client-side lag.
    - *Build:* `MetricsViewer` key → integration; KafkaConsumerLag.
    - *Verify:* execution at 0 replicas + orders → alert; KEDA scale-up clears it.
22. **Azure platform add-on.**
    - *Learn:* Azure Monitor metrics, system logs, KQL, read-only principals, diagnostic settings.
    - *Build:* `terraform/observability-azure.tf` (see above); Platform row; ContainerRestarts and RevisionProvisioningFailed.
    - *Verify:* 0→1 replicas visible; a broken revision alerts; no console logs in the workspace.
23. **Finish.**
    - *Build:* Faro in prod + source-map upload; FrontendErrors; Telemetry Health dashboard; "Observability" section in [architecture.md](architecture.md) (removed from the gaps list).
    - *Verify:* a de-minified prod JS error; the end-to-end check below passes.

## End-to-end verification
- **Builds:** `mvn clean verify -B` for every service (export disabled in tests) and `promtool test rules` both pass.
- **Local:** placing an order in the UI gives one trace: browser → orderservice → riskservice → kafka → executionservice → `execution.match`. Its logs share the `trace_id`, and an exemplar on the latency chart opens the same trace.
- **Failure:** stopping Risk causes a fail-safe reject. RiskFailSafeRejects fires, the trace shows the gRPC `UNAVAILABLE` span with the `RiskGrpcClient` frame, and NewErrorLogs stays silent.
- **Scale to zero:** an idle or stopped service raises no alerts except ServiceDown.
- **Prod:**
  - active series stay < 10k after a week of churn;
  - synthetic checks are green;
  - a test alert reaches email and IRM;
  - deploy annotations are visible;
  - a broken revision alerts.
