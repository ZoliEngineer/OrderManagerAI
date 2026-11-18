# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

# Run locally (requires .env with REDIS_PASSWORD, KAFKA credentials, OIDC_ISSUER_URI, OIDC_CLIENT_IDS)
cd marketdata && export $(cat ../.env | xargs) && mvn spring-boot:run

# Trading advisor chat UI on http://localhost:8085 (requires ANTHROPIC_API_KEY in .env and mcpservice on :8084)
cd advisorservice && export $(cat ../.env | xargs) && mvn spring-boot:run

# Local observability stack (Prometheus on http://localhost:9091, Grafana on http://localhost:3001; see observability/README.md)
cd observability && docker compose up -d
```


### Frontend (React — `frontend/`)
```bash
cd frontend && npm start         # Vite dev server on :3000
cd frontend && npm test          # Vitest
cd frontend && npm run typecheck # tsc (strict); Vite itself does not type-check
cd frontend && npm run lint      # typescript-eslint (type-aware)
cd frontend && npm run build     # tsc + vite build → dist/
```

In dev mode, the `server.proxy` block in `vite.config.ts` routes `/api/accounts` → 8081, `/api/orders` → 8082 and other `/api` and `/ws` → 8080, so the frontend talks directly to the local backends without CORS issues.


For major architecture, planning and service design tasks, see docs/requirements.md. Do not use it for coding, debugging, and general questions.

@docs/architecture.md
