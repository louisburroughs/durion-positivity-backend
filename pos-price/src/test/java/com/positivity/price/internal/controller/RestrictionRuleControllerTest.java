package com.positivity.price.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.price.BaseContractIntegrationTest;
import com.positivity.price.internal.dto.RestrictionRuleResponse;
import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import com.positivity.price.internal.exception.RestrictionRuleNotFoundException;
import com.positivity.price.service.RestrictionRuleService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@DisplayName("RestrictionRuleController – contract tests")
class RestrictionRuleControllerTest extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RestrictionRuleService restrictionRuleService;

    @Override
    protected String defaultAuthorities() {
        return "pricing:view";
    }

    @Test
    @DisplayName("createRule_withManageAuthority_validBody_returns201")
    void createRule_withManageAuthority_validBody_returns201() throws Exception {
        when(restrictionRuleService.createRule(any())).thenReturn(activeRuleResponse());

        mockMvc.perform(post("/v1/price/restrictions/rules")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRuleBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").isNotEmpty());
    }

    @Test
    @DisplayName("createRule_withoutManageAuthority_returns403")
    void createRule_withoutManageAuthority_returns403() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/price/restrictions/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateRuleBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("createRule_missingProductId_returns400")
    void createRule_missingProductId_returns400() throws Exception {
        mockMvc.perform(post("/v1/price/restrictions/rules")
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingProductIdBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("getRuleById_existingRule_returns200")
    void getRuleById_existingRule_returns200() throws Exception {
        UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(restrictionRuleService.getRuleById(any())).thenReturn(activeRuleResponse(ruleId));

        mockMvc.perform(withGatewayAuth(get("/v1/price/restrictions/rules/{ruleId}", ruleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").isNotEmpty());
    }

    @Test
    @DisplayName("getRuleById_unknownRule_returns404")
    void getRuleById_unknownRule_returns404() throws Exception {
        UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(restrictionRuleService.getRuleById(any()))
                .thenThrow(new RestrictionRuleNotFoundException("Rule not found: " + ruleId));

        mockMvc.perform(withGatewayAuth(get("/v1/price/restrictions/rules/{ruleId}", ruleId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("listRules_authenticated_returns200")
    void listRules_authenticated_returns200() throws Exception {
        when(restrictionRuleService.listRules()).thenReturn(List.of(activeRuleResponse()));

        mockMvc.perform(withGatewayAuth(get("/v1/price/restrictions/rules")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("deactivateRule_withManageAuthority_returns200")
    void deactivateRule_withManageAuthority_returns200() throws Exception {
        UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(restrictionRuleService.deactivateRule(any())).thenReturn(inactiveRuleResponse(ruleId));

        mockMvc.perform(delete("/v1/price/restrictions/rules/{ruleId}", ruleId)
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:manage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("deactivateRule_unknownRule_returns404")
    void deactivateRule_unknownRule_returns404() throws Exception {
        UUID ruleId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(restrictionRuleService.deactivateRule(any()))
                .thenThrow(new RestrictionRuleNotFoundException("Rule not found: " + ruleId));

        mockMvc.perform(delete("/v1/price/restrictions/rules/{ruleId}", ruleId)
                        .header("X-User", "contract-test-user")
                        .header("X-Authorities", "pricing:restriction:manage"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("openApiSpec_restrictionRuleEndpoints_haveDescriptions")
    @SuppressWarnings("unchecked")
    void openApiSpec_restrictionRuleEndpoints_haveDescriptions() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Map<String, Object> openApiSpec = objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) openApiSpec.get("paths");

        assertOperationDescription(paths, "/v1/price/restrictions/rules", "get");
        assertOperationDescription(paths, "/v1/price/restrictions/rules", "post");
        assertOperationDescription(paths, "/v1/price/restrictions/rules/{ruleId}", "get");
        assertOperationDescription(paths, "/v1/price/restrictions/rules/{ruleId}", "delete");
    }

    @SuppressWarnings("unchecked")
    private static void assertOperationDescription(Map<String, Object> paths, String path, String method) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        org.assertj.core.api.Assertions.assertThat(pathItem).as("path %s should exist", path).isNotNull();

        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        org.assertj.core.api.Assertions.assertThat(operation)
                .as("%s %s operation should exist", method.toUpperCase(), path)
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(operation.get("description"))
                .as("%s %s should have a non-empty description", method.toUpperCase(), path)
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }

    private RestrictionRuleResponse activeRuleResponse() {
        return activeRuleResponse(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private RestrictionRuleResponse activeRuleResponse(UUID ruleId) {
        return new RestrictionRuleResponse(
                ruleId,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                LocationTag.RETAIL_STORE,
                ServiceTag.WORKORDER,
                true,
                LocalDate.of(2025, 1, 1),
                null);
    }

    private RestrictionRuleResponse inactiveRuleResponse(UUID ruleId) {
        return new RestrictionRuleResponse(
                ruleId,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                LocationTag.RETAIL_STORE,
                ServiceTag.WORKORDER,
                false,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 2));
    }

    private String validCreateRuleBody() {
        return """
                {
                  "productId":"10000000-0000-0000-0000-000000000001",
                  "locationTag":"RETAIL_STORE",
                  "serviceTag":"WORKORDER",
                                    "effectiveFrom":"2025-01-01",
                                    "effectiveTo":null,
                                    "policyVersion":1,
                                    "overrideable":false
                }
                """;
    }

    private String missingProductIdBody() {
        return """
                {
                  "locationTag":"RETAIL_STORE",
                  "serviceTag":"WORKORDER",
                                    "effectiveFrom":"2025-01-01",
                                    "effectiveTo":null,
                                    "policyVersion":1,
                                    "overrideable":false
                }
                """;
    }
}
