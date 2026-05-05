# Service Discovery And Load Balancer Migration Analysis

## Goal

Move internal service-to-service HTTP calls away from hardcoded `host:port` base URLs and onto Eureka service discovery plus Spring Cloud load balancing.

The target end state is:

- internal callers reference logical service IDs such as `http://security-service` or `http://people`
- only the called service owns its listen port via `server.port`
- Docker host-published ports remain an ops concern, not an application client concern
- request paths remain configurable where needed, but host/port duplication disappears

This analysis is based on a repo-wide scan of `RestClient`, `RestClient.Builder`, `DiscoveryClient`, `LoadBalancerClient`, `@LoadBalanced`, and `base-url` configuration usage on May 4, 2026.

## Current State

### What already exists

- Most runtime modules already include `spring-cloud-starter-netflix-eureka-client`.
- The API gateway already has a load-balanced client bean in [GatewayWebClientConfig.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayWebClientConfig.java).
- `pos-mcp-server` already uses discovery for dynamic OpenAPI tool registration:
  - `DiscoveryClient` in `OpenApiDocumentFetcher`
  - `LoadBalancerClient` in `OperationProxyFactory`

### What is missing

- A shared production `@LoadBalanced RestClient.Builder` pattern now exists in multiple modules
  (for example: `pos-catalog` SecurityConfig, `pos-customer` SecurityConfig, `pos-people`
  RestClientConfig, `pos-shop-manager` SecurityConfig, and `pos-mcp-server` McpServerConfiguration).
- Most `RestClient` usage still depends on explicit base URLs such as:
  - `http://pos-security-service:8080`
  - `http://pos-people:8080`
  - `http://localhost:8080`
  - gateway URLs like `http://localhost:8080/workorder/...`
- Several modules duplicate port knowledge across:
  - service `server.port`
  - Docker `ports:`
  - client `base-url` properties

### Core source-of-truth problem

Today, the same network truth is encoded in multiple places:

- the callee service's `server.port`
- `docker-compose.yml` internal and host-published ports
- calling modules' `base-url` properties

That is exactly what service discovery is meant to remove.

## Target Architecture

### Internal calls

For internal service-to-service traffic, use:

- Eureka registration
- Spring Cloud LoadBalancer
- `@LoadBalanced RestClient.Builder`
- logical base URLs such as:
  - `http://security-service`
  - `http://people`
  - `http://inventory`
  - `http://workorder`

Example target pattern:

```java
@Configuration
public class ServiceClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient securityServiceRestClient(@LoadBalanced RestClient.Builder builder) {
        return builder.baseUrl("http://security-service").build();
    }
}
```

### External calls

External systems should stay explicit and should not use Eureka:

- Ollama cloud
- Exa
- carapi
- NHTSA
- any third-party tax or reference API

### Gateway-only cases

Some calls currently go through the gateway on purpose. Those need a separate decision:

- keep gateway routing if gateway policy/path rewriting is required
- or move direct to the downstream service and forward the same auth headers

The migration is not “replace every URL blindly.” It is “replace internal topology-dependent URLs with service IDs.”

## Impact On Angular SDK And Frontend Consumers

This migration affects the backend internals much more than it affects the Angular SDK or the frontend runtime.

### What should not change for the frontend

- The browser must not participate in Eureka discovery.
- The Angular SDK must not resolve service IDs such as `http://people` or `http://security-service`.
- Frontend consumers should continue to target:
  - the API gateway, or
  - stable public backend base URLs exposed through gateway routing

The frontend runs outside the backend service mesh and cannot use backend-only service discovery semantics.

### Source of truth for the frontend

For `durion-positivity-sdk-angular` and `durion-positivity-frontend`, the source of truth remains:

- backend OpenAPI contracts
- gateway/public route structure
- environment-specific frontend API base URL configuration

Not:

- Docker internal service names
- backend container ports
- Eureka service IDs

### What can affect the SDK indirectly

Even though service discovery is internal, the migration can still affect SDK generation and frontend adoption if it causes any of the following:

- path changes
- request/response shape changes
- operationId changes
- tag changes that rename generated Angular service classes
- gateway route changes
- auth/header behavior changes on public endpoints

That means the migration must preserve external HTTP contracts unless a coordinated SDK/frontend change is intentional.

### Safe migration rule

Internal discovery migration is safe for the Angular SDK only if:

- external REST paths stay the same
- OpenAPI documents stay semantically the same
- gateway routing stays the same for frontend-facing traffic
- no public endpoint hostnames or prefixes are changed without coordinated SDK/frontend work

### Unsafe migration patterns

The following would create frontend or SDK fallout:

- replacing gateway-facing public paths with internal service-ID assumptions
- changing OpenAPI tags or operationIds as part of the refactor
- moving endpoints from gateway-routed paths to direct service-only paths without frontend coordination
- introducing backend-only routing assumptions into generated SDK docs or examples

### Practical implication

The service discovery migration should be treated as an internal transport refactor for backend-to-backend calls.

For the frontend and `durion-positivity-sdk-angular`, the expected outcome is:

- no change in how SDK clients are generated
- no change in browser-side base URL strategy
- no change in frontend environment configuration

Unless the migration also intentionally changes public gateway routes.

### Additional validation required

Any migration wave that touches a module with frontend-exposed APIs should include:

1. regenerate the affected OpenAPI output
2. diff the generated spec for path, tag, operationId, and schema drift
3. rebuild `durion-positivity-sdk-angular`
4. confirm no frontend consumer has to change unless that change was planned

Relevant existing docs in this repo:

- [PRD-sdk-migration-backend-unblock.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/docs/PRD-sdk-migration-backend-unblock.md)
- [PRD-missing-backend-endpoints.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/docs/PRD-missing-backend-endpoints.md)

## Source Of Truth After Migration

After migration, there should be two distinct truths:

1. Service listen port

- owned by the called service
- exposed as `server.port` / `SERVER_PORT`

2. Host-published port

- owned by deployment config only
- used for host access, local browser access, or ops tooling

Internal callers should not know either one.

That means:

- Docker `ports:` remains for host access
- internal callers use service IDs only
- Eureka instance metadata becomes the runtime source of truth for host and port resolution

## Required Cross-Cutting Changes

### 1. Introduce a shared load-balanced RestClient configuration

Recommended:

- add a reusable config in a shared module such as `pos-security-common`, or
- add the same pattern to each module that performs internal HTTP calls

Needed bean:

```java
@Bean
@LoadBalanced
public RestClient.Builder loadBalancedRestClientBuilder() {
    return RestClient.builder();
}
```

Likely companion bean:

- a non-load-balanced `RestClient.Builder` or explicit plain `RestClient` for external calls

Reason:

- some modules need both internal discovery-based calls and external calls
- the two use cases should not share the same base URL assumptions

### 2. Standardize service IDs

Clients must use Eureka service IDs, not Docker service names, unless they happen to match.

Examples already visible in the repo:

- `security-service`
- `people`
- `workorder`

Potential mismatch risk:

- Docker service names like `pos-security-service` and `pos-people`
- Spring application names like `security-service` and `people`

This must be normalized before migration, otherwise clients will compile but fail resolution at runtime.

### 3. Split base host selection from path templates

For internal clients:

- move the host portion to service ID based resolution
- keep per-endpoint path templates configurable only where route shape is unstable

Example:

- before: `http://pos-security-service:8080/v1/users`
- after:
  - base URL: `http://security-service`
  - path: `/v1/users`

### 4. Preserve request-level concerns

Migration must not drop:

- timeouts
- request factories
- custom headers like `X-User`, `X-Authorities`, `Authorization`
- retry and circuit breaker wrapping
- logging interceptors

### 5. Revisit gateway-based internal calls

Some modules currently call the API gateway instead of the target service directly.

Those must be audited individually because they may depend on:

- gateway route prefixes such as `/workorder/...`
- gateway auth propagation
- gateway-only filters

### 6. Strengthen tests

Needed tests:

- service discovery resolution tests
- failure behavior when no Eureka instance exists
- header propagation tests
- module boot tests ensuring the load-balanced builder is wired

## Module Inventory

This section classifies scanned `RestClient` usage. “Candidate” means good fit for service discovery. “External” means it should remain explicit. “Needs decision” means gateway-vs-direct or unclear service ownership.

### pos-mcp-server

Files:

- facade tools under [pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools)
- [PermissionRegistration.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/PermissionRegistration.java)
- [EventTypeInitializer.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/src/main/java/com/positivity/mcp/internal/config/EventTypeInitializer.java)
- discovery classes already using `DiscoveryClient` and `LoadBalancerClient`

Assessment:

- strong candidate
- best first adopter because the MCP server already depends conceptually on service discovery
- `ExaWebSearchTool` and Ollama/Exa model clients remain explicit external clients

Notes:

- MCP dynamic OpenAPI proxy path is already discovery-aware
- facade tools are still static `RestClient` clients and should be aligned with the same approach

### pos-accounting

Files:

- [CustomerBillingRulesClient.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-accounting/src/main/java/com/positivity/accounting/internal/client/CustomerBillingRulesClient.java)
- [InvoiceServiceClient.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-accounting/src/main/java/com/positivity/accounting/internal/client/InvoiceServiceClient.java)
- [WorkorderInvoiceClient.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-accounting/src/main/java/com/positivity/accounting/internal/client/WorkorderInvoiceClient.java)
- [RestClientConfig.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-accounting/src/main/java/com/positivity/accounting/internal/config/RestClientConfig.java)

Assessment:

- strong candidate
- currently concatenates full URLs in client methods
- should move to service-ID base URLs plus relative paths
- circuit breaker logic can remain unchanged

### pos-catalog

Files:

- `InventoryClientImpl`
- `PricingClientImpl`
- event/permission registration

Assessment:

- strong candidate for internal discovery

### pos-customer

Files:

- `PeopleClient`
- `VehicleInventoryClient`
- event/permission registration

Assessment:

- strong candidate

### pos-documents

Files:

- `DocumentEventTypeInitializer`
- `DocumentPermissionRegistration`

Assessment:

- candidate for internal infra calls
- likely straightforward

### pos-inventory

Files include:

- `ExternalAvailabilityClientImpl`
- `ProductSubstituteClientImpl`
- `SiteDefaultsClient`
- `SourceDocumentStubClient`
- `StorageLocationValidationClient`
- `WorkorderValidationClient`
- event/permission registration

Assessment:

- mixed

Breakdown:

- `WorkorderValidationClient`: needs decision
  - currently calls the gateway path `/workorder/v1/workorders/...`
  - could move direct to `http://workorder`, but only after confirming auth/header semantics
- `ExternalAvailabilityClientImpl`: likely explicit, not discovery, unless that endpoint is actually another internal service
- other internal validation/reference clients: likely good candidates

### pos-invoice

Files:

- `TaxServiceClient`
- event/permission registration

Assessment:

- strong candidate for internal tax service calls

### pos-location

Files:

- `PersonClient`
- `LocationInventoryInquiryClient`
- event/permission registration

Assessment:

- mixed
- `LocationInventoryInquiryClient` currently appears gateway-based and needs the same gateway-vs-direct review

### pos-order

Files:

- event/permission registration
- `SecurityConfig` defines local RestClient builder

Assessment:

- low complexity
- mostly infra client cleanup

### pos-people

Files:

- `SecurityServiceClient`
- `WorkexecJobTimeClient`
- `LocationReferenceClient`
- [RestClientConfig.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-people/src/main/java/com/positivity/people/internal/config/RestClientConfig.java)
- event/permission registration

Assessment:

- strong candidate
- currently a good example of duplicated hardcoded internal ports
- one likely naming problem: `workexec` appears to map to workorder-style functionality and needs service-ID clarification

### pos-price

Files:

- event/permission registration
- `SecurityConfig` with local RestClient

Assessment:

- low complexity

### pos-security-service

Files:

- `PeopleRegistrationClient`
- `CustomerRegistrationClient`
- [RestClientConfig.java](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-security-service/src/main/java/com/positivity/securityservice/internal/config/RestClientConfig.java)
- event initializer

Assessment:

- strong candidate
- must preserve internal trusted headers like `X-User` and `X-Authorities`

### pos-shop-manager

Files:

- `CrmCustomerClient`
- `CrmVehicleClient`
- `HrAvailabilityClient`
- `LocationClient`
- `PersonClient`
- `ServiceEntityClient`
- `SecurityConfig`
- event/permission registration

Assessment:

- strong candidate
- currently one of the densest internal client modules
- good second-wave migration target after a shared pattern exists

### pos-tax

Files:

- `ExternalTaxServiceClient`
- event initializer

Assessment:

- mixed
- if `ExternalTaxServiceClient` is third-party, keep explicit
- internal infra calls can still use discovery

### pos-workorder

Files:

- `DocumentClient`
- `InventoryPickClient`
- `InvoiceClient`
- `PeopleAvailabilityClient`
- `PeopleLocationClient`
- `ShopmgrOperationalContextClient`
- `TaxClient`
- `BillingRulesClientServiceImpl`
- `ChangeRequestServiceImpl`
- `CustomerReferenceService`
- `VehicleReferenceService`
- multiple `*ClientConfig.java` files

Assessment:

- largest internal migration surface
- very strong candidate
- should probably be migrated as a dedicated wave because it touches many collaborating services

### pos-bulk-loader

Files:

- batch config
- event/permission registration

Assessment:

- likely low complexity, but verify whether its HTTP usage is internal or infra-only

### pos-vehicle-fitment

Files:

- `VehicleFitmentServiceImpl`
- local `RestClientConfig`
- event/permission registration

Assessment:

- likely mixed
- vehicle fitment external reference APIs should stay explicit
- infra calls can use discovery

### pos-vehicle-reference-carapi

Assessment:

- external only
- should not move to discovery

### pos-vehicle-reference-nhtsa

Assessment:

- external only
- should not move to discovery

### pos-document-helper

Assessment:

- helper library, not a runtime module in the same sense
- could support discovery-aware consumers, but should not become Eureka-coupled by default

## Migration Strategy

### Phase 1: Shared plumbing

1. Introduce a standard load-balanced `RestClient.Builder`
2. Keep a separate plain builder for external clients
3. Publish a service-ID naming guide
4. Decide whether the shared config belongs in `pos-security-common` or a new shared client module

### Phase 2: Infrastructure clients

Migrate the simplest internal calls first:

- permission registration clients
- event type initializer clients

These are low-risk and repeated across many modules.

### Phase 3: MCP

Migrate MCP facade tools to discovery-based base URLs.

Why early:

- high visibility
- already partly discovery-aware
- currently suffering from host/port drift

### Phase 4: Domain service clients

Recommended order:

1. `pos-people`
2. `pos-accounting`
3. `pos-invoice`
4. `pos-shop-manager`
5. `pos-workorder`
6. `pos-inventory`

### Phase 5: Gateway-path review

Audit all clients that currently call:

- `gateway.url`
- gateway-prefixed service paths

For each one, decide:

- keep gateway
- or move direct and preserve headers

### Phase 6: Remove duplicated port config

After internal clients stop using `host:port`:

- remove redundant internal service port references from client properties
- keep only:
  - service listen port on the callee
  - host-published port in deployment

## Key Risks

### 1. Service name mismatch

Docker service names and Eureka service IDs are not guaranteed to match.

This is the biggest operational migration risk.

### 2. Gateway semantic drift

Clients using gateway URLs may rely on:

- route rewriting
- shared headers
- auth behavior

Those calls cannot be changed mechanically.

### 3. Header propagation regressions

Trusted internal headers are used in several places.

Discovery migration must preserve:

- `Authorization`
- `X-Token`
- `X-User`
- `X-Authorities`

### 4. External/internal confusion

Not every `RestClient` should use discovery.

A wrong blanket migration would break:

- Ollama
- Exa
- NHTSA
- carapi
- any real third-party service

### 5. Accidental frontend contract drift

If the migration changes public routing or OpenAPI-visible controller metadata while refactoring internal clients, it can create unrelated breakage in:

- `durion-positivity-sdk-angular`
- `durion-positivity-frontend`

This is avoidable if internal transport refactors are kept separate from public contract changes.

## Recommended Implementation Pattern

For each module that calls internal services:

1. Add a load-balanced builder bean
2. Build internal clients from service IDs, not ports
3. Keep path templates relative
4. Keep external clients on a separate explicit builder

Example:

```java
@Configuration
public class InternalClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient peopleServiceRestClient(@LoadBalanced RestClient.Builder builder) {
        return builder.baseUrl("http://people").build();
    }
}
```

Then in the client:

```java
restClient.get().uri("/v1/people/{personId}", personId)
```

Not:

```java
restClient.get().uri("http://pos-people:8080/v1/people/{personId}", personId)
```

For frontend-facing controllers and SDK generation, keep the public contract stable while changing only the internal caller construction behind it.

## Recommendation

This migration is worth doing.

The repo is already paying the operational cost of duplicated ports and hostnames while also already carrying Eureka almost everywhere. The current setup has the complexity of service discovery without consistently using its benefits.

Best next step:

1. establish a shared `@LoadBalanced RestClient.Builder` pattern
2. migrate infra clients first
3. migrate `pos-mcp-server` next
4. then move client-heavy modules like `pos-people`, `pos-shop-manager`, and `pos-workorder`

Add one more constraint:

5. for each migration wave, verify that no OpenAPI-visible contract drift was introduced unless it is part of an explicitly coordinated SDK/frontend delivery

## Appendix: High-Level Repo Scan Summary

Modules with notable runtime `RestClient` usage:

- `pos-accounting`
- `pos-bulk-loader`
- `pos-catalog`
- `pos-customer`
- `pos-documents`
- `pos-inventory`
- `pos-invoice`
- `pos-location`
- `pos-mcp-server`
- `pos-order`
- `pos-people`
- `pos-price`
- `pos-security-service`
- `pos-shop-manager`
- `pos-tax`
- `pos-vehicle-fitment`
- `pos-workorder`

Modules that are mainly external-client oriented and should remain explicit:

- `pos-vehicle-reference-carapi`
- `pos-vehicle-reference-nhtsa`
- external model/search integrations inside `pos-mcp-server`

---

## Addendum: Codebase Verification, ADR Conflicts, Missing Requirements, and Pitfalls

_Added 2026-05-04. Based on cross-referencing the original analysis against the live codebase, ADRs 0011/0014/0025/0026/0040/0041/0006, and the deployment architecture._

---

### A1. Verified Eureka Service ID Inventory

The `spring.application.name` values — which become the Eureka service IDs — were verified across all modules. None carry the `pos-` prefix:

| Module                  | Eureka Service ID                                                              |
| ----------------------- | ------------------------------------------------------------------------------ |
| `pos-people`            | `people`                                                                       |
| `pos-security-service`  | `security-service`                                                             |
| `pos-accounting`        | `accounting`                                                                   |
| `pos-shop-manager`      | `shop-manager`                                                                 |
| `pos-workorder`         | `workorder`                                                                    |
| `pos-inventory`         | `inventory`                                                                    |
| `pos-mcp-server`        | `mcp-server`                                                                   |
| `pos-service-discovery` | `pos-service-discovery` (Eureka server itself — does not register as a client) |

This confirms the service name mismatch risk identified in the original analysis. It also surfaces a concrete defect addressed below.

---

### A2. Critical: MCP Facade Tools Already Use the Wrong Service Identifiers

The original analysis lists `pos-mcp-server` as a "strong first adopter" and notes the facade tools "should be aligned with the same approach." The codebase scan found that the facade tools are **already using Docker container hostnames**, not Eureka service IDs:

```
pos.accounting.base-url:  http://pos-accounting/v1/accounting
pos.catalog.base-url:     http://pos-catalog/v1/catalog
pos.customer.base-url:    http://pos-customer/v1/customers
pos.inventory.base-url:   http://pos-inventory/v1/inventory
pos.workorder.base-url:   http://pos-workorder/v1/workorders
```

These Docker names resolve via Docker DNS today because the services are on the same Docker network. They would **not** resolve through Spring Cloud LoadBalancer because the Eureka IDs are `accounting`, `catalog`, `customer`, `inventory`, `workorder` — without the `pos-` prefix.

The migration of MCP facade tools is therefore not just "add `@LoadBalanced`." It must also rename all service identifiers in base-URL configuration properties from `pos-{service}` to the verified Eureka service ID.

Note that `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java` already uses `LoadBalancerClient.choose(serviceId)` for the dynamic OpenAPI proxy path. Its service IDs should be checked for the same prefix issue.

---

### A3. Load-balanced builders exist in multiple modules (gateway + RestClient builders)

Multiple modules now declare `@LoadBalanced` load-balanced client builders. The gateway continues to provide
a `@LoadBalanced WebClient.Builder` (GatewayWebClientConfig), and several other modules expose
`@LoadBalanced RestClient.Builder` beans in their configuration classes (for example: `pos-catalog`
SecurityConfig, `pos-customer` SecurityConfig, `pos-people` RestClientConfig, `pos-shop-manager`
SecurityConfig, and `pos-mcp-server` McpServerConfiguration). Spring Cloud LoadBalancer supports both
WebClient and RestClient use cases; the key point is that the repository now contains multiple
load-balanced builder beans, so migration guidance should treat the gateway pattern as an example but
acknowledge these additional RestClient builder usages.

**Implication for shared module placement:** If the shared `@LoadBalanced RestClient.Builder` bean is placed
in `pos-security-common`, that module will gain a Spring Cloud LoadBalancer dependency. Modules that import
`pos-security-common` for auth purposes only will pick up this dependency whether they need it or not.
Consider whether a new lightweight `pos-client-common` module is preferable to avoid coupling transport concerns
into the security common module.

---

### A4. ADR-0014 Conflict: pos-tax Must Not Use Service Discovery

ADR-0014 explicitly classifies `pos-tax` as internal-only and states it should default to `register-with-eureka: false`. The migration codebase scan confirms this setting is active.

The original analysis assesses `pos-invoice`'s `TaxServiceClient` as a "strong candidate" for service discovery. This conflicts with ADR-0014. `pos-invoice` calling `pos-tax` via a discovery-resolved URL would require `pos-tax` to register with Eureka, which violates the established architecture decision.

The correct treatment for `TaxServiceClient` is one of:

- keep it as an explicit `http://pos-tax:{port}` URL (Docker-internal only), or
- move to in-process direct calls if `pos-tax` is used as a library, or
- route through the API gateway if `pos-tax` ever needs external exposure (which ADR-0014 says it should not).

ADR-0021 (Tax API Consumption Policy) should also be consulted before making this call.

---

### A5. ADR-0011 / ADR-0040 Conflict: X-Authorities Is Not Present in Direct Service-to-Service Calls

This is the most significant architectural gap in the migration plan.

Per ADR-0011, the API gateway is the authentication enforcement boundary. It validates the JWT, decodes `perm_bits`/`perm_ver` claims, and injects a trusted `X-Authorities` header downstream. Backend services rely on this header — via `GatewayAuthoritiesFilter` in `pos-security-common` — to populate the Spring Security context for `@PreAuthorize` checks.

When services call each other directly (bypassing the gateway), only the original `Authorization: Bearer <token>` is forwarded. The gateway never touches the call, so `X-Authorities` is never injected. The receiving service's security filter receives a raw JWT with no pre-resolved authority header, and `@PreAuthorize` checks will fail silently or deny access.

#### Recommendation

**All service-to-service calls must route through the API gateway.** This is the only approach that preserves the established auth chain without duplicating gateway logic in `pos-security-common` or introducing a second trust model. It is consistent with ADR-0011 and ADR-0014, which position the gateway as the single enforcement boundary and explicitly prohibit internal services from being reached except via explicit gateway routes.

The service discovery migration therefore applies to how service instances are _resolved_, not to whether calls traverse the gateway. The `@LoadBalanced` client resolves the gateway's own Eureka-registered address rather than a downstream service address directly.

In practice, internal callers that currently use `http://pos-people:8084/v1/...` adopt the pattern:

```java
// Base URL resolves to the gateway via Eureka; path is the gateway-routed path
restClient.get().uri("/people/v1/people/{id}", id)
```

where the base URL is `http://api-gateway` (the gateway's Eureka service ID) and the path prefix matches the gateway route for that service.

#### Explicit exceptions

The following categories of call are explicitly exempt from the gateway-routing requirement and may call downstream services directly via Eureka:

1. **Circular call risk.** Any call where routing through the gateway would create a cycle must be direct. The primary cases are:
   - `pos-security-service` calling `pos-people` or `pos-customer` during token issuance or user registration. Routing these through the gateway would require a valid JWT to already exist, which it does not at that point.
   - `pos-api-gateway` itself performing any internal lookup (it cannot route to itself).

2. **Startup infra calls.** Permission registration and event type initialization clients (see A6). These fire before a valid user JWT exists and have no `@PreAuthorize` enforcement on the receiving end.

3. **Internal-only services with no `@PreAuthorize` enforcement.** Services that are not gateway-routed (per ADR-0014) and whose endpoints carry no Spring Security authorization checks. These are already in the `register-with-eureka: false` category and are called directly by design (e.g., `pos-tax` as a library-mode service).

Each exception must be explicitly documented in the calling module's `RestClientConfig` with the reason it bypasses the gateway. No undocumented direct calls.

#### Impact on migration scope

This decision narrows the Eureka-direct call surface significantly. The `@LoadBalanced` pattern is used in most modules to resolve `http://api-gateway`, not individual service IDs. Individual service IDs are used only for the explicit exceptions listed above. This simplifies the migration — most modules need one load-balanced client bean pointing at the gateway, not one per downstream service.

---

### A6. Startup Race Condition for Permission and Event Registration Clients

Permission registration (`PermissionRegistration`) and event type initialization (`EventTypeInitializer`) use `RestClient` to call `pos-security-service` at startup. These calls happen as part of `ApplicationRunner` or `CommandLineRunner` beans.

If these clients are migrated to `@LoadBalanced` resolution they will fail with `NoInstanceAvailableException` if `pos-security-service` has not yet registered with Eureka when the calling service starts. Currently they fail with a connection-refused `IOException`, which is tolerated as best-effort. The distinction matters: `NoInstanceAvailableException` occurs before any connection attempt and has no retry semantics built in, whereas an `IOException` can be caught and retried.

#### Recommendation

Apply three layers of defense, in order of impact.

**1. Exempt infra registration clients from the discovery migration entirely.**

Permission registration and event type initialization are fire-and-forget startup calls to a single known endpoint. There is no operational benefit to routing them through Eureka — they do not need load balancing across multiple instances, and Eureka availability must not be a prerequisite for a service to start.

These clients should use a plain (non-`@LoadBalanced`) `RestClient` with the Docker service hostname and no port:

```java
// Plain RestClient — no @LoadBalanced, no port
RestClient.builder().baseUrl("http://security-service").build()
```

Within Docker networking, `security-service` resolves directly via Docker DNS regardless of Eureka state. This sidesteps `NoInstanceAvailableException` entirely while still removing the hardcoded port.

This is the only category of clients that should be deliberately excluded from the discovery migration by policy.

**2. Add Docker Compose health checks for Eureka and `pos-security-service`.**

Add an HTTP health check to `pos-service-discovery` and `pos-security-service` in `docker-compose.yml`, and change all dependent services to use `depends_on.condition: service_healthy`. This eliminates the case where a service starts before either Eureka or the security service is able to serve requests:

```yaml
pos-service-discovery:
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8761/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 5

pos-security-service:
  depends_on:
    pos-service-discovery:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8086/actuator/health"]
    interval: 10s
    timeout: 5s
    retries: 5

pos-accounting: # representative of all other services
  depends_on:
    pos-security-service:
      condition: service_healthy
```

Note that `service_healthy` waits for the health check to pass, not for Eureka registration to complete. There is still a window between a service passing its own health check and its Eureka registration being visible to other services (the default heartbeat interval is 30 seconds). Docker health checks are necessary but not sufficient alone.

**3. Add Resilience4j retry to all startup registration calls.**

Wrap `ApplicationRunner` / `CommandLineRunner` registration logic with a `@Retry` configuration using exponential backoff. Resilience4j is already a declared dependency:

```java
@Retry(name = "permission-registration", fallbackMethod = "logRegistrationSkipped")
public void registerPermissions() { ... }
```

```yaml
resilience4j.retry.instances.permission-registration:
  maxAttempts: 8
  waitDuration: 5s
  enableExponentialBackoff: true
  exponentialBackoffMultiplier: 2
  retryExceptions:
    - org.springframework.web.client.ResourceAccessException
    - io.github.resilience4j.core.registry.NoSuchInstanceException
```

The fallback method should log a warning and continue startup, not throw. Permission registration failures are already survivable — the service should start even if registration is delayed, consistent with existing best-effort behavior.

Layers 2 and 3 apply even if layer 1 is implemented, because discovery-migrated clients in the same service may also experience Eureka unavailability at startup.

---

### A7. pos-people `workexecRestClient` Is Half-Migrated

`pos-people/src/main/java/com/positivity/people/internal/config/RestClientConfig.java` contains:

```
workexecRestClient: ${pos.workexec.base-url:http://workorder:8087}
```

The service ID `workorder` is correct (matches `spring.application.name: workorder` in `pos-workorder`), but port `8087` is still hardcoded. After migration, this should become `http://workorder` with no port. It should also be renamed from `pos.workexec.base-url` to `pos.workorder.base-url` to match the actual service name and remove the confusing `workexec` naming artifact (documented in ADR-0006 as a legacy naming issue pending cleanup).

---

### A8. BearerTokenRelayInterceptor Must Be Preserved Through the Migration

`pos-mcp-server` has a `BearerTokenRelayInterceptor` that relays the incoming `Authorization: Bearer` token to outbound `RestClient` calls. This interceptor is applied to `RestClient` builders in `McpServerConfiguration`.

When the MCP facade tools are migrated to a `@LoadBalanced RestClient.Builder`, the interceptor must be explicitly applied to that builder. Spring Cloud's `@LoadBalanced` wraps the builder's interceptor chain at instance-resolution time. Failing to wire the `BearerTokenRelayInterceptor` to the load-balanced builder will silently drop auth headers on all facade tool calls, breaking downstream authorization.

Other modules (`pos-security-service`, `pos-accounting`) forward `X-User` and `X-Authorities` headers manually in their `RestClient` configurations. These forwarding configurations must not be lost when RestClient beans are rebuilt around a `@LoadBalanced` builder.

---

### A9. LoadBalancerClient vs @LoadBalanced: Inconsistent Approaches

`OperationProxyFactory` in `pos-mcp-server` uses programmatic `LoadBalancerClient.choose(serviceId)` to resolve instances. The migration proposes declarative `@LoadBalanced RestClient.Builder` for the facade tools.

Both approaches use Spring Cloud LoadBalancer under the hood but expose different APIs:

- `LoadBalancerClient.choose()` resolves one `ServiceInstance` synchronously; the caller constructs the full URI.
- `@LoadBalanced RestClient.Builder` intercepts the builder transparently; the URI uses the service ID as hostname.

These two patterns coexisting in the same module is not a problem, but the codebase should document which to use when and why. In particular, if `OperationProxyFactory` is ever refactored, it should converge on `@LoadBalanced` rather than a second programmatic resolver.

---

### A10. pos-shop-manager Gateway URLs Are Understated as a Risk

The module assessment marks `pos-shop-manager` as a "strong candidate" but the codebase contains:

```
pos.crm.customer-base-url:  http://localhost:8080/v1/customers
pos.crm.vehicle-base-url:   http://localhost:8080/v1/vehicles
```

`localhost:8080` is the API gateway address in local development. These are gateway-routed calls. The analysis correctly identifies that gateway calls need individual review (Phase 5), but the per-module assessment does not flag this conflict. `pos-shop-manager` should be marked "mixed/needs gateway review" not "strong candidate," because at least two of its clients cannot be mechanically migrated to direct service discovery.

---

### A11. Java SDK (durion-positivity-sdk-java) Is Not Addressed

The migration analysis mentions `durion-positivity-sdk-angular` and `durion-positivity-frontend` but does not mention `durion-positivity-sdk-java`, which exists as a multi-module Maven project covering all major service domains:

```
sdk-java-security, sdk-java-customer, sdk-java-workorder,
sdk-java-accounting, sdk-java-people, sdk-java-shop-manager, ...
```

The generated `ApiClient` classes have hardcoded default base paths sourced from the OpenAPI spec's `servers` definition. For example:

```java
// sdk-java-security/ApiClient.java
protected String basePath = "http://localhost:8086";
```

This defaults to the security service's local Docker port — meaningless in any deployed environment.

#### Recommendation

**1. The Java SDK is for external consumers only. Internal backend modules must not use it.**

Within the platform, service-to-service calls must use `@LoadBalanced RestClient` beans wired directly to Eureka service IDs. The generated Java SDK is not Eureka-aware, carries no load-balancing, and exposes the entire API surface of a service — it is the wrong tool for internal calls. Any existing or planned internal use of `sdk-java-*` classes for backend-to-backend calls must be replaced with direct `RestClient` configuration.

**2. Update the OpenAPI `servers` definition in each service to target the API gateway.**

The generated `basePath` is derived directly from the `servers.url` field in each service's OpenAPI document. Changing that field fixes the generated SDK for all consumers at once, without touching the generator or the SDK templates.

The target server URL for each service should follow the gateway routing path, not the service's own `server.port`:

```yaml
# in each service's springdoc / openapi config or openapi.yaml
servers:
  - url: http://localhost:8080/{service-base-path}
    description: Local gateway
    variables:
      service-base-path:
        default: v1/customers # example for pos-customer
```

For deployed environments, the SDK consumer overrides `basePath` with the tenant's gateway URL:

```java
apiClient.setBasePath("https://api.{tenant}.durion.com");
```

The gateway is the correct and only external entry point per ADR-0011 and ADR-0014. SDK consumers must not bypass it, regardless of whether they are Java or Angular clients.

**3. Document the override requirement prominently in each `sdk-java-*` module README.**

Until the `servers` definitions are updated, each SDK module README must state:

- The default `basePath` is a local development placeholder only.
- Non-local consumers must call `apiClient.setBasePath(gatewayUrl)` before making any requests.
- The expected value is the API gateway base URL for the target environment, not any individual service URL.

**4. This migration does not change the Java SDK's transport or contract.** The service discovery migration is entirely internal. SDK consumers targeting the gateway see no change in paths, responses, or auth behavior as a result of this migration, consistent with the safe-migration rule already stated in the Angular SDK section.

---

### A12. AWS Deployment: Eureka Is the Architectural Choice — AWS Must Comply

**Updated position:** Eureka is the platform's service discovery standard. The AWS deployment must provision and operate Eureka as part of each tenant cell. AWS-native alternatives (Cloud Map, App Mesh, Service Connect) are not under consideration.

This is consistent with the tenant cell model in the deployment architecture: each tenant cell is a self-contained runtime. Eureka runs as a service within that cell alongside the application services. Netflix operated Eureka at scale on AWS for years. It is suitable for this deployment model.

#### Operational requirements for Eureka on AWS

These requirements must be addressed in the AWS provisioning runbook before any production tenant cell goes live:

**High availability.** Run at minimum two `pos-service-discovery` replicas within each tenant cell, deployed to separate Availability Zones. Eureka nodes peer-register with each other via `eureka.client.serviceUrl.defaultZone`. All application services must list both peers in their Eureka client configuration so that loss of one node does not interrupt discovery.

**Persistent registration.** Eureka registrations are in-memory. A `pos-service-discovery` restart causes all registrations to expire until services re-heartbeat. With the default 30-second heartbeat and 90-second eviction timeout, a clean rolling restart of the Eureka node is survivable. Plan for this in the deployment runbook and add a readiness gate in the deployment pipeline that waits for all expected service IDs to be visible in Eureka before declaring the tenant cell healthy.

**Health check integration.** Eureka's self-preservation mode (triggered when registration loss exceeds a threshold) can mask actual failures. Configure `eureka.server.renewal-percent-threshold` appropriately for the tenant cell size, and monitor Eureka's own `/actuator/health` and `/eureka/apps` endpoints in the observability stack.

**Feature code must not reference Eureka-specific APIs.** `@LoadBalanced RestClient.Builder` and the `DiscoveryClient` abstraction in Spring Cloud are registry-agnostic. Feature code and `RestClient` configuration classes must not import or reference `com.netflix.eureka.*` or `com.netflix.discovery.*` directly. All Eureka-specific configuration belongs in `application.yml` and the `pos-service-discovery` module. This keeps the application layer portable and the infrastructure layer replaceable.

**Service ID naming must be stable and registry-agnostic.** The `spring.application.name` values (`people`, `security-service`, `accounting`, etc.) function as logical service identifiers. They must be treated as stable platform contracts, not Eureka implementation details. This naming should be documented in the service-ID guide produced in Phase 1.

---

### A13. Missing Requirements for Each Migration Wave

The original plan specifies phases but does not define per-wave exit criteria. Based on the analysis above, each migration wave should require:

1. **Eureka service ID verified** against `spring.application.name` for every called service — not Docker container names.
2. **Auth header propagation documented** — which headers are forwarded, how, and whether the receiving service's security filter handles them correctly without the gateway.
3. **BearerToken and custom header interceptors wired** to any new `@LoadBalanced` builder.
4. **Startup registration clients** wrapped with retry if they call `pos-security-service` or `pos-events`.
5. **Circuit breaker failure categories updated** where discovery `NoInstanceAvailableException` should be classified separately from network `IOException`.
6. **Docker Compose `healthcheck`** added for the target service and `depends_on.condition: service_healthy` set on the calling service, or retry logic is verified as sufficient.
7. **OpenAPI spec diff** if the module being migrated has frontend-exposed APIs, per the existing SDK validation requirement.

---

### A14. Summary: Items to Resolve Before Phase 1

| #   | Item                                                                                                                                                                                     | Severity | Status                    | Blocking?        |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------- | ---------------- |
| A2  | MCP facade tools use `pos-{service}` Docker names, not Eureka IDs                                                                                                                        | High     | Open                      | Phase 3          |
| A4  | `pos-tax` must not use Eureka — TaxServiceClient migration plan needs rework                                                                                                             | High     | Open                      | Phase 4          |
| A5  | **Resolved**: all calls route through the gateway; `@LoadBalanced` resolves `api-gateway` not individual services; direct calls only for documented circular-call and startup exceptions | Critical | Recommendation documented | Before Phase 2   |
| A6  | **Resolved**: exempt infra registration clients from discovery; Docker health checks; Resilience4j retry                                                                                 | Medium   | Recommendation documented | Phase 2          |
| A7  | `workexecRestClient` has correct ID but wrong port — low-risk fix                                                                                                                        | Low      | Open                      | Phase 4          |
| A8  | `BearerTokenRelayInterceptor` must be preserved on `@LoadBalanced` builder                                                                                                               | High     | Open                      | Phase 3          |
| A9  | `LoadBalancerClient` vs `@LoadBalanced` — inconsistency needs a style decision                                                                                                           | Low      | Open                      | Phase 3          |
| A10 | `pos-shop-manager` module assessment understates gateway dependency                                                                                                                      | Medium   | Open                      | Phase 4          |
| A11 | **Resolved**: Java SDK is external-consumer only; fix `servers.url` in OpenAPI specs to gateway path; internal callers use `@LoadBalanced RestClient`                                    | Medium   | Recommendation documented | Pre-Phase 1      |
| A12 | **Resolved**: Eureka is the platform standard; AWS must run Eureka within each tenant cell; HA and operational requirements documented                                                   | N/A      | Position confirmed        | AWS provisioning |
| A13 | Per-wave exit criteria missing — defined above                                                                                                                                           | Medium   | Pre-Phase 1               |
