# Architecture

**OrderManagerAI** is a learning project that implements a small Order Management System (OMS): live stock prices, advisor-managed client accounts, order entry with pre-trade risk checks, and a mock exchange with a matching engine.

It is built as a set of microservices following a classic trading-system shape:
- **Synchronous request path** for what the user waits on (order entry → risk check).
- **Asynchronous event path** via Kafka for everything downstream.
- **Real-time push path** for prices (Redis Pub/Sub → WebSocket).

The target design (including services not yet built) is described in [requirements.md](requirements.md).

## High-level picture

```
                         ┌──────────────── Amazon Cognito (OIDC) ──────────────┐
                         │  issues JWTs; every service validates them          │
                         └──────────────────────────────────────────────────────┘
                                         ▲ OIDC login (code + PKCE)
┌──────────────────────────┐   REST + WebSocket   ┌──────────────────────────────┐
│ React SPA (S3+CloudFront)│─────────────────────▶│ Market Data  :8080 (WebFlux) │◀── Finnhub WS/REST
│ Market / Portfolio /     │                      │  or RandomPriceSimulator     │    (live prices)
│ Orders / Order ticket    │──REST──▶ Account :8081│                              │
└──────────────────────────┘    │                 └───────┬───────────┬──────────┘
            │                   │                         │ pub/sub   │ produce
            │ REST              ▼                         ▼           ▼
            │           PostgreSQL (RLS)          Redis channel   Kafka: marketdata_stream
            │                   │                 market.prices       │
            │                   │ write-through           │           │ consume (price cache)
            │                   ▼                         │           ▼
            │           Redis KV  ──────────────────────────────▶ Risk Service :9090 (gRPC)
            │           account:buying-power:*     read            ▲
            ▼                                                       │ gRPC Check(), 800ms deadline,
     Order Service :8082 ───────────────────────────────────────────┘ fail-safe reject
            │  PostgreSQL (orders)
            │  after commit → Kafka: order.created (ORDER_CREATED / ORDER_CANCELLED)
            ▼
     Execution Service (actuator only) ── Kafka consumer → LMAX Disruptor ring buffer
                                    → per-symbol matching engine (limit order book)

  Advisor chat page (/advisor/) ──REST + SSE, JWT (mcp.trade)──▶ Advisor Service :8085 ──Claude API (web_search, tool use)
                                                           │ user approves every order in the chat
                                                           ▼
  AI agent (MCP client) ──Streamable HTTP + JWT (mcp.trade)──▶ MCP Service :8084
                                    ├─ REST (caller's token) ─▶ Order Service  POST/DELETE /api/orders
                                    └─ REST (caller's token) ─▶ Market Data    GET /api/marketdata
```

### Communication styles

| Style | Technology | Used where | Why |
|---|---|---|---|
| Sync, user-facing | REST/JSON + JWT | Browser → Market Data, Account, Order | Simple, cacheable, browser-native |
| Sync, service-to-service | gRPC/Protobuf over HTTP/2 | Order → Risk | Low latency, typed contract ([proto/risk.proto](../proto/risk.proto)), deadlines |
| Async events | Kafka (Confluent Cloud) | Prices → Risk; Orders → Execution | Decoupling, per-key ordering, replayable log |
| Real-time fan-out | Redis Pub/Sub → WebSocket | Prices → browser | Fire-and-forget broadcast to many sockets |
| AI tool calls | MCP (JSON-RPC over Streamable HTTP) + JWT | AI agent / Advisor Service → MCP Service → Order / Market Data | Standard tool protocol for LLM agents; delegates to the existing REST APIs |
| Chat streaming | Server-Sent Events over `fetch` + JWT | Browser → Advisor Service | One-way token/event stream per turn; `fetch` (not `EventSource`) so the token stays in the `Authorization` header |
| Shared fast state | Redis key-value | Account → Risk (buying power) | Sub-millisecond reads on the risk path |

## Services

All backend services are Java 21 / Spring Boot 4.0.5, packaged under `com.juzo.ai.ordermanager.*`, built from the root Maven multi-module [pom.xml](../pom.xml) (which manages the gRPC and Testcontainers BOMs).

### Market Data (`marketdata/`, port 8080) — Spring WebFlux / Project Reactor
- **Role:** price source for 10 tickers; pushes ticks to browsers and to Kafka.
- **Price sources** (`service/PriceUpdateSource` strategy):
  - `FinnhubPriceSource` — active when `FINNHUB_API_KEY` is set (`@ConditionalOnProperty`). Fetches an initial REST `/quote` snapshot, then streams trades over a reactive WebSocket client with exponential-backoff reconnect.
  - `RandomPriceSimulator` — fallback when no key is set; one **virtual thread** per ticker generating random ticks.
- **Publishing:** `MarketDataService` fans each update out to every `StockEventPublisher`:
  - `RedisStockEventPublisher` → Redis channel `market.prices`
  - `KafkaStockEventPublisher` → Kafka topic `marketdata_stream` (keyed by ticker, `acks=1`)
- **Endpoints:** `GET /api/marketdata` (REST snapshot for initial load), `GET /api/market-status` (US exchange open/closed, via Finnhub), `ws://<host>/ws/marketdata`.
- **WebSocket auth:** [MarketDataWebSocketHandler.java](../marketdata/src/main/java/com/juzo/ai/ordermanager/websocket/MarketDataWebSocketHandler.java) requires the first client frame to be `{"type":"auth","token":"<JWT>"}`. The token is validated with the `ReactiveJwtDecoder`; a `Sinks.One<Jwt>` gates the outbound Redis subscription until auth succeeds. Failure closes with `POLICY_VIOLATION`. This keeps tokens out of URLs and access logs (`/ws/marketdata` is `permitAll` at the HTTP layer for this reason).
- **Why reactive:** the service is dominated by long-lived socket I/O; non-blocking avoids a thread per connection.

### Account Service (`accountservice/`, port 8081) — Spring MVC + Spring Data JDBC + PostgreSQL
- **Role:** advisors' client accounts, cash balances and buying power. One Cognito user (advisor) owns multiple trade accounts.
- **Endpoints:** `GET /api/accounts` (summaries for the current user), `GET /api/accounts/{id}` (details incl. buying power).
- **Schema:** Flyway migrations in `src/main/resources/db/migration/`; balances are `NUMERIC(19,4)` with `CHECK` constraints (non-negative, reserved ≤ cash).
- **Row-Level Security:** [RlsTransactionManager.java](../accountservice/src/main/java/com/juzo/ai/ordermanager/account/config/RlsTransactionManager.java) runs `set_config('app.current_user_id', <JWT sub>, true)` at the start of every transaction; the Postgres policy (`V3`, `V4`) restricts rows to that user. The `__system__` sentinel lets internal jobs (cache warmer) read all rows. `FORCE ROW LEVEL SECURITY` applies the policy to the table owner too.
- **Buying-power cache:** `cash − reserved` is written to Redis (`account:buying-power:{id}`, 24h TTL) — pre-warmed at startup by `BuyingPowerCacheWarmer` and filled on read-miss.
- **Why:** balances are relational and transactional → Postgres. RLS provides database-enforced tenant isolation as defense-in-depth. Redis puts buying power where Risk can read it fast.

### Order Service (`orderservice/`, port 8082) — Spring MVC + PostgreSQL + gRPC client + Kafka producer
- **Role:** order entry, listing and cancellation; owns the order state machine (`NEW → PENDING → PARTIALLY_FILLED → FILLED / CANCELLED / REJECTED`).
- **Place order flow** (`POST /api/orders`):
  1. `RiskGrpcClient` calls `RiskCheck.Check` with an **800 ms deadline**, forwarding the user's bearer token as gRPC metadata.
  2. Any exception → **fail-safe reject** (HTTP 422).
  3. Approved orders are persisted, and `ORDER_CREATED` is published to Kafka topic `order.created` in a `TransactionSynchronization.afterCommit` hook — rolled-back orders are never announced (a lightweight stand-in for a transactional outbox).
- **Cancel** (`DELETE /api/orders/{id}`): conditional `UPDATE` guards against concurrent status changes; publishes `ORDER_CANCELLED` after commit.
- Kafka producer uses `acks=all`, `retries=3`, keyed by order id.
- Flyway uses its own history table (`flyway_schema_history_order`).

### Risk Service (`riskservice/`, gRPC 9090, actuator 8083) — Spring Boot + gRPC server
- **Role:** pre-trade check. BUY: `quantity × price ≤ buying power` (limit orders use the limit price; market orders use the cached market price).
- **State:**
  - Buying power — read from Redis (written by Account Service).
  - Prices — in-memory `ConcurrentHashMap`. [PriceCache.java](../riskservice/src/main/java/com/juzo/ai/ordermanager/risk/service/PriceCache.java) seeds itself at startup by seeking each `marketdata_stream` partition back ~500 offsets, then starts the live `@KafkaListener`.
- **Response:** `APPROVED` / `REJECTED` with typed reason codes (`INSUFFICIENT_FUNDS`, `INSUFFICIENT_SHARES`, `BUYING_POWER_UNAVAILABLE`, `PRICE_UNAVAILABLE`, `INVALID_REQUEST`) and a `decision_id`.
- **Why:** stateless and in-memory → millisecond latency. Rebuilding state by replaying Kafka illustrates why a log beats Pub/Sub for state reconstruction.

### Execution Service (`executionservice/`, actuator only on 8086) — Kafka + LMAX Disruptor
- **Role:** mock exchange / matching engine. Its only HTTP endpoint is the actuator on port 8086 (health + Prometheus metrics); on ECS it is used for the container health check and is not routed by the ALB.
- **Flow:** `OrderEventConsumer` (Kafka `order.created`) translates events into pre-allocated slots on a **Disruptor ring buffer** (65,536 slots, `YieldingWaitStrategy`, multi-producer) → single-threaded `MatchingEngineDispatcher` → per-ticker `SymbolMatchingEngine` → `LimitOrderBook`.
- **Order book:** bids/asks in price-sorted `TreeMap`s with FIFO `ArrayDeque`s per price level (price-time priority); O(1) lazy cancellation via an id index.
- **Why:** the canonical low-latency exchange design — single writer, lock-free, allocation-free hot path, deterministic ordering.

### MCP Service (`mcpservice/`, port 8084) — Spring MVC + Spring AI MCP server
- **Role:** exposes trading to AI agents as [MCP](https://modelcontextprotocol.io) tools over Streamable HTTP at `/mcp`. Stateless — no database, Kafka or Redis; all business rules stay in the downstream services.
- **Tools** (`@McpTool`, with read-only/destructive hints):
  - `get_market_prices(tickers?)` → `GET /api/marketdata` on Market Data, filtered by ticker.
  - `place_order(accountId, ticker, side, type, quantity, limitPrice?)` → `POST /api/orders`. A risk rejection (HTTP 422) is returned as `accepted=false` + reason, not as a tool error.
  - `cancel_order(orderId)` → `DELETE /api/orders/{id}`; 404/409 come back as `cancelled=false` with an explanation.
- **Auth (delegated):** the agent obtains a Cognito access token *on behalf of an advisor* with the `ordermanager/mcp.trade` scope. `/mcp` requires `SCOPE_mcp.trade` (the resource-server prefix is stripped by `JwtSecurityConfig`). The token's `sub` is the advisor, so the Order Service's ownership checks and Postgres RLS apply unchanged. Spring Security serves the RFC 9728 metadata at `/.well-known/oauth-protected-resource` and advertises it in `WWW-Authenticate` on 401s, as the MCP authorization spec requires. Cognito has no dynamic client registration and no RFC 8707 resource indicators, so agents use a pre-registered app client; its discovery document is `/.well-known/openid-configuration`, not the RFC 8414 path.
- **Token forwarding:** [McpTransportConfig.java](../mcpservice/src/main/java/com/juzo/ai/ordermanager/mcp/config/McpTransportConfig.java) replaces the auto-configured transport with one whose context extractor copies the validated JWT into the MCP transport context; tools read the token from there (not from the thread-local `SecurityContext`) and forward it as `Authorization: Bearer`.
- **Guardrails:** `mcp.trading.max-quantity-per-order` and `mcp.trading.max-notional-per-order` (limit price, or current market price for market orders) reject oversized agent orders before they reach the Order Service. Every mutating call is logged on the `audit.mcp` logger (subject, agent client id, args, outcome).
- **Why a separate service:** one endpoint for agents, existing services untouched, and a natural place for agent-specific policy (scopes, limits, audit).

### Advisor Service (`advisorservice/`, port 8085) — Spring MVC + Anthropic Java SDK + MCP client
- **Role:** a standalone AI trading advisor. It serves its own chat page, runs a Claude agent loop, and trades **only through the MCP Service**, so every guardrail and audit log of `mcpservice` still applies.
- **UI:** static `index.html` / `app.js` / `styles.css` in `src/main/resources/static` (`oidc-client-ts`, `marked`, DOMPurify from a CDN, no Node build). Sign-in settings come from the public `GET /config.json`. The trade account id is a plain text box and is sent with each message as `Active trade account: <uuid>`.
- **Endpoints:** `POST /api/conversations`; `POST /api/conversations/{id}/messages` and `POST /api/conversations/{id}/approvals` both answer with an SSE stream of `text_delta`, `thinking`, `tool_activity`, `approval_required`, `error` and `done` events. `/api/**` requires `SCOPE_mcp.trade`.
- **Agent loop** ([AdvisorAgent.java](../advisorservice/src/main/java/com/juzo/ai/ordermanager/advisor/agent/AdvisorAgent.java)): a manual tool-use loop with adaptive thinking, streaming and a cached system prompt. To keep this experimental feature cheap it defaults to `claude-sonnet-5-5` at `low` effort, the cheapest model that supports both `web_search_20260209` (dynamic filtering) and the server-side refusal fallback. Model, effort, web-search variant and fallback can each be changed through `ADVISOR_*` env vars: Haiku 5.5 needs the basic `web_search_20250305` and no fallback; Opus 5.5 at `high` gives deeper analysis. Tools are the MCP Service's tools (listed at runtime and converted into Claude tool definitions) plus Claude's server-side web search.
- **Human in the loop:** a tool runs straight away only when its MCP annotation says `readOnlyHint=true` (`get_market_prices`). Any other call (`place_order`, `cancel_order`, and any mutating tool added later) pauses the turn and shows an approval card. Approved calls run with the user's current token. Rejected, expired (10 min) or superseded calls go back to Claude as `is_error` tool results. Decisions and executions are logged on the `audit.advisor` logger.
- **Auth:** the browser's Cognito access token (scope `ordermanager/mcp.trade`) is validated here and forwarded to the MCP Service, so `sub`, ownership checks and Postgres RLS apply unchanged. The Claude API key stays on the server (`ANTHROPIC_API_KEY`).
- **State:** conversations are held in memory per user (`sub`) and evicted after 2h idle. A restart loses them.

### Frontend (`frontend/`) — React 18 + OIDC
- **Auth:** `src/auth/oidc.js` builds an `oidc-client-ts` `UserManager` (authorization code + PKCE, session-storage cache) wrapped by `react-oidc-context`; it works with Cognito or any OIDC provider. `src/api/client.js` gets the access token (silent refresh) and injects `Authorization: Bearer` on `fetch` calls.
- **Runtime config:** `src/config.js` fetches `/config.json` at startup (API URLs, WebSocket base, OIDC authority / client id / scope). Terraform writes that file next to the SPA, so one build serves every environment; `REACT_APP_*` variables are only the dev/test fallback.
- **API modules:** `marketDataApi.js`, `marketDataWebSocket.js` (sends the auth frame on open), `accountApi.js`, `ordersApi.js`.
- **State:** plain hooks — `useMarketData` (REST snapshot, then WebSocket deltas), `useMarketStatus`, `useAccounts`, `useAccountDetails`, `useOrders`.
- **UI:** `MarketDataTable` (price-flash animations), `AccountSelector`, `OrderTicketModal`, `OrdersPage`, `PortfolioPage`.
- **Dev:** `src/setupProxy.js` routes `/api/accounts` → 8081, `/api/orders` → 8082, other `/api` and `/ws` → 8080.
- **Prod:** the CRA build is uploaded to S3 and served by CloudFront (the distribution's `*.cloudfront.net` address). The Dockerfile (Node 20 → nginx) remains for running the SPA in a container; it takes no build args.

## Cross-cutting concerns

| Concern | Technology | How / why |
|---|---|---|
| Identity | Amazon Cognito (OIDC) + Spring Security OAuth2 Resource Server | One user pool (Terraform `cognito.tf`) with an `ordermanager` resource server (`market.read`, `internal.read`, `mcp.trade`) and separate `web` / `advisor` app clients. Every service validates JWTs via `OIDC_ISSUER_URI`; Cognito access tokens have no `aud`, so `JwtSecurityConfig` checks `client_id` against `OIDC_CLIENT_IDS` (or `aud`, for other IdPs) and rejects ID tokens. `sub` is the user identity end-to-end, down to Postgres RLS. |
| Runtime | Java 21, Spring Boot 4.0.5 | Virtual threads, records, switch expressions. |
| Contracts | Protobuf (`proto/`) | Single shared `.proto`; `protobuf-maven-plugin` generates client and server stubs. |
| Messaging | Confluent Cloud Kafka (SASL_SSL) | `acks=all` + retries for orders; `acks=1` for prices (a lost tick is tolerable). Keys give per-ticker / per-order ordering. |
| Cache / bus | Redis Cloud | Two roles: Pub/Sub broadcast for prices; key-value store for buying power. |
| Databases | Managed PostgreSQL (Supabase) | Free-tier managed Postgres; Flyway schemas, enabled only via `FLYWAY_ENABLED=true`. |
| Testing | JUnit 5, Reactor `StepVerifier`, MockWebServer, gRPC testing, Testcontainers | |
| Containers | Docker | Temurin 21 JRE Alpine per Java service, heap sized from the container limit (`-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError`). |
| Hosting | AWS ECS Fargate (0.25 vCPU / 1 GB per service), public subnets, no NAT | See "AWS deployment" below. Fargate has no scale-to-zero: "off" means desired count 0 (`scripts/env.sh`, `env-scale.yml`). |
| IaC | Terraform (aws provider) | Reusable `modules/ecs-service`; task definitions use the `:latest` image tag, so CI only forces a new deployment. State in an S3 bucket with native locking — run `terraform/bootstrap.sh` before the first `terraform apply`. |
| Observability | Micrometer → Prometheus (pull) → Grafana | Every service exposes `/actuator/prometheus`; Prometheus rules alert on down / 5xx / failsafe rejects. Logs go to stdout (CloudWatch Logs on ECS). Local compose stack, or optional Prometheus + Grafana ECS services on AWS (`observability_enabled`; Grafana at `/grafana/`, login required). ECS health checks use the liveness group (`/actuator/health/liveness`). See [observability.md](observability.md). |
| Secrets | AWS Secrets Manager (one JSON secret) injected into task definitions; `.env` locally | Loaded via `spring.config.import=optional:file:../.env[.properties]`. Hosts and usernames stay explicit in `application.properties`; only secrets and OIDC settings are env vars. |
| Registry | Amazon ECR (private, last 3 images kept) | Pulled by the task execution role. |

Runtime config for each service lives in `<service>/src/main/resources/application.properties`.

## AWS deployment

```
 Browser ── https://<id>.cloudfront.net (one origin: no CORS, no custom domain) ──▶ CloudFront
          /                                            ─▶ S3 (SPA + config.json)
          /api/*, /ws/*, /mcp*, /.well-known/*, /advisor*, /grafana*  ─▶ ALB (HTTP, only reachable from CloudFront, idle timeout 900 s)
              /api/marketdata*, /api/market-status*, /ws/*  ─▶ marketdata :8080
              /api/accounts*                                 ─▶ account    :8081
              /api/orders*                                   ─▶ order      :8082
              /mcp*, /.well-known/*                          ─▶ mcp        :8084
              /advisor*  (context path /advisor)             ─▶ advisor    :8085   (/advisor/actuator* answers 403)
              /grafana*  (only if observability_enabled)     ─▶ grafana    :3000

 Inside the VPC (Cloud Map DNS, namespace ordermanagerai.local, plain HTTP / gRPC):
   order ─▶ risk.ordermanagerai.local:9090 (gRPC)     order ─▶ marketdata:8080
   mcp   ─▶ order:8082, marketdata:8080               advisor ─▶ mcp:8084/mcp
   prometheus (optional) ─▶ every service's actuator   grafana (optional) ─▶ prometheus:9090
```

- **Network:** one VPC with two public subnets and **no NAT gateway** (a NAT would cost about $33/month). Tasks get public IPs so they can reach Kafka, Redis, Postgres, the Claude API and Cognito; security groups allow inbound only from the ALB and from other tasks. Risk is no longer reachable from the internet.
- **Compute:** one ECS service per backend, `deployment_minimum_healthy_percent = 0` so a deploy replaces the single task in place. Optional Fargate Spot (`use_spot`) and ARM (`cpu_architecture`).
- **Secrets and config:** credentials live in one Secrets Manager secret; OIDC issuer, client ids, Cloud Map URLs and `SERVER_FORWARD_HEADERS_STRATEGY=framework` (so the apps see the ALB's HTTPS) are plain task environment variables.
- **Region:** us-east-2 (the account's assigned Region; regional services are blocked elsewhere). Supabase (us-east-1) is one cross-Region hop away.
- **TLS / front door:** CloudFront's default `*.cloudfront.net` certificate terminates HTTPS; the ALB only speaks HTTP and accepts only CloudFront's address range plus a secret `X-Origin-Verify` header (anything else gets 403). A CloudFront Function does SPA routing for the S3 behaviour only, so API errors are not rewritten. CloudFront waits at most 60 s for origin bytes, so the advisor sends an SSE keep-alive comment every 15 s. The Tomcat services that build absolute URLs (MCP, advisor) read the scheme from `CloudFront-Forwarded-Proto`.
- **ALB address changes:** the ALB gets a new DNS name each time `scripts/env.sh up` re-creates it, so CloudFront's origin is updated each time (a few minutes).
- **Observability (optional):** with `observability_enabled = true`, Prometheus (private, scrapes the services by Cloud Map name) and Grafana (public at `/grafana/`, admin password from Secrets Manager) run as two more ECS services. About $0.04/hour while running; `scripts/env.sh` and `env-scale.yml` start and stop them with the rest.
- **Cost control:** services start at desired count 0. With the ALB removed and tasks stopped, the floor is a few dollars a month (Secrets Manager, ECR). `AWS Budgets` alerts at 80% actual / 100% forecast.
- **Still outside AWS:** Redis Cloud and Confluent Cloud are third-party SaaS and keep their current hosts (their clusters are in Azure regions, so traffic to them crosses clouds); Supabase Postgres is on AWS us-east-1.

## CI/CD (`.github/workflows/`)
- **ci.yml** — on every push/PR; uses a paths filter to run `mvn clean verify -B` only for changed services (marketdata, accountservice, orderservice, riskservice, mcpservice, advisorservice) and `npm ci && npm test` for the frontend; publishes test reports.
- **deploy.yml** — runs after CI succeeds on `main` (or manually), authenticating to AWS with the access key of a dedicated IAM user stored as GitHub secrets (GitHub OIDC is blocked by this account's managed policy). A matrix job builds each JAR (and the Prometheus and Grafana images) and pushes `<sha>` + `latest` images to ECR; the frontend job builds the SPA, syncs it to S3 (never touching Terraform's `config.json`) and invalidates CloudFront; the deploy job forces a new ECS deployment of every service that exists (Prometheus and Grafana only if observability is enabled) and checks running vs desired counts. Needs repository secrets `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` and variables `FRONTEND_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID` (from `terraform output`).
- **env-scale.yml** — manual start/stop of all ECS services (desired count 1/0). `scripts/env.sh up|down` additionally creates/removes the ALB with Terraform (needs admin credentials).
- **ai-code-review.yml** — Claude API reviews PR diffs, posts a comment, and auto-merges if approved.
- **ai-issue-handler.yml** — Claude API turns issues labelled `bug` or `enhancement` into a feature branch + PR, validated by tests.

## Current gaps vs. target design (as of 2026-11)
- Not yet built: Position, Audit and Notification services; APIM; Resilience4j. Kafka topics `order.pending` / `execution.report` have no producers yet.
- The matching engine logs matches but does not publish fills; `MARKET_TICK` handling is a stub.
- Risk rejects all SELL orders (no position data yet).
- Risk does not validate the forwarded bearer token (`grpc.server.security.enabled=false`). It is reachable only inside the VPC (Cloud Map), not from the internet.
- Cognito access tokens carry no `aud` claim, so the audience-binding the MCP authorization spec recommends (RFC 8707) is replaced by a `client_id` allow-list.
- Redis Cloud and Confluent Cloud clusters are hosted in Azure regions, so the AWS services reach them across clouds (extra latency and egress). Re-creating them in AWS us-east-1 only needs new hosts and credentials.
- Placing an order does not update `reserved_balance` or the Redis buying-power key, so buying power can be double-spent across orders.
- CI does not run tests for `executionservice` (it has none yet).
- `POST /api/orders` has no client order id, so an agent retrying `place_order` after a timeout can create a duplicate order (the tool description warns against blind retries).
- Advisor conversations are in memory only (lost on restart, single replica), and the trade account id is typed by hand instead of being picked from the Account Service.
