# Issue 647 Customer Directory Browse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated paged CRM account browse endpoint for Customer Directory, keep criteria search separate, and publish a stable shared response contract for frontend routing.

**Architecture:** Extend the existing CRM accounts API with a new `GET /v1/crm/accounts/parties` browse route that delegates to a new `PartyService.browseParties(Pageable)` method. Implement browse with repository-backed pagination in `PartyServiceImpl`, reuse `SearchPartiesResponse` for both browse and search, and publish a separate browse event plus OpenAPI entries so the frontend can route empty query input to browse safely.

**Tech Stack:** Java 25, Spring Boot Web MVC, Spring Data JPA, Spring Security, springdoc/OpenAPI YAML, JUnit 5, Mockito, Maven Wrapper.

---

## File Structure

### Existing files to modify

- `pos-customer/src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java`
  - add the new GET browse endpoint on the CRM accounts surface
- `pos-customer/src/main/java/com/positivity/customer/service/PartyService.java`
  - add the browse service contract
- `pos-customer/src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java`
  - implement pageable browse logic and deterministic default sorting
- `pos-customer/src/main/java/com/positivity/customer/internal/config/EventTypes.java`
  - register a dedicated browse event type
- `pos-customer/openapi.yaml`
  - add a GET operation under the existing `/v1/crm/accounts/parties` path
- `pos-customer/src/test/java/com/positivity/customer/internal/controller/CrmAccountsControllerTest.java`
  - add controller coverage for browse success and authorization
- `pos-customer/src/test/java/com/positivity/customer/service/PartyServiceImplTest.java`
  - add service coverage for browse defaults, mapping, and empty results

### New test file to create

- `pos-customer/src/test/java/com/positivity/customer/internal/config/EventTypesTest.java`
  - assert the browse event is registered distinctly from search

## Task 1: Expose the browse controller contract

**Files:**
- Modify: `pos-customer/src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java`
- Modify: `pos-customer/src/main/java/com/positivity/customer/service/PartyService.java`
- Modify: `pos-customer/src/test/java/com/positivity/customer/internal/controller/CrmAccountsControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

Add these tests to `pos-customer/src/test/java/com/positivity/customer/internal/controller/CrmAccountsControllerTest.java`:

```java
    @Test
    void listParties_returns200WithSharedResponse_whenAuthorized() throws Exception {
        SearchPartiesResponse response = SearchPartiesResponse.builder()
                .results(List.of(SearchPartiesResponse.PartySummary.builder()
                        .partyId(PARTY_ID.toString())
                        .legalName("Acme Corp")
                        .displayName("Acme")
                        .partyType("COMMERCIAL")
                        .status("ACTIVE")
                        .createdAt("2024-01-01T00:00:00Z")
                        .build()))
                .totalCount(1)
                .pageNumber(0)
                .pageSize(20)
                .build();

        when(partyService.browseParties(any())).thenReturn(response);

        mockMvc.perform(get("/v1/crm/accounts/parties").header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].partyId").value(PARTY_ID.toString()))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    void listParties_returns403_whenUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/crm/accounts/parties"))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: Run the controller test to verify it fails**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=CrmAccountsControllerTest test
```

Expected: FAIL with a compile error for `browseParties(...)` or a request mapping failure because the GET endpoint does not exist yet.

- [ ] **Step 3: Add the minimal controller and service interface contract**

In `pos-customer/src/main/java/com/positivity/customer/service/PartyService.java`, add the browse method:

```java
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;

@NonNull
SearchPartiesResponse browseParties(@NonNull Pageable pageable);
```

In `pos-customer/src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java`, add the browse handler above the existing `searchParties` method:

```java
    @Operation(summary = "Browse parties", description = "Browse parties with paging and sorting")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Browse results returned",
                        content = @Content(schema = @Schema(implementation = SearchPartiesResponse.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content)
            })
    @GetMapping("/parties")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CUSTOMER_PARTY_BROWSE", apiVersion = "1")
    public ResponseEntity<SearchPartiesResponse> browseParties(
            @Parameter(description = "Pagination parameters (page, size, sort)")
                    @PageableDefault(size = 20, sort = "legalName")
                    Pageable pageable) {
        log.info("browseParties pageable={}", pageable);
        return ResponseEntity.ok(partyService.browseParties(pageable));
    }
```

Also add these imports in `CrmAccountsController.java`:

```java
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
```

- [ ] **Step 4: Run the controller test to verify it passes**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=CrmAccountsControllerTest test
```

Expected: PASS for the new browse endpoint tests and the pre-existing controller tests.

- [ ] **Step 5: Commit the controller contract**

Run:

```bash
git add \
  pos-customer/src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java \
  pos-customer/src/main/java/com/positivity/customer/service/PartyService.java \
  pos-customer/src/test/java/com/positivity/customer/internal/controller/CrmAccountsControllerTest.java
git commit -m "feat: add crm party browse endpoint contract"
```

## Task 2: Implement pageable browse logic in `PartyServiceImpl`

**Files:**
- Modify: `pos-customer/src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java`
- Modify: `pos-customer/src/test/java/com/positivity/customer/service/PartyServiceImplTest.java`

- [ ] **Step 1: Write the failing browse service tests**

Add these tests to `pos-customer/src/test/java/com/positivity/customer/service/PartyServiceImplTest.java`:

```java
    @Test
    void browseParties_usesDefaultPageAndSort_whenPageableIsUnpaged() {
        CommercialParty first = party(UUID.fromString("00000000-0000-0000-0000-000000000101"));
        first.setLegalName("Acme Corp");

        PageRequest expectedPageable = PageRequest.of(
                0,
                20,
                Sort.by(Sort.Order.asc("legalName").ignoreCase(), Sort.Order.asc("partyId")));

        when(partyRepository.findAll(expectedPageable))
                .thenReturn(new PageImpl<>(List.of(first), expectedPageable, 1));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getPageNumber()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getResults()).extracting(SearchPartiesResponse.PartySummary::getLegalName)
                .containsExactly("Acme Corp");
    }

    @Test
    void browseParties_returnsEmptyResultsWithPagingMetadata() {
        PageRequest expectedPageable = PageRequest.of(
                0,
                20,
                Sort.by(Sort.Order.asc("legalName").ignoreCase(), Sort.Order.asc("partyId")));

        when(partyRepository.findAll(expectedPageable))
                .thenReturn(new PageImpl<>(List.of(), expectedPageable, 0));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalCount()).isZero();
        assertThat(response.getPageNumber()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
    }
```

Add these imports if they are not already present:

```java
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
```

- [ ] **Step 2: Run the service test to verify it fails**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=PartyServiceImplTest test
```

Expected: FAIL because `PartyServiceImpl` does not implement `browseParties(Pageable)` yet.

- [ ] **Step 3: Implement the minimal browse service**

In `pos-customer/src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java`, add this method and helper near `searchParties(...)`:

```java
    @Override
    @Transactional(readOnly = true)
    public @NonNull SearchPartiesResponse browseParties(@NonNull Pageable pageable) {
        Pageable normalizedPageable = normalizeBrowsePageable(pageable);
        Page<CommercialParty> page = partyRepository.findAll(normalizedPageable);

        List<SearchPartiesResponse.PartySummary> summaries =
                page.getContent().stream().map(this::mapToPartySummary).toList();

        return SearchPartiesResponse.builder()
                .results(summaries)
                .totalCount(Math.toIntExact(page.getTotalElements()))
                .pageNumber(normalizedPageable.getPageNumber())
                .pageSize(normalizedPageable.getPageSize())
                .build();
    }

    private Pageable normalizeBrowsePageable(@Nullable Pageable pageable) {
        Sort fallbackSort = Sort.by(Sort.Order.asc("legalName").ignoreCase(), Sort.Order.asc("partyId"));
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, 20, fallbackSort);
        }
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : fallbackSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
```

Also add the required imports:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=PartyServiceImplTest test
```

Expected: PASS for the new browse tests and the existing `PartyServiceImplTest` cases.

- [ ] **Step 5: Commit the browse service implementation**

Run:

```bash
git add \
  pos-customer/src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java \
  pos-customer/src/test/java/com/positivity/customer/service/PartyServiceImplTest.java
git commit -m "feat: add paged crm party browse service"
```

## Task 3: Publish observability and OpenAPI contract updates

**Files:**
- Modify: `pos-customer/src/main/java/com/positivity/customer/internal/config/EventTypes.java`
- Modify: `pos-customer/openapi.yaml`
- Create: `pos-customer/src/test/java/com/positivity/customer/internal/config/EventTypesTest.java`

- [ ] **Step 1: Write the failing event registration test**

Create `pos-customer/src/test/java/com/positivity/customer/internal/config/EventTypesTest.java` with:

```java
package com.positivity.customer.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventTypesTest {

    @Test
    void all_containsDistinctBrowseAndSearchEvents() {
        List<String> typeCodes = EventTypes.all().stream()
                .map(registration -> registration.getTypeCode())
                .toList();

        assertThat(typeCodes).contains("CUSTOMER_PARTY_BROWSE", "CUSTOMER_PARTY_SEARCH");
        assertThat(typeCodes.stream().filter("CUSTOMER_PARTY_BROWSE"::equals).count()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the event test to verify it fails**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=EventTypesTest test
```

Expected: FAIL because `CUSTOMER_PARTY_BROWSE` is not registered yet.

- [ ] **Step 3: Implement the event registration and OpenAPI path**

In `pos-customer/src/main/java/com/positivity/customer/internal/config/EventTypes.java`, add the browse registration beside `CUSTOMER_PARTY_SEARCH`:

```java
                EventTypeRegistration.fastRead(
                                "CUSTOMER_PARTY_BROWSE", "Browse parties with paging and sorting")
                        .build(),
                EventTypeRegistration.search("CUSTOMER_PARTY_SEARCH", "Search for parties based on various criteria")
                        .build(),
```

In `pos-customer/openapi.yaml`, add the new GET operation alongside the existing `post` operation under `/v1/crm/accounts/parties`:

```yaml
  /v1/crm/accounts/parties:
    get:
      tags:
      - CRM Accounts
      summary: Browse parties
      description: Browse parties with paging and sorting
      operationId: browseParties
      parameters:
      - name: page
        in: query
        schema:
          type: integer
          format: int32
          minimum: 0
          default: 0
      - name: size
        in: query
        schema:
          type: integer
          format: int32
          minimum: 1
          default: 20
      - name: sort
        in: query
        schema:
          type: string
          example: legalName,asc
      responses:
        '200':
          description: Browse results returned
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SearchPartiesResponse'
        '403':
          description: Forbidden - insufficient permissions
      security:
      - bearerAuth:
        - crm:party:view
      x-required-permissions:
      - crm:party:view
```

- [ ] **Step 4: Run targeted tests and OpenAPI validation**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=CrmAccountsControllerTest,PartyServiceImplTest,EventTypesTest test
./mvnw -pl pos-openapi-validation -DskipTests=false test
```

Expected: PASS for the targeted customer tests and PASS for repository OpenAPI validation.

- [ ] **Step 5: Commit the contract publication changes**

Run:

```bash
git add \
  pos-customer/src/main/java/com/positivity/customer/internal/config/EventTypes.java \
  pos-customer/openapi.yaml \
  pos-customer/src/test/java/com/positivity/customer/internal/config/EventTypesTest.java
git commit -m "docs: publish crm party browse contract"
```

## Task 4: Run final verification for issue 647

**Files:**
- Modify: none
- Test: `pos-customer/src/test/java/com/positivity/customer/internal/controller/CrmAccountsControllerTest.java`
- Test: `pos-customer/src/test/java/com/positivity/customer/service/PartyServiceImplTest.java`
- Test: `pos-customer/src/test/java/com/positivity/customer/internal/config/EventTypesTest.java`

- [ ] **Step 1: Run the focused customer module regression suite**

Run:

```bash
./mvnw -q -pl pos-customer -DskipTests=false -Dtest=CrmAccountsControllerTest,PartyServiceImplTest,EventTypesTest test
```

Expected: PASS with all browse-related controller, service, and event tests green.

- [ ] **Step 2: Run repository OpenAPI validation**

Run:

```bash
./mvnw -pl pos-openapi-validation -DskipTests=false test
```

Expected: PASS with module spec and aggregate spec validation green.

- [ ] **Step 3: Run the full repository test suite**

Run:

```bash
./mvnw -DskipTests=false clean test
```

Expected: PASS with no regressions outside `pos-customer`.

- [ ] **Step 4: Review the final diff before handoff**

Run:

```bash
git --no-pager diff --stat HEAD~3..HEAD
git --no-pager diff -- pos-customer/src/main/java/com/positivity/customer/internal/controller/CrmAccountsController.java \
  pos-customer/src/main/java/com/positivity/customer/service/PartyService.java \
  pos-customer/src/main/java/com/positivity/customer/internal/service/PartyServiceImpl.java \
  pos-customer/src/main/java/com/positivity/customer/internal/config/EventTypes.java \
  pos-customer/openapi.yaml
```

Expected: Only the browse endpoint, browse service, event registration, OpenAPI files, and their tests are changed.

- [ ] **Step 5: Hand off with frontend coordination note**

Include this exact note in the implementation handoff:

```text
Backend contract for issue #647 is ready. Frontend should call GET /v1/crm/accounts/parties when the trimmed query is empty and POST /v1/crm/accounts/parties/search when the query is non-empty. Both responses use the shared SearchPartiesResponse shape, and empty result sets are a normal success state.
```
