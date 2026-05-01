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

# Run locally (requires .env with REDIS_PASSWORD, KAFKA credentials)
cd marketdata && export $(cat ../.env | xargs) && mvn spring-boot:run
```


### Frontend (React — `frontend/`)
```bash
cd frontend && npm start     # dev server on :3000
cd frontend && npm test      # run tests
cd frontend && npm run build # production build
```

In dev mode, `src/setupProxy.js` proxies `/api` and `/ws` requests to `http://localhost:8080`, so the frontend talks directly to the local backend without CORS issues.


For major architecture, planning and service design tasks, see docs/requirements.md. Do not use it for coding, debugging, and general questions.

@docs/architecture.md
