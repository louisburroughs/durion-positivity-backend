# Internal Service Discovery Reconciliation Design

## Problem

Internal service-to-service HTTP calls in `durion-positivity-backend` currently use multiple incompatible routing models:

- direct Docker DNS hostnames plus fixed ports
- gateway-routed URLs rooted at `http://api-gateway`
- localhost-based development overrides
- a partial Spring Cloud LoadBalancer and Eureka pattern

That inconsistency is now breaking down because many services run on dynamic ports such as `server.port: 0`, while `http://api-gateway` only resolves in environments where a Docker alias or equivalent naming convention exists.

The current model duplicates network truth across caller config, callee config, and deployment manifests. It also treats the API gateway as both the external edge and an internal transport root, which is no longer necessary now that direct service discovery is acceptable for backend-to-backend calls.

## Decision

Adopt direct service discovery as the default pattern for internal runtime HTTP calls.

The target rule is:

- internal runtime callers use `@LoadBalanced RestClient.Builder`
- callers target logical Eureka service IDs, not hostnames or ports
- callers own only the downstream path contract, not deployment topology
- the API gateway remains the external entry point and public route owner
- startup/bootstrap calls, external integrations, and documented exemptions remain direct/plain clients

This is a deliberate move away from using `http://api-gateway` as the default internal root.

## Scope

### In scope

- Replace internal runtime gateway-root or fixed-port service calls with direct discovery-based calls
- Rename ambiguous internal client configuration from `*.base-url` style properties to explicit `*.service-id` and `*.base-path`
- Normalize client construction patterns across modules that already expose `@LoadBalanced RestClient.Builder`
- Update service discovery and migration documentation to reflect the new default policy
- Add verification steps to catch remaining internal `http://api-gateway` assumptions

### Out of scope

- Frontend or SDK base URL changes
- Public gateway route changes for browser or external consumers
- Replacing startup bootstrap clients that intentionally avoid Eureka readiness
- Changing external third-party API integrations
- Re-architecting `pos-tax` beyond the already documented exemption boundary

## Policy

### 1. Internal runtime calls

Internal runtime calls should resolve through Eureka plus Spring Cloud LoadBalancer.

Each client should:

- use `@LoadBalanced RestClient.Builder`
- build the client against `http://{serviceId}`
- issue requests against the downstream service's native path shape

Example target policy:

- `pos.people.service-id=people`
- `pos.people.base-path=/v1/people`
- `builder.baseUrl("http://" + serviceId).build()`
- `GET {basePath}/...`

This removes gateway prefixes such as `/people/...` or `/inventory/...` from internal callers when those prefixes only exist for gateway routing.

The `service-id` value must match the downstream service's `spring.application.name` exactly. Spring Cloud LoadBalancer performs case-insensitive matching against the Eureka registry, but the value in configuration should be lowercase to match the convention used in `docs/service-discovery-migration/service-id-registry.md`.

### 2. Local development

Local development uses the `eureka-server` service already defined in `docker-compose.yml` as the discovery registry.

- Services started inside Docker Compose register at `http://eureka-server:8761/eureka/` (already wired in `docker-compose.yml`).
- Services started from an IDE or the command line outside Docker register at `http://localhost:8761/eureka/`, which maps to the same server via the host-port binding.

The minimum local-dev prerequisite for a module under migration is to have `eureka-server` (and any directly called services) running via Docker Compose. A service started from the IDE does not need the full stack — only the services it calls need to be registered so that `@LoadBalanced` can resolve them.

No additional `spring.cloud.discovery.enabled=false` override or static instance list is required. The existing Docker Compose setup is sufficient.

### 3. Service-to-service authentication

Direct internal callers do not pass through the API gateway and therefore do not receive a JWT-validated security context from the gateway filter. They authenticate to downstream services by injecting both a service principal header (`X-User`) and a minimal `X-Authorities` header containing only the permissions required for the specific call.

Each internal client is responsible for:

- injecting `X-User` with the caller service identity (for example, `pos-shop-manager`)
- injecting `X-Authorities` with a precise, minimal permission set (e.g., `PERM_inventory:item:view`)
- not injecting `X-Roles` unless explicitly required by the downstream endpoint

Downstream services accept this via `GatewayAuthoritiesFilter`'s `X-Authorities` CSV fallback path, with `X-User` providing the service principal for audit/context propagation where required by service logic. This trust model assumes network isolation (Docker network `pos-network`); see the existing security model ADR for the formal trust boundary statement.

This policy applies to all internal runtime callers converted under this migration. It must not be removed or weakened during the client conversion.

### 4. Direct exceptions

Direct or plain clients remain valid only for clearly documented categories:

- startup-infra registration and bootstrap calls that must not depend on Eureka readiness
- external third-party APIs
- `pos-tax` and any other explicit architectural exemptions already documented in ADRs or migration docs
- rare dynamic-target flows where `LoadBalancerClient.choose(serviceId)` is required because the target service ID is not known until request time

`LoadBalancerClient.choose(serviceId)` is appropriate only when the service ID is computed at request time from user input or external data, not from a static configuration property. When the target is always the same, use `@LoadBalanced RestClient.Builder` instead.

These are exceptions, not parallel defaults.

### 5. Gateway role

`pos-api-gateway` remains:

- the external entry point
- the public route and auth boundary for browser and other external clients
- the owner of gateway-specific path prefixes and route policy

`pos-api-gateway` should no longer be treated as the default internal backend mesh root.

## Configuration Model

### Current problem

Properties like `pos.people.base-url=http://api-gateway` or `gateway.url=http://api-gateway` mix three separate concerns:

- service identity
- routing topology
- HTTP path prefix

That makes internal callers dependent on environment-specific naming and gateway path structure.

### Target model

Rename internal runtime client configuration to explicit fields:

- `*.service-id`
- `*.base-path`

Optional additional fields may remain where needed for non-topology concerns such as timeouts or local fallback toggles.

Examples:

- `pos.people.service-id=people`
- `pos.people.base-path=/v1/people`
- `pos.inventory.service-id=inventory`
- `pos.inventory.base-path=/v1/inventory`

This makes the configuration self-describing:

- `service-id` tells Spring Cloud LoadBalancer which Eureka registration to resolve
- `base-path` tells the caller which downstream HTTP contract it is using

No backward-compatibility layer is required for old property names. However, because backward compatibility is not preserved, **all callers of a given service must be migrated in the same pass**. Partial rollout — where module A has been converted but still calls module B whose properties have not yet been renamed — will leave the deployment broken. The execution plan must sequence changes so each touched module is internally coherent at the end of each commit.

## Path Mapping

The largest implementation risk is converting callers from gateway-prefixed paths to downstream-native paths incorrectly. The gateway rewrites paths at route time (stripping prefixes such as `/people` before forwarding to the `people` service), so the URI a caller sends to `http://api-gateway/people/v1/people/{id}` is not the same as the URI the downstream service actually handles (`/v1/people/{id}`).

For each client being converted:

1. Consult `docs/service-discovery-migration/service-id-registry.md` for the authoritative service ID and gateway route prefix.
2. Identify the downstream controller's native path by reading the controller source, not by inferring from the gateway route.
3. Set `base-path` to the downstream-native path root, not the gateway-prefixed path.

A starter mapping is maintained in the service ID registry. Any gap found during conversion should be added there as part of the same commit.

## Component Impact

The primary impacted areas are the modules already identified in the discovery audit and policy matrix, especially those currently using:

- `http://api-gateway`
- `http://localhost:8080`
- Docker DNS plus fixed internal ports for runtime service-to-service calls

Expected touch points include:

- internal client classes using `RestClient`
- module `application.yml` and environment overlays
- `additional-spring-configuration-metadata.json`
- tests that assert gateway-root URI construction
- migration and operations documentation that currently recommends gateway-routed internal calls

The existing registry in `docs/service-discovery-migration/service-id-registry.md` should remain the source of truth for service ID naming, but its guidance should shift from "gateway path prefix plus target URI" toward "direct discovery default plus documented exceptions."

## Migration Strategy

The existing `docs/service-discovery-loadbalancer-migration-analysis.md` and `docs/service-discovery-migration/client-policy-matrix.md` represent prior analysis work that classified all runtime clients. Phase 1 begins by updating those documents to reflect the new direct-discovery default rather than the earlier gateway-root-normalization intent. The client policy matrix is not being replaced — its per-client classification rows are reused; only the target pattern and action column descriptions change.

### Phase 1: Policy and inventory alignment

- Update `docs/service-discovery-loadbalancer-migration-analysis.md` to state that direct discovery is the default internal runtime model
- Update `docs/service-discovery-migration/client-policy-matrix.md` categories and actions to reflect direct discovery rather than gateway-root normalization
- Confirm the authoritative Eureka service ID inventory for all affected modules against the service ID registry
- Identify all internal runtime clients still rooted at `http://api-gateway`, `localhost`, or fixed service ports

### Phase 2: Configuration rename and client conversion

- Rename internal runtime client properties from `*.base-url` to `*.service-id` and `*.base-path`
- Refactor client construction to use logical service IDs and downstream-native paths
- Remove gateway path prefixes from internal request URIs where those prefixes are only meaningful at the gateway layer
- Keep existing direct-exception clients plain and explicitly documented
- Update `additional-spring-configuration-metadata.json` for each touched module in the same commit as the property rename

### Phase 3: Tests and verification updates

- Update unit or slice tests that currently assert `http://api-gateway` or gateway-prefixed URIs
- Add targeted tests around direct-discovery URI construction for representative modules
- Add repo-level grep verification for stale internal gateway-root defaults where practical

### Phase 4: Documentation cleanup

- Update service discovery migration docs
- Update impacted module README or configuration guidance where internal callers are documented
- Update config metadata descriptions and defaults so IDE assistance reflects the new model
- Remove or rewrite docs that still present `http://api-gateway` as the generic internal service root

## Documentation Impact

The documentation set must be updated as part of the migration, not after it.

At minimum this work should update:

- `docs/service-discovery-loadbalancer-migration-analysis.md`
- `docs/service-discovery-migration/client-policy-matrix.md`
- `docs/service-discovery-migration/service-id-registry.md`
- any module `README.md` or config guidance that still instructs internal callers to use gateway-root URLs
- `additional-spring-configuration-metadata.json` descriptions and defaults for touched modules

Documentation outcomes should be:

- one clear default rule for internal runtime calls
- one short exception list
- no mixed guidance between gateway-root routing and direct discovery for the same client category
- no examples that require internal callers to know a dynamic port

## Testing Strategy

### Configuration and client tests

Add or update focused tests that verify:

- clients build against `http://{serviceId}` rather than `http://api-gateway`
- request URIs use downstream-native paths rather than gateway prefixes
- renamed properties are wired correctly in Spring configuration

### Regression checks

Run module-targeted tests for every touched module and add a lightweight audit for stale defaults:

- compile and test touched modules
- grep for remaining internal `http://api-gateway` defaults outside approved exceptions
- grep for stale `*.base-url` property names after migration in touched modules

The baseline for the audit is: 25+ `@Value` injections with `http://api-gateway` defaults across 15 modules at the time this spec was written. The target is zero occurrences in runtime `application.yml` defaults and `@Value` fallbacks outside the documented exception list.

### Documentation checks

Audit the updated docs to ensure:

- the policy is internally consistent
- service IDs match the registry
- documented exceptions match the code

## Risks

### Path-shape mistakes

The biggest implementation risk is converting callers from gateway-prefixed paths to downstream-native paths incorrectly. Each client must be checked against the downstream controller contract, not inferred from the gateway route alone. See the Path Mapping section for the required process.

### Mixed property rollout

Because backward compatibility is intentionally not preserved, partial rollout across modules can leave modules temporarily inconsistent. The execution plan must sequence changes so each touched module is internally coherent in one pass. No module should be left in a state where the property name has been renamed but the client construction has not yet been updated, or vice versa.

### Hidden gateway dependencies

Some internal callers may rely implicitly on gateway behavior such as path rewriting, header mutation, or auth assumptions. Those dependencies must be identified explicitly rather than carried forward by habit. In particular, callers that currently rely on the gateway to inject auth headers must be updated to inject `X-Authorities` directly per the service-to-service authentication policy above.

## Success Criteria

- Internal runtime clients default to direct service discovery rather than gateway-root routing
- Ambiguous `*.base-url` runtime client properties are replaced by explicit `*.service-id` and `*.base-path` naming
- Direct exceptions are short, documented, and intentional
- Dynamic service ports are no longer encoded into internal runtime caller configuration
- Documentation consistently describes the same policy that the code implements
- Zero occurrences of `http://api-gateway` in runtime `application.yml` defaults or `@Value` fallbacks outside the approved exception list
- All converted clients inject `X-Authorities` where downstream endpoints require it

## Non-Goals

- Making frontend consumers discovery-aware
- Removing the API gateway from the public architecture
- Forcing all direct/bootstrap integrations through Eureka
- Introducing a compatibility layer for old internal property names
