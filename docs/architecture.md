# Architecture

**OrderManagerAI** is a learning project that implements a small Order Management System (OMS): live stock prices, advisor-managed client accounts, order entry with pre-trade risk checks, and a mock exchange with a matching engine.

It is built as a set of microservices following a classic trading-system shape:
- **Synchronous request path** for what the user waits on (order entry → risk check).
- **Asynchronous event path** via Kafka for everything downstream.
- **Real-time push path** for prices (Redis Pub/Sub → WebSocket).

The target design (including services not yet built) is described in [requirements.md](requirements.md).

## High-level picture

```
                         ┌──────────────── Entra ID (Azure AD) ────────────────┐
                         │  issues JWTs; every service validates them          │
                         └──────────────────────────────────────────────────────┘
                                         ▲ MSAL login
┌──────────────────────────┐   REST + WebSocket   ┌──────────────────────────────┐
│ React SPA (nginx)        │─────────────────────▶│ Market Data  :8080 (WebFlux) │◀── Finnhub WS/REST
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
     Execution Service (no HTTP) ── Kafka consumer → LMAX Disruptor ring buffer
                                    → per-symbol matching engine (limit order book)

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
| AI tool calls | MCP (JSON-RPC over Streamable HTTP) + JWT | AI agent → MCP Service → Order / Market Data | Standard tool protocol for LLM agents; delegates to the existing REST APIs |
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
- **Role:** advisors' client accounts, cash balances and buying power. One Entra ID user (advisor) owns multiple trade accounts.
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

### Execution Service (`executionservice/`, no HTTP) — Kafka + LMAX Disruptor
- **Role:** mock exchange / matching engine (`spring.main.web-application-type=none`).
- **Flow:** `OrderEventConsumer` (Kafka `order.created`) translates events into pre-allocated slots on a **Disruptor ring buffer** (65,536 slots, `YieldingWaitStrategy`, multi-producer) → single-threaded `MatchingEngineDispatcher` → per-ticker `SymbolMatchingEngine` → `LimitOrderBook`.
- **Order book:** bids/asks in price-sorted `TreeMap`s with FIFO `ArrayDeque`s per price level (price-time priority); O(1) lazy cancellation via an id index.
- **Why:** the canonical low-latency exchange design — single writer, lock-free, allocation-free hot path, deterministic ordering.

### MCP Service (`mcpservice/`, port 8084) — Spring MVC + Spring AI MCP server
- **Role:** exposes trading to AI agents as [MCP](https://modelcontextprotocol.io) tools over Streamable HTTP at `/mcp`. Stateless — no database, Kafka or Redis; all business rules stay in the downstream services.
- **Tools** (`@McpTool`, with read-only/destructive hints):
  - `get_market_prices(tickers?)` → `GET /api/marketdata` on Market Data, filtered by ticker.
  - `place_order(accountId, ticker, side, type, quantity, limitPrice?)` → `POST /api/orders`. A risk rejection (HTTP 422) is returned as `accepted=false` + reason, not as a tool error.
  - `cancel_order(orderId)` → `DELETE /api/orders/{id}`; 404/409 come back as `cancelled=false` with an explanation.
- **Auth (delegated):** the agent obtains an Entra ID token *on behalf of an advisor* with the `mcp.trade` scope (admin consent). `/mcp` requires `SCOPE_mcp.trade`. The token's `sub` is the advisor, so the Order Service's ownership checks and Postgres RLS apply unchanged. Spring Security serves the RFC 9728 metadata at `/.well-known/oauth-protected-resource` and advertises it in `WWW-Authenticate` on 401s, as the MCP authorization spec requires. Entra ID has no dynamic client registration, so agents use a pre-registered client ID.
- **Token forwarding:** [McpTransportConfig.java](../mcpservice/src/main/java/com/juzo/ai/ordermanager/mcp/config/McpTransportConfig.java) replaces the auto-configured transport with one whose context extractor copies the validated JWT into the MCP transport context; tools read the token from there (not from the thread-local `SecurityContext`) and forward it as `Authorization: Bearer`.
- **Guardrails:** `mcp.trading.max-quantity-per-order` and `mcp.trading.max-notional-per-order` (limit price, or current market price for market orders) reject oversized agent orders before they reach the Order Service. Every mutating call is logged on the `audit.mcp` logger (subject, agent client id, args, outcome).
- **Why a separate service:** one endpoint for agents, existing services untouched, and a natural place for agent-specific policy (scopes, limits, audit).

### Frontend (`frontend/`) — React 18 + MSAL
- **Auth:** `src/auth/msalConfig.js` configures `@azure/msal-browser` / `@azure/msal-react` (tenant, client ID, API scope from `REACT_APP_*` env vars; session-storage cache). `src/api/client.js` acquires tokens silently and injects `Authorization: Bearer` on `fetch` calls.
- **API modules:** `marketDataApi.js`, `marketDataWebSocket.js` (sends the auth frame on open), `accountApi.js`, `ordersApi.js`.
- **State:** plain hooks — `useMarketData` (REST snapshot, then WebSocket deltas), `useMarketStatus`, `useAccounts`, `useAccountDetails`, `useOrders`.
- **UI:** `MarketDataTable` (price-flash animations), `AccountSelector`, `OrderTicketModal`, `OrdersPage`, `PortfolioPage`.
- **Dev:** `src/setupProxy.js` routes `/api/accounts` → 8081, `/api/orders` → 8082, other `/api` and `/ws` → 8080.
- **Prod:** multi-stage Docker build (Node 20 → nginx); service URLs are baked in at build time via `REACT_APP_*` build args.

## Cross-cutting concerns

| Concern | Technology | How / why |
|---|---|---|
| Identity | Entra ID + Spring Security OAuth2 Resource Server | Single app registration (Terraform `azuread_application`) exposes an `api://` scope. Every service validates JWTs via `issuer-uri`; `sub` is the user identity end-to-end, down to Postgres RLS. |
| Runtime | Java 21, Spring Boot 4.0.5 | Virtual threads, records, switch expressions. |
| Contracts | Protobuf (`proto/`) | Single shared `.proto`; `protobuf-maven-plugin` generates client and server stubs. |
| Messaging | Confluent Cloud Kafka (SASL_SSL) | `acks=all` + retries for orders; `acks=1` for prices (a lost tick is tolerable). Keys give per-ticker / per-order ordering. |
| Cache / bus | Redis Cloud | Two roles: Pub/Sub broadcast for prices; key-value store for buying power. |
| Databases | Managed PostgreSQL (Supabase / Neon) | Free-tier managed Postgres; Flyway schemas, enabled only via `FLYWAY_ENABLED=true`. |
| Testing | JUnit 5, Reactor `StepVerifier`, MockWebServer, gRPC testing, Testcontainers | |
| Containers | Docker | Temurin 21 JRE Alpine per Java service; Node → nginx for the UI. |
| Hosting | Azure Container Apps | Scale to zero (`min_replicas = 0`). Risk uses `transport = http2` for gRPC; Execution has no ingress. |
| IaC | Terraform (azurerm + azuread) | Reusable `modules/container-app`; `ignore_changes` on image lets CI own deployments. Remote state in Azure Blob Storage — run `terraform/bootstrap.sh` before the first `terraform apply`. |
| Secrets | Azure Key Vault + Container App secrets; `.env` locally | Loaded via `spring.config.import=optional:file:../.env[.properties]`. |
| Registry | GitHub Container Registry (ghcr.io) | Replaced Azure Container Registry for cost saving. |

Runtime config for each service lives in `<service>/src/main/resources/application.properties`.

## CI/CD (`.github/workflows/`)
- **ci.yml** — on every push/PR; uses a paths filter to run `mvn clean verify -B` only for changed services (marketdata, accountservice, orderservice, riskservice) and `npm ci && npm test` for the frontend; publishes test reports.
- **deploy.yml** — runs after CI succeeds on `main` (or manually): builds all JARs and the frontend, queries live Container App FQDNs (baked into the frontend image), builds and pushes images to GHCR tagged `<sha>` + `latest`, then `az containerapp update` per service, followed by `/actuator/health` smoke tests against marketdata, account and order services (Risk exposes only gRPC externally).
- **ai-code-review.yml** — Claude API reviews PR diffs, posts a comment, and auto-merges if approved.
- **ai-issue-handler.yml** — Claude API turns issues labelled `bug` or `enhancement` into a feature branch + PR, validated by tests.

## Current gaps vs. target design (as of 2026-11)
- Not yet built: Position, Audit and Notification services; APIM; Resilience4j. Kafka topics `order.pending` / `execution.report` have no producers yet.
- The matching engine logs matches but does not publish fills; `MARKET_TICK` handling is a stub.
- Risk rejects all SELL orders (no position data yet).
- Risk does not validate the forwarded bearer token (`grpc.server.security.enabled=false`), while its ingress is external.
- Placing an order does not update `reserved_balance` or the Redis buying-power key, so buying power can be double-spent across orders.
- CI does not run tests for `executionservice` (it has none yet).
- `POST /api/orders` has no client order id, so an agent retrying `place_order` after a timeout can create a duplicate order (the tool description warns against blind retries).
