# Architecture

**OrderManagerAI** is a real-time market data streaming application. The backend simulates live price ticks for 10 stock symbols and streams them to connected frontend clients via WebSocket.

## Data Flow
```
Price Simulator (backend scheduler, virtual threads)
  → Redis Pub/Sub (channel: market.prices)
    → MarketDataWebSocketHandler (backend subscriber)
      → WebSocket connections (token via ?access_token= query param)
        → React useMarketData hook (MSAL token injected)
          → MarketDataTable UI (flash green/red on price change)
```

The backend also exposes a REST snapshot at `GET /api/marketdata` for initial page load. WebSocket endpoint: `ws://<host>/ws/marketdata`.

## Backend (`marketdata/`)
Spring Boot 4.0.5 reactive application (Spring WebFlux + Project Reactor), Java 21.

Key packages under `com.juzo.ai.ordermanager`:
- `entity/` — `Stock.java` domain model
- `service/` — price simulation logic using `Executors.newVirtualThreadPerTaskExecutor()`
- `controller/` — REST endpoint (`/api/marketdata`)
- `websocket/` — WebSocket handler that bridges Redis Pub/Sub to connected clients
- `config/` — `SecurityConfig` (OAuth2 JWT + WebSocket query-param token), `CorsConfig` (reads `CORS_ALLOWED_ORIGINS` env var), `WebSocketConfig`, `KafkaConfig`

Security uses Spring Security OAuth2 Resource Server with Entra ID (Azure AD) JWT validation. WebSocket clients pass the bearer token as a `?access_token=` query parameter because browser WebSocket APIs can't set headers.

Redis is used as a pub/sub bus between the price simulator and WebSocket broadcaster. Kafka config and dependency exist in `pom.xml` but are commented out and not active.

Runtime config is in `marketdata/src/main/resources/application.properties`. Secrets (Redis password, Kafka credentials) come from environment variables loaded from `.env` locally and from **Azure Key Vault** in production.

## Frontend (`frontend/`)
React 18 SPA with MSAL (Microsoft Authentication Library) for Entra ID auth, served by nginx in production (multi-stage Docker build).

- `src/auth/msalConfig.js` — MSAL instance config (tenant ID, client ID, scopes from env vars)
- `src/api/client.js` — axios HTTP client with MSAL token injection; `marketDataApi.js` uses it for the REST snapshot; `marketDataWebSocket.js` manages the WebSocket connection
- `src/hooks/useMarketData.js` — fetches initial state, manages WebSocket lifecycle, updates React state
- `src/components/MarketDataTable.js` — table with price-flash animations
- `src/App.js` — shell with navigation placeholders (Portfolio, Orders, Buy/Sell)

## Infrastructure (`terraform/`)
Azure resources: Resource Group, Key Vault, Container Registry (ACR), Container Apps Environment, two Container Apps (backend + frontend). Apps scale to zero replicas when idle (`min_replicas = 0`, `max_replicas = 1`). Terraform remote state is stored in Azure Blob Storage. Run `terraform/bootstrap.sh` to provision the state storage before the first `terraform apply`.

## CI/CD (`.github/workflows/`)
- **ci.yml** — runs backend (`mvn clean verify -B`) and frontend (`npm ci && npm test`) in parallel on every push/PR
- **deploy.yml** — triggers after CI on `main`; builds and pushes Docker images to ACR (tagged `<sha>` + `latest`), deploys to Azure Container Apps, runs smoke test against `/actuator/health`
- **ai-code-review.yml** / **ai-issue-handler.yml** — automated AI review and issue handling workflows