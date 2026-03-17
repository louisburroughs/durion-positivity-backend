# PRD: Durion Positivity Backend SDK

Status: Draft
Date: 2026-03-16
Owner: Platform API / SDK

## Summary

Build a first-party SDK as a standalone project outside both `durion` and
`durion-positivity-backend`. The SDK should be generated from module-level
OpenAPI files in `durion-positivity-backend`, with `openapi.yaml` treated as
canonical when both YAML and JSON are present, and shaped by behavioral rules
in the sibling `../durion/domains/*/.business-rules/` workspace.

The SDK should make the backend feel like one coherent platform instead of 19 loosely related service contracts. It must preserve contract fidelity to OpenAPI while adding domain-aware ergonomics for workflows such as approvals, state transitions, retries, idempotent mutations, and security-sensitive operations.

## Problem

Durion currently has:

- 19 service-level OpenAPI specs plus 1 gateway transport spec in this repo.
- 601 documented operations across the 19 service-level specs.
- 14 domain business-rules directories in the sibling `../durion/domains` workspace that describe behavior, invariants, permissions, and workflow intent.
- Cross-cutting ADRs that define security, response codes, service boundaries, internal-only services, and UUID contract expectations.

What is missing is a single supported SDK that:

- hides per-service transport details behind one client experience,
- applies the required gateway headers and authentication consistently,
- gives consumers typed request and response models,
- reflects important domain workflows instead of exposing only raw HTTP,
- and stays in sync with rapidly evolving contracts.

Without an SDK, every consumer must rediscover auth rules, path conventions, correlation headers, idempotency behavior, and domain-specific workflows independently.

## Vision

Provide a first-party SDK that lets product teams integrate with Durion backend capabilities through stable, typed, domain-oriented clients while preserving strict alignment with the authoritative OpenAPI contracts.

## Current Repo Snapshot

- `openapi.yaml` exists for 20 modules: 19 service modules plus `pos-api-gateway`.
- `openapi.json` also exists for 13 modules; these artifacts are not consistently present across the repo and should be treated as compatibility outputs, not the canonical generation source.
- The largest current service specs by operation count are:
  - `pos-workorder`: 85
  - `pos-accounting`: 83
  - `pos-security-service`: 82
  - `pos-inventory`: 63
  - `pos-catalog`: 52
  - `pos-people`: 43
- The gateway whitelist currently exposes 17 route prefixes, but `pos-inquiry` does not currently publish an `openapi.yaml` file, so it is not yet a viable SDK generation source.

## Goals

- Generate strongly typed clients from each `pos-*/openapi.yaml`.
- Publish one coherent SDK surface with module clients for the gateway-whitelisted modules that currently publish contracts: accounting, catalog, customer, inventory, invoice, location, order, people, price, security-service, shop-manager, image, event-receiver, vehicle-fitment, vehicle-inventory, and workorder.
- Encode cross-cutting transport defaults:
  - gateway base URL,
  - `X-API-Version`,
  - bearer token auth,
  - `X-Correlation-Id`,
  - `Idempotency-Key` for retry-safe mutations.
- Reflect business-rule semantics in helper APIs and docs:
  - approval flows,
  - lifecycle transitions,
  - retry and reprocess operations,
  - async or saga-style operations,
  - read-only actor fields populated by the backend,
  - permission-sensitive mutations.
- Separate public platform APIs from internal-only service contracts.
- Call out routed-but-not-generatable surfaces such as `pos-inquiry` until an OpenAPI source is added.
- Establish a repeatable generation and release workflow that can run whenever OpenAPI changes.

## Non-Goals

- Replacing OpenAPI as the source of truth.
- Re-implementing backend business logic in the SDK.
- Exposing internal `internal.*` Java package contracts from services.
- Hiding all HTTP details completely; advanced consumers should still be able to inspect requests, headers, and raw responses.
- Shipping a public SDK for services explicitly designated internal-only by architecture policy unless an internal profile is requested.

## Target Users

- Frontend and app teams integrating with gateway-exposed backend APIs.
- Internal platform teams building orchestration, automation, and agent tooling.
- QA and integration-test authors who need typed fixtures and predictable client behavior.
- Future partner or extension teams, if Durion chooses to externalize parts of the platform.

## Recommended Product Shape

- Deliver one first-party SDK package with sub-clients per domain or module.
- Generate the raw operation layer from OpenAPI.
- Add a thin handwritten layer for cross-cutting configuration and workflow helpers.
- Treat the gateway OpenAPI as transport metadata, not as the sole source of individual service operations, because `pos-api-gateway/openapi.yaml` currently documents headers and routes but exposes no operation paths.
- Default to public, gateway-safe modules.
- Support an optional internal profile for internal-only service clients if platform teams need it.

## Source Material

### Canonical API Sources

- Prefer `pos-*/openapi.yaml` as the canonical source for generation.
- Use `pos-*/openapi.json` only as a compatibility artifact where present; do not require it repo-wide.
- Notable module sizes by operation count:
  - `pos-workorder`: 85
  - `pos-accounting`: 83
  - `pos-security-service`: 82
  - `pos-inventory`: 63
  - `pos-catalog`: 52
  - `pos-people`: 43

### Domain Behavior Sources

Use the contents of `../durion/domains/{domain}/.business-rules/`, especially:

- `BACKEND_CONTRACT_GUIDE.md`
- `BACKEND_API_REFERENCE.generated.md`
- `DOMAIN_NOTES.md`
- `CROSS_DOMAIN_INTEGRATION_CONTRACTS*.md`
- `PERMISSION_TAXONOMY.md`
- workflow files such as:
  - `WORKORDER_STATE_MACHINE.md`
  - `CUSTOMER_APPROVAL_WORKFLOW.md`
  - `CHANGE_REQUEST_WORKFLOW.md`

### Cross-Cutting Reference Sources

- `docs/ARCHITECTURE_GUIDE.md`
- `docs/DEVELOPMENT_GUIDE.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `../durion/docs/architecture/api/BACKEND_CONTRACT_GLOBAL_STANDARDS.md`

## ADRs That Should Inform SDK Design

- ADR-0011: API Gateway security architecture
  - Source security semantics from `pos-security-service`.
  - Assume gateway-authenticated traffic and bearer-token usage.
- ADR-0014: Gateway whitelist routing
  - Do not assume every service is publicly reachable.
  - Internal-only services must not be part of the public SDK surface by default.
- ADR-0017: API controller HTTP response codes standard
  - Normalize error handling around the canonical status model and shared error envelope.
- ADR-0021: Tax API consumption and internal access policy
  - `pos-tax` should be internal-only unless an internal SDK profile is intentionally enabled.
- ADR-0025: Permissions manifest registration policy
  - SDK docs should reference canonical permission names where operations are permission-sensitive.
- ADR-0026: Service contract boundary policy
  - SDK contract source is OpenAPI and public service contracts only, never internal implementation details.
- ADR-0027: UUID-typed identifier contract policy
  - SDK should strongly type UUID identifiers where the OpenAPI schema marks them as `format: uuid`.

## Functional Requirements

### 1. Unified Client Configuration

The SDK must provide a shared configuration layer for:

- `baseUrl`
- `apiVersion` defaulting to `1`
- bearer token or token provider
- correlation ID provider
- retry policy hooks
- request timeout
- optional idempotency-key generator

### 2. Module and Domain Clients

The SDK must expose typed clients for gateway-whitelisted modules that currently publish OpenAPI contracts, including:

- Accounting
- Catalog
- Customer
- Event receiver
- Image
- Inventory
- Invoice
- Location
- Order
- People
- Price
- Security service
- Shop manager
- Vehicle fitment
- Vehicle inventory
- Workorder

The SDK may expose separate internal clients for:

- Tax
- Documents
- MCP server

Internal clients must be clearly labeled as non-public and opt-in.
`Inquiry` must not be promised as a generated client until `pos-inquiry` publishes an OpenAPI contract.

### 3. Raw Operation Fidelity

For every supported operation, the generated layer must preserve:

- `operationId`
- path, method, query, header, and body schema
- response typing by status code when feasible
- enum values
- UUID formats
- examples where present

### 4. Workflow Helpers Driven By Business Rules

The SDK should add thin handwritten helpers for common business workflows where raw operation calls are awkward. Initial helper candidates:

- Order price override submission, approval, and rejection.
- Workorder estimate creation, approval, promotion, and change-request flows.
- Inventory purchase order, ASN, receiving, and availability flows.
- Accounting event retry and reprocess workflows.
- Security login, token refresh, validation, role assignment, and permission inspection.

These helpers must compose raw operations rather than inventing new contract semantics.

### 5. Security and Authorization Ergonomics

The SDK must:

- support bearer-token auth aligned with ADR-0011,
- document permission-sensitive operations when known from business rules,
- expose auth-related clients from `pos-security-service`,
- avoid implying that downstream services own identity or role lifecycle.

### 6. Cross-Cutting Header Support

The SDK must support:

- `X-API-Version` on gateway traffic,
- `X-Correlation-Id` on all externally callable operations,
- `Idempotency-Key` on idempotent or retry-safe mutation operations.

The SDK should make correlation and idempotency easy to opt into globally and override per request. `X-Correlation-Id` should be modeled explicitly in OpenAPI as a standard request header and echoed in responses where applicable. Legacy body-field idempotency contracts should be treated as migration debt and normalized to the header in OpenAPI over time.

### 7. Error Model

The SDK must provide a standard error abstraction that preserves:

- HTTP status,
- backend error body,
- correlation ID,
- field errors when present,
- raw response access for debugging.

Status handling should reflect ADR-0017 semantics for `400`, `401`, `403`, `404`, `409`, `422`, and `500`.

### 8. Public vs Internal API Classification

The SDK release must classify APIs into:

- Public gateway-consumable
- Internal platform-only
- Experimental or draft

This classification should be derived from ADRs, gateway routing policy, and domain contract maturity.

### 9. Contract and Documentation Generation

The SDK build must generate:

- typed clients,
- API reference docs grouped by module and domain,
- usage examples for high-value workflows,
- changelog entries showing added, removed, or changed operations.

## Non-Functional Requirements

- Regeneration must be deterministic in CI.
- SDK releases must be traceable to OpenAPI source revisions.
- Consumers must be able to upgrade module-by-module without ambiguous breaking changes.
- The SDK should tolerate mixed module maturity, including draft domains.
- The handwritten wrapper layer must stay thin enough that OpenAPI regeneration remains cheap.

## Domain-Specific Design Implications

- Order business rules show approval-driven price override workflows and pending contract gaps for cart creation and cancellation. The SDK needs draft-safe handling and clear experimental markings.
- Workexec business rules suggest high-value workflow helpers around estimates, approvals, timers, labor, and change requests.
- Inventory rules define explicit behavioral semantics such as ATP calculation, adjustment approvals, and procure-to-receive lifecycle. These are strong candidates for examples and helper docs.
- Security rules and ADR-0011 make auth, token lifecycle, role assignment, and permission inspection first-class SDK concerns.
- Accounting has a large surface area and multiple idempotent and retry-oriented operations; it should be treated as a priority module for contract stability and error modeling.

## Delivery Plan

### Phase 1: Contract Foundation

- Define SDK package structure and target language.
- Build OpenAPI aggregation and generation pipeline.
- Implement shared transport configuration.
- Publish raw generated clients for highest-value modules:
  - security
  - order
  - inventory
  - workorder
  - accounting

### Phase 2: Public SDK Beta

- Add remaining public gateway-facing modules.
- Add standard error model and correlation support.
- Add idempotency helpers.
- Add workflow examples from business rules.
- Publish beta docs and migration notes.

### Phase 3: Workflow Layer

- Add thin handwritten helpers for approvals, retries, and lifecycle transitions.
- Add internal profile for internal-only consumers if still needed.
- Add contract-diff reporting and release automation.

## Acceptance Criteria

- A consumer can configure the SDK once and call multiple Durion modules through a unified client.
- Every supported operation is traceable back to an `operationId` in a module OpenAPI file.
- UUID-typed identifiers are modeled as strong SDK types where the target language supports it.
- Gateway-level headers and bearer auth can be applied globally.
- Permission-sensitive and idempotent operations are clearly documented.
- Internal-only APIs are excluded from the public default build.
- The SDK release process can detect OpenAPI changes and regenerate clients automatically.

## Risks and Current Gaps

### Contract Drift

Current source material shows some drift that should be resolved or explicitly handled:

- The sibling business-rules docs mix `openapi.json` and `openapi.yaml` references. The SDK pipeline should standardize on `openapi.yaml` as canonical and treat JSON as optional compatibility output.
- Domain behavior sources live in the sibling `../durion/domains` workspace, not in this repository. SDK generation and CI must either mount that workspace, vendor the needed files, or make the workflow-layer enrichment optional when those docs are unavailable.
- `pos-inquiry` is gateway-routed but does not currently publish `pos-inquiry/openapi.yaml`, so it cannot be generated into the SDK yet.
- Order business rules still contain TODO operations for cart creation and cancellation that are not fully anchored in the current OpenAPI.
- Inventory mixes `/v1/...`, `/api/...`, and `/api/v1/...` path styles, which may require normalization guidance in the SDK.

### Spec Hygiene

- `pos-api-gateway/openapi.yaml` documents headers and route prefixes but has no concrete `paths`, so it is useful for shared transport metadata but not sufficient as the only input to client generation.
- Idempotency should be standardized in OpenAPI as an explicit `Idempotency-Key` request header. Existing body-field or prose-only semantics should be migrated to the header or documented as temporary exceptions with clear replay/conflict behavior.
- Correlation tracing should be standardized in OpenAPI as an explicit `X-Correlation-Id` header across all externally callable modules, with response echo behavior documented consistently.
- Runtime security is centralized through `pos-security-service` and `pos-api-gateway`, but the module OpenAPI specs do not yet model that with one clearly shared, reusable security convention across all modules.

### Product Decisions Still Needed

The SDK must be implemented as a new, standalone project/repository and must
not live under either the `durion` or `durion-positivity-backend` repository.
Those repositories are input sources for contracts, ADRs, and domain behavior,
not the SDK implementation home.

The initial target framework for the SDK is Angular, but the implementation
should remain framework-agnostic across the broader JavaScript ecosystem. The
first pass is an internal SDK release focused on internal platform and product
teams, with an explicit plan to evolve toward an external-facing SDK in a later
phase.

The first pass is version `1`. SDK versioning should track the highest backend
API version represented by the generated contracts so consumers can quickly
understand compatibility boundaries.

Helper methods should be domain-based. Because Durion domains generally map
closely to modules, the helper surface should follow that mapping and avoid
overcomplicating abstractions.

## Additional Documentation That Would Improve SDK Creation

The SDK can be built from the current sources, but these documents would materially improve quality:

- A definitive gateway route inventory mapping public URL prefixes to backend modules and exposure level.
- A platform-wide auth and token usage guide with concrete request examples for gateway consumers.
- A single cross-module error envelope reference with real payload examples.
- A canonical idempotency standard describing which endpoints accept `Idempotency-Key` and the expected replay/conflict semantics.
- A deprecation and versioning policy for OpenAPI operations and SDK releases.
- A consumer-facing permission catalog derived from `permissions.yaml` so SDK docs can explain required authorities without scraping code.
- Example end-to-end workflows for the highest-value domains:
  - quote to order
  - estimate to workorder to invoice
  - purchase order to ASN to receiving
  - login to token refresh to protected call

## Recommendation

Proceed with an SDK program that is OpenAPI-first and business-rules-informed:

- OpenAPI remains the contract source of truth.
- Business-rules guides supply workflow semantics, examples, and helper priorities.
- ADRs define exposure, auth, error, and typing constraints.

Before implementation starts, align on target language, public versus internal scope, and a small contract-hygiene pass on the mismatches called out above.
