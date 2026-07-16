# Durion Positivity Backend

Durion backend is a multi-module Java platform for POS capabilities, organized as independent Spring Boot services plus shared libraries. Services communicate through the API gateway, service discovery, and event-driven integrations.

## Current Technology Baseline

- Java `25`
- Spring Boot `4.1.0`
- Spring Cloud `2025.1.2`
- Maven wrapper (`./mvnw`)
- PostgreSQL 16 / TimescaleDB
- Kafka
- OpenTelemetry + Prometheus + Grafana + Jaeger

## Repository Structure

Top-level module types:

- Core runtime services: gateway, discovery, security, event receiver
- Domain services: accounting, catalog, customer, documents, image, inquiry, inventory, invoice, location, order, people, price, shop manager, tax, vehicle services, workorder, bulk loader, MCP server
- Shared libraries: events, shared DTOs, security common, tax common, document helper, bulk ingest lib, dependency BOM
- Quality/validation modules: ArchUnit and OpenAPI validation

## Key Modules

Infrastructure and platform services:

- `pos-api-gateway`
- `pos-service-discovery`
- `pos-security-service`
- `pos-event-receiver`

Representative business services:

- `pos-order`
- `pos-workorder`
- `pos-accounting`
- `pos-inventory`
- `pos-customer`
- `pos-catalog`
- `pos-invoice`
- `pos-price`
- `pos-location`
- `pos-people`
- `pos-shop-manager`
- `pos-tax`
- `pos-documents`
- `pos-image`
- `pos-bulk-loader`
- `pos-mcp-server`

## Prerequisites

- Java 25 (SDKMAN recommended)
- Docker + Compose v2
- Git

Optional but recommended:

- `sdk env install` support (`.sdkmanrc` exists)

## Build

```bash
# Full workspace build
./mvnw clean package

# Full verification (tests + checks)
./mvnw clean verify

# Build one service with dependencies
./mvnw -pl pos-order -am clean package
```

## Test

```bash
# All tests
./mvnw clean test

# Single module tests
./mvnw -pl pos-order -am test
```

## Local Run

Single service:

```bash
cd pos-order
../mvnw spring-boot:run
```

Gateway-focused flow:

```bash
cd ..
./mvnw -pl pos-api-gateway -am clean package
./mvnw -pl pos-api-gateway spring-boot:run
```

## Docker Compose Development

Start shared local stack:

```bash
docker compose up -d
```

This compose includes platform dependencies and observability components, including:

- PostgreSQL
- Prometheus
- Grafana
- Jaeger
- OTEL collector
- service containers

Use `.env.example` as the baseline for local environment variables.

## API and Security Notes

- External traffic enters through `pos-api-gateway`
- Gateway enforces auth and propagates identity context to downstream services
- Services should not trust externally provided identity headers
- API versioning and route mediation happen at the gateway layer

## Observability

Relevant folders:

- `observability/` (collector, Prometheus, Grafana setup)
- `application-observability.yml`

Common local endpoints:

- Gateway: `http://localhost:8080`
- Eureka: `http://localhost:8761`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Jaeger UI: `http://localhost:16686`

## Development Conventions

Before implementation, read:

- `AGENTS.md`
- applicable ADRs in `../durion/docs/adr/`
- module-local `README.md` files

Important conventions include:

- strict internal package boundaries with service interfaces as module API
- architecture enforcement through ArchUnit tests
- event instrumentation standards using `pos-events`
- null-safety annotation requirements and secure coding constraints

## CI Workflows

GitHub Actions in `.github/workflows/`:

- `ci.yml`
- `pr-checks.yml`
- `build-push-ecr.yml`
- `contract-sync.yml`
- `dependency-check.yml`
- `nightly-full-stack-compose.yml`

## Useful References

- `docs/ARCHITECTURE_GUIDE.md`
- `docs/DEVELOPMENT_GUIDE.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/README.md`
- `AGENTS.md`
