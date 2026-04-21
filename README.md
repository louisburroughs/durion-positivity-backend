# Durion Positivity Backend

A domain-driven, event-sourced **Point of Sale (POS)** platform built as a suite of 35+ independent microservices. Each service owns its own database schema, API contract, and event streams. Communication is either synchronous REST (via the API gateway) or asynchronous (via Kafka and the internal event bus).

---

## Table of Contents

- [Durion Positivity Backend](#durion-positivity-backend)
  - [Table of Contents](#table-of-contents)
  - [Architecture Overview](#architecture-overview)
  - [Service Catalog](#service-catalog)
    - [Infrastructure](#infrastructure)
    - [Domain Services](#domain-services)
    - [Shared Libraries (not deployed)](#shared-libraries-not-deployed)
  - [Technology Stack](#technology-stack)
  - [Getting Started](#getting-started)
    - [Prerequisites](#prerequisites)
    - [Build](#build)
    - [Run Locally](#run-locally)
  - [API \& Authentication](#api--authentication)
    - [Authentication](#authentication)
    - [API Versioning](#api-versioning)
    - [Identity Headers (injected by gateway)](#identity-headers-injected-by-gateway)
    - [Error Envelope](#error-envelope)
  - [Observability](#observability)
  - [Configuration Reference](#configuration-reference)
  - [Project Structure](#project-structure)
  - [CI/CD](#cicd)
  - [Contributing](#contributing)
  - [Further Reading](#further-reading)

---

## Architecture Overview

```
                         ┌───────────────────────────────┐
                         │         External Clients        │
                         └──────────────┬────────────────┘
                                        │ HTTPS :8080
                         ┌──────────────▼────────────────┐
                         │         pos-api-gateway         │
                         │  JWT validation · routing       │
                         │  X-API-Version header rewrite   │
                         │  permission bitset injection    │
                         └──────────────┬────────────────┘
                                        │ lb:// (Eureka)
          ┌─────────────────────────────┼──────────────────────────┐
          │                             │                            │
  ┌───────▼──────┐           ┌──────────▼─────────┐      ┌─────────▼──────┐
  │ pos-security │           │  Domain Services    │      │ pos-event-     │
  │   -service   │           │  (see catalog below)│      │   receiver     │
  │ JWT issuer   │           │  (dynamic ports,    │      │ (event hub)    │
  │ RBAC         │           │   registered in     │      │                │
  └──────────────┘           │   Eureka @ :8761)   │      └────────────────┘
                             └─────────────────────┘
```

Key architectural decisions:

- **API Gateway is the security boundary.** All JWT validation and permission bitset decoding happens there. Downstream services trust the injected `X-Authorities` / `X-User-Id` headers.
- **Database per service.** Services hold only IDs to data owned by other services; no cross-service foreign keys.
- **API versioning via header.** Clients send `X-API-Version: N`; the gateway rewrites the path to `/{domain}/vN/...` before forwarding.
- **UUID v7** is used for all primary keys (time-ordered, globally unique; see `docs/UUID_V7_MIGRATION.md`).
- **Event emission via AOP.** Services annotate methods with `@EmitEvent`; the `pos-events` library publishes to `pos-event-receiver` asynchronously.

---

## Service Catalog

### Infrastructure

| Service | Default Port | Role |
|---|---|---|
| `pos-api-gateway` | **8080** | External entry point; JWT auth, routing, API versioning |
| `pos-service-discovery` | **8761** | Eureka registry |
| `pos-security-service` | dynamic | JWT issuer, OAuth2, role/permission lifecycle |
| `pos-event-receiver` | internal | Event aggregation hub (shared-secret auth, not public) |

### Domain Services

| Service | Domain | Key Responsibilities |
|---|---|---|
| `pos-customer` | CRM / Party | Accounts (commercial & person), contacts, vehicles, promotions, bulk ingest |
| `pos-order` | Orders | Sales order lifecycle, price overrides, cancellation |
| `pos-inventory` | Stock | Movements, returns, shortages, reservations |
| `pos-accounting` | Finance | GL posting, ledger, financial reporting, Stripe payments |
| `pos-catalog` | Products | Product definitions, catalog master data |
| `pos-invoice` | Invoicing | Invoice generation and delivery |
| `pos-workorder` | Work Orders | Work order processing (Kafka-driven) |
| `pos-tax` | Tax | External tax passthrough + configurable test mode |
| `pos-price` | Pricing | Dynamic pricing calculations |
| `pos-location` | Locations | Multi-store location management |
| `pos-people` | Employees | Employee profiles and assignments |
| `pos-shop-manager` | Operations | Shop-level management |
| `pos-vehicle-inventory` | Vehicle Stock | Vehicle stock tracking (Kafka listener) |
| `pos-vehicle-fitment` | Fitment | Part-to-vehicle compatibility data |
| `pos-vehicle-reference-carapi` | Ref Data | CarAPI integration for vehicle lookups |
| `pos-vehicle-reference-nhtsa` | Ref Data | NHTSA integration for vehicle lookups |
| `pos-image` | Images | Product and asset image storage/retrieval |
| `pos-documents` | Documents | Document generation and storage |
| `pos-bulk-loader` | Batch | Spring Batch bulk import orchestration |
| `pos-mcp-server` | AI | Model Context Protocol server (LangChain4j + Ollama) |

### Shared Libraries (not deployed)

| Library | Purpose |
|---|---|
| `pos-events` | AOP-based `@EmitEvent` annotation and event publishing |
| `pos-shared-dtos` | Shared request/response/error DTOs (`ApiError`, `InvoiceGenerationRequest`, etc.) |
| `pos-security-common` | Shared security utilities |
| `pos-tax-common` | Shared tax DTOs and enums |
| `pos-bulk-ingest-lib` | Bulk import support library |
| `pos-document-helper` | Document generation helpers |
| `pos-dependencies` | Internal BOM for dependency version management |
| `pos-archunit` | ArchUnit architecture test rules |

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 25 (Eclipse Temurin 25.0.2-tem) |
| Framework | Spring Boot 4.0.5 |
| Service mesh | Spring Cloud 2025.1.1 (Gateway, Eureka) |
| Build | Maven 3.8.1+ (wrapper included) |
| Database | PostgreSQL 16 + TimescaleDB |
| ORM / migrations | Spring Data JPA · Hibernate · Flyway |
| Messaging | Apache Kafka (Spring Kafka) |
| Security | Spring Security · JJWT HS256 |
| Caching | Caffeine |
| Observability | Micrometer · Prometheus · Grafana · OpenTelemetry · Jaeger |
| AI / ML | LangChain4j · Ollama · pgvector |
| Payments | Stripe SDK |
| Testing | JUnit 5 · Mockito · Testcontainers · RestAssured · ArchUnit |
| Code quality | Spotless · Checkstyle · SpotBugs · SonarCloud · JaCoCo |

---

## Getting Started

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 25 (Temurin) | Use SDKMAN!: `sdk env install` reads `.sdkmanrc` |
| Docker | 24+ | Required for PostgreSQL and observability stack |
| Docker Compose | v2 | Bundled with Docker Desktop |
| Maven | 3.8.1+ | Wrapper (`./mvnw`) included — no local install needed |

### Build

```bash
# Build all modules (skip tests for speed)
./mvnw clean package -DskipTests

# Build a single service and its dependencies
./mvnw -pl pos-order -am clean package -DskipTests

# Full build with tests
./mvnw clean verify

# Apply code formatting
./mvnw spotless:apply
```

### Run Locally

**Option 1 — Docker Compose (recommended)**

Starts the full stack: all services, PostgreSQL, Kafka, Prometheus, Grafana, Jaeger, and Ollama.

```bash
# Copy and fill in required secrets
cp .env.example .env   # set POSTGRES_PASSWORD, POS_SECURITY_API_SECRET, etc.

docker-compose up -d

# Verify the gateway is healthy
curl http://localhost:8080/actuator/health
```

**Option 2 — Bare JVM (minimal)**

```bash
# 1. Start infrastructure
cd pos-service-discovery && ../mvnw spring-boot:run &

# 2. Start the security service
cd pos-security-service && ../mvnw spring-boot:run &

# 3. Start any domain service with the dev profile
cd pos-order && ../mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev"
```

**Useful local ports**

| Service | URL |
|---|---|
| API Gateway | <http://localhost:8080> |
| Eureka Dashboard | <http://localhost:8761> |
| Swagger UI (aggregated) | <http://localhost:8080/swagger-ui.html> |
| Prometheus | <http://localhost:9090> |
| Grafana | <http://localhost:3000> |
| Jaeger UI | <http://localhost:16686> |
| PostgreSQL | `localhost:5432` |
| Ollama | <http://localhost:11434> |

---

## API & Authentication

### Authentication

```http
POST /security-service/v1/auth/login
Content-Type: application/json

{ "username": "...", "password": "..." }
```

Returns a signed JWT. Include it in all subsequent requests:

```http
Authorization: Bearer <token>
X-API-Version: 1
```

### API Versioning

Every request **must** include the `X-API-Version` header. The gateway rewrites the path before routing:

```
Client:  GET /customer/crm/accounts   X-API-Version: 1
Gateway: GET /customer/v1/crm/accounts  →  lb://CUSTOMER
Service: receives GET /v1/crm/accounts
```

### Identity Headers (injected by gateway)

| Header | Content |
|---|---|
| `X-User` | Username extracted from JWT |
| `X-User-Id` | User UUID |
| `X-Authorities` | Decoded permission bitset |
| `X-API-Version` | Forwarded API version |

Downstream services must **never** accept these headers from external clients — the gateway strips any inbound identity headers.

### Error Envelope

All error responses follow a standard envelope (see `docs/ERROR_ENVELOPE.md`):

```json
{
  "timestamp": "2026-04-21T12:00:00Z",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Account not found",
  "path": "/v1/crm/accounts/abc"
}
```

---

## Observability

All services expose Spring Actuator endpoints. The OTEL Java agent is bundled in every Docker image.

| Signal | Endpoint / URL |
|---|---|
| Health | `GET /actuator/health` |
| Metrics (Prometheus) | `GET /actuator/prometheus` |
| Tracing | Sent to OTEL collector → Jaeger at `:16686` |
| Structured logs | Logback JSON → Grafana via Loki (when configured) |

Configuration lives in `observability/`.

---

## Configuration Reference

Services are configured via Spring profiles. Each module has its own `application.yml` plus profile overlays.

| Profile | Use Case | Database |
|---|---|---|
| `dev` | Local bare-JVM development | H2 in-memory |
| `docker` | Docker Compose | PostgreSQL (internal network) |
| `alpha` | Staging/pre-prod | PostgreSQL (remote) |
| `prod` | Production | PostgreSQL (remote) |

**Key environment variables (Docker Compose)**

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL superuser password |
| `POS_SECURITY_API_SECRET` | Shared secret for service-to-service calls to security service |
| `POS_EVENTS_API_SECRET` | Shared secret for posting to event-receiver |
| `STRIPE_API_KEY` | Stripe payment integration (pos-accounting) |
| `EXA_API_KEY` | Exa web search (optional, pos-mcp-server) |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | Grafana login |

**Database initialization**

On first run, `postgres/init-databases.sql` is mounted and creates all 20+ per-service databases. Flyway handles schema migrations automatically on service startup.

---

## Project Structure

```
durion-positivity-backend/
├── pom.xml                      # Parent POM — centralised dependency versions
├── mvnw / mvnw.cmd              # Maven wrapper
├── .sdkmanrc                    # Java 25.0.2-tem lock (SDKMAN!)
├── docker-compose.yml           # Local dev orchestration
├── postgres/
│   └── init-databases.sql       # Creates all per-service databases
├── docs/                        # Architecture, development, operations guides
├── observability/               # Prometheus, Grafana, OTEL configs
├── build-tools/                 # Spotless / Checkstyle configs
├── scripts/                     # Helper scripts (Kafka setup, etc.)
├── deployment/
│   └── alpha/                   # Alpha/staging compose overrides
├── pos-api-gateway/
├── pos-service-discovery/
├── pos-security-service/
├── pos-customer/
├── pos-order/
├── pos-inventory/
├── pos-accounting/
├── pos-catalog/
├── pos-invoice/
├── pos-workorder/
├── pos-tax/
├── pos-price/
├── pos-location/
├── pos-people/
├── pos-shop-manager/
├── pos-vehicle-inventory/
├── pos-vehicle-fitment/
├── pos-vehicle-reference-carapi/
├── pos-vehicle-reference-nhtsa/
├── pos-image/
├── pos-documents/
├── pos-bulk-loader/
├── pos-mcp-server/
├── pos-event-receiver/
├── pos-events/                  # Shared library
├── pos-shared-dtos/             # Shared library
├── pos-security-common/         # Shared library
├── pos-tax-common/              # Shared library
├── pos-bulk-ingest-lib/         # Shared library
├── pos-document-helper/         # Shared library
├── pos-dependencies/            # Internal BOM
├── pos-archunit/                # Architecture tests
└── pos-coverage-aggregate/      # Aggregated JaCoCo reports
```

---

## CI/CD

| Workflow | Trigger | Purpose |
|---|---|---|
| `ci.yml` | PR · push to `main` | Detect changed modules, run tests, build images |
| `build-push-ecr.yml` | Push to `main` | Publish changed service images to ECR |
| `contract-sync.yml` | Schedule | Sync OpenAPI contracts |
| `dependency-check.yml` | Schedule | OWASP dependency vulnerability scan |
| `nightly-full-stack-compose.yml` | Nightly | Full-stack integration tests via Docker Compose |
| `pr-checks.yml` | PR | Code quality gates (Spotless, Checkstyle, SpotBugs) |

Docker images use `eclipse-temurin:25-jdk-alpine` as the base and include the Grafana OpenTelemetry Java agent (v2.9.0).

---

## Contributing

1. **Java version** — run `sdk env install` to activate the correct JVM.
2. **Format before committing** — `./mvnw spotless:apply`.
3. **Test your service** — `./mvnw -pl <module> -am clean verify`.
4. **Architecture rules** — `pos-archunit` enforces package-level boundaries; CI will fail if they are violated.
5. See `docs/DEVELOPMENT_GUIDE.md` for detailed guidance on adding new services, registering permissions, and using the event system.

---

## Further Reading

| Document | Location |
|---|---|
| Architecture Guide | `docs/ARCHITECTURE_GUIDE.md` |
| Development Guide | `docs/DEVELOPMENT_GUIDE.md` |
| Operations Runbook | `docs/OPERATIONS_RUNBOOK.md` |
| Error Envelope Spec | `docs/ERROR_ENVELOPE.md` |
| UUID v7 Migration | `docs/UUID_V7_MIGRATION.md` |
| API Gateway deep-dive | `pos-api-gateway/README.md` |
| Customer bulk ingest | `pos-customer/README.md` |
| Tax service config | `pos-tax/README.md` |
| Docs index | `docs/README.md` |
