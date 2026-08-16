package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.service.PurchaseSuggestionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for PurchaseSuggestionController (odoo-parity F4, #1044): permission
 * gating — including convert's DUAL authority requirement — and request validation.
 */
@WebMvcTest(PurchaseSuggestionController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class PurchaseSuggestionControllerTest {

    private static final UUID SUGGESTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final String VIEW = "inventory:on_hand:view";
    private static final String MANAGE = "inventory:replenishment:manage";
    private static final String PO_CREATE = "order:purchase_order:create";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    PurchaseSuggestionService purchaseSuggestionService;

    @org.junit.jupiter.api.BeforeEach
    void stubClock() {
        // The module exception advice timestamps every ApiError from the injected Clock.
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-07-23T00:00:00Z"));
    }

    private PurchaseSuggestionResponse sampleResponse(String status) {
        return PurchaseSuggestionResponse.builder()
                .suggestionId(SUGGESTION_ID)
                .policyId(UUID.fromString("00000000-0000-0000-0000-000000000071"))
                .itemSKU("SKU-F4")
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000072"))
                .suggestedQuantity(24)
                .suggestedVendorType("MANUFACTURER")
                .vendorRefId(UUID.fromString("00000000-0000-0000-0000-000000000073"))
                .unitCostMinor(499L)
                .selectionReason("test")
                .status(status)
                .build();
    }

    // ─── GET list / get (inventory:on_hand:view) ─────────────────────────────

    @Test
    void list_withViewAuthority_returns200() throws Exception {
        when(purchaseSuggestionService.listPurchaseSuggestions(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse("SUGGESTED"))));

        mockMvc.perform(get("/v1/inventory/purchase-suggestions").header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].suggestionId").value(SUGGESTION_ID.toString()));
    }

    @Test
    void list_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/purchase-suggestions").header("X-Authorities", MANAGE))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOne_withViewAuthority_returns200() throws Exception {
        when(purchaseSuggestionService.getPurchaseSuggestion(SUGGESTION_ID)).thenReturn(sampleResponse("SUGGESTED"));

        mockMvc.perform(get("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID)
                        .header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUGGESTED"));
    }

    // ─── POST accept / dismiss (inventory:replenishment:manage) ──────────────

    @Test
    void accept_withManageAuthority_returns200() throws Exception {
        when(purchaseSuggestionService.acceptPurchaseSuggestion(eq(SUGGESTION_ID), anyString()))
                .thenReturn(sampleResponse("ACCEPTED"));

        mockMvc.perform(post("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID + "/accept")
                        .header("X-Authorities", MANAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void accept_withViewOnlyAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID + "/accept")
                        .header("X-Authorities", VIEW))
                .andExpect(status().isForbidden());
        verify(purchaseSuggestionService, never()).acceptPurchaseSuggestion(any(), anyString());
    }

    @Test
    void dismiss_withManageAuthorityAndReason_returns200() throws Exception {
        when(purchaseSuggestionService.dismissPurchaseSuggestion(eq(SUGGESTION_ID), anyString(), anyString()))
                .thenReturn(sampleResponse("DISMISSED"));

        mockMvc.perform(post("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID + "/dismiss")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"vendor under renegotiation"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void dismiss_withoutReason_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID + "/dismiss")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(purchaseSuggestionService, never()).dismissPurchaseSuggestion(any(), anyString(), anyString());
    }

    @Test
    void dismiss_missingManageAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/" + SUGGESTION_ID + "/dismiss")
                        .header("X-Authorities", VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"nope"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ─── POST convert (DUAL authority: manage AND purchase_order:create) ─────

    @Test
    void convert_withBothAuthorities_returns201() throws Exception {
        when(purchaseSuggestionService.convertPurchaseSuggestions(any(), anyString()))
                .thenReturn(ConvertPurchaseSuggestionsResponse.builder()
                        .purchaseOrderId(UUID.fromString("00000000-0000-0000-0000-000000000074"))
                        .poNumber("PO-000123")
                        .purchaseOrderStatus("DRAFT")
                        .convertedSuggestionIds(List.of(SUGGESTION_ID))
                        .build());

        mockMvc.perform(post("/v1/inventory/purchase-suggestions/convert")
                        .header("X-Authorities", MANAGE + "," + PO_CREATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"suggestionIds":["%s"]}
                                """.formatted(SUGGESTION_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseOrderStatus").value("DRAFT"))
                .andExpect(jsonPath("$.poNumber").value("PO-000123"));
    }

    @Test
    void convert_withOnlyManageAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/convert")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"suggestionIds":["%s"]}
                                """.formatted(SUGGESTION_ID)))
                .andExpect(status().isForbidden());
        verify(purchaseSuggestionService, never()).convertPurchaseSuggestions(any(), anyString());
    }

    @Test
    void convert_withOnlyPoCreateAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/convert")
                        .header("X-Authorities", PO_CREATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"suggestionIds":["%s"]}
                                """.formatted(SUGGESTION_ID)))
                .andExpect(status().isForbidden());
        verify(purchaseSuggestionService, never()).convertPurchaseSuggestions(any(), anyString());
    }

    @Test
    void convert_withEmptySuggestionList_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/purchase-suggestions/convert")
                        .header("X-Authorities", MANAGE + "," + PO_CREATE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"suggestionIds":[]}
                                """))
                .andExpect(status().isBadRequest());
        verify(purchaseSuggestionService, never()).convertPurchaseSuggestions(any(), anyString());
    }
}
