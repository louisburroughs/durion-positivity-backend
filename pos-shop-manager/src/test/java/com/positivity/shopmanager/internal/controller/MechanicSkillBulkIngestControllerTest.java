package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.service.MechanicSyncService;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The mechanic-skill ingest, whose whole job is the folding: the underlying operation replaces a
 * mechanic's entire skill set, so applying rows one at a time would leave each mechanic holding
 * only their last skill.
 */
@WebMvcTest(MechanicSkillBulkIngestController.class)
@SuppressWarnings({"java:S100", "java:S1192"})
class MechanicSkillBulkIngestControllerTest {

    private static final String PATH = "/v1/shop-manager/mechanics/bulk-ingest";
    private static final String ALICE = "01960011-0000-7000-8000-000000000005";
    private static final String BOB = "01960011-0000-7000-8000-000000000006";

    private static final String TWO_MECHANICS = """
            {"jobId":"01960011-0000-7000-8000-0000000000a0",
             "locationId":"01960011-0000-7000-8000-0000000000a1",
             "operatorId":"seed-operator",
             "records":[
               {"personId":"01960011-0000-7000-8000-000000000005","skillCode":"T4-BRAKES","proficiencyLevel":4},
               {"personId":"01960011-0000-7000-8000-000000000006","skillCode":"T6-ELECTRICAL","proficiencyLevel":3},
               {"personId":"01960011-0000-7000-8000-000000000005","skillCode":"T3-ALIGN","proficiencyLevel":2}
             ]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MechanicSyncService mechanicSyncService;

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void bulkIngest_foldsAMechanicsRowsIntoOneReplacement() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(TWO_MECHANICS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(3))
                .andExpect(jsonPath("$.failureCount").value(0));

        // Alice's two rows become one call carrying both skills, not two calls each dropping the
        // other's skill.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HrMechanicEvent.Payload.Skill>> captor = ArgumentCaptor.forClass(List.class);
        verify(mechanicSyncService).replaceSkills(eq(ALICE), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(mechanicSyncService, times(1)).replaceSkills(eq(BOB), any());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void bulkIngest_everyRowOfAFailedMechanicFails() throws Exception {
        // The rows were applied together, so they share the outcome; reporting one as successful
        // would say a skill landed when the whole set was refused.
        doThrow(new IllegalStateException("mechanic projection not there yet"))
                .when(mechanicSyncService)
                .replaceSkills(eq(ALICE), any());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(TWO_MECHANICS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.results[0].errorCode").value("MECHANIC_SKILL_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].success").value(true))
                .andExpect(jsonPath("$.results[2].errorCode").value("MECHANIC_SKILL_INGEST_FAILED"));
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void bulkIngest_resultsStayInRowOrder() throws Exception {
        // Grouping must not reorder the response: a caller matches results to its own file by index.
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(TWO_MECHANICS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].rowIndex").value(0))
                .andExpect(jsonPath("$.results[1].rowIndex").value(1))
                .andExpect(jsonPath("$.results[2].rowIndex").value(2));
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:edit")
    void bulkIngest_rejectsAnOutOfRangeProficiency() throws Exception {
        String outOfRange = """
                {"jobId":"01960011-0000-7000-8000-0000000000a0",
                 "locationId":"01960011-0000-7000-8000-0000000000a1",
                 "records":[{"personId":"01960011-0000-7000-8000-000000000005","skillCode":"T4","proficiencyLevel":9}]}""";

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(outOfRange))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void bulkIngest_withoutTheEditAuthority_isForbidden() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(TWO_MECHANICS))
                .andExpect(status().isForbidden());
    }

    /** Mirrors MechanicSkillControllerTest.SliceTestConfig: Clock for GlobalExceptionHandler,
     * method security, and 403 mapping for AccessDeniedException in the slice. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.fixed(java.time.Instant.parse("2026-08-26T12:00:00Z"), java.time.ZoneOffset.UTC);
        }

        @org.springframework.context.annotation.Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    @org.springframework.web.bind.annotation.ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @org.springframework.web.bind.annotation.ExceptionHandler(
                org.springframework.security.access.AccessDeniedException.class)
        @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // status set by @ResponseStatus
        }
    }
}
