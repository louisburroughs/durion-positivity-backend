# Durion Positivity Backend

This repository contains the Spring Boot microservice suite that powers the Durion POS platform. The main entry points are the API gateway, platform services, shared libraries, and domain modules.

## Quick Start

```bash
# Build one service and its deps
./mvnw -pl pos-order -am clean package

# Run backend tests
./mvnw clean test

# Start the local stack
docker compose up -d
```

## Key Areas

- `pos-api-gateway/` — ingress and route enforcement
- `pos-security-service/` — identity and RBAC
- `pos-event-receiver/` — event ingestion and audit pipeline
- `pos-*` domain modules — service implementations and business logic
- `pos-archunit/` — architecture validation rules

## Related Docs

- `AGENTS.md` — implementation rules and commands
- `../durion/knowledge-catalog/backend/` — catalog of backend modules
- `../durion/docs/` — ADRs and architecture references
