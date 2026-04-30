# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- Java 21, Maven 3.x
- Node 20, npm
- Terraform (for infrastructure changes)

## Commands

### Backend (Spring Boot — `marketdata/`)
```bash
# Build
mvn package

# Run tests
cd marketdata && mvn test

# Run a single test class
cd marketdata && mvn test -Dtest=MarketDataServiceTest

# Full CI-style verification
cd marketdata && mvn clean verify -B

# Run locally (requires .env with REDIS_PASSWORD, KAFKA credentials)
cd marketdata && export $(cat ../.env | xargs) && mvn spring-boot:run
```

Test classes are in `marketdata/src/test/java/com/juzo/ai/ordermanager/`: `MarketDataControllerTest` and `MarketDataServiceTest`.

### Frontend (React — `frontend/`)
```bash
cd frontend && npm install   # first-time setup
cd frontend && npm start     # dev server on :3000
cd frontend && npm test      # run tests
cd frontend && npm run build # production build
```

In dev mode, `src/setupProxy.js` proxies `/api` and `/ws` requests to `http://localhost:8080`, so the frontend talks directly to the local backend without CORS issues.

### Docker
```bash
docker build -t ordermanagerai-marketdata marketdata/
docker build --build-arg REACT_APP_API_URL="http://localhost:8080" \
             --build-arg REACT_APP_WS_BASE="ws://localhost:8080" \
             --build-arg REACT_APP_AAD_CLIENT_ID="<client-id>" \
             --build-arg REACT_APP_AAD_TENANT_ID="<tenant-id>" \
             --build-arg REACT_APP_AAD_SCOPE="<scope>" \
             -t ordermanagerai-frontend frontend/
```

The frontend Docker image is a multi-stage build: Node builds the React app, then nginx serves the static output.

## Local Setup

Copy `.env.example` to `.env` (or create `.env`) with:
```
REDIS_PASSWORD=<password>
KAFKA_API_KEY=<key>
KAFKA_API_SECRET=<secret>
```

The backend reads these from environment at startup. Redis host/port and Kafka bootstrap are configured in `application.properties`; only secrets come from env.

@docs/architecture.md
@docs/requirements.md