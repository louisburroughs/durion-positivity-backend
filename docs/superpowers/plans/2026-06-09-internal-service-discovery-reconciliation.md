# Internal Service Discovery Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all internal gateway-root and Docker-DNS service calls with direct Eureka/Spring Cloud LoadBalancer discovery, rename `*.base-url` properties to `*.service-id` + `*.base-path`, strip gateway path prefixes from internal calls, and inject `X-Authorities` headers on every direct service-to-service call.

**Architecture:** Every `@LoadBalanced RestClient.Builder` bean already exists in the modules that need migration; the change is mechanical: rename the property, update the `baseUrl("http://{serviceId}")` construction, strip the gateway prefix segment from request paths, and inject explicit auth headers. `pos-mcp-server` is a declared exception — it continues routing through the gateway because it relays end-user bearer tokens that require JWT validation.

**Tech Stack:** Spring Cloud LoadBalancer, Spring `RestClient`, Eureka service registry (`spring.application.name` as the service ID), `MockRestServiceServer` / embedded `HttpServer` for tests.

---

## Service ID Quick Reference

| Downstream Module | Eureka service-id | Gateway prefix stripped from path |
|---|---|---|
| pos-accounting | `accounting` | `/accounting` |
| pos-catalog | `catalog` | `/catalog` |
| pos-customer | `customer` | `/customer` |
| pos-inventory | `inventory` | `/inventory` |
| pos-invoice | `invoice` | `/invoice` |
| pos-location | `location` | `/location` |
| pos-people | `people` | `/people` |
| pos-price | `price` | `/price` |
| pos-security-service | `security-service` | `/security-service` |
| pos-shop-manager | `shop-manager` | `/shop-manager` |
| pos-vehicle-inventory | `vehicle-inventory` | `/vehicle-inventory` |
| pos-workorder | `workorder` | `/workorder` |
| pos-documents | `documents` | not gateway-routed; native path unchanged |

Source: `docs/service-discovery-migration/service-id-registry.md`

---

## Scope Notes

Tasks 2–10 are independent of each other and can be executed in any order. Each task touches one module end-to-end (config + clients + tests + metadata + commit) so every module remains internally coherent after its task. **Do not partial-migrate a module**: rename the property and update the client in the same commit.

Task 11 (pos-mcp-server) is a partial migration only — base URL switches from Docker DNS to LoadBalanced `http://api-gateway`, gateway path prefixes are preserved.

---

### Task 1: Phase 1 — Policy doc updates

**Files:**
- Modify: `docs/service-discovery-loadbalancer-migration-analysis.md`
- Modify: `docs/service-discovery-migration/client-policy-matrix.md`

- [ ] **Step 1: Update migration analysis doc**

Open `docs/service-discovery-loadbalancer-migration-analysis.md`. Find the section describing the intended migration strategy. Replace the current description (which says gateway-root normalization is the target) with direct discovery as the default. The new statement should say:

> Internal runtime service-to-service calls default to direct Eureka discovery via `@LoadBalanced RestClient.Builder` targeting `http://{serviceId}`. The API gateway remains the public edge for browser and external clients only. Gateway routing for internal calls is a documented exception (currently: pos-mcp-server, which relays end-user bearer tokens requiring JWT validation).

- [ ] **Step 2: Update client policy matrix — gateway-routed rows**

Open `docs/service-discovery-migration/client-policy-matrix.md`.

Change the `Category` column for all `gateway-routed` runtime client rows to `direct-discovery`.

Update the `Migration Action` column for those rows to:

> Migrate to direct Eureka discovery. Rename `*.base-url` → `*.service-id` + `*.base-path` (where applicable). Strip gateway path prefix. Inject `X-Authorities: <required-permission>` and `X-User: <caller-service-name>`.

Change `pos-mcp-server` rows (AccountingFacadeTool, WorkorderFacadeTool, InventoryFacadeTool, VehicleFacadeTool) category to `gateway-exception` and action to:

> Keep routing through `http://api-gateway` via LoadBalanced builder. Fix base URL from Docker DNS (`http://pos-*:8080`) to `http://api-gateway`. Preserve gateway path prefixes. Bearer token relay requires gateway JWT validation.

- [ ] **Step 3: Commit**

```bash
git add docs/service-discovery-loadbalancer-migration-analysis.md \
        docs/service-discovery-migration/client-policy-matrix.md
git commit -m "docs: update discovery migration docs for direct-discovery default"
```

---

### Task 2: pos-accounting migration

**Files:**
- Modify: `pos-accounting/src/main/java/com/positivity/accounting/internal/client/CustomerBillingRulesClient.java`
- Modify: `pos-accounting/src/main/java/com/positivity/accounting/internal/client/InvoiceServiceClient.java`
- Modify: `pos-accounting/src/main/java/com/positivity/accounting/internal/client/WorkorderInvoiceClient.java`
- Modify: `pos-accounting/src/main/resources/META-INF/additional-spring-configuration-metadata.json` (if it exists)

Context: All three clients already use `@Qualifier("invoiceServiceRestClient")` which is built from `loadBalancedRestClientBuilder`. The RestClient has no base URL. Each client composes the URL as `serviceUrl + "/prefix/v1/..."`. `CustomerBillingRulesClient` and `WorkorderInvoiceClient` already inject correct `X-User`/`X-Authorities` headers. `InvoiceServiceClient` does not.

- [x] **Step 1: Write failing tests**

Create `pos-accounting/src/test/java/com/positivity/accounting/internal/client/AccountingClientUriTest.java`:

```java
package com.positivity.accounting.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AccountingClientUriTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient restClient = builder.build();

    @Test
    void customerBillingRulesClient_usesDirectDiscoveryUrl() {
        UUID customerId = UUID.randomUUID();
        server.expect(requestTo("http://customer/v1/crm/snapshot/party/" + customerId + "/billing-rules"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Authorities", "crm:party:view"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CustomerBillingRulesClient client = new CustomerBillingRulesClient(restClient, "customer");
        try { client.getBillingRules(customerId); } catch (Exception ignored) {}
        server.verify();
    }

    @Test
    void invoiceServiceClient_usesDirectDiscoveryUrl_andInjectsAuthHeaders() {
        UUID invoiceId = UUID.randomUUID();
        server.expect(requestTo("http://invoice/v1/invoices/" + invoiceId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User", "pos-accounting"))
                .andExpect(header("X-Authorities", "workorder:invoice:view"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        InvoiceServiceClient client = new InvoiceServiceClient(restClient, null, "invoice");
        try { client.getInvoiceDetails(invoiceId); } catch (Exception ignored) {}
        server.verify();
    }

    @Test
    void workorderInvoiceClient_usesDirectDiscoveryUrl() {
        UUID workorderId = UUID.randomUUID();
        server.expect(requestTo("http://workorder/v1/workorders/" + workorderId + "/generate-invoice"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Authorities", "workorder:workorder:generate_invoice"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        WorkorderInvoiceClient client = new WorkorderInvoiceClient(restClient, "workorder");
        try { client.regenerateInvoiceFromWorkorder(workorderId, null); } catch (Exception ignored) {}
        server.verify();
    }
}
```

- [x] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-accounting test -Dtest=AccountingClientUriTest -q 2>&1 | tail -20
```

Expected: FAIL — compilation error (constructors don't accept a String serviceId yet).

- [ ] **Step 3: Implement — CustomerBillingRulesClient**

In `CustomerBillingRulesClient.java`:

Replace:
```java
@Value("${pos.customer.service.url:http://api-gateway}")
private String customerServiceUrl;
```

With:
```java
@Value("${pos.customer.service-id:customer}")
private String customerServiceId;
```

Replace the URI construction:
```java
.uri(customerServiceUrl + "/customer/v1/crm/snapshot/party/{partyId}/billing-rules", customerId)
```

With:
```java
.uri("http://" + customerServiceId + "/v1/crm/snapshot/party/{partyId}/billing-rules", customerId)
```

Add a constructor that accepts the `RestClient` and `serviceId` for testability (the existing `@RequiredArgsConstructor` constructor uses field injection for `@Value` — add a package-private constructor):

```java
// package-private for tests
CustomerBillingRulesClient(RestClient restClient, String customerServiceId) {
    this.restClient = restClient;
    this.customerServiceId = customerServiceId;
}
```

- [ ] **Step 4: Implement — InvoiceServiceClient**

Replace:
```java
@Value("${pos.invoice.service.url:http://api-gateway}")
private String invoiceServiceUrl;
```

With:
```java
@Value("${pos.invoice.service-id:invoice}")
private String invoiceServiceId;
```

In all URI constructions, replace `invoiceServiceUrl + "/invoice/v1/invoices/..."` with `"http://" + invoiceServiceId + "/v1/invoices/..."`:
- `invoiceServiceUrl + "/invoice/v1/invoices/{id}"` → `"http://" + invoiceServiceId + "/v1/invoices/{id}"`
- `invoiceServiceUrl + "/invoice/v1/invoices/{id}/apply-payment"` → `"http://" + invoiceServiceId + "/v1/invoices/{id}/apply-payment"`
- `invoiceServiceUrl + "/invoice/v1/invoices/{id}/reverse-payment"` → `"http://" + invoiceServiceId + "/v1/invoices/{id}/reverse-payment"`
- `invoiceServiceUrl + "/invoice/v1/invoices/{id}/apply-credit-memo"` → `"http://" + invoiceServiceId + "/v1/invoices/{id}/apply-credit-memo"`

Check `pos-invoice/src/main/java/.../controller/InvoiceController.java` for the `@PreAuthorize` annotation on `GET /v1/invoices/{id}` and `POST /v1/invoices/{id}/apply-payment`. Add `X-User: pos-accounting` and `X-Authorities: <required>` headers to each request in this client.

From checking the test assertion above, `GET /v1/invoices/{id}` should require `workorder:invoice:view`. Verify this:
```bash
grep -n "@PreAuthorize" pos-invoice/src/main/java/com/positivity/invoice/internal/controller/InvoiceController.java | head -10
```

Add auth injection to each method in `InvoiceServiceClient`, for example:
```java
InvoiceDetails details = restClient
    .get()
    .uri("http://" + invoiceServiceId + "/v1/invoices/{id}", invoiceId)
    .header("X-User", "pos-accounting")
    .header("X-Authorities", "workorder:invoice:view")  // verify against controller
    .retrieve()
    ...
```

Add package-private test constructor:
```java
InvoiceServiceClient(RestClient restClient, CircuitBreaker circuitBreaker, String invoiceServiceId) {
    this.restClient = restClient;
    this.invoiceServiceCircuitBreaker = circuitBreaker;
    this.invoiceServiceId = invoiceServiceId;
}
```

- [ ] **Step 5: Implement — WorkorderInvoiceClient**

Replace:
```java
@Value("${pos.workorder.service.url:http://api-gateway}")
private String workorderServiceUrl;
```

With:
```java
@Value("${pos.workorder.service-id:workorder}")
private String workorderServiceId;
```

Replace URI:
```java
.uri(workorderServiceUrl + "/workorder/v1/workorders/{workorderId}/generate-invoice", workorderId)
```

With:
```java
.uri("http://" + workorderServiceId + "/v1/workorders/{workorderId}/generate-invoice", workorderId)
```

Add package-private test constructor:
```java
WorkorderInvoiceClient(RestClient restClient, String workorderServiceId) {
    this.restClient = restClient;
    this.workorderServiceId = workorderServiceId;
}
```

- [ ] **Step 6: Update additional-spring-configuration-metadata.json**

Check if the file exists:
```bash
ls pos-accounting/src/main/resources/META-INF/additional-spring-configuration-metadata.json 2>/dev/null
```

If it exists, remove any entries for `pos.customer.service.url`, `pos.invoice.service.url`, `pos.workorder.service.url` and add:
```json
{ "name": "pos.customer.service-id", "type": "java.lang.String",
  "description": "Eureka service ID for pos-customer.",
  "defaultValue": "customer" },
{ "name": "pos.invoice.service-id", "type": "java.lang.String",
  "description": "Eureka service ID for pos-invoice.",
  "defaultValue": "invoice" },
{ "name": "pos.workorder.service-id", "type": "java.lang.String",
  "description": "Eureka service ID for pos-workorder.",
  "defaultValue": "workorder" }
```

- [ ] **Step 7: Run tests — verify they pass**

```bash
./mvnw -pl pos-accounting test -Dtest=AccountingClientUriTest -q
./mvnw -pl pos-accounting test -q 2>&1 | tail -10
```

Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add pos-accounting/
git commit -m "feat(pos-accounting): migrate service clients to direct Eureka discovery"
```

---

### Task 3: pos-catalog migration

**Files:**
- Modify: `pos-catalog/src/main/java/com/positivity/catalog/internal/client/InventoryClientImpl.java`
- Modify: `pos-catalog/src/main/java/com/positivity/catalog/internal/client/PricingClientImpl.java`
- Modify: `pos-catalog/src/test/java/com/positivity/catalog/internal/client/CatalogClientBuilderTest.java`
- Modify: `pos-catalog/src/main/resources/META-INF/additional-spring-configuration-metadata.json`

Context: Both clients receive `baseUrl` as a constructor parameter (already the LoadBalanced builder pattern). Test `CatalogClientBuilderTest` asserts `http://api-gateway` base URL and gateway-prefixed paths (`/inventory/v1/...`, `/price/v1/...`).

Downstream native paths:
- `InventoryClientImpl`: strip `/inventory` → `GET /v1/inventory/availability/query`, `GET /v1/inventory/availability/lead-time`
- `PricingClientImpl`: strip `/price` → `POST /v1/price/quotes`

Need to add `X-Authorities` to both clients. Check:
```bash
grep -n "@PreAuthorize" pos-inventory/src/main/java/com/positivity/inventory/internal/controller/InventoryAvailabilityController.java | head -5
grep -n "@PreAuthorize" pos-price/src/main/java/com/positivity/price/internal/controller/PriceQuoteController.java | head -5
```

- [ ] **Step 1: Update CatalogClientBuilderTest to assert direct discovery**

In `CatalogClientBuilderTest.java`, change the constant and all assertions:

```java
// OLD
private static final String BASE_URL = "http://api-gateway";

// NEW — split by service
private static final String INVENTORY_BASE_URL = "http://inventory";
private static final String PRICE_BASE_URL = "http://price";
```

Update `inventoryAndPricingClientsUseLoadBalancedBuilderWithDefaultGatewayBaseUrl`:
```java
@Test
void inventoryAndPricingClientsUseLoadBalancedBuilderWithDirectServiceIds() {
    contextRunner.run(context -> {
        RestClient.Builder loadBalancedBuilder =
                context.getBean("loadBalancedRestClientBuilder", RestClient.Builder.class);

        context.getBean(InventoryClientImpl.class);
        context.getBean(PricingClientImpl.class);

        verify(loadBalancedBuilder, times(1)).baseUrl(INVENTORY_BASE_URL);
        verify(loadBalancedBuilder, times(1)).baseUrl(PRICE_BASE_URL);
        verify(loadBalancedBuilder, times(2)).build();
    });
}
```

Update `inventoryClientUsesGatewayPrefixedAvailabilityQueryPath` → rename to `inventoryClientUsesNativeAvailabilityQueryPath`:
```java
@Test
void inventoryClientUsesNativeAvailabilityQueryPath() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
    InventoryClientImpl client = new InventoryClientImpl(builder, "inventory", "/v1/inventory");
    UUID locationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    mockServer
        .expect(requestTo("http://inventory/v1/inventory/availability/query?productSku=SKU-123&locationId=" + locationId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(...));

    assertThat(client.fetchAvailability("SKU-123", locationId)).isPresent();
    mockServer.verify();
}
```

Update `inventoryClientUsesGatewayPrefixedLeadTimePath` → rename to `inventoryClientUsesNativeLeadTimePath`, change URL to `http://inventory/v1/inventory/availability/lead-time?...`.

Update `pricingClientUsesGatewayPrefixedQuotesPath` → rename to `pricingClientUsesNativeQuotesPath`, change URL to `http://price/v1/price/quotes`.

Update `TestClientConfiguration` to match new constructor:
```java
@Bean
InventoryClientImpl inventoryClientImpl(...) {
    return new InventoryClientImpl(loadBalancedRestClientBuilder, "inventory", "/v1/inventory");
}
@Bean
PricingClientImpl pricingClientImpl(...) {
    return new PricingClientImpl(loadBalancedRestClientBuilder, "price", "/v1/price");
}
```

Update `BuilderProbeConfiguration.mockBuilder()` to stub both `baseUrl("http://inventory")` and `baseUrl("http://price")`.

- [ ] **Step 2: Run test — verify it fails**

```bash
./mvnw -pl pos-catalog test -Dtest=CatalogClientBuilderTest -q 2>&1 | tail -20
```

Expected: FAIL — compilation errors (constructors don't match yet).

- [ ] **Step 3: Implement InventoryClientImpl**

Change constructor signature:
```java
public InventoryClientImpl(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
    @Value("${pos.inventory.service-id:inventory}") String serviceId,
    @Value("${pos.inventory.base-path:/v1/inventory}") String basePath) {
    this.basePath = basePath;
    this.restClient = builder.baseUrl("http://" + serviceId).build();
}

private final String basePath;
```

In all URI calls replace `/inventory/v1/inventory/...` with `basePath + "/..."`:
- `"/inventory/v1/inventory/availability/query"` → `basePath + "/availability/query"`
- `"/inventory/v1/inventory/availability/lead-time"` → `basePath + "/availability/lead-time"`

Add auth headers (check `@PreAuthorize` on `InventoryAvailabilityController`):
```bash
grep -n "@PreAuthorize" pos-inventory/src/main/java/com/positivity/inventory/internal/controller/InventoryAvailabilityController.java | head -5
```

- [ ] **Step 4: Implement PricingClientImpl**

Change constructor:
```java
public PricingClientImpl(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
    @Value("${pos.price.service-id:price}") String serviceId,
    @Value("${pos.price.base-path:/v1/price}") String basePath) {
    this.basePath = basePath;
    this.restClient = builder.baseUrl("http://" + serviceId).build();
}

private final String basePath;
```

Replace URI `/price/v1/price/quotes` → `basePath + "/quotes"`.

Add auth headers for price calls (check pos-price's PriceQuoteController).

- [ ] **Step 5: Update additional-spring-configuration-metadata.json**

In `pos-catalog/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, replace:
```json
{ "name": "pos.inventory.base-url", "defaultValue": "http://api-gateway" }
{ "name": "pos.price.base-url", "defaultValue": "http://api-gateway" }
```

With:
```json
{ "name": "pos.inventory.service-id", "type": "java.lang.String",
  "description": "Eureka service ID for pos-inventory.", "defaultValue": "inventory" },
{ "name": "pos.inventory.base-path", "type": "java.lang.String",
  "description": "Base path for pos-inventory API.", "defaultValue": "/v1/inventory" },
{ "name": "pos.price.service-id", "type": "java.lang.String",
  "description": "Eureka service ID for pos-price.", "defaultValue": "price" },
{ "name": "pos.price.base-path", "type": "java.lang.String",
  "description": "Base path for pos-price API.", "defaultValue": "/v1/price" }
```

- [ ] **Step 6: Run tests — verify they pass**

```bash
./mvnw -pl pos-catalog test -q 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add pos-catalog/
git commit -m "feat(pos-catalog): migrate inventory and price clients to direct Eureka discovery"
```

---

### Task 4: pos-customer migration

**Files:**
- Modify: `pos-customer/src/main/java/com/positivity/customer/internal/client/PeopleClient.java`
- Modify: `pos-customer/src/main/java/com/positivity/customer/internal/client/VehicleInventoryClient.java`

Context: `PeopleClient` uses `loadBalancedRestClientBuilder` and receives base URL as a constructor parameter. `VehicleInventoryClient` uses `@Value("${pos.vehicle-inventory.base-url:http://api-gateway}")` and `@Qualifier("loadBalancedRestClientBuilder")`.

Downstream native paths:
- `PeopleClient`: strip `/people` → `GET /v1/people/{id}`
- `VehicleInventoryClient`: strip `/vehicle-inventory` → `POST /v1/vehicles`, `GET /v1/vehicles/vin/{vin}`, `GET /v1/vehicles/{vehicleId}`

Neither client injects auth headers. Check downstream controllers:
```bash
grep -n "@PreAuthorize" pos-people/src/main/java/com/positivity/people/internal/controller/PersonController.java | head -5
grep -n "@PreAuthorize" pos-vehicle-inventory/src/main/java/com/positivity/vehicleinventory/internal/controller/VehicleController.java 2>/dev/null | head -5
```

`GET /v1/people/{id}` requires `people:person:view` (confirmed).

- [ ] **Step 1: Write failing tests**

Create `pos-customer/src/test/java/com/positivity/customer/internal/client/CustomerClientUriTest.java`:

```java
package com.positivity.customer.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CustomerClientUriTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void peopleClient_usesDirectDiscoveryUrl_andAuthHeader() {
        server.expect(requestTo("http://people/v1/people/42"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "people:person:view"))
              .andRespond(withSuccess("{\"personId\":42,\"username\":\"test\"}", MediaType.APPLICATION_JSON));

        PeopleClient client = new PeopleClient(builder, "people");
        client.getPersonById(42L);
        server.verify();
    }

    @Test
    void vehicleInventoryClient_usesDirectDiscoveryUrl() {
        import java.util.UUID;
        UUID vehicleId = UUID.randomUUID();
        server.expect(requestTo("http://vehicle-inventory/v1/vehicles/" + vehicleId))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VehicleInventoryClient client = new VehicleInventoryClient(builder, "vehicle-inventory");
        try { client.getVehicleById(vehicleId); } catch (Exception ignored) {}
        server.verify();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-customer test -Dtest=CustomerClientUriTest -q 2>&1 | tail -20
```

- [ ] **Step 3: Implement PeopleClient**

Change constructor to accept `serviceId`:
```java
public PeopleClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
    @Value("${pos.people.service-id:people}") String serviceId) {
    this.restClient = builder.baseUrl("http://" + serviceId).build();
}
```

Change URI from `peopleServiceUrl + "/people/v1/people/{id}"` to `/v1/people/{id}` (relative to base URL).

Add auth header to the request:
```java
.header("X-Authorities", "people:person:view")
.header("X-User", "pos-customer")
```

- [ ] **Step 4: Implement VehicleInventoryClient**

Replace:
```java
@Value("${pos.vehicle-inventory.base-url:http://api-gateway}") String vehicleInventoryBaseUrl
```

With:
```java
@Value("${pos.vehicle-inventory.service-id:vehicle-inventory}") String serviceId
```

Change `builder.baseUrl(vehicleInventoryBaseUrl)` → `builder.baseUrl("http://" + serviceId)`.

Strip the `/vehicle-inventory` prefix from all URI paths:
- `"/vehicle-inventory/v1/vehicles"` → `"/v1/vehicles"`
- `"/vehicle-inventory/v1/vehicles/vin/{vin}"` → `"/v1/vehicles/vin/{vin}"`
- `VEHICLE_BY_ID_PATH = "/vehicle-inventory/v1/vehicles/{vehicleId}"` → `"/v1/vehicles/{vehicleId}"`

Check pos-vehicle-inventory controller for required permissions and add auth headers.

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw -pl pos-customer test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add pos-customer/
git commit -m "feat(pos-customer): migrate people and vehicle-inventory clients to direct Eureka discovery"
```

---

### Task 5: pos-inventory migration

**Files:**
- Modify: `pos-inventory/src/main/java/com/positivity/inventory/internal/client/SiteDefaultsClient.java`
- Modify: `pos-inventory/src/main/java/com/positivity/inventory/internal/client/StorageLocationValidationClient.java`
- Modify: `pos-inventory/src/main/java/com/positivity/inventory/internal/client/WorkorderValidationClient.java`

Context: All three clients use `gateway.url` property and bake the path prefix into the RestClient base URL. All three copy auth headers from the incoming HTTP request context. After migration: use `pos.location.service-id` / `pos.workorder.service-id`, remove request-context-header forwarding, inject `X-Authorities` statically.

Downstream native paths:
- `SiteDefaultsClient`: `http://location` + `GET /v1/locations/{siteId}/defaults` — requires `location:read` (confirmed from `SiteDefaultsController` line 74)
- `StorageLocationValidationClient`: `http://location` + `GET /v1/storage-locations/{storageLocationId}/validation` — requires `location:read` (confirmed from `StorageLocationValidationController` line 42)
- `WorkorderValidationClient`: `http://workorder` + `GET /v1/workorders/{workorderId}/detail` — check:
  ```bash
  grep -n "@PreAuthorize" pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderDetailController.java | head -5
  ```

- [x] **Step 1: Write failing tests**

Create `pos-inventory/src/test/java/com/positivity/inventory/internal/client/InventoryClientUriTest.java`:

```java
package com.positivity.inventory.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InventoryClientUriTest {

    @Test
    void siteDefaultsClient_usesDirectLocationDiscovery() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID siteId = UUID.randomUUID();

        server.expect(requestTo("http://location/v1/locations/" + siteId + "/defaults"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "location:read"))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SiteDefaultsClient client = new SiteDefaultsClient(builder, "location");
        client.getDefaultStagingLocationId(siteId);
        server.verify();
    }

    @Test
    void storageLocationValidationClient_usesDirectLocationDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID storageLocationId = UUID.randomUUID();

        server.expect(requestTo("http://location/v1/storage-locations/" + storageLocationId + "/validation"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "location:read"))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        StorageLocationValidationClient client = new StorageLocationValidationClient(builder, "location");
        client.getStorageLocationValidation(storageLocationId.toString());
        server.verify();
    }

    @Test
    void workorderValidationClient_usesDirectWorkorderDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID workorderId = UUID.randomUUID();

        server.expect(requestTo("http://workorder/v1/workorders/" + workorderId + "/detail"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "workorder:workorder:view"))
              .andRespond(withSuccess("{\"status\":\"OPEN\",\"parts\":[]}", MediaType.APPLICATION_JSON));

        WorkorderValidationClient client = new WorkorderValidationClient(builder, "workorder");
        try { client.getWorkorderLineValidation(workorderId.toString(), UUID.randomUUID().toString()); }
        catch (Exception ignored) {}
        server.verify();
    }
}
```

- [x] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-inventory test -Dtest=InventoryClientUriTest -q 2>&1 | tail -20
```

- [x] **Step 3: Implement SiteDefaultsClient**

Replace constructor:
```java
public SiteDefaultsClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.location.service-id:location}") String locationServiceId) {
    this.restClient = restClientBuilder.baseUrl("http://" + locationServiceId).build();
}
```

Change URI from `"/{siteId}/defaults"` (relative to old gateway + path base URL) to `"/v1/locations/{siteId}/defaults"`.

Replace `applySecurityHeaders(HttpHeaders headers)` method and the call `.headers(this::applySecurityHeaders)` with explicit header injection:
```java
.header("X-User", "pos-inventory")
.header("X-Authorities", "location:read")
```

Remove `copyHeaderIfPresent`, `applySecurityHeaders`, and the `jakarta.servlet.*` / `RequestContextHolder` imports.

- [x] **Step 4: Implement StorageLocationValidationClient**

Replace constructor:
```java
public StorageLocationValidationClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.location.service-id:location}") String locationServiceId) {
    this.restClient = restClientBuilder
        .baseUrl("http://" + locationServiceId)
        .build();
}
```

Change URI from `"/{storageLocationId}/validation"` to `"/v1/storage-locations/{storageLocationId}/validation"`.

Replace `.header(HttpHeaders.AUTHORIZATION, authorizationHeader)` with:
```java
.header("X-User", "pos-inventory")
.header("X-Authorities", "location:read")
```

Remove `resolveAuthorizationHeader()`, `BEARER_PREFIX` constant, `jakarta.servlet.*`, `RequestContextHolder` imports.

- [x] **Step 5: Implement WorkorderValidationClient**

Replace constructor:
```java
public WorkorderValidationClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.workorder.service-id:workorder}") String workorderServiceId) {
    this.restClient = restClientBuilder.baseUrl("http://" + workorderServiceId).build();
}
```

Change URI from `"/{workorderId}/detail"` to `"/v1/workorders/{workorderId}/detail"`.

Confirm the required permission:
```bash
grep -n "@PreAuthorize" pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderDetailController.java | head -5
```

Replace `.headers(this::applySecurityHeaders)` with:
```java
.header("X-User", "pos-inventory")
.header("X-Authorities", "workorder:workorder:view")  // verify against controller
```

Remove `applySecurityHeaders`, `copyHeaderIfPresent`, `jakarta.servlet.*`, `RequestContextHolder`, `GatewaySecurityConstants` imports.

- [x] **Step 6: Run tests — verify they pass**

```bash
./mvnw -pl pos-inventory test -q 2>&1 | tail -10
```

- [x] **Step 7: Commit**

```bash
git add pos-inventory/
git commit -m "feat(pos-inventory): migrate location and workorder clients to direct Eureka discovery"
```

---

### Task 6: pos-location migration

**Files:**
- Modify: `pos-location/src/main/java/com/positivity/location/internal/client/PersonClient.java`
- Modify: `pos-location/src/main/java/com/positivity/location/internal/client/LocationInventoryInquiryClient.java`
- Modify: `pos-location/src/test/java/com/positivity/location/internal/client/PersonClientTest.java`

Context: `PersonClient` receives `gatewayBaseUrl` as constructor parameter. `LocationInventoryInquiryClient` bakes path prefix into base URL and uses bearer token forwarding from request context.

Downstream native paths:
- `PersonClient`: `http://people` + `GET /v1/people/{id}` — requires `people:person:view`
- `LocationInventoryInquiryClient`: `http://inventory` + `GET /v1/inventory/locations/{storageLocationId}/inventory-inquiry` — requires `inventory:on_hand:view` (confirmed)

- [ ] **Step 1: Update PersonClientTest**

In `PersonClientTest.java`, change:

```java
private static final String BASE_URL = "http://people";  // was "http://api-gateway"

// Setup in @BeforeEach:
personClient = new PersonClient(builder, BASE_URL);
```

Update both test assertions:
```java
// was: BASE_URL + "/people/v1/people/42"
mockServer.expect(requestTo(BASE_URL + "/v1/people/42"))
```

Add a header assertion:
```java
.andExpect(header("X-Authorities", "people:person:view"))
```

- [ ] **Step 2: Run test — verify it fails**

```bash
./mvnw -pl pos-location test -Dtest=PersonClientTest -q 2>&1 | tail -20
```

Expected: FAIL — wrong URL.

- [ ] **Step 3: Implement PersonClient**

Rename the constructor parameter `gatewayBaseUrl` → `serviceId` (and corresponding `@Value`):
```java
public PersonClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.people.service-id:people}") String serviceId) {
    this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
}
```

Change URI from `"/people/v1/people/{id}"` to `"/v1/people/{id}"`.

Add auth header:
```java
.header("X-Authorities", "people:person:view")
.header("X-User", "pos-location")
```

Remove `peopleServiceUrl` field if it existed; the base URL is now embedded in the RestClient.

- [ ] **Step 4: Implement LocationInventoryInquiryClient**

Write a new test first:

Add to a new file `pos-location/src/test/java/com/positivity/location/internal/client/LocationInventoryInquiryClientTest.java`:

```java
package com.positivity.location.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LocationInventoryInquiryClientTest {

    @Test
    void getOnHandQuantity_usesDirectInventoryDiscovery_andAuthHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID locationId = UUID.randomUUID();

        server.expect(requestTo("http://inventory/v1/inventory/locations/" + locationId + "/inventory-inquiry"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "inventory:on_hand:view"))
              .andExpect(header("X-User", "pos-location"))
              .andRespond(withSuccess("{\"onHandQuantity\": 5}", MediaType.APPLICATION_JSON));

        LocationInventoryInquiryClient client = new LocationInventoryInquiryClient(builder, "inventory");
        assertThat(client.getOnHandQuantity(locationId)).isEqualTo(5);
        server.verify();
    }
}
```

Run the test to confirm it fails:
```bash
./mvnw -pl pos-location test -Dtest=LocationInventoryInquiryClientTest -q 2>&1 | tail -20
```

Now implement the change in `LocationInventoryInquiryClient.java`:

Replace constructor:
```java
public LocationInventoryInquiryClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.inventory.service-id:inventory}") String inventoryServiceId) {
    this.restClient = restClientBuilder
        .baseUrl("http://" + inventoryServiceId)
        .build();
}
```

Change URI from `"/{storageLocationId}/inventory-inquiry"` (which was relative to the old path-baked base URL) to `"/v1/inventory/locations/{storageLocationId}/inventory-inquiry"`.

Replace the `.header(HttpHeaders.AUTHORIZATION, resolveAuthorizationHeader())` line with:
```java
.header("X-User", "pos-location")
.header("X-Authorities", "inventory:on_hand:view")
```

Remove `resolveAuthorizationHeader()`, `BEARER_PREFIX`, `GatewaySecurityConstants` imports, `jakarta.servlet.*` imports, `RequestContextHolder`.

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw -pl pos-location test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add pos-location/
git commit -m "feat(pos-location): migrate people and inventory clients to direct Eureka discovery"
```

---

### Task 7: pos-people migration

**Files:**
- Modify: `pos-people/src/main/java/com/positivity/people/internal/config/RestClientConfig.java`
- Modify: `pos-people/src/main/java/com/positivity/people/internal/client/SecurityServiceClient.java`
- Modify: `pos-people/src/main/java/com/positivity/people/internal/client/LocationReferenceClient.java`
- Modify: `pos-people/src/main/java/com/positivity/people/internal/client/WorkexecJobTimeClient.java`

Context: All three beans (`securityServiceRestClient`, `workexecRestClient`, `locationServiceRestClient`) use `pos.X.base-url:http://api-gateway`. None of the clients inject auth headers.

Downstream service IDs and native paths after stripping gateway prefix:
- `securityServiceRestClient` → service-id `security-service`; strip `/security-service`: `/v1/users`, `/v1/roles`, `/v1/roles/by-name/{name}`, `/v1/roles/assignments`, `/v1/roles/assignments/user/{userId}`, `/v1/roles/assignments/{assignmentId}`
- `workexecRestClient` → service-id `workorder` (workexec is part of pos-workorder); strip `/workorder`: `/v1/workexec/job-time-totals`
- `locationServiceRestClient` → service-id `location`; strip `/location`: `/v1/locations/{id}/validation`, `/v1/locations/{id}`

Required permissions:
- Security service endpoints: check `pos-security-service/.../controller/UserController.java`, `RoleController.java`, `RoleAssignmentController.java`
  ```bash
  grep -n "@PreAuthorize" pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/UserController.java | head -5
  grep -n "@PreAuthorize" pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/RoleController.java | head -5
  grep -n "@PreAuthorize" pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/RoleAssignmentController.java | head -5
  ```
- Location validation: `location:read` (confirmed)
- Workexec job time totals: check pos-workorder's workexec controller:
  ```bash
  grep -n "@PreAuthorize" pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkexecController.java 2>/dev/null | head -5
  ```

- [ ] **Step 1: Write failing tests**

Create `pos-people/src/test/java/com/positivity/people/internal/client/PeopleClientUriTest.java`:

```java
package com.positivity.people.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PeopleClientUriTest {

    @Test
    void locationReferenceClient_usesDirectLocationDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // baseUrl("http://location") in the RestClient
        RestClient restClient = builder.baseUrl("http://location").build();

        // rebuild server binding — for simplicity, just use MockRestServiceServer with a bound client
        // that already has http://location as base
        server.expect(requestTo("http://location/v1/locations/00000000-0000-0000-0000-000000000001/validation"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "location:read"))
              .andRespond(withSuccess("{\"exists\":true,\"active\":true}", MediaType.APPLICATION_JSON));

        // Note: this test validates the URI path constructed by LocationReferenceClient
        // The actual bean uses `locationServiceRestClient` which is built with
        // baseUrl("http://location") after migration. The test here wires the mock server directly.
    }
}
```

Note: Because the RestClient's base URL comes from the RestClientConfig bean (not the client constructor), the test approach is to bind `MockRestServiceServer` against a pre-built RestClient with the expected base URL. Write the tests per-client that way, or add package-private constructors.

A simpler approach: use `MockRestServiceServer` bound to a `RestClient.Builder`, build the client, then pass it to the client class via a package-private constructor.

For `LocationReferenceClient`:
```java
// package-private constructor for tests:
LocationReferenceClient(RestClient restClient) {
    this.restClient = restClient;
}
```

Test:
```java
@Test
void locationReferenceClient_isActive_callsNativePath() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RestClient client = builder.build();
    UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    server.expect(requestTo("http://location/v1/locations/" + locationId + "/validation"))
          .andExpect(method(HttpMethod.GET))
          .andExpect(header("X-Authorities", "location:read"))
          .andRespond(withSuccess("{\"exists\":true,\"active\":true}", MediaType.APPLICATION_JSON));

    // Build with a mock base-URL client
    RestClient.Builder lb = RestClient.builder().baseUrl("http://location");
    MockRestServiceServer.bindTo(lb).build()
        .expect(requestTo("http://location/v1/locations/" + locationId + "/validation"))
        .andExpect(header("X-Authorities", "location:read"))
        .andRespond(withSuccess("{\"exists\":true,\"active\":true}", MediaType.APPLICATION_JSON));

    LocationReferenceClient lrc = new LocationReferenceClient(lb.build());
    assertThat(lrc.isLocationActive(locationId)).isTrue();
}
```

(Write similar tests for `WorkexecJobTimeClient` and `SecurityServiceClient`.)

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-people test -Dtest=PeopleClientUriTest -q 2>&1 | tail -20
```

- [ ] **Step 3: Implement RestClientConfig**

In `pos-people/src/main/java/com/positivity/people/internal/config/RestClientConfig.java`:

Replace:
```java
@Value("${pos.security-service.base-url:http://api-gateway}") String securityServiceBaseUrl
```
With:
```java
@Value("${pos.security-service.service-id:security-service}") String serviceId
```
And change `builder.requestFactory(factory).baseUrl(securityServiceBaseUrl).build()` → `builder.requestFactory(factory).baseUrl("http://" + serviceId).build()`.

Also add `defaultHeader` calls for auth:
```java
return builder.requestFactory(factory)
    .baseUrl("http://" + serviceId)
    .defaultHeader("X-User", "pos-people")
    .defaultHeader("X-Authorities", "<permissions>")  // verify from controller
    .build();
```

Repeat the same pattern for `workexecRestClient` (`pos.workexec.service-id:workorder`) and `locationServiceRestClient` (`pos.location-service.service-id:location`).

For `locationServiceRestClient`, after migration to `baseUrl("http://location")`, the `LocationReferenceClient` URI paths must change:
- `/location/v1/locations/{locationId}/validation` → `/v1/locations/{locationId}/validation`
- `/location/v1/locations/{locationId}` → `/v1/locations/{locationId}`

For `workexecRestClient` → `WorkexecJobTimeClient`:
- `/workorder/v1/workexec/job-time-totals` → `/v1/workexec/job-time-totals`

For `securityServiceRestClient` → `SecurityServiceClient`:
- `/security-service/v1/users` → `/v1/users`
- `/security-service/v1/roles/by-name/{name}` → `/v1/roles/by-name/{name}`
- `/security-service/v1/roles` → `/v1/roles`
- `/security-service/v1/roles/assignments/user/{userId}` → `/v1/roles/assignments/user/{userId}`
- `/security-service/v1/roles/assignments` → `/v1/roles/assignments`
- `/security-service/v1/roles/assignments/{assignmentId}` → `/v1/roles/assignments/{assignmentId}`

- [ ] **Step 4: Update URI paths in all three client classes**

Apply path changes in `SecurityServiceClient.java`, `LocationReferenceClient.java`, and `WorkexecJobTimeClient.java` as listed above.

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw -pl pos-people test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add pos-people/
git commit -m "feat(pos-people): migrate security-service, location, workexec clients to direct Eureka discovery"
```

---

### Task 8: pos-security-service migration

**Files:**
- Modify: `pos-security-service/src/main/java/com/positivity/securityservice/internal/config/RestClientConfig.java`
- Modify: `pos-security-service/src/main/java/com/positivity/securityservice/internal/client/PeopleRegistrationClient.java`
- Modify: `pos-security-service/src/main/java/com/positivity/securityservice/internal/client/CustomerRegistrationClient.java`

Context: Both beans already inject `X-User` and `X-Authorities` via `defaultHeader`. Only the base URL and URI paths need to change. After migration to `http://people` and `http://customer`, strip gateway prefixes from paths.

Downstream native path changes:
- `PeopleRegistrationClient` (base: `http://people`):
  - `/people/v1/people/resolve` → `/v1/people/resolve`
  - `/people/v1/people/{personId}/users` → `/v1/people/{personId}/users`
  - `/people/v1/people/users/link` → `/v1/people/users/link`
  - `/people/v1/people/{personId}` → `/v1/people/{personId}`
- `CustomerRegistrationClient` (base: `http://customer`):
  - `/customer/v1/crm/persons` → `/v1/crm/persons`

- [ ] **Step 1: Write failing tests**

Create `pos-security-service/src/test/java/com/positivity/securityservice/internal/client/SecurityServiceClientUriTest.java`:

```java
package com.positivity.securityservice.internal.client;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecurityServiceClientUriTest {

    @Test
    void peopleRegistrationClient_usesNativePeoplePath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://people");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://people/v1/people/resolve"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(header("X-Authorities", "people:person:create,people:person:delete,people:userLink:view,people:userLink:write"))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        PeopleRegistrationClient client = new PeopleRegistrationClient(builder.build());
        try { client.resolvePerson(new com.positivity.securityservice.internal.client.dto.PeopleResolvePersonRequest()); }
        catch (Exception ignored) {}
        server.verify();
    }

    @Test
    void customerRegistrationClient_usesNativeCustomerPath() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://customer");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://customer/v1/crm/persons"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CustomerRegistrationClient client = new CustomerRegistrationClient(builder.build());
        client.searchPersons(null, null, null);
        server.verify();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-security-service test -Dtest=SecurityServiceClientUriTest -q 2>&1 | tail -20
```

- [ ] **Step 3: Implement RestClientConfig**

Replace in `peopleRegistrationRestClient`:
```java
@Value("${pos.people.base-url:http://api-gateway}") String peopleBaseUrl
```
→
```java
@Value("${pos.people.service-id:people}") String serviceId
```

Change: `builder.requestFactory(factory).baseUrl(peopleBaseUrl)` → `builder.requestFactory(factory).baseUrl("http://" + serviceId)`.

Repeat for `customerRegistrationRestClient`:
- `pos.customer.base-url:http://api-gateway` → `pos.customer.service-id:customer`

- [ ] **Step 4: Update URI paths**

In `PeopleRegistrationClient.java`, strip `/people` prefix from all URIs:
- `.uri("/people/v1/people/resolve")` → `.uri("/v1/people/resolve")`
- `.uri("/people/v1/people/{personId}/users", personId)` → `.uri("/v1/people/{personId}/users", personId)`
- `.uri("/people/v1/people/users/link")` → `.uri("/v1/people/users/link")`
- `.uri("/people/v1/people/{personId}", personId)` → `.uri("/v1/people/{personId}", personId)`

In `CustomerRegistrationClient.java`:
- `.path("/customer/v1/crm/persons")` → `.path("/v1/crm/persons")`

- [ ] **Step 5: Run tests — verify they pass**

```bash
./mvnw -pl pos-security-service test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add pos-security-service/
git commit -m "feat(pos-security-service): migrate people and customer registration clients to direct Eureka discovery"
```

---

### Task 9: pos-shop-manager migration

**Files:**
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/config/SecurityConfig.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/CrmCustomerClient.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/CrmVehicleClient.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/HrAvailabilityClient.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/LocationClient.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/PersonClient.java`
- Modify: `pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/client/ServiceEntityClient.java`

Context: `CrmCustomerClient` and `CrmVehicleClient` use `@Qualifier("crmRestClient")` (no base URL, timeout configured); each composes the URL from a `@Value` property. The other four clients use `@Qualifier("loadBalancedRestClientBuilder")` with their own base URL properties.

None of these clients inject auth headers.

Downstream native paths:
- `CrmCustomerClient` → `http://customer` + `GET /v1/customers/{id}` — check pos-customer CustomerController
- `CrmVehicleClient` → `http://customer` + `GET /v1/vehicles/{vehicleId}` — check pos-customer VehicleController
- `HrAvailabilityClient` → `http://people` + `GET /v1/availability/overlay`, `GET /hr/v1/schedules`
- `LocationClient` → `http://location` + `GET /v1/locations/bays`, etc.
- `PersonClient` → `http://people` + `GET /v1/people/{id}` — requires `people:person:view`
- `ServiceEntityClient` → `http://catalog` + `GET /v1/services/{id}` — check pos-catalog ServiceController

Check permissions:
```bash
grep -n "@PreAuthorize" pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerController.java | head -5
grep -n "@PreAuthorize" pos-location/src/main/java/com/positivity/location/internal/controller/LocationController.java | grep -E "5[0-9]:" | head -5
grep -n "@PreAuthorize" pos-catalog/src/main/java/com/positivity/catalog/internal/controller/ServiceEntityController.java 2>/dev/null | head -5
```

- [ ] **Step 1: Write failing tests**

Create `pos-shop-manager/src/test/java/com/positivity/shopmanager/internal/client/ShopManagerClientUriTest.java`:

```java
package com.positivity.shopmanager.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ShopManagerClientUriTest {

    @Test
    void crmCustomerClient_usesDirectCustomerDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UUID customerId = UUID.randomUUID();

        server.expect(requestTo("http://customer/v1/customers/" + customerId))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "crm:party:view"))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CrmCustomerClient client = new CrmCustomerClient(builder.build(), "customer");
        client.getCustomerById(customerId);
        server.verify();
    }

    @Test
    void personClient_usesDirectPeopleDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://people/v1/people/42"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "people:person:view"))
              .andRespond(withSuccess("{\"personId\":42}", MediaType.APPLICATION_JSON));

        PersonClient client = new PersonClient(builder, "people");
        client.getPersonById(42L);
        server.verify();
    }

    @Test
    void locationClient_usesDirectLocationDiscovery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("http://location/v1/locations/bays"))
              .andExpect(method(HttpMethod.GET))
              .andExpect(header("X-Authorities", "location:read"))
              .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        LocationClient client = new LocationClient(builder, "location");
        client.getBays();
        server.verify();
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw -pl pos-shop-manager test -Dtest=ShopManagerClientUriTest -q 2>&1 | tail -20
```

- [ ] **Step 3: Implement SecurityConfig — crmRestClient base URL**

In `SecurityConfig.java`, add `serviceId` parameter to `crmRestClient`:

```java
@Bean(name = "crmRestClient")
public RestClient crmRestClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
    @Value("${pos.customer.service-id:customer}") String serviceId,
    @Value("${pos.crm.connect-timeout-ms:200}") int connectTimeoutMs,
    @Value("${pos.crm.read-timeout-ms:2000}") int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    return builder.requestFactory(factory).baseUrl("http://" + serviceId).build();
}
```

- [ ] **Step 4: Implement CrmCustomerClient**

Remove `@Value("${pos.crm.customer-base-url:...}") private String customerBaseUrl;`.

Change URI from `customerBaseUrl + "/customer/v1/customers/{customerId}"` to `"/v1/customers/{customerId}"`.

Add auth header:
```java
.header("X-User", "pos-shop-manager")
.header("X-Authorities", "crm:party:view")
```

Add package-private constructor for tests:
```java
CrmCustomerClient(RestClient crmRestClient, String ignored) {
    this.crmRestClient = crmRestClient;
}
```

(Or use a single constructor with the `RestClient` only, since the base URL is now in the config.)

- [ ] **Step 5: Implement CrmVehicleClient**

Same pattern as `CrmCustomerClient`:
- Remove `pos.crm.vehicle-base-url` field
- Change URI from `vehicleBaseUrl + "/customer/v1/vehicles/{vehicleId}"` → `"/v1/vehicles/{vehicleId}"`
- Add `X-User: pos-shop-manager` + `X-Authorities: crm:party:view`

- [ ] **Step 6: Implement HrAvailabilityClient**

Change constructor parameter from `pos.hr.base-url:http://api-gateway` to `pos.people.service-id:people`.

Change `builder.baseUrl(hrBaseUrl)` → `builder.baseUrl("http://" + serviceId)`.

Strip `/people` from URI paths:
- `.path("/people/v1/availability/overlay")` → `.path("/v1/availability/overlay")`
- `.path("/people/hr/v1/schedules")` → `.path("/hr/v1/schedules")`

Add auth headers to each request (check `pos-people`'s availability and HR controllers).

- [ ] **Step 7: Implement LocationClient**

Change constructor from `location.service.url:http://api-gateway` to `pos.location.service-id:location`.

Store `serviceId`; change URL composition from `locationServiceUrl + "/location/v1/locations/..."` to `"http://" + locationServiceId + "/v1/locations/..."`.

OR: Set base URL on the RestClient and use relative paths:
```java
this.restClient = builder.baseUrl("http://" + serviceId).build();
// then use: .uri("/v1/locations/bays") etc.
```

Add auth headers (use `location:read` for GET, `location:write` for POST/PUT/DELETE).

- [ ] **Step 8: Implement PersonClient**

Change `people.service.url:http://api-gateway` → `pos.people.service-id:people`.

Change URI from `peopleServiceUrl + "/people/v1/people/{id}"` → switch to base URL construction:
```java
this.restClient = builder.baseUrl("http://" + serviceId).build();
// usage: .uri("/v1/people/{id}", id)
```

Add auth:
```java
.header("X-Authorities", "people:person:view")
.header("X-User", "pos-shop-manager")
```

- [ ] **Step 9: Implement ServiceEntityClient**

Change `catalog.service.url:http://api-gateway` → `pos.catalog.service-id:catalog`.

Change URI from `catalogServiceUrl + "/catalog/v1/services/{id}"` → base URL + relative path:
```java
this.restClient = builder.baseUrl("http://" + serviceId).build();
// usage: .uri("/v1/services/{id}", id)
```

Add auth headers (check pos-catalog's service entity controller for `@PreAuthorize`).

- [ ] **Step 10: Run tests — verify they pass**

```bash
./mvnw -pl pos-shop-manager test -q 2>&1 | tail -10
```

- [ ] **Step 11: Commit**

```bash
git add pos-shop-manager/
git commit -m "feat(pos-shop-manager): migrate all service clients to direct Eureka discovery"
```

---

### Task 10: pos-workorder migration

**Files:**
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/CustomerValidationClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/ShopmgrOperationalContextClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/config/InventoryClientConfig.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/config/InvoiceClientConfig.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/config/PeopleClientConfig.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/config/DocumentClientConfig.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/InventoryPickClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/InvoiceClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/PeopleLocationClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/PeopleAvailabilityClient.java`
- Modify: `pos-workorder/src/main/java/com/positivity/workorder/internal/client/DocumentClient.java`
- Modify: `pos-workorder/src/main/resources/META-INF/additional-spring-configuration-metadata.json`
- Modify: `pos-workorder/src/test/java/com/positivity/workorder/client/ShopmgrOperationalContextClientTest.java`
- Modify: `pos-workorder/src/test/java/com/positivity/workorder/client/PeopleAvailabilityClientTest.java`

Context: `CustomerValidationClient` and `ShopmgrOperationalContextClient` take `baseUrl` in constructor; their existing tests use embedded HttpServer and don't assert URI paths. `DocumentClientConfig` currently uses the plain (not LoadBalanced) `RestClient.Builder` — it must switch to the LoadBalanced builder.

`TaxClientConfig` and `TaxClient` remain unchanged (tax-exemption exception).

Downstream native paths:
- `CustomerValidationClient` (→ `http://customer`): strip `/customer`
  - `/customer/v1/customers/{id}/requirements-met` → `/v1/customers/{id}/requirements-met`
  - `/customer/v1/approvals/{id}/is-approved` → `/v1/approvals/{id}/is-approved`
- `ShopmgrOperationalContextClient` (→ `http://shop-manager`): strip `/shop-manager`
  - `/shop-manager/v1/shopmgr/workorders/{id}/operationalContext` → `/v1/shopmgr/workorders/{id}/operationalContext`
  - `/shop-manager/v1/shopmgr/locations/{id}/bays` → `/v1/shopmgr/locations/{id}/bays`
- `InventoryClientConfig` → `InventoryPickClient` (→ `http://inventory`): strip `/inventory`
  - `/inventory/v1/inventory/pick-lists/...` → `/v1/inventory/pick-lists/...`
- `InvoiceClientConfig` → `InvoiceClient` (→ `http://invoice`): strip `/invoice`
  - `/invoice/v1/invoices` → `/v1/invoices`
- `PeopleClientConfig` → `PeopleLocationClient` + `PeopleAvailabilityClient` (→ `http://people`): strip `/people`
  - `/people/v1/people/me/primary-location` → `/v1/people/me/primary-location`
  - `/people/v1/people/availability` → `/v1/people/availability`
- `DocumentClientConfig` → `DocumentClient` (→ `http://documents`): path already native (`/v1/documents/render`), no prefix to strip

Check permissions:
```bash
grep -n "@PreAuthorize" pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerRequirementsController.java 2>/dev/null | head -5
grep -n "@PreAuthorize" pos-invoice/src/main/java/com/positivity/invoice/internal/controller/InvoiceController.java | head -5
grep -n "@PreAuthorize" pos-documents/src/main/java/com/positivity/documents/internal/controller/DocumentController.java 2>/dev/null | head -5
grep -n "@PreAuthorize" pos-people/src/main/java/com/positivity/people/internal/controller/PeopleAvailabilityController.java 2>/dev/null | head -5
```

- [ ] **Step 1: Add URI path assertions to ShopmgrOperationalContextClientTest**

In `ShopmgrOperationalContextClientTest.java`, `buildClient()` currently passes a port-based URL. Add a new test specifically asserting path:

```java
@Test
@DisplayName("getOperationalContext calls native shop-manager path (no gateway prefix)")
void getOperationalContext_callsNativePath() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/shopmgr/workorders/")))
          .andExpect(method(HttpMethod.GET))
          .andExpect(header("X-Authorities", "<required-permission>"))
          .andRespond(withSuccess("{\"version\":\"1\",\"locationId\":\"" + LOCATION_ID + "\",\"bayId\":\"B1\",\"locked\":false}", MediaType.APPLICATION_JSON));

    ShopmgrOperationalContextClient client = new ShopmgrOperationalContextClient(builder, "shop-manager");
    client.getOperationalContext(WORKORDER_ID);
    server.verify();
}
```

- [ ] **Step 2: Add URI path assertion to PeopleAvailabilityClientTest**

In `PeopleAvailabilityClientTest.java`, the embedded-server tests accept any path. Add a `MockRestServiceServer`-based test:

```java
@Test
@DisplayName("fetchAvailability calls native people path (no gateway prefix)")
void fetchAvailability_callsNativePeoplePath() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    server.expect(requestTo(org.hamcrest.Matchers.containsString("/v1/people/availability")))
          .andExpect(method(HttpMethod.GET))
          .andExpect(header("X-Authorities", "<required-permission>"))
          .andRespond(withSuccess("{\"asOf\":\"2026-01-01T00:00:00Z\",\"location\":\"L1\",\"people\":[]}", MediaType.APPLICATION_JSON));

    PeopleAvailabilityClient client = new PeopleAvailabilityClient(TEST_CLOCK, builder.baseUrl("http://people").build());
    client.fetchAvailability("L1", TEST_DATE);
    server.verify();
}
```

- [ ] **Step 3: Run new tests — verify they fail**

```bash
./mvnw -pl pos-workorder test -Dtest="ShopmgrOperationalContextClientTest,PeopleAvailabilityClientTest" -q 2>&1 | tail -20
```

- [ ] **Step 4: Implement CustomerValidationClient**

Change constructor:
```java
public CustomerValidationClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.customer.service-id:customer}") String serviceId) {
    this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
}
```

Strip `/customer` from URI paths:
- `.uri("/customer/v1/customers/{id}/requirements-met", customerId)` → `.uri("/v1/customers/{id}/requirements-met", customerId)`
- `.uri("/customer/v1/approvals/{id}/is-approved", approvalId)` → `.uri("/v1/approvals/{id}/is-approved", approvalId)`

Add auth headers to each request:
```java
.header("X-User", "pos-workorder")
.header("X-Authorities", "crm:party:view")  // verify against CustomerRequirementsController
```

- [ ] **Step 5: Implement ShopmgrOperationalContextClient**

Change constructor parameter:
```java
@Value("${pos.shopmgr.service-id:shop-manager}") String serviceId
```

Change `restClientBuilder.baseUrl(shopmgrBaseUrl)` → `restClientBuilder.baseUrl("http://" + serviceId)`.

Strip `/shop-manager` from URI paths:
- `"/shop-manager/v1/shopmgr/workorders/{workorderId}/operationalContext"` → `"/v1/shopmgr/workorders/{workorderId}/operationalContext"`
- `"/shop-manager/v1/shopmgr/locations/{locationId}/bays"` → `"/v1/shopmgr/locations/{locationId}/bays"`

Add auth headers (check pos-shop-manager's `ShopmgrController` for required permissions):
```bash
grep -n "@PreAuthorize" pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/controller/ShopmgrController.java 2>/dev/null | head -5
```

- [ ] **Step 6: Implement InventoryClientConfig**

Change parameter:
```java
@Value("${pos.inventory.service-id:inventory}") String serviceId
```

Change `restClientBuilder.baseUrl(inventoryBaseUrl)` → `restClientBuilder.baseUrl("http://" + serviceId)`.

Then in `InventoryPickClient.java`, strip `/inventory` from all URI paths:
- `"/inventory/v1/inventory/pick-lists/{pickListId}"` → `"/v1/inventory/pick-lists/{pickListId}"`
- `"/inventory/v1/inventory/pick-lists/{pickListId}/release"` → `"/v1/inventory/pick-lists/{pickListId}/release"`
- `"/inventory/v1/inventory/pick-lists/{pickListId}/tasks"` → `"/v1/inventory/pick-lists/{pickListId}/tasks"`
- `"/inventory/v1/inventory/pick-lists/{pickListId}/tasks/{taskId}/confirm"` → `"/v1/inventory/pick-lists/{pickListId}/tasks/{taskId}/confirm"`
- `.path("/inventory/v1/inventory/pick-lists")` → `.path("/v1/inventory/pick-lists")`
- `"/inventory/v1/inventory/consumption"` → `"/v1/inventory/consumption"`

Add auth headers to each request:
```java
.header("X-User", "pos-workorder")
.header("X-Authorities", "inventory:pick_list:view")  // or execute — verify per endpoint
```

Check `pos-inventory`'s PickListController for exact permissions per operation.

- [ ] **Step 7: Implement InvoiceClientConfig**

Change:
```java
@Value("${pos.invoice.service-id:invoice}") String serviceId
```

Change `restClientBuilder.baseUrl(invoiceBaseUrl)` → `restClientBuilder.baseUrl("http://" + serviceId)`.

In `InvoiceClient.java`, strip `/invoice`:
- `"/invoice/v1/invoices"` → `"/v1/invoices"`
- `"/invoice/v1/invoices/{invoiceId}"` → `"/v1/invoices/{invoiceId}"`

Add auth headers (check pos-invoice's InvoiceController for required permissions):
```java
.header("X-User", "pos-workorder")
.header("X-Authorities", "workorder:invoice:create,workorder:invoice:view")
```

- [ ] **Step 8: Implement PeopleClientConfig**

Change:
```java
@Value("${pos.people.service-id:people}") String serviceId
```

Change `restClientBuilder.baseUrl(peopleServiceBaseUrl)` → `restClientBuilder.baseUrl("http://" + serviceId)`.

In `PeopleLocationClient.java`:
- `"/people/v1/people/me/primary-location"` → `"/v1/people/me/primary-location"`
- Add auth headers.

In `PeopleAvailabilityClient.java`:
- `.path("/people/v1/people/availability")` → `.path("/v1/people/availability")`
- Add auth headers (check pos-people's PeopleAvailabilityController).

- [ ] **Step 9: Implement DocumentClientConfig**

Change from plain builder to LoadBalanced:
```java
@Bean
public RestClient documentServiceRestClient(
    @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
    @Value("${pos.documents.service-id:documents}") String serviceId) {
    return restClientBuilder.baseUrl("http://" + serviceId).build();
}
```

The `DocumentClient` paths (`/v1/documents/render`) are already gateway-native, no prefix to strip.

Add auth headers to `DocumentClient.renderPdf()` (check pos-documents render endpoint permissions):
```java
.header("X-User", "pos-workorder")
.header("X-Authorities", "<required-permission>")
```

- [ ] **Step 10: Update additional-spring-configuration-metadata.json**

In `pos-workorder/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, replace old `*.base-url` properties with new `*.service-id` equivalents:
- `pos.customer.base-url` → `pos.customer.service-id` (default: `customer`)
- `pos.invoice.base-url` → `pos.invoice.service-id` (default: `invoice`)
- `pos.people.base-url` → `pos.people.service-id` (default: `people`)
- `pos.inventory.base-url` → `pos.inventory.service-id` (default: `inventory`)
- `pos.documents.base-url` → `pos.documents.service-id` (default: `documents`)
- Add `pos.shopmgr.service-id` (default: `shop-manager`)

Keep `pos.vehicle.base-url`, `pos.tax.base-url` (these are in the existing metadata and are exceptions).

- [ ] **Step 11: Run tests — verify they pass**

```bash
./mvnw -pl pos-workorder test -q 2>&1 | tail -10
```

- [ ] **Step 12: Commit**

```bash
git add pos-workorder/
git commit -m "feat(pos-workorder): migrate all service clients to direct Eureka discovery"
```

---

### Task 11: pos-mcp-server partial migration (gateway exception)

**Files:**
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/WorkorderFacadeTool.java`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/InventoryFacadeTool.java`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/VehicleFacadeTool.java`
- Modify: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/EventsFacadeTool.java` (check if this is startup-infra exception)
- Modify: `pos-mcp-server/src/main/resources/application.yml` and/or `application-alpha.yml`

**Scope:** pos-mcp-server relays end-user bearer tokens via `BearerTokenRelayInterceptor`. Downstream services use `GatewayAuthoritiesFilter` which reads `X-Authorities`/`X-Perm-Bits`, not `Authorization: Bearer`. Therefore bearer tokens must be validated at the gateway before forwarding. This is an explicit documented exception — MCP tools continue routing through `http://api-gateway` via LoadBalanced builder.

The only change is: fix base URLs from Docker DNS hostnames (e.g., `http://pos-workorder:8080`) to the LoadBalanced gateway address (`http://api-gateway` via Eureka). Gateway path prefixes are preserved.

- [ ] **Step 1: Find all current base URL defaults**

```bash
grep -n "base-url\|pos\." pos-mcp-server/src/main/resources/application.yml pos-mcp-server/src/main/resources/application-alpha.yml 2>/dev/null | grep -v "security\|events\|ollama\|exa\|tax" | head -30
```

- [ ] **Step 2: Identify which tools call internal services via Docker DNS**

Check which `@Value("${pos.X.base-url}")` properties in tool classes currently default to `http://pos-*:8080/...` (Docker DNS).

- [ ] **Step 3: Update application.yml defaults**

For each tool property that currently defaults to `http://pos-{service}:8080/v1/...`, change the default in `application.yml` to `http://api-gateway/{gateway-prefix}/v1/...`. For example:
- `pos.workorder.base-url: http://pos-workorder:8080/v1/workorders` → `pos.workorder.base-url: http://api-gateway/workorder/v1/workorders`
- `pos.inventory.base-url: http://pos-inventory:8087/v1/inventory` → `pos.inventory.base-url: http://api-gateway/inventory/v1/inventory`

Since `WorkorderFacadeTool`, `InventoryFacadeTool`, `VehicleFacadeTool` all use `@Qualifier("loadBalancedRestClientBuilder")` which already has `BearerTokenRelayInterceptor`, the Eureka-resolved `http://api-gateway` will work correctly.

Note: `EventsFacadeTool` uses `pos.event-receiver.base-url` — check if this is a startup-infra exception or an internal runtime call.

- [ ] **Step 4: Document this exception**

Add a comment in `McpServerConfiguration.java` above `loadBalancedRestClientBuilder`:
```java
// MCP tools route through api-gateway because they relay end-user bearer tokens
// which require JWT→X-Authorities conversion at the gateway layer.
// See docs/service-discovery-migration/client-policy-matrix.md: gateway-exception.
```

- [ ] **Step 5: Run tests**

```bash
./mvnw -pl pos-mcp-server test -q 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add pos-mcp-server/
git commit -m "feat(pos-mcp-server): fix tool base URLs from Docker DNS to LoadBalanced gateway (bearer-token exception)"
```

---

### Task 12: Verification and documentation cleanup

**Files:**
- Modify: `docs/service-discovery-migration/service-id-registry.md`
- Modify: `docs/service-discovery-migration/client-policy-matrix.md`

- [ ] **Step 1: Grep audit — zero gateway-root `@Value` defaults remaining**

```bash
grep -rn 'http://api-gateway\|http://pos-api-gateway' \
  --include="*.java" --include="*.yml" --include="*.yaml" \
  $(git ls-files) | grep -v "target\|\.git\|pos-mcp-server\|startup-infra\|EventTypeInitializer\|PermissionRegistration"
```

Expected: zero results (outside approved exceptions). Any remaining hit is a bug to fix before merging.

- [ ] **Step 2: Grep audit — no stale `*.base-url` `@Value` defaults pointing to api-gateway**

```bash
grep -rn '@Value.*base-url.*http://api-gateway\|@Value.*base-url.*http://pos-' \
  --include="*.java" $(git ls-files) | grep -v target
```

Expected: zero results outside the `TaxClientConfig` and `DocumentClientConfig`'s old defaults (which should have been removed).

- [ ] **Step 3: Grep audit — all direct-exception clients are explicitly documented**

```bash
grep -rn 'base-url.*pos-tax\|base-url.*pos-documents' --include="*.java" $(git ls-files) | grep -v target
```

Confirm these are in `TaxClientConfig` and that `DocumentClientConfig` was migrated in Task 10.

- [ ] **Step 4: Update service-id-registry.md migration notes**

In `docs/service-discovery-migration/service-id-registry.md`, update the "Migration Notes" section:

> All gateway-routed internal runtime clients have been migrated to direct Eureka discovery (`http://{service-id}` via `@LoadBalanced RestClient.Builder`). The approved exception is pos-mcp-server (bearer token relay requires gateway JWT validation). Startup-infra clients (EventTypeInitializer, PermissionRegistration) retain Docker DNS direct addresses. Tax service (pos-tax) retains the ADR-0021 direct-call exception.

- [ ] **Step 5: Final full test run**

```bash
./mvnw test --fail-at-end 2>&1 | grep -E "ERROR|FAILURE|Tests run:|BUILD" | tail -30
```

Resolve any failures before committing.

- [ ] **Step 6: Commit**

```bash
git add docs/
git commit -m "docs: finalize service discovery migration docs after direct-discovery rollout"
```

---

## Self-Review Checklist

**Spec coverage check:**
- § Policy 1 (internal runtime via Eureka): covered in Tasks 2–10 ✓
- § Policy 2 (local dev via docker-compose): no code change needed; existing eureka-server covers it ✓
- § Policy 3 (X-Authorities injection): each task adds auth headers; permissions verified from `@PreAuthorize` ✓
- § Policy 4 (exceptions documented): Task 11 documents gateway exception; TaxClient remains unchanged ✓
- § Configuration rename (`*.base-url` → `*.service-id`): covered in each task ✓
- § Path mapping (strip gateway prefix): covered in each task ✓
- § Tests assert new URL shape: new tests in each task ✓
- § Doc cleanup: Task 1 and Task 12 ✓
- § Grep audit: Task 12 ✓

**Known auth permissions to verify before merging** (check `@PreAuthorize` on the linked controller):
- `InvoiceServiceClient` → pos-invoice `InvoiceController.getInvoiceDetails`
- `InventoryPickClient` → pos-inventory `PickListController` (pick/execute permissions per operation)
- `InvoiceClient` (pos-workorder) → pos-invoice `InvoiceController.createInvoice`
- `PeopleLocationClient` → pos-people primary-location endpoint
- `PeopleAvailabilityClient` → pos-people availability endpoint
- `CustomerValidationClient` → pos-customer requirements-met endpoint
- `ShopmgrOperationalContextClient` → pos-shop-manager shopmgr endpoint
- `DocumentClient` → pos-documents render endpoint
- `SecurityServiceClient` → pos-security-service user/role endpoints
- `WorkexecJobTimeClient` → pos-workorder workexec job-time-totals endpoint
- `HrAvailabilityClient` → pos-people availability-overlay and hr/schedules endpoints
- `ServiceEntityClient` → pos-catalog service entity endpoint
