# AGENTS.md — durion-positivity-backend

## Project Overview
POS backend microservice suite for Durion. Multi-module Maven project containing gateway + `pos-*` services (Spring Boot 3.x, Java 21).

## Quick Prerequisites
- Java 21+
- Maven (use `./mvnw` wrapper)
- Docker for local test stacks

## Setup & Build
```bash
cd durion-positivity-backend
./mvnw -pl pos-api-gateway -am clean package  # build gateway + deps
# Build a single service (example: pos-order)
./mvnw -pl pos-order -am clean package
```

## Run Locally
Run a single service:
```bash
cd durion-positivity-backend/pos-order
./mvnw spring-boot:run
# or
java -jar target/pos-order-*.jar
```

## Testing & Linting
```bash
# Run all backend tests
cd durion-positivity-backend
./mvnw -DskipTests=false clean test
# Module-only tests
./mvnw -pl pos-order -am test
```

## Observability (backend-focused)
- Prefer OpenTelemetry Java agent for baseline; use manual SDK instrumentation for high-value business metrics.
- Attach attributes: `service.name`, `service.version`, `deployment.environment`, `container_id`, `component`, `status`.
- Expose Actuator endpoints (`/actuator/health`, `/actuator/prometheus`) where applicable for monitoring.
- Reference: `../docs/architecture/observability/OBSERVABILITY.md` and `.github/agents/sre.agent.md`.

## Useful Commands
```bash
# Build and run gateway
./mvnw -pl pos-api-gateway -am spring-boot:run
# Run a module's tests
./mvnw -pl pos-order -am test
```

## Agent Docs to Consult
- `.github/agents/sre.agent.md` (observability)
- `.github/agents/dev-deploy.agent.md` (deploy/CI guidance)
- `../AGENTS.md` (workspace-level guidance)
- Backend test agent: `.github/agents/test.agent.md`

## Notes for Agents
- Do not hardcode credentials in CI or code. Use environment variables or secret stores.
- For incidents, follow cross-stack triage: frontend → gateway → backend service → DB.
