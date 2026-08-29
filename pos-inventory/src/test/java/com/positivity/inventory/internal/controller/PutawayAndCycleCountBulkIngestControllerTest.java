package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.cyclecount.service.CycleCountPlanService;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanBulkIngestRecord;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleBulkIngestRecord;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.putaway.service.PutawayRuleService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Putaway rules and cycle count plans: the two inventory packs whose ingest has to be careful about
 * re-runs, for different reasons — one enabled ANY rule may exist, and plan names are not unique.
 */
@WebMvcTest({PutawayRuleBulkIngestController.class, CycleCountPlanBulkIngestController.class})
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class PutawayAndCycleCountBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000080");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000081");
    private static final UUID BIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000082");
    private static final UUID RULE_ID = UUID.fromString("00000000-0000-0000-0000-000000000083");
    private static final UUID EXISTING_ANY_RULE = UUID.fromString("00000000-0000-0000-0000-000000000084");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-0000-0000-000000000085");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PutawayRuleService putawayRuleService;

    @MockitoBean
    CycleCountPlanService cycleCountPlanService;

    @MockitoBean
    Clock clock;

    private void clockIsFixed() {
        when(clock.instant()).thenReturn(Instant.parse("2026-03-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private <T> BulkIngestRequest<T> request(List<T> records) {
        BulkIngestRequest<T> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(SITE_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(records);
        return request;
    }

    // ─── putaway rules ───────────────────────────────────────────────────────

    private static PutawayRuleBulkIngestRecord rule(PutawayRuleMatchType matchType, UUID matchValue) {
        PutawayRuleBulkIngestRecord record = new PutawayRuleBulkIngestRecord();
        record.setPriority(10);
        record.setMatchType(matchType);
        record.setMatchValue(matchValue);
        record.setDestinationLocationId(BIN_ID);
        record.setDestinationStrategy(PutawayDestinationStrategy.FIXED);
        return record;
    }

    @Test
    void putawayRules_createEachRow() throws Exception {
        when(putawayRuleService.createRule(any()))
                .thenReturn(
                        PutawayRuleResponse.builder().ruleId(RULE_ID.toString()).build());

        mockMvc.perform(post("/v1/inventory/putaway/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(List.of(rule(PutawayRuleMatchType.CATEGORY, UUID.randomUUID()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(RULE_ID.toString()));
    }

    @Test
    void putawayRules_secondEnabledAnyRule_isReportedWithTheExistingRuleId() throws Exception {
        // Not a failure — the tier is configured — but the operator has to be told which rule holds
        // it, because an ANY rule pointing somewhere unintended refuses every unclassified line
        // rather than catching it.
        when(putawayRuleService.createRule(any()))
                .thenThrow(new DuplicateEnabledAnyPutawayRuleException(EXISTING_ANY_RULE));

        mockMvc.perform(post("/v1/inventory/putaway/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(List.of(rule(PutawayRuleMatchType.ANY, null))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].entityId").value(EXISTING_ANY_RULE.toString()))
                .andExpect(
                        jsonPath("$.results[0].errorCode").value(DuplicateEnabledAnyPutawayRuleException.ERROR_CODE));
    }

    @Test
    void putawayRules_otherErrorsFailTheirRow() throws Exception {
        when(putawayRuleService.createRule(any()))
                .thenThrow(new IllegalArgumentException("LOCATION_NOT_VALID_FOR_SKU"));

        mockMvc.perform(post("/v1/inventory/putaway/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                request(List.of(rule(PutawayRuleMatchType.CATEGORY, UUID.randomUUID()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("PUTAWAY_RULE_INGEST_FAILED"));
    }

    // ─── cycle count plans ───────────────────────────────────────────────────

    private static CycleCountPlanBulkIngestRecord plan(String name, Integer daysOut) {
        CycleCountPlanBulkIngestRecord record = new CycleCountPlanBulkIngestRecord();
        record.setPlanName(name);
        record.setZoneIds(List.of(BIN_ID));
        record.setScheduledDaysOut(daysOut);
        return record;
    }

    @Test
    void cycleCountPlans_scheduleFromToday_soAReplayedFileIsNeverInThePast() throws Exception {
        clockIsFixed();
        when(cycleCountPlanService.listPlans(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(cycleCountPlanService.createPlan(any(), anyString()))
                .thenReturn(CycleCountPlanResponse.builder().planId(PLAN_ID).build());

        mockMvc.perform(post("/v1/inventory/cycleCountPlans/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(plan("Q1 Fast Movers", 30))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(cycleCountPlanService)
                .createPlan(
                        org.mockito.ArgumentMatchers.argThat((CreateCycleCountPlanRequest req) ->
                                LocalDate.of(2026, 3, 31).equals(req.getScheduledDate())),
                        eq("seed-operator"));
    }

    @Test
    void cycleCountPlans_anExplicitDateIsHonoured() throws Exception {
        clockIsFixed();
        when(cycleCountPlanService.listPlans(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(cycleCountPlanService.createPlan(any(), anyString()))
                .thenReturn(CycleCountPlanResponse.builder().planId(PLAN_ID).build());

        CycleCountPlanBulkIngestRecord dated = plan("Dated", null);
        dated.setScheduledDate(LocalDate.of(2026, 7, 1));

        mockMvc.perform(post("/v1/inventory/cycleCountPlans/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(dated)))))
                .andExpect(status().isOk());

        verify(cycleCountPlanService)
                .createPlan(
                        org.mockito.ArgumentMatchers.argThat((CreateCycleCountPlanRequest req) ->
                                LocalDate.of(2026, 7, 1).equals(req.getScheduledDate())),
                        anyString());
    }

    @Test
    void cycleCountPlans_anExistingPlanNameIsRecognised_notDuplicated() throws Exception {
        // Nothing in the schema makes plan names unique, so without this a re-run creates a second
        // plan every time.
        clockIsFixed();
        when(cycleCountPlanService.listPlans(eq(SITE_ID), any(), anyInt(), anyInt()))
                .thenReturn(List.of(CycleCountPlanResponse.builder()
                        .planId(PLAN_ID)
                        .planName("Q1 Fast Movers")
                        .build()));

        mockMvc.perform(post("/v1/inventory/cycleCountPlans/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(plan("Q1 Fast Movers", 30))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(PLAN_ID.toString()));

        verify(cycleCountPlanService, never()).createPlan(any(), anyString());
    }

    @Test
    void cycleCountPlans_listExistingPlansOncePerSite() throws Exception {
        clockIsFixed();
        when(cycleCountPlanService.listPlans(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(cycleCountPlanService.createPlan(any(), anyString()))
                .thenReturn(CycleCountPlanResponse.builder().planId(PLAN_ID).build());

        mockMvc.perform(post("/v1/inventory/cycleCountPlans/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(plan("One", 30), plan("Two", 30))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2));

        verify(cycleCountPlanService, times(1)).listPlans(eq(SITE_ID), any(), anyInt(), anyInt());
    }
}
