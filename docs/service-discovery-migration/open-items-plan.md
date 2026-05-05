# Service Discovery Migration: Open Items Plan

Remaining work after the initial migration waves. Items are ordered by commit sequence — active bugs first, then correctness fixes, then documentation.

---

## Item 1 — pos-catalog `application.yml` overrides conflict with gateway-routing pattern

**Type:** Active bug  
**Module:** `pos-catalog`

### Problem

`pos-catalog/src/main/resources/application.yml` sets:

```yaml
pos:
  price:
    base-url: http://pos-price
  inventory:
    base-url: http://pos-inventory
```

Both `PricingClientImpl` and `InventoryClientImpl` use a `@LoadBalanced RestClient.Builder` and fall back to `http://api-gateway` in their Java `@Value` defaults. They call gateway-prefixed paths (`/price/v1/price/quotes`, `/inventory/v1/inventory/...`).

The `application.yml` values take precedence over the Java defaults at runtime, so the `@LoadBalanced` interceptor tries to resolve `pos-price` and `pos-inventory` as Eureka service IDs. The actual Eureka IDs are `price` and `inventory` (no `pos-` prefix), so resolution fails with `NoInstanceAvailableException`.

### Verify before changing

Confirm that the gateway routes for `price` and `inventory` in `pos-api-gateway/src/main/resources/application.yml` include `StripPrefix=1` so the `/price/` and `/inventory/` path prefixes are removed before the call reaches each service.

### Changes

**File:** `pos-catalog/src/main/resources/application.yml`

```yaml
# Before:
pos:
  price:
    base-url: http://pos-price
  inventory:
    base-url: http://pos-inventory

# After:
pos:
  price:
    base-url: http://api-gateway
  inventory:
    base-url: http://api-gateway
```

**File:** `pos-catalog/src/main/resources/META-INF/additional-spring-configuration-metadata.json`

Update the `defaultValue` fields for `pos.inventory.base-url` and `pos.price.base-url` from `http://pos-inventory` / `http://pos-price` to `http://api-gateway`. These metadata values drive IDE autocomplete defaults and should match the corrected `application.yml`.

### Tests

No changes required. `CatalogClientBuilderTest` already asserts the clients resolve to `http://api-gateway` with gateway-prefixed paths and will serve as the regression test.

---

## Item 2 — pos-invoice `TaxServiceClient` wrong port + stale events base-url overrides

**Type:** Active bug  
**Modules:** `pos-invoice`, `pos-bulk-loader`, `pos-documents`

### Problem A — wrong port in `TaxServiceClient`

`TaxServiceClient` defaults to `http://pos-tax:8090/v1/tax`. `pos-tax` runs on port `8091` (confirmed in `pos-tax/src/main/resources/application.yml`). The reference implementation in `pos-workorder TaxClientConfig` already uses `http://pos-tax:8091`. The default in `pos-invoice` is wrong and will fail any deployment that does not supply `INVOICE_TAX_BASE_URL`.

Per `direct-call-exceptions.md`, `TaxServiceClient` is classified as a `tax-exemption` direct call — it must remain a plain (non-`@LoadBalanced`) `RestClient` pointing directly to `pos-tax` via Docker DNS, consistent with ADR-0014 (`pos-tax` must not register with Eureka).

### Problem B — stale events base-url overrides in three application.yml files

Three modules have a committed `pos.events.base-url` that falls back to `http://localhost:8085` when `POS_EVENTS_BASE_URL` is not set:

| File | Line | Current value |
|------|------|---------------|
| `pos-invoice/src/main/resources/application.yml` | 61 | `base-url: http://localhost:8085` |
| `pos-bulk-loader/src/main/resources/application.yml` | 53 | `base-url: ${POS_EVENTS_BASE_URL:http://localhost:8085}` |
| `pos-documents/src/main/resources/application.yml` | 32 | `base-url: ${POS_EVENTS_BASE_URL:http://localhost:8085}` |

In Docker the `POS_EVENTS_BASE_URL` anchor supplies the correct value, masking the bug. Outside Docker without the env var, all three modules will fail to reach `pos-event-receiver` at startup. The correct Docker-internal address is `http://pos-event-receiver:8080`.

Note: the Java fallback defaults in `BulkLoaderEventTypeInitializer` and `DocumentEventTypeInitializer` are already correct (`http://pos-event-receiver:8080`), but the yml override wins over the Java `@Value` fallback, so the Java defaults are never reached when the yml property is present.

Also related: `pos-invoice/src/main/resources/META-INF/additional-spring-configuration-metadata.json` advertises a stale `defaultValue` for `pos.events.base-url` sourced from the same `localhost:8085` value. Update it alongside the yml fix.

### Changes

**File:** `pos-invoice/src/main/java/com/positivity/invoice/internal/client/TaxServiceClient.java`

```java
// Before:
@Value("${invoice.tax.base-url:http://pos-tax:8090/v1/tax}")

// After:
@Value("${invoice.tax.base-url:http://pos-tax:8091/v1/tax}")
```

**File:** `pos-invoice/src/main/resources/application.yml` — remove the hardcoded override (the Java `@Value` fallback and docker-compose anchor are both correct):

```yaml
# Remove this line entirely:
    base-url: http://localhost:8085
```

**File:** `pos-bulk-loader/src/main/resources/application.yml`

```yaml
# Before:
    base-url: ${POS_EVENTS_BASE_URL:http://localhost:8085}

# After:
    base-url: ${POS_EVENTS_BASE_URL:http://pos-event-receiver:8080}
```

**File:** `pos-documents/src/main/resources/application.yml`

```yaml
# Before:
    base-url: ${POS_EVENTS_BASE_URL:http://localhost:8085}

# After:
    base-url: ${POS_EVENTS_BASE_URL:http://pos-event-receiver:8080}
```

**File:** `pos-invoice/src/main/resources/META-INF/additional-spring-configuration-metadata.json` — update the `defaultValue` for `pos.events.base-url` to `http://pos-event-receiver:8080`.

### Tests

`InvoiceServiceImplTest` mocks `TaxServiceClient` directly with Mockito `@Mock`, so no test changes are required.

---

## Item 3 — `PermissionRegistration` wrong default port across 15 modules

**Type:** Correctness fix (non-Docker runs broken)  
**Modules:** All modules with a startup permission registrar except `pos-mcp-server`

### Problem

Every startup permission registrar except `pos-mcp-server` uses `:8086` as the Java fallback default for `pos.security.base-url`:

```java
@Value("${pos.security.base-url:http://pos-security-service:8086}")
```

`pos-security-service` runs on container port `8080`. Port `8086` is the host-published port used for browser or tool access from outside Docker. The docker-compose anchor injects `POS_SECURITY_BASE_URL=http://pos-security-service:8080`, which overrides the Java default in all Docker deployments — so Docker runs are not broken. Any run outside Docker that does not set the env var will fail to connect.

`pos-mcp-server` already has the correct default (`:8080`) and is excluded.

### Change

One-line change in each of the 15 files below. Replace the fallback default:

```java
// Before:
@Value("${pos.security.base-url:http://pos-security-service:8086}")

// After:
@Value("${pos.security.base-url:http://pos-security-service:8080}")
```

**Files:**

- `pos-accounting/src/main/java/com/positivity/accounting/internal/config/PermissionRegistration.java`
- `pos-bulk-loader/src/main/java/com/positivity/bulkloader/internal/config/PermissionRegistration.java`
- `pos-catalog/src/main/java/com/positivity/catalog/internal/config/PermissionRegistration.java`
- `pos-customer/src/main/java/com/positivity/customer/internal/config/PermissionRegistration.java`
- `pos-documents/src/main/java/com/positivity/documents/internal/config/DocumentPermissionRegistration.java`
- `pos-inventory/src/main/java/com/positivity/inventory/internal/config/PermissionInitializer.java`
- `pos-invoice/src/main/java/com/positivity/invoice/internal/config/PermissionRegistration.java`
- `pos-location/src/main/java/com/positivity/location/internal/config/LocationPermissionRegistration.java`
- `pos-order/src/main/java/com/positivity/order/internal/config/PermissionRegistration.java`
- `pos-people/src/main/java/com/positivity/people/internal/config/PermissionRegistration.java`
- `pos-price/src/main/java/com/positivity/price/internal/config/PermissionRegistration.java`
- `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/config/PermissionRegistration.java`
- `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/config/PermissionRegistration.java`
- `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/config/PermissionRegistration.java`
- `pos-workorder/src/main/java/com/positivity/workorder/internal/config/PermissionRegistration.java`

### Tests

No existing tests cover this path in a way that will break. No new tests required.

---

## Item 4 — EventTypeInitializer startup registrars incorrectly routed through API gateway

**Type:** Correctness fix (route miss + policy violation)  
**Modules:** `pos-order` (1 file), `pos-workorder` (2 files)

### Problem

Three startup event-type initializers construct their base URL from `gateway.base-url`:

```java
@Value("${gateway.base-url:http://pos-api-gateway:8080}") String gatewayBaseUrl,
...
.baseUrl(gatewayBaseUrl + "/v1/event-receiver/v1/eventTypes/code")
```

This produces a request to:

```
http://pos-api-gateway:8080/v1/event-receiver/v1/eventTypes/code
```

The gateway route for `pos-event-receiver` matches `Path=/event-receiver/**`. The path `/v1/event-receiver/v1/eventTypes/code` starts with `/v1/`, not `/event-receiver/`, so it never matches the route — the gateway returns a 404 before any forwarding or prefix-stripping occurs. This is a route miss, not a StripPrefix issue.

The actual `EventTypeController` endpoint is at `/v1/eventTypes/code/{typeCode}` on `pos-event-receiver`. The correct base URL to reach it is `http://pos-event-receiver:8080`, with path suffix `/v1/eventTypes/code`.

This also violates the startup-infra exemption policy: startup registration clients must use a plain direct `RestClient`, not a load-balanced or gateway-routed client. All other modules (`pos-catalog`, `pos-invoice`, `pos-customer`, etc.) already use `pos.events.base-url` pointing directly at `pos-event-receiver:8080`.

**Affected files (3):**

- `pos-order/src/main/java/com/positivity/order/internal/config/EventTypeInitializer.java`
- `pos-workorder/src/main/java/com/positivity/workorder/internal/config/EventTypeInitializer.java`
- `pos-workorder/src/main/java/com/positivity/workorder/internal/config/PickEventTypeInitializer.java`

All three have the same constructor pattern and the same broken URL construction.

### Changes

Same change in all three files:

```java
// Before:
@Value("${gateway.base-url:http://pos-api-gateway:8080}") String gatewayBaseUrl,
...
.baseUrl(gatewayBaseUrl + "/v1/event-receiver/v1/eventTypes/code")

// After:
@Value("${pos.events.base-url:http://pos-event-receiver:8080}") String eventServiceBaseUrl,
...
.baseUrl(eventServiceBaseUrl + "/v1/eventTypes/code")
```

The docker-compose anchor `POS_EVENTS_BASE_URL=http://pos-event-receiver:8080` will automatically supply the correct value in Docker runs, consistent with all other modules.

### Tests

`pos-order EventTypeInitializer` is annotated `@Profile("!test")` so tests are unaffected. `pos-workorder PickEventTypeInitializer` has no profile guard — verify that no existing test wires it. No new tests required.

---

## Item 5 — `OperationProxyFactory` style decision comment

**Type:** Documentation / style decision  
**Module:** `pos-mcp-server`

### Problem

`OperationProxyFactory` uses programmatic `LoadBalancerClient.choose(serviceId)` while facade tools (`AccountingFacadeTool`, `CatalogFacadeTool`, etc.) use `@LoadBalanced RestClient.Builder`. Both patterns are valid but their coexistence in the same module is unexplained, and any future contributor refactoring `OperationProxyFactory` may incorrectly converge it onto the wrong pattern.

### Change

Add a comment before the `loadBalancerClient.choose(serviceId)` call in `OperationProxyFactory` explaining:

- `LoadBalancerClient.choose()` is used here because the service ID is **dynamic** — resolved at runtime from the OpenAPI discovery registry. A static `@LoadBalanced` base URL cannot be pre-built at construction time when the target service ID is unknown until the request arrives.
- Facade tools use `@LoadBalanced RestClient.Builder` because their service ID and base URL are **static** and known at startup.
- Rule: prefer `@LoadBalanced RestClient` for new static-target clients. Use `LoadBalancerClient.choose()` only when the service ID or full URI must be resolved dynamically per request.

**File:** `pos-mcp-server/src/main/java/com/positivity/mcp/internal/discovery/OperationProxyFactory.java`

### Tests

None required.

---

## Suggested Commit Sequence

| # | Commit | Files |
|---|--------|-------|
| 1 | fix(catalog): correct service base-url defaults in application.yml and metadata | 2 |
| 2 | fix(invoice,bulk-loader,documents): correct TaxServiceClient port and stale events base-url overrides | 5 |
| 3 | fix(service-discovery): correct PermissionRegistration security base-url default port across all modules | 15 |
| 4 | fix(service-discovery): fix EventTypeInitializer gateway route miss in pos-order and pos-workorder | 3 |
| 5 | docs(mcp): document LoadBalancerClient vs @LoadBalanced usage decision in OperationProxyFactory | 1 |

---

## Note: Stale sections in the companion analysis document

`docs/service-discovery-loadbalancer-migration-analysis.md` contains two claims that are now outdated:

- **Line 28** ("there is no shared production `@LoadBalanced RestClient.Builder` pattern"): False. `@LoadBalanced RestClient.Builder` beans now exist in `pos-catalog SecurityConfig`, `pos-customer SecurityConfig`, `pos-people RestClientConfig`, `pos-shop-manager SecurityConfig`, and `pos-mcp-server McpServerConfiguration`.
- **Line 817** ("the entire codebase has exactly one `@LoadBalanced` annotation"): Same — this was written before the migration waves completed.

The service-ID mismatch analysis and ADR conflict sections remain accurate. Only the "current state" snapshot is stale. No action required unless the document is being used as a live reference; in that case update those two paragraphs to reflect the post-migration state.
