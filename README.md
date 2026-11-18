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
├── mcpservice/   # MCP server for AI trading agents (port 8084)
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

### MCP server for AI agents

`mcpservice` (port 8084) exposes `get_market_prices`, `place_order` and `cancel_order` as MCP tools at `/mcp` (Streamable HTTP). Run it alongside the market data, order, risk and account services:

```bash
cd mcpservice
export $(cat ../.env | xargs)   # needs OIDC_ISSUER_URI, OIDC_CLIENT_IDS, MCP_SCOPE
mvn spring-boot:run
```

Agents call it with a Cognito access token issued on behalf of an advisor with the `ordermanager/mcp.trade` scope (set `MCP_SCOPE` accordingly). For example, with Claude Code:

```bash
claude mcp add --transport http ordermanager http://localhost:8084/mcp \
  --header "Authorization: Bearer <token>"
```

The MCP Inspector (`npx @modelcontextprotocol/inspector`) works too. Per-order limits are set with `MCP_MAX_QUANTITY_PER_ORDER` (default 1000) and `MCP_MAX_NOTIONAL_PER_ORDER` (default 50000).

## Tests

```bash
cd marketdata
mvn test
```

## AWS deployment

Infrastructure is managed with Terraform in `terraform/` (ECS Fargate, ALB, CloudFront + S3, Cognito, ECR, Secrets Manager). There is no custom domain: everything is served from one `https://<id>.cloudfront.net` address, with the advisor chat under `/advisor/`. See [docs/architecture.md](docs/architecture.md#aws-deployment) for the topology.

```bash
cd terraform
./bootstrap.sh                                  # one-time: S3 state bucket + terraform init
cp terraform.tfvars.example terraform.tfvars    # budget email, advisor emails
terraform apply
```

The app URL is printed by `terraform output frontend_url`; the advisor chat is at `<that URL>/advisor/` (locally: http://localhost:8085/advisor/).

Then create an access key for the deploy user and store it, with the other values, in GitHub (the account's managed policy blocks GitHub OIDC):

```bash
aws iam create-access-key --user-name "$(terraform output -raw github_deploy_user)"   # shown once
gh secret set AWS_ACCESS_KEY_ID
gh secret set AWS_SECRET_ACCESS_KEY
gh variable set FRONTEND_BUCKET --body "$(terraform output -raw frontend_bucket)"
gh variable set CLOUDFRONT_DISTRIBUTION_ID --body "$(terraform output -raw cloudfront_distribution_id)"
```

Run the **Deploy to AWS** workflow to push the first images, and start everything with `scripts/env.sh up`. The account's assigned Region is us-east-2 (new-style AWS accounts can only create regional resources in their own Region).

To save money when you are not working on it, run `scripts/env.sh down` (stops all tasks and removes the ALB). The **Environment — start / stop services** workflow starts and stops only the tasks.

The previous Azure setup is preserved in the `azure-final` git tag.
