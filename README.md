# Durion Positivity Backend

The **Durion Positivity Backend** is the server-side microservice suite for the Durion platform. Built on **Java 25** and **Spring Boot 4.0.x**, it provides the core business logic, data persistence, and API gateway capabilities for the Point of Sale (POS) system.

This repository works in tandem with Durion frontend applications and is governed by the workspace-level policies in the [Durion](../durion/README.md) root repository.

## Architecture

The backend follows a domain-driven microservices architecture. Each bounded context (Accounting, Inventory, Order, etc.) is an independent service with its own database schema, API contract, and event streams.

### Conceptual Diagram

```ascii
+-------------------+      +-------------------+      +-------------------+
|                   |      |                   |      |                   |
|   Subscriber A    |      |   Subscriber B    |      |    Durion UI      |
| (Experience Layer)|      | (Experience Layer)|      |  (Frontend Apps)  |
|                   |      |                   |      |                   |
+-------------------+      +-------------------+      +-------------------+
        |                           |                           |
        |                           |                           |
        v                           v                           v
+-----------------------------------------------------------------------+
|                       API Gateway (Spring Cloud Gateway)              |
|          - Routing, Load Balancing, JWT Validation, Route Control     |
+-----------------------------------------------------------------------+
        |                                       |
        |                                       |
        v                                       v
+-------------------+                   +-------------------+
|                   |                   |                   |
|  Security Service |                   |   Metrics Service |
| (OAuth2/JWT AuthN)|<--------------->|    (Telemetry)    |
|                   |                   |                   |
+-------------------+                   +-------------------+
        ^                                       ^
        |                                       | (Async Communication)
        | (Service Registration)                |
        |                                       |
+-----------------------------------------------------------------------+
|                       Service Discovery                               |
+-----------------------------------------------------------------------+
        ^                       ^                       ^
        |                       |                       |
        |                       |                       |
+-------------------+   +-------------------+   +-------------------+
|   pos-inventory   |   |     pos-order     |   |   pos-accounting  |
|   - Spring Boot   |   |   - Spring Boot   |   |   - Spring Boot   |
|   - PostgreSQL    |   |   - PostgreSQL    |   |   - PostgreSQL    |
|   - REST APIs     |   |   - REST APIs     |   |   - REST APIs     |
+-------------------+   +-------------------+   +-------------------+
```

### Key Components

- **API Gateway (`pos-api-gateway`)**: The single entry point for all client requests. Handles routing, cross-cutting concerns (CORS, headers), and initial request validation.
- **Security Service (`pos-security-service`)**: System of record for identities, roles, permissions, and assignments. Issues JWTs and owns role/permission lifecycle.
- **Domain Services**: Independent `pos-*` modules packaging business logic (e.g., `pos-order` for checkout flows, `pos-inventory` for stock management).
- **Core Services**: Foundational modules like `pos-service-discovery` (if applicable) and shared libraries.

## Technology Stack

- **Language**: Java 25
- **Framework**: Spring Boot 4.0.x
- **Build System**: Maven (via `./mvnw` wrapper)
- **Database**: PostgreSQL (Each service owns its own schema/database)
- **Infrastructure**: Docker & Docker Compose (for local development)

## Project Structure

The project is organized as a multi-module Maven build. Key directories include:

```
durion-positivity-backend/
├── pom.xml                 # Parent POM handling dependency versions
├── mvnw / mvnw.cmd         # Maven wrapper scripts
├── docs/                   # Backend-specific architectural documentation
├── pos-api-gateway/        # Edge gateway service
├── pos-security-service/   # Auth & Identity service
├── pos-accounting/         # Accounting & Ledger domain
├── pos-order/              # Order placement & processing
├── pos-inventory/          # Stock tracking & reservations
├── pos-customer/           # Customer profiles & loyalty
├── pos-catalog/            # Product catalog & definitions
└── ... (other pos-* modules)
```

## Quick Start

### Prerequisites

- **Java 25+** (Recommended: Use [SDKMAN!](https://sdkman.io/) for automatic version management)

  ```bash
  # Install SDKMAN! (if not already installed)
  curl -s "https://get.sdkman.io" | bash

  # After installation, cd into the project directory
  # SDKMAN! will automatically switch to Java 25.0.2-tem based on .sdkmanrc
  cd durion-positivity-backend
  sdk env install  # Install the required Java version if needed
  ```

- **Docker** (for running databases/infrastructure)
- **Maven** (optional, wrapper provided)

### Build & Run

1. **Build the entire suite**:

    ```bash
    ./mvnw clean package
    ```

2. **Run a specific service** (e.g., Order Service):

    ```bash
    cd pos-order
    ../mvnw spring-boot:run
    ```

    *Note: Ensure dependent infrastructure (PostgreSQL, Registry, etc.) is running via Docker.*

3. **Run Tests**:

    ```bash
    ./mvnw test
    ```

### Frontend SSR Host Allowlist (Docker Compose)

When running `pos-frontend` via `docker-compose.yml`, set `NG_ALLOWED_HOSTS` in your `.env` file.
This controls Angular SSR host validation and should be a comma-separated list of allowed hostnames/IPs.

Example:

```bash
NG_ALLOWED_HOSTS=durionpos.org,www.durionpos.org,34.202.151.97
```

### Local Kafka For `pos-workorder`

Start a single-node local Kafka stack (broker + Kafka UI + topic init):

```bash
./scripts/workorder-kafka-local.sh up
```

Run `pos-workorder` against local Kafka:

```bash
./mvnw -pl pos-workorder spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=dev,local-kafka --server.port=8090"
```

Useful endpoints:

- Kafka bootstrap server: `localhost:9092`
- Kafka UI: `http://localhost:8098`

Stop and clean up Kafka local stack:

```bash
./scripts/workorder-kafka-local.sh down
```

For detailed agent commands and local stack setup, refer to [AGENTS.md](AGENTS.md).

## Known Issues & Migration Notes

### Spring Boot 4.0.2+ MockMvc Import Change

**Issue**: The `@AutoConfigureMockMvc` annotation has moved in Spring Boot 4.0.2+.

**Old Import** (Spring Boot 3.4.x and earlier):

```java
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
```

**New Import** (Spring Boot 4.0.2+):

```java
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
```

**Impact**: Integration tests using `@AutoConfigureMockMvc` will fail to compile until the import is updated.

**Resolution**: Update all test classes to use the new import path. This affects contract behavior integration tests that use MockMvc for REST API testing.

## Agents & Documentation

This repository participates in the workspace-wide agent ecosystem.

- **Agent Guide**: [AGENTS.md](AGENTS.md) (Local context and commands)
- **Architecture Guide**: [docs/ARCHITECTURE_GUIDE.md](docs/ARCHITECTURE_GUIDE.md) (Docker, ports, service communication, observability)
- **Development Guide**: [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md) (OpenAPI, POM, version management, pos-events)
- **Operations Runbook**: [docs/OPERATIONS_RUNBOOK.md](docs/OPERATIONS_RUNBOOK.md) (Operations, RBAC, permissions)
- **Workspace Agents**: [../durion/AGENTS.md](../durion/AGENTS.md)

Refer to the root [Durion](../durion/README.md) repository for governance, ADRs, and shared architectural standards.

## Gateway Authentication & Headers

See [pos-api-gateway/README.md](pos-api-gateway/README.md) for gateway authentication boundary behavior.

- Injected headers:
  - X-Authorities: canonical authority list for service-level `@PreAuthorize` checks
  - X-User: authenticated subject
  - X-User-Id: stable user identifier from JWT `uid` claim (legacy `userId` claim is compatibility-only)
- JWT issuer and role/permission authority: `pos-security-service` (see [ADR-0011](../durion/docs/adr/0011-api-gateway-security-architecture.adr.md))
