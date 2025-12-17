# Project Context

## Overview
Positivity is a multi-service, Java 21-based platform organized as a monorepo with Spring Boot microservices supporting retail/point-of-sale, catalog, customer, orders, pricing, inventory, events, images, vehicle fitment/reference, shop management, security, and service discovery. It includes an API gateway and agent framework components for automation and integration.

## Goals
- Provide modular, domain-focused services for POS and automotive/tire industry needs.
- Expose consistent REST APIs through an API Gateway.
- Support event-driven workflows via event receiver and events modules.
- Enable automation through an agent framework.

## Tech Stack
- Language: Java 21
- Framework: Spring Boot (3.x)
- Build: Maven (root `pom.xml` with `mvn`)
- Container: Docker Compose for local orchestration
- Discovery & Gateway: Service Discovery + API Gateway modules

## Repository Structure (high-level)
- `pos-accounting/` — accounting domain
- `pos-agent-framework/` — automation/agents infra
- `pos-api-gateway/` — edge/API aggregation
- `pos-catalog/` — product/catalog services
- `pos-customer/` — customer/party services
- `pos-event-receiver/` — inbound event ingestion
- `pos-events/` — event models and publishing
- `pos-image/` — image/media handling
- `pos-inquiry/` — inquiry/search endpoints
- `pos-inventory/` — stock/inventory services
- `pos-invoice/` — invoicing and billing
- `pos-location/` — store/site/location services
- `pos-order/` — order lifecycle management
- `pos-people/` — user/identity domain (non-auth)
- `pos-price/` — pricing/discounts
- `pos-security-service/` — authN/authZ services
- `pos-service-discovery/` — service registry
- `pos-shop-manager/` — shop/workflow management
- `pos-vehicle-fitment/` — fitment logic (tire/wheel compatibility)
- `pos-vehicle-inventory/` — vehicle-level inventory
- `pos-vehicle-reference-carapi/` — car API integration
- `pos-vehicle-reference-nhtsa/` — NHTSA data integration
- `pos-work-order/` — work orders/jobs

## Operational Notes
- Java 21 is enforced in Maven build; ensure local JDK 21 is active.
- Use Docker Compose for local end-to-end testing across services.
- API Gateway is the preferred external ingress for clients.
- Events modules coordinate asynchronous flows; avoid tight coupling between services.

## Adjacent Projects
- `moqui` — Durion frontend platform built on Moqui; integrates with Positivity services via REST.
- `durion/workspace-agents` — Java 17 Gradle project for agents/testing.

## Security & Compliance
- Secrets must come from environment variables or secret stores; never hardcoded.
- Follow OWASP best practices for input validation, authorization, and logging.

## Development Guidance
- Favor clear domain boundaries and DTOs for API contracts.
- Use parameterized queries and standard Spring patterns; no raw SQL concatenation.
- Write tests for critical paths (auth, pricing, order lifecycle, inventory updates).

## Context Management Rules

### Primary Rules
- If required inputs are missing from current context:
  SAY: "Context insufficient – re-anchor needed"
- If referring to decisions not found in:
  - current files
  - /ai/context.md
  - /ai/glossary.md
  STOP

### Temporary Context Store Rules
**Purpose:** Minimize redundant file reads and maintain continuity across multi-step tasks.

1. **Context Store Location:** Maintain `.ai/session.md` as a temporary working document for the current development session
2. **Session Initialization:** At the start of each task:
   - Check if `.ai/session.md` exists and is recent (updated within current session)
   - If yes: READ `.ai/session.md` first before re-reading project files
   - If no or stale: BEGIN from `.ai/context.md` and `.ai/glossary.md`
3. **What to Store in Session:**
   - Current task objective and status
   - Key architectural decisions made in this session
   - Recent file paths and structures accessed
   - Active requirements/constraints being addressed
   - Integration points or dependencies discovered
   - Open questions or blockers
4. **Session Updates:** After completing any subtask or making significant progress:
   - UPDATE `.ai/session.md` with findings, decisions, and next steps
   - INCLUDE: timestamp, current file state, discovered patterns, decisions made
   - OMIT: full file contents (link instead)
5. **Session Cleanup:** At task completion or session end:
   - PRESERVE key decisions and learnings in `.ai/context.md` if they're durable
   - DELETE or ARCHIVE `.ai/session.md` when starting a new unrelated task
6. **Conflict Resolution:** If session context contradicts project context:
   - Trust the permanent files (`.ai/context.md`, `.ai/glossary.md`, source files)
   - UPDATE session.md to reflect the authoritative state
7. **Large Tasks:** For multi-file edits or complex deployments:
   - Create an `.ai/session-{task-id}.md` variant if the session spans hours or multiple contexts
   - Link it in the main `.ai/session.md` for continuity
