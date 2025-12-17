# Glossary

- API Gateway: Edge service that routes, aggregates, and secures requests to backend microservices.
- Agent Framework: Infrastructure and libraries enabling automation agents to run tasks, integrate with services, and orchestrate workflows.
- Agent Structure System: Framework of specialized AI agents for positivity POS development
- Agent: A specialized AI assistant with domain-specific expertise
- Microservice: Independent Spring Boot application with its own data store
- POS System: Point of Sale system for retail/automotive service operations
- Domain Boundary: Logical separation between different business capabilities
- Agent Response: Guidance provided by agents to developers
- Discovery (Service Discovery): Mechanism for services to register and discover each other (e.g., registry/consul/eureka-like patterns).
- DTO (Data Transfer Object): Schema for requests/responses between services and clients; defines stable API contracts.
- Event Receiver: Ingress component that accepts external events/webhooks and translates them into internal domain events.
- Events Module: Shared event models and publishing/subscription utilities for asynchronous processing.
- Fitment: Compatibility determination for vehicle/tire/wheel assemblies (e.g., size, bolt pattern, offset).
- Inventory (POS): Stock levels and movements for products and vehicle-specific items.
- NHTSA: U.S. National Highway Traffic Safety Administration; provides vehicle reference data.
- CarAPI: Third-party vehicle data provider used for reference and enrichment.
- Order Lifecycle: Stages from quote → order → fulfillment → invoicing → settlement.
- People Domain: Non-auth user/party domain (profiles, roles, associations) distinct from security service.
- Positivity: The monorepo containing POS-related microservices for Durion/TIOTF initiatives.
- Pricing: Rules, discounts, and calculations used to price products and services.
- Security Service: Authentication/authorization microservice providing tokens, roles, and policy enforcement.
- Shop Manager: Module for operational workflows in service shops (appointments, bays, technicians, tasks).
- Work Order: Job record detailing tasks, parts, labor, and status within shop operations.
- Vehicle Inventory: Inventory tied to specific vehicles (e.g., used car lot, incoming/outgoing units).
- Vehicle Reference: External reference datasets used to enrich or validate vehicle attributes.
- Inquiry: Read-only search/exploration endpoints across catalog, orders, customers, etc.
- Image Service: Storage/transformation endpoints for product and document images.
- Location Service: Store/site/location metadata (addresses, hours, capabilities).
- Accounting: Financial postings, ledgers, and reconciliation related to POS operations.
- Customer (CRM-lite): Customer records, contacts, preferences, basic CRM operations.
- Product/Catalog: Master data for products, categories, attributes, and availability.

## Agent Framework Terminology

- Architecture Agent: Core agent providing system-wide architectural guidance and design patterns for microservice boundaries and integration.
- Implementation Agent: Core agent specializing in Spring Boot microservice development, business logic, and data access patterns.
- Deployment Agent: Core agent focused on Docker containerization, AWS Fargate deployment, and CI/CD pipeline design.
- Testing Agent: Core agent providing comprehensive testing strategies including unit, integration, and contract testing.
- Architectural Governance Agent: Specialized agent enforcing domain-driven design principles, preventing circular dependencies, and managing technical debt.
- Integration & Gateway Agent: Specialized agent for API Gateway integration, REST API design, OpenAPI specifications, and external service patterns.
- Security Agent: Specialized agent ensuring comprehensive security across microservices including JWT authentication, OWASP compliance, and secrets management.
- Observability Agent: Specialized agent for OpenTelemetry instrumentation, Grafana dashboards, Prometheus metrics, and distributed tracing.
- Documentation Agent: Specialized agent for technical documentation standards, API documentation, and documentation synchronization with code.
- Business Domain Agent: Specialized agent providing POS-specific domain knowledge, payment processor integration, and business workflow patterns.
- Pair Programming Navigator Agent: Specialized agent for real-time collaboration, implementation loop detection, and mandatory stop-phrase interruption.

## Context Management Terminology

- Context Integrity: Validation that all required project information is available before providing guidance or making decisions.
- Session Context: Temporary working document (`.ai/session.md`) maintaining continuity across multi-step development tasks.
- Context Re-anchoring: Process of returning to authoritative project files when context becomes insufficient or contradictory.
- Stop-Phrase: Mandatory interruption mechanism used by pair programming agents to halt problematic implementation patterns.
- Loop Detection: Automated identification of repetitive or stalled implementation progress requiring intervention.
- Architectural Drift: Deviation from established design patterns and domain boundaries during implementation.

## Property-Based Testing Terminology

- Correctness Property: Formal statement about system behavior that should hold true across all valid executions.
- Property-Based Test (PBT): Automated test that validates correctness properties across randomly generated inputs using frameworks like jqwik.
- jqwik: Property-based testing framework for Java used to validate universal properties with configurable iteration counts.
- Domain Coverage Property: Correctness property ensuring all required agent domains are available for guidance requests.
- Guidance Quality Property: Correctness property validating that agent recommendations follow established patterns and best practices.
- Collaboration Consistency Property: Correctness property ensuring multi-agent recommendations are consistent and conflict-free.
