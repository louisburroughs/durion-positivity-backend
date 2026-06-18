package com.positivity.customer.internal.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpsertBillingRulesRequest;
import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.service.AccountTierService;
import com.positivity.customer.service.PartyService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CrmAccountsController.class)
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CrmAccountsControllerTest {

    private static final UUID PARTY_ID = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PartyService partyService;

    @MockitoBean
    AccountTierService accountTierService;

    @MockitoBean
    java.time.Clock clock;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        lenient().when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    // ─── B-21: GET /v1/crm/accounts/parties/duplicate-check ─────────────────

    @Test
    @DisplayName(
            "B-21: GET duplicate-check with valid legalName and crm:party:search authority → 200 with duplicatesFound and potentialDuplicates")
    void checkPartyDuplicates_returnsMatches_when200() throws Exception {
        DuplicateCheckResponse response = DuplicateCheckResponse.builder()
                .duplicatesFound(true)
                .exactMatchPartyId(PARTY_ID.toString())
                .potentialDuplicates(List.of(DuplicateCheckResponse.PartyMatch.builder()
                        .partyId(PARTY_ID.toString())
                        .legalName("Acme Corp")
                        .matchType("EXACT")
                        .score(1.0)
                        .build()))
                .build();

        when(partyService.checkPartyDuplicates("Acme")).thenReturn(response);

        mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", "Acme")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicatesFound").value(true))
                .andExpect(jsonPath("$.potentialDuplicates").isArray())
                .andExpect(jsonPath("$.potentialDuplicates[0].partyId").value(PARTY_ID.toString()));
    }

    @Test
    @DisplayName("B-21: GET duplicate-check with blank legalName → 400")
    void checkPartyDuplicates_returns400_whenLegalNameBlank() throws Exception {
        mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", "")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("checkPartyDuplicates returns 400 when legalName trims to fewer than 2 characters")
    void checkPartyDuplicates_returns400_whenLegalNameTrimsToBelowMinLength() throws Exception {
        when(partyService.checkPartyDuplicates(eq(" A ")))
                .thenThrow(new IllegalArgumentException("legalName must contain at least 2 non-whitespace characters"));

        mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check")
                        .param("legalName", " A ")
                        .header("X-Authorities", "crm:party:search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B-21: GET duplicate-check without crm:party:search authority → 403")
    void checkPartyDuplicates_returns403_whenUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/crm/accounts/parties/duplicate-check").param("legalName", "Acme"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("browseParties returns 200 with shared response when authorized")
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

        when(partyService.browseParties(
                        any(Pageable.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/v1/crm/accounts/parties").header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].partyId").value(PARTY_ID.toString()))
                .andExpect(jsonPath("$.results[0].legalName").value("Acme Corp"))
                .andExpect(jsonPath("$.results[0].displayName").value("Acme"))
                .andExpect(jsonPath("$.results[0].partyType").value("COMMERCIAL"))
                .andExpect(jsonPath("$.results[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.results[0].createdAt").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(partyService).browseParties(
                pageableCaptor.capture(), any(), any(), any(), any(), any(), any());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(20, pageableCaptor.getValue().getPageSize());
        assertTrue(pageableCaptor.getValue().getSort().isSorted());
        var sortIterator = pageableCaptor.getValue().getSort().iterator();
        var legalNameOrder = sortIterator.next();
        assertEquals("legalName", legalNameOrder.getProperty());
        assertEquals(Sort.Direction.ASC, legalNameOrder.getDirection());
        var partyIdOrder = sortIterator.next();
        assertEquals("partyId", partyIdOrder.getProperty());
        assertEquals(Sort.Direction.ASC, partyIdOrder.getDirection());
    }

    @Test
    @DisplayName("browseParties forwards filter and sort query params to the service")
    void browsePartiesForwardsFilterSortParams() throws Exception {
        SearchPartiesResponse response = SearchPartiesResponse.builder()
                .results(List.of())
                .totalCount(0)
                .pageNumber(0)
                .pageSize(20)
                .build();
        when(partyService.browseParties(
                        any(Pageable.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/v1/crm/accounts/parties")
                        .param("name", "acme")
                        .param("status", "ACTIVE")
                        .param("partyType", "ORGANIZATION")
                        .param("customerNumber", "CUST-1")
                        .param("sortField", "customerNumber")
                        .param("sortOrder", "desc")
                        .header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk());

        verify(partyService).browseParties(
                any(Pageable.class),
                eq("acme"),
                eq("ACTIVE"),
                eq("ORGANIZATION"),
                eq("CUST-1"),
                eq("customerNumber"),
                eq("desc"));
    }

    @Test
    @DisplayName("browseParties returns 403 when unauthorized")
    void listParties_returns403_whenUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/crm/accounts/parties")).andExpect(status().isForbidden());
    }

    // ─── B-22: PUT /v1/crm/accounts/parties/{partyId}/billing-rules ─────────

    @Test
    @DisplayName(
            "B-22: PUT billing-rules with valid body and crm:billing_rules:edit authority → 200 with BillingRuleRef")
    void upsertBillingRules_returns200_whenValid() throws Exception {
        UpsertBillingRulesRequest request = UpsertBillingRulesRequest.builder()
                .paymentTerms("Net 30")
                .taxExempt(false)
                .poRequired(false)
                .creditHold(false)
                .autoPayEnabled(true)
                .build();

        BillingRuleRef ref = new BillingRuleRef();
        ref.setPaymentTerms("Net 30");
        ref.setAutoPayEnabled(true);

        when(partyService.upsertBillingRulesForParty(eq(PARTY_ID), any(UpsertBillingRulesRequest.class)))
                .thenReturn(ref);

        mockMvc.perform(put("/v1/crm/accounts/parties/{partyId}/billing-rules", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Authorities", "crm:billing_rules:edit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentTerms").value("Net 30"))
                .andExpect(jsonPath("$.autoPayEnabled").value(true));
    }

    @Test
    @DisplayName("upsertBillingRules returns 404 via ResponseStatusException when party not found")
    void upsertBillingRules_returns404_whenPartyNotFound() throws Exception {
        UpsertBillingRulesRequest request = UpsertBillingRulesRequest.builder()
                .taxExempt(false)
                .poRequired(false)
                .creditHold(false)
                .autoPayEnabled(false)
                .build();

        when(partyService.upsertBillingRulesForParty(eq(PARTY_ID), any(UpsertBillingRulesRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + PARTY_ID));

        mockMvc.perform(put("/v1/crm/accounts/parties/{partyId}/billing-rules", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Authorities", "crm:billing_rules:edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT billing-rules returns 400 when request has invalid fields")
    void upsertBillingRules_returns400_whenRequestInvalid() throws Exception {
        UpsertBillingRulesRequest request = UpsertBillingRulesRequest.builder()
                .creditLimit(new java.math.BigDecimal("-1.00"))
                .taxExempt(false)
                .poRequired(false)
                .creditHold(false)
                .autoPayEnabled(false)
                .build();

        mockMvc.perform(put("/v1/crm/accounts/parties/{partyId}/billing-rules", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Authorities", "crm:billing_rules:edit"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("B-22: PUT billing-rules without crm:billing_rules:edit authority → 403")
    void upsertBillingRules_returns403_whenUnauthorized() throws Exception {
        UpsertBillingRulesRequest request = UpsertBillingRulesRequest.builder()
                .taxExempt(false)
                .poRequired(false)
                .creditHold(false)
                .autoPayEnabled(false)
                .build();

        mockMvc.perform(put("/v1/crm/accounts/parties/{partyId}/billing-rules", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("upsertBillingRules returns 400 when service throws IllegalArgumentException for invalid input")
    void upsertBillingRules_returns400_whenServiceThrowsIllegalArgumentException() throws Exception {
        UpsertBillingRulesRequest request = UpsertBillingRulesRequest.builder()
                .taxExempt(false)
                .poRequired(false)
                .creditHold(false)
                .autoPayEnabled(false)
                .build();

        when(partyService.upsertBillingRulesForParty(eq(PARTY_ID), any(UpsertBillingRulesRequest.class)))
                .thenThrow(new IllegalArgumentException("Invalid billing rule: currency code required"));

        mockMvc.perform(put("/v1/crm/accounts/parties/{partyId}/billing-rules", PARTY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Authorities", "crm:billing_rules:edit"))
                .andExpect(status().isBadRequest());
    }
}
