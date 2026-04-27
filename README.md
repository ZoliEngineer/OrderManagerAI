# OrderManager AI

A market data application with a Spring Boot backend and React frontend. The backend streams real-time stock prices via Redis Pub/Sub and WebSocket, consumes events from Confluent Kafka, and exposes a REST API.

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 20+ and npm (for running the frontend in dev mode)
- Access to a Redis instance and a Confluent Kafka cluster (credentials via `.env`)

## Project Structure

```
OrderManagerAI/
├── marketdata/   # Spring Boot service (port 8080)
└── frontend/     # React app (port 3000)
```

## Local secrets setup

Create a `.env` file at the repo root (already gitignored) with your credentials:

```
REDIS_PASSWORD=...
KAFKA_CLUSTER_API_KEY=...
KAFKA_CLUSTER_API_SECRET=...
```

These map to `${REDIS_PASSWORD}`, `${KAFKA_CLUSTER_API_KEY}`, and `${KAFKA_CLUSTER_API_SECRET}` placeholders in `marketdata/src/main/resources/application.properties`.

**VS Code:** The `.vscode/launch.json` config loads `.env` automatically via `envFile` — just use Run and Debug (`Ctrl+Shift+D`) and select **MarketData**.

**Terminal:** Export the variables before running:

```bash
export $(cat .env | xargs) && mvn spring-boot:run
```

## Build

Build all modules from the project root:

```bash
mvn package
```

## Run

### Backend

```bash
cd marketdata
export $(cat ../.env | xargs)   # skip if using VS Code launch config
mvn spring-boot:run
```

Available endpoints:
- `GET http://localhost:8080/api/marketdata` — returns current stock list from Redis
- `WS  ws://localhost:8080/ws/marketdata` — WebSocket stream of live price updates
- `GET http://localhost:8080/actuator/health` — health check

### Frontend (dev mode)

Open a separate terminal:

```bash
cd frontend
npm install   # only needed the first time
npm start
```

The app opens at `http://localhost:3000` and displays live market data fetched from the backend.

> **Note:** Run the backend before starting the frontend. On Windows use a **bash** terminal (Git Bash) — `npm` may not work in PowerShell by default due to execution policy restrictions.

## Tests

```bash
cd marketdata
mvn test
```

## Azure deployment

Infrastructure is managed with Terraform in the `terraform/` directory. Secrets are configured as App Service Application Settings — see the Terraform variables for required values.
