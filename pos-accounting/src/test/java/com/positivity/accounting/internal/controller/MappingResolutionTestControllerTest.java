package com.positivity.accounting.internal.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.MappingResolutionTestRequest;
import com.positivity.accounting.internal.dto.MappingResolutionTestResponse;
import com.positivity.accounting.service.MappingResolutionTestService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration tests for the dry-run mapping resolution endpoint (story E3,
 * issue #957) on {@link GLMappingController}.
 */
@DisplayName("GLMappingController dry-run resolution endpoint (E3)")
class MappingResolutionTestControllerTest extends BaseIntegrationTest {

    private static final String RESOLVE_TEST_URL = "/v1/accounting/mappings/resolve-test";
    private static final String VIEW_AUTHORITY = "accounting:posting_rules:view";

    private static final UUID RULE_SET_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("01960003-0000-7000-8000-000000000002");
    private static final UUID ACCOUNT_ID = UUID.fromString("01960003-0000-7000-8000-000000000003");

    @MockitoBean
    private MappingResolutionTestService mappingResolutionTestService;

    private static MappingResolutionTestRequest validRequest() {
        return MappingResolutionTestRequest.builder()
                .eventType("billing.invoicePosted")
                .samplePayload(Map.of("totalAmount", "125.00", "channel", "POS"))
                .transactionDate(LocalDate.of(2026, 1, 15))
                .build();
    }

    private static MappingResolutionTestResponse matchedResponse() {
        return MappingResolutionTestResponse.builder()
                .matched(true)
                .matchedRule(MappingResolutionTestResponse.MatchedRule.builder()
                        .postingRuleSetId(RULE_SET_ID)
                        .ruleSetName("Invoice finalization postings")
                        .ruleVersionId(VERSION_ID)
                        .versionNumber(3)
                        .build())
                .matchedMapping(MappingResolutionTestResponse.MatchedMapping.builder()
                        .mappingType("exact")
                        .mappingKey("payload.channel == 'POS'")
                        .keysEvaluated(List.of("payload.channel == 'POS'"))
                        .build())
                .resolvedLines(List.of(MappingResolutionTestResponse.ResolvedLine.builder()
                        .glAccountId(ACCOUNT_ID)
                        .accountCode("4000")
                        .accountName("Sales Revenue")
                        .debitAmount(new BigDecimal("125.00"))
                        .creditAmount(BigDecimal.ZERO)
                        .splitGroup("revenue-split")
                        .factorPercent(new BigDecimal("60.0"))
                        .build()))
                .predicateEvaluations(List.of(MappingResolutionTestResponse.PredicateEvaluation.builder()
                        .predicate("payload.channel == 'POS'")
                        .matched(true)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("returns 200 with the dry-run resolution result")
    void returnsResolutionResult() throws Exception {
        when(mappingResolutionTestService.resolveTest(any(MappingResolutionTestRequest.class)))
                .thenReturn(matchedResponse());

        mockMvc.perform(post(RESOLVE_TEST_URL)
                        .header("X-Authorities", VIEW_AUTHORITY)
                        .header("X-User", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched", is(true)))
                .andExpect(jsonPath("$.matchedRule.postingRuleSetId", is(RULE_SET_ID.toString())))
                .andExpect(jsonPath("$.matchedRule.versionNumber", is(3)))
                .andExpect(jsonPath("$.matchedMapping.mappingType", is("exact")))
                .andExpect(jsonPath("$.resolvedLines[0].accountCode", is("4000")))
                .andExpect(jsonPath("$.resolvedLines[0].splitGroup", is("revenue-split")))
                .andExpect(jsonPath("$.predicateEvaluations[0].matched", is(true)));
    }

    @Test
    @DisplayName("returns 400 when the sample payload cannot be interpreted")
    void returns400ForUninterpretablePayload() throws Exception {
        when(mappingResolutionTestService.resolveTest(any(MappingResolutionTestRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "Sample payload field 'payload.totalAmount' value 'abc' cannot be interpreted as an amount"));

        mockMvc.perform(post(RESOLVE_TEST_URL)
                        .header("X-Authorities", VIEW_AUTHORITY)
                        .header("X-User", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 400 when the event type is blank")
    void returns400ForBlankEventType() throws Exception {
        MappingResolutionTestRequest invalid = MappingResolutionTestRequest.builder()
                .eventType(" ")
                .transactionDate(LocalDate.of(2026, 1, 15))
                .build();

        mockMvc.perform(post(RESOLVE_TEST_URL)
                        .header("X-Authorities", VIEW_AUTHORITY)
                        .header("X-User", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 403 without the posting_rules view authority")
    void returns403WithoutAuthority() throws Exception {
        mockMvc.perform(post(RESOLVE_TEST_URL)
                        .header("X-Authorities", "accounting:gl-mapping:create")
                        .header("X-User", "test-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }
}
