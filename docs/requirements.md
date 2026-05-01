# Requirements

Domain Concepts to Understand First
An OMS handles the full lifecycle of a trade order:
Order Types:   Market, Limit, Stop, Stop-Limit, Trailing Stop
Order States:  NEW → PENDING → PARTIALLY_FILLED → FILLED
                                                 → CANCELLED
                                                 → REJECTED
Order Sides:   BUY / SELL
Asset Types:   Equities (stocks) — keep it simple, no derivatives needed
A real trade flow looks like:
Client places order
  → Risk check (do they have enough balance/shares?)
  → Route to Exchange (mocked)
  → Exchange sends back Execution Reports (fills)
  → Position & P&L updated
  → Client notified in real time
  → Audit trail written immutably

Architecture Overview
ReactJS (Trading UI — order blotter, portfolio, live prices)
     │  WebSocket (real-time) + REST
     ▼
Azure API Management (APIM)
     │
     ├──▶ Market Data Service    (Spring WebFlux → Redis pub/sub → WebSocket push)
     ├──▶ Account Service        (Spring Boot → PostgreSQL, Spring Security/JWT)
     ├──▶ Order Service          (Spring Boot → PostgreSQL, Kafka producer)
     ├──▶ Risk Service           (Spring Boot → Redis, synchronous pre-trade check)
     ├──▶ Execution Service      (Spring Boot → mocked exchange, Kafka producer)
     ├──▶ Position Service       (Spring WebFlux → MongoDB, Kafka consumer)
     ├──▶ Notification Service   (Spring Boot → Kafka consumer, Azure Comms)
     └──▶ Audit Service          (Spring Boot → Kafka consumer → CosmosDB append-only)

Internal high-speed calls: gRPC (Order → Risk, Order → Execution)
Async event backbone: Kafka
Real-time data: Redis pub/sub + WebSocket

Service-by-Service Breakdown
1. Market Data Service
Tech: Spring WebFlux (reactive), Redis pub/sub, WebSocket, Kafka
Simulates a price feed for a handful of ticker symbols (AAPL, MSFT, GOOGL, etc.). Generates random price ticks on a schedule, publishes to a Redis channel AND a Kafka topic (market.prices). The React UI subscribes via WebSocket and sees live price updates. This is the most visually impressive part of the whole application — watching prices tick in real time.
This is your showcase for reactive programming (Spring WebFlux, Project Reactor) and justifies it clearly: you don't want a thread-per-connection model for potentially thousands of price subscribers.

2. Account Service
Tech: Spring Boot, PostgreSQL, Spring Security, OAuth2/JWT, Redis, Azure Entra ID
Manages trader accounts, cash balances, and buying power. PostgreSQL is the clear choice — financial balances are transactional and relational. Redis caches account buying power for the Risk Service to query with sub-millisecond latency (critical for pre-trade checks). Azure Entra ID as the external identity provider is a natural fit.

3. Order Service
Tech: Spring Boot, PostgreSQL, Kafka (producer), gRPC, Resilience4j, Flyway
The core of the system. Receives order placement requests, runs a synchronous pre-trade risk check via gRPC to the Risk Service (fast, typed, binary — perfect justification over REST here), persists the order to PostgreSQL, then publishes an order.created event to Kafka.
This is where you model the Order state machine explicitly — great for design discussions. Flyway manages the PostgreSQL schema migrations.
Resilience4j wraps the gRPC call to the Risk Service — if risk is down, orders fail safe (rejected, not silently passed).

4. Risk Service
Tech: Spring Boot, Redis, gRPC server
Stateless risk check logic. Given an order, checks: does the account have enough cash (buy) or enough shares (sell)? Buying power is read from Redis (put there by Account Service). Returns APPROVED or REJECTED with a reason code in milliseconds. Synchronous, fast, simple. The gRPC interface is well-suited here — define your .proto, generate the stubs, done.
Pre-trade risk is a real concept in trading systems and interviewers in finance will know exactly what you're talking about.

5. Execution Service (Mock Exchange)
Tech: Spring Boot, Kafka (consumer + producer), gRPC
Consumes order.pending events. Simulates an exchange: Market orders fill immediately at current price (read from Redis). Limit orders fill only when the market price crosses the limit price — you can run a simple background checker. Publishes execution.report events to Kafka with fill quantity, fill price, and remaining quantity. Partial fills are supported — a great talking point for the order state machine.

6. Position Service
Tech: Spring WebFlux, MongoDB, Kafka (consumer)
Consumes execution.report events and maintains each account's portfolio: positions (shares held per ticker), average cost basis, unrealized P&L (calculated against live Redis prices), realized P&L. MongoDB fits perfectly — document-shaped, per-account portfolio data that doesn't fit neatly into relational tables. Reactive stack here because the UI polls P&L frequently and you want non-blocking reads.

7. Audit Service
Tech: Spring Boot, Kafka (consumer), Azure CosmosDB (append-only)
Consumes every significant event from Kafka — order created, risk decision, execution report, position update — and writes them immutably to CosmosDB with a timestamp. This is your regulatory audit trail. Documents are never updated or deleted. CosmosDB's schemaless nature is ideal because each event type has a different shape. This is a genuinely real requirement in financial systems (think MiFID II, SEC Rule 17a-4) and interviewers love hearing you mention compliance context.

8. Notification Service
Tech: Spring Boot, Kafka (consumer), Azure Communication Services
Listens for execution.report events and sends trade confirmation emails/SMS. Stateless and simple, but demonstrates event-driven decoupling perfectly.

React UI — "TradeFlow Dashboard"
Tech: ReactJS, TypeScript, Redux Toolkit, React Query, WebSocket, Recharts
Four main views:
Market Watch — live ticker prices updating via WebSocket. Recharts candlestick or line chart for price history. This is visually the most impressive screen.
Order Entry — form to place Buy/Sell orders. Select ticker, order type, quantity, limit price (if applicable). Shows risk rejection messages inline.
Order Blotter — table of all open and historical orders with real-time status updates (NEW → FILLED via WebSocket). The classic trading desk screen.
Portfolio View — current positions, shares held, average cost, unrealized P&L (ticking live against market prices), realized P&L, total account value.

Kafka Topics Design
market.prices          ← Market Data Service produces
order.created          ← Order Service produces
order.pending          ← Order Service produces (post-risk approval)
order.rejected         ← Order/Risk Service produces
execution.report       ← Execution Service produces
position.updated       ← Position Service produces
notification.trigger   ← any service produces, Notification consumes
audit.events           ← all services produce, Audit Service consumes
This Kafka topic design alone is a great whiteboard conversation in interviews.

Infrastructure & DevOps (Same Stack, Better Justification)
Terraform Modules
infra/
├── modules/
│   ├── aks/              ← AKS cluster, node pools
│   ├── networking/       ← VNet, subnets, NSGs
│   ├── databases/        ← PostgreSQL Flexible Server, CosmosDB, Redis
│   ├── messaging/        ← Azure Event Hubs (Kafka-compatible) or Confluent
│   ├── monitoring/       ← Application Insights, Log Analytics Workspace
│   ├── security/         ← Key Vault, Managed Identities
│   └── apim/             ← API Management
└── environments/
    ├── local/
    └── prod/

Project Structure
tradeflow/
├── infra/                        ← Terraform
│   ├── modules/
│   └── environments/
├── services/
│   ├── market-data-service/      ← Spring WebFlux
│   ├── account-service/          ← Spring Boot
│   ├── order-service/            ← Spring Boot (core)
│   ├── risk-service/             ← Spring Boot + gRPC
│   ├── execution-service/        ← Spring Boot (mock exchange)
│   ├── position-service/         ← Spring WebFlux
│   ├── notification-service/     ← Spring Boot
│   └── audit-service/            ← Spring Boot
├── frontend/                     ← React + TypeScript
├── proto/                        ← shared .proto definitions (gRPC)
├── helm/                         ← Helm charts per service
└── .azuredevops/
    └── pipelines/
The shared proto/ directory at the root is worth noting — in a real system you'd publish generated stubs as a private Maven package to Azure Artifacts. Another good talking point.