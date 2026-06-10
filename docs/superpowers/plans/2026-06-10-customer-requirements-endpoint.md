# Customer Requirements Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /v1/customers/{id}/requirements-met` to `pos-customer` so `pos-workorder` can validate customer standing without any client changes.

**Architecture:** Add one focused internal service to resolve a customer party and evaluate standing rules, then expose that logic through a dedicated internal controller route secured with `crm:party:view`. Verify the contract with integration tests that exercise raw boolean responses, not-found behavior, and gateway-header auth.

**Tech Stack:** Java 25, Spring Boot, Spring MVC, Spring Security, Spring Data JPA, JUnit 5, MockMvc, Maven

---

### Task 1: Add Contract Tests First

**Files:**
- Create: `pos-customer/src/test/java/com/positivity/customer/contract/CustomerRequirementsContractBehaviorIT.java`
- Reference: `pos-customer/src/test/java/com/positivity/customer/contract/BaseContractIntegrationTest.java`
- Reference: `pos-customer/src/test/java/com/positivity/customer/contract/BillingRulesContractBehaviorIT.java`
- Reference: `pos-customer/src/main/java/com/positivity/customer/internal/entity/CommercialParty.java`
- Reference: `pos-customer/src/main/java/com/positivity/customer/internal/entity/PersonParty.java`

- [x] **Step 1: Write the failing contract test file**

```java
package com.positivity.customer.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CustomerRequirementsContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private CommercialPartyRepository commercialPartyRepository;

    @Autowired
    private PersonPartyRepository personPartyRepository;

    private CommercialParty createCommercialParty(AccountStatus status, Boolean creditHold) {
        CommercialParty party = new CommercialParty();
        party.setPartyType(PartyType.COMMERCIAL);
        party.setLegalName("Requirements Test Corp");
        party.setDisplayName("Requirements Corp");
        party.setPartyNumber("REQ-" + UUID.randomUUID());
        party.setCustomerNumber("CUST-REQ-" + UUID.randomUUID().toString().substring(0, 8));
        party.setStatus(status);

        if (creditHold != null) {
            BillingRulesEmbeddable billingRules = new BillingRulesEmbeddable();
            billingRules.setCreditHold(creditHold);
            party.setBillingRules(billingRules);
        }

        Contact contact = new Contact();
        contact.setPersonId(UUID.randomUUID());
        contact.setFirstName("Test");
        contact.setLastName("Contact");
        contact.setActive(true);
        contact.setCommercialParty(party);
        party.getContacts().add(contact);

        return commercialPartyRepository.saveAndFlush(party);
    }

    private PersonParty createPersonParty(AccountStatus status) {
        PersonParty party = new PersonParty();
        party.setPersonId(UUID.randomUUID());
        party.setCustomerNumber("CUST-PER-" + UUID.randomUUID().toString().substring(0, 8));
        party.setFirstName("Jane");
        party.setLastName("Doe");
        party.setPreferredContactMethod(PreferredContactMethod.NONE);
        party.setStatus(status);
        return personPartyRepository.saveAndFlush(party);
    }

    @Test
    @DisplayName("CR-001: returns true for active commercial party without credit hold")
    void requirementsMet_returnsTrue_forActiveCommercialPartyWithoutCreditHold() throws Exception {
        CommercialParty party = createCommercialParty(AccountStatus.ACTIVE, false);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    @DisplayName("CR-002: returns true for active person party")
    void requirementsMet_returnsTrue_forActivePersonParty() throws Exception {
        PersonParty party = createPersonParty(AccountStatus.ACTIVE);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    @DisplayName("CR-003: returns false for non-active statuses")
    void requirementsMet_returnsFalse_forInactiveOnHoldAndMerged() throws Exception {
        CommercialParty inactive = createCommercialParty(AccountStatus.INACTIVE, false);
        CommercialParty onHold = createCommercialParty(AccountStatus.ON_HOLD, false);
        CommercialParty merged = createCommercialParty(AccountStatus.MERGED, false);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", inactive.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", onHold.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", merged.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    @DisplayName("CR-004: returns false for active commercial party on credit hold")
    void requirementsMet_returnsFalse_forCommercialPartyOnCreditHold() throws Exception {
        CommercialParty party = createCommercialParty(AccountStatus.ACTIVE, true);

        mockMvc.perform(get("/v1/customers/{id}/requirements-met", party.getPartyId())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    @DisplayName("CR-005: returns 404 for unknown customer id")
    void requirementsMet_returns404_forUnknownCustomerId() throws Exception {
        mockMvc.perform(get("/v1/customers/{id}/requirements-met", UUID.randomUUID())
                        .header("X-User", TEST_USER)
                        .header("X-Authorities", PARTY_VIEW_AUTHORITY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CR-006: returns 401 when unauthenticated")
    void requirementsMet_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/v1/customers/{id}/requirements-met", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
```

- [x] **Step 2: Run the new test to verify it fails**

Run:

```bash
../mvnw -Dtest=CustomerRequirementsContractBehaviorIT test
```

Workdir:

```bash
pos-customer
```

Expected:

```text
FAIL because GET /v1/customers/{id}/requirements-met has no handler yet
```

### Task 2: Implement Customer Standing Resolution

**Files:**
- Create: `pos-customer/src/main/java/com/positivity/customer/internal/service/CustomerRequirementsService.java`
- Create: `pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerRequirementsController.java`
- Reference: `pos-customer/src/main/java/com/positivity/customer/internal/service/CommercialPartyServiceImpl.java`
- Reference: `pos-customer/src/main/java/com/positivity/customer/internal/service/PersonPartyServiceImpl.java`
- Reference: `pos-customer/src/main/java/com/positivity/customer/internal/security/CrmPermissionRegistry.java`

- [x] **Step 1: Write the standing service**

```java
package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountStatus;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerRequirementsService {

    private final CommercialPartyServiceImpl commercialPartyService;
    private final PersonPartyServiceImpl personPartyService;

    @Transactional(readOnly = true)
    public @NonNull Optional<Boolean> checkRequirementsMet(@NonNull UUID customerId) {
        return resolveParty(customerId).map(this::isEligibleForWorkorder);
    }

    @Transactional(readOnly = true)
    protected @NonNull Optional<AbstractParty> resolveParty(@NonNull UUID customerId) {
        return commercialPartyService.findPartyById(customerId)
                .map(AbstractParty.class::cast)
                .or(() -> personPartyService.findPartyById(customerId).map(AbstractParty.class::cast));
    }

    private boolean isEligibleForWorkorder(AbstractParty party) {
        if (party.getStatus() != AccountStatus.ACTIVE) {
            return false;
        }
        if (party instanceof CommercialParty commercialParty) {
            return commercialParty.getBillingRules() == null
                    || !Boolean.TRUE.equals(commercialParty.getBillingRules().getCreditHold());
        }
        return true;
    }
}
```

- [x] **Step 2: Add entity lookup helpers to the existing party services**

Modify `CommercialPartyServiceImpl` and `PersonPartyServiceImpl` to add:

```java
    @Transactional(readOnly = true)
    public Optional<CommercialParty> findPartyById(@NonNull UUID id) {
        return commercialRepository.findById(id);
    }
```

```java
    @Transactional(readOnly = true)
    public Optional<PersonParty> findPartyById(@NonNull UUID id) {
        return customerRepository.findById(id);
    }
```

- [x] **Step 3: Add the controller route**

```java
package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.CustomerRequirementsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
@Tag(name = "Customer Requirements API", description = "Internal customer standing checks for workorder creation")
public class CustomerRequirementsController {

    private final CustomerRequirementsService customerRequirementsService;

    @Operation(
            summary = "Check whether customer requirements are met",
            description = "Returns a raw boolean indicating whether the customer is in good standing for a new workorder.")
    @ApiResponse(responseCode = "200", description = "Requirements evaluation returned successfully.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @GetMapping("/{id}/requirements-met")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.PARTY_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<Boolean> checkRequirementsMet(
            @Parameter(description = "Customer ID to evaluate") @PathVariable UUID id) {
        log.info("Checking requirements for customer {}", id);
        return customerRequirementsService.checkRequirementsMet(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [x] **Step 4: Run the targeted contract test to verify it passes**

Run:

```bash
../mvnw -Dtest=CustomerRequirementsContractBehaviorIT test
```

Workdir:

```bash
pos-customer
```

Expected:

```text
BUILD SUCCESS
Tests run: 6, Failures: 0, Errors: 0
```

### Task 3: Verify Regression Surface and Commit

**Files:**
- Verify: `pos-customer/src/test/java/com/positivity/customer/contract/CustomerRequirementsContractBehaviorIT.java`
- Verify: `pos-customer/src/main/java/com/positivity/customer/internal/service/CustomerRequirementsService.java`
- Verify: `pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerRequirementsController.java`
- Modify: `docs/superpowers/plans/2026-06-10-customer-requirements-endpoint.md`

- [x] **Step 1: Run the broader customer contract checks most likely to overlap**

Run:

```bash
../mvnw -Dtest=CustomerRequirementsContractBehaviorIT,BillingRulesContractBehaviorIT,CrmSnapshotContractBehaviorIT test
```

Workdir:

```bash
pos-customer
```

Expected:

```text
BUILD SUCCESS
```

- [x] **Step 2: Mark completed plan steps in this file**

Update each completed checkbox in `docs/superpowers/plans/2026-06-10-customer-requirements-endpoint.md` from `- [ ]` to `- [x]`.

- [x] **Step 3: Commit only the issue #653 files**

```bash
git add \
  pos-customer/src/main/java/com/positivity/customer/internal/controller/CustomerRequirementsController.java \
  pos-customer/src/main/java/com/positivity/customer/internal/service/CustomerRequirementsService.java \
  pos-customer/src/main/java/com/positivity/customer/internal/service/CommercialPartyServiceImpl.java \
  pos-customer/src/main/java/com/positivity/customer/internal/service/PersonPartyServiceImpl.java \
  pos-customer/src/test/java/com/positivity/customer/contract/CustomerRequirementsContractBehaviorIT.java \
  docs/superpowers/plans/2026-06-10-customer-requirements-endpoint.md
git commit -m "feat(customer): add requirements-met endpoint"
```
