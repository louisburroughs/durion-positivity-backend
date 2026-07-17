# Durion Positivity Backend

![Java](https://img.shields.io/badge/Java-25-007396)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-6db33f)
![Branch](https://img.shields.io/badge/branch-main-brightgreen)

## Overview

Durion backend is a multi-module Java platform for POS capabilities, organized
as independent Spring Boot services plus shared libraries. Services communicate
through the API gateway, service discovery, and event-driven integrations.

## Tech Stack

- Java `25`
- Spring Boot `4.1.0`
- Spring Cloud `2025.1.2`
- Maven wrapper (`./mvnw`)
- PostgreSQL 16 / TimescaleDB
- Kafka
- OpenTelemetry, Prometheus, Grafana, Jaeger

## Prerequisites

- Java 25 (SDKMAN recommended)
- Docker and Compose v2
- Git

Optional but recommended:

- SDKMAN auto-env support (`.sdkmanrc`)

## Quick Start

```bash
# Build all modules
./mvnw clean package

# Start local stack
docker compose up -d

# Run gateway-focused build and start
./mvnw -pl pos-api-gateway -am clean package
./mvnw -pl pos-api-gateway spring-boot:run
```

## Common Commands

```bash
# Full verification
./mvnw clean verify

# All tests
./mvnw clean test

# Build one service with dependencies
./mvnw -pl pos-order -am clean package

# Test one service
./mvnw -pl pos-order -am test

# Run one service directly
cd pos-order
../mvnw spring-boot:run
```

## Repository Layout

Top-level module groups:

- Core runtime services: gateway, discovery, security, event receiver
- Domain services: accounting, catalog, customer, documents, image, inquiry, inventory, invoice, location, order, people, price, shop manager, tax, vehicle services, workorder, bulk loader, MCP server
- Shared libraries: events, shared DTOs, security common, tax common, document helper, bulk ingest lib, dependency BOM
- Quality and validation modules: ArchUnit and OpenAPI validation

Key modules:

- Platform services: `pos-api-gateway`, `pos-service-discovery`, `pos-security-service`, `pos-event-receiver`
- Domain services: `pos-order`, `pos-workorder`, `pos-accounting`, `pos-inventory`, `pos-customer`, `pos-catalog`, `pos-invoice`, `pos-price`, `pos-location`, `pos-people`, `pos-shop-manager`, `pos-tax`, `pos-documents`, `pos-image`, `pos-bulk-loader`, `pos-mcp-server`

## Standards and Workflow

Security and platform notes:

- External traffic enters through `pos-api-gateway`
- Gateway enforces authentication and propagates identity context
- Services must not trust externally supplied identity headers
- API version mediation is handled at the gateway

Development policy reminders:

- Read `AGENTS.md`, applicable ADRs, and module-local README files first
- Respect internal package boundaries and service interface exposure
- Keep ArchUnit architecture rules passing
- Follow event instrumentation standards (`pos-events`)

Observability and local endpoints:

- Config folders: `observability/`, `application-observability.yml`
- Gateway: `http://localhost:8080`
- Eureka: `http://localhost:8761`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Jaeger: `http://localhost:16686`

CI workflows in `.github/workflows/`:

- `ci.yml`
- `pr-checks.yml`
- `build-push-ecr.yml`
- `contract-sync.yml`
- `dependency-check.yml`
- `nightly-full-stack-compose.yml`

## References

- `docs/ARCHITECTURE_GUIDE.md`
- `docs/DEVELOPMENT_GUIDE.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/README.md`
- `AGENTS.md`
