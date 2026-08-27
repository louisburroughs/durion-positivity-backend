package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.service.PutawayRuleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for the putaway rule API (issue #1514): permission gating, request-body validation
 * of the matchType/matchValue pairing, and the 409 on a second enabled ANY rule.
 */
@WebMvcTest(PutawayRuleController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@DisplayName("PutawayRuleController")
@SuppressWarnings({"java:S6813", "java:S1192"})
class PutawayRuleControllerTest {

    private static final String VIEW = "inventory:putaway_rule:view";
    private static final String MANAGE = "inventory:putaway_rule:manage";
    /** A neighbouring putaway permission: holding it must NOT grant rule administration. */
    private static final String OTHER = "inventory:putaway:generate";

    private static final UUID RULE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final UUID DESTINATION = UUID.fromString("01960004-0001-7000-8000-000000000047");
    private static final UUID TIRES_CATEGORY = UUID.fromString("01960030-0000-7000-8000-000000000001");

    private static final String CATEGORY_RULE_JSON = """
            {"priority":10,"matchType":"CATEGORY","matchValue":"01960030-0000-7000-8000-000000000001",
             "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    PutawayRuleService putawayRuleService;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-08-27T00:00:00Z"));
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    private static PutawayRuleResponse sample() {
        return PutawayRuleResponse.builder()
                .ruleId(RULE_ID.toString())
                .priority(10)
                .matchType("CATEGORY")
                .matchValue(TIRES_CATEGORY.toString())
                .destinationLocationId(DESTINATION.toString())
                .destinationStrategy("FIXED")
                .isEnabled(true)
                .build();
    }

    // ─── Reads ───────────────────────────────────────────────────────────────

    @Test
    void listRules_withViewAuthority_returns200() throws Exception {
        when(putawayRuleService.listRules()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/v1/inventory/putaway/rules").header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value(RULE_ID.toString()))
                .andExpect(jsonPath("$[0].matchType").value("CATEGORY"));
    }

    @Test
    void listRules_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/putaway/rules").header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRule_withViewAuthority_returns200() throws Exception {
        when(putawayRuleService.getRule(RULE_ID.toString())).thenReturn(sample());

        mockMvc.perform(get("/v1/inventory/putaway/rules/" + RULE_ID).header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchValue").value(TIRES_CATEGORY.toString()));
    }

    @Test
    void getRule_unknown_returns404() throws Exception {
        when(putawayRuleService.getRule(RULE_ID.toString()))
                .thenThrow(new ResourceNotFoundException("PutawayRule", RULE_ID.toString()));

        mockMvc.perform(get("/v1/inventory/putaway/rules/" + RULE_ID).header("X-Authorities", VIEW))
                .andExpect(status().isNotFound());
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    @Test
    void createRule_withManageAuthority_returns201() throws Exception {
        when(putawayRuleService.createRule(any(PutawayRuleRequest.class))).thenReturn(sample());

        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CATEGORY_RULE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").value(RULE_ID.toString()));
    }

    @Test
    @DisplayName("#1514 - view alone does not permit authoring rules")
    void createRule_withOnlyViewAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CATEGORY_RULE_JSON))
                .andExpect(status().isForbidden());

        verify(putawayRuleService, never()).createRule(any(PutawayRuleRequest.class));
    }

    @Test
    @DisplayName("#1514 - a typed rule with no matchValue is rejected before it reaches the service")
    void createRule_typedRuleWithoutMatchValue_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"CATEGORY",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isBadRequest());

        verify(putawayRuleService, never()).createRule(any(PutawayRuleRequest.class));
    }

    @Test
    @DisplayName("#1514 - an ANY rule carrying a matchValue is rejected: the matcher would ignore it")
    void createRule_anyRuleWithMatchValue_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"ANY",
                                 "matchValue":"01960030-0000-7000-8000-000000000001",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isBadRequest());

        verify(putawayRuleService, never()).createRule(any(PutawayRuleRequest.class));
    }

    @Test
    @DisplayName("#1514 - a bare ANY rule is accepted")
    void createRule_anyRuleWithoutMatchValue_returns201() throws Exception {
        when(putawayRuleService.createRule(any(PutawayRuleRequest.class))).thenReturn(sample());

        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"ANY",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("#1514 - a matchValue that is not a UUID is rejected rather than never matching")
    void createRule_nonUuidMatchValue_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"CATEGORY","matchValue":"OIL-",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRule_missingDestination_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"ANY"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRule_negativePriority_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":-1,"matchType":"ANY",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("#1514 - a second enabled ANY rule is a 409")
    void createRule_secondEnabledAnyRule_returns409() throws Exception {
        when(putawayRuleService.createRule(any(PutawayRuleRequest.class)))
                .thenThrow(new DuplicateEnabledAnyPutawayRuleException(RULE_ID));

        mockMvc.perform(post("/v1/inventory/putaway/rules")
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"priority":10,"matchType":"ANY",
                                 "destinationLocationId":"01960004-0001-7000-8000-000000000047"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DuplicateEnabledAnyPutawayRuleException.ERROR_CODE));
    }

    // ─── Update and delete ───────────────────────────────────────────────────

    @Test
    void updateRule_withManageAuthority_returns200() throws Exception {
        when(putawayRuleService.updateRule(org.mockito.ArgumentMatchers.eq(RULE_ID.toString()), any()))
                .thenReturn(sample());

        mockMvc.perform(put("/v1/inventory/putaway/rules/" + RULE_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CATEGORY_RULE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value(RULE_ID.toString()));
    }

    @Test
    void updateRule_missingManageAuthority_returns403() throws Exception {
        mockMvc.perform(put("/v1/inventory/putaway/rules/" + RULE_ID)
                        .header("X-Authorities", VIEW)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CATEGORY_RULE_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateRule_unknown_returns404() throws Exception {
        when(putawayRuleService.updateRule(org.mockito.ArgumentMatchers.eq(RULE_ID.toString()), any()))
                .thenThrow(new ResourceNotFoundException("PutawayRule", RULE_ID.toString()));

        mockMvc.perform(put("/v1/inventory/putaway/rules/" + RULE_ID)
                        .header("X-Authorities", MANAGE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CATEGORY_RULE_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRule_withManageAuthority_returns204() throws Exception {
        mockMvc.perform(delete("/v1/inventory/putaway/rules/" + RULE_ID).header("X-Authorities", MANAGE))
                .andExpect(status().isNoContent());

        verify(putawayRuleService).deleteRule(RULE_ID.toString());
    }

    @Test
    void deleteRule_missingManageAuthority_returns403() throws Exception {
        mockMvc.perform(delete("/v1/inventory/putaway/rules/" + RULE_ID).header("X-Authorities", VIEW))
                .andExpect(status().isForbidden());

        verify(putawayRuleService, never()).deleteRule(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void deleteRule_unknown_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("PutawayRule", RULE_ID.toString()))
                .when(putawayRuleService)
                .deleteRule(RULE_ID.toString());

        mockMvc.perform(delete("/v1/inventory/putaway/rules/" + RULE_ID).header("X-Authorities", MANAGE))
                .andExpect(status().isNotFound());
    }
}
