package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.dto.ConflictResponse;
import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.exception.ConflictOverrideStateException;
import com.positivity.shopmanager.internal.exception.SchedulingConflictException;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.ConflictOverrideService;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The override endpoint over real JSON (CAP-326): the {@code @Jacksonized} body still deserializes
 * (issue #1699's regression), the gate is {@code shop:conflict:override} alone, and each refusal
 * the service can raise reaches the client with the status and shape DECISION-SHOPMGMT-002 gives it.
 */
@WebMvcTest(ConflictOverrideController.class)
class ConflictOverrideControllerJsonBodyTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID CONFLICT_ID = UUID.fromString("01960003-0000-7000-8000-000000000010");
    private static final String BODY = """
            {"conflictIds":["01960003-0000-7000-8000-000000000010"],
             "overrideReason":"Customer waiting on-site"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConflictOverrideService conflictOverrideService;

    @Test
    @WithMockUser(authorities = ShopPermissions.CONFLICT_OVERRIDE)
    void executeOverride_deserializesJsonBodyAndReachesTheService() throws Exception {
        when(conflictOverrideService.execute(eq(APPOINTMENT_ID), any()))
                .thenReturn(ConflictOverrideResponse.builder()
                        .appointmentId(APPOINTMENT_ID)
                        .overriddenBy("jane.manager")
                        .approvedAt(Instant.parse("2026-09-04T12:00:00Z"))
                        .overrideReason("Customer waiting on-site")
                        .overrides(List.of(ConflictOverrideResponse.OverrideEntry.builder()
                                .overrideId(UUID.fromString("01960003-0000-7000-8000-000000000099"))
                                .conflictId(CONFLICT_ID)
                                .ruleCode("MECHANIC_OVERTIME")
                                .severity("SOFT")
                                .build()))
                        .build());

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overrides[0].overrideId").value("01960003-0000-7000-8000-000000000099"))
                .andExpect(jsonPath("$.overrides[0].ruleCode").value("MECHANIC_OVERTIME"))
                .andExpect(jsonPath("$.overriddenBy").value("jane.manager"));

        ArgumentCaptor<ConflictOverrideRequest> captor = ArgumentCaptor.forClass(ConflictOverrideRequest.class);
        verify(conflictOverrideService).execute(eq(APPOINTMENT_ID), captor.capture());
        assertThat(captor.getValue().getConflictIds()).containsExactly(CONFLICT_ID);
        assertThat(captor.getValue().getOverrideReason()).isEqualTo("Customer waiting on-site");
    }

    @Test
    @WithMockUser(authorities = ShopPermissions.CONFLICT_OVERRIDE)
    void executeOverride_emptyConflictIds_isRejectedBeforeTheService() throws Exception {
        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conflictIds":[],"overrideReason":"Customer waiting on-site"}"""))
                .andExpect(status().isBadRequest());
        verify(conflictOverrideService, never()).execute(any(), any());
    }

    @Test
    @WithMockUser(authorities = {ShopPermissions.SCHEDULE_EDIT, ShopPermissions.APPOINTMENTS_RESCHEDULE})
    void executeOverride_scheduleEditAlone_isForbidden() throws Exception {
        // The pre-CAP-326 gate; DISPATCHER holds both of these and must no longer override (spec D12).
        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(conflictOverrideService, never()).execute(any(), any());
    }

    @Test
    @WithMockUser(authorities = ShopPermissions.CONFLICT_OVERRIDE)
    void executeOverride_whenAppointmentNotFound_answers404WithAppointmentNotFoundCode() throws Exception {
        when(conflictOverrideService.execute(eq(APPOINTMENT_ID), any()))
                .thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = ShopPermissions.CONFLICT_OVERRIDE)
    void executeOverride_hardConflict_answers409WithTheDecision002Envelope() throws Exception {
        ConflictResponse envelope = new ConflictResponse(
                "SCHEDULING_CONFLICT",
                "HARD conflicts cannot be overridden",
                null,
                null,
                List.of(new ConflictResponse.Conflict("HARD", "BAY_DOUBLE_BOOKED", "Bay 1 is booked", false, "bay-1")));
        when(conflictOverrideService.execute(eq(APPOINTMENT_ID), any()))
                .thenThrow(new SchedulingConflictException(envelope));

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header("X-Correlation-Id", "01960003-0000-7000-8000-0000000000ff"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULING_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.conflicts[0].code").value("BAY_DOUBLE_BOOKED"))
                .andExpect(jsonPath("$.conflicts[0].overridable").value(false))
                .andExpect(jsonPath("$.correlationId").value("01960003-0000-7000-8000-0000000000ff"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-04T12:00:00Z"));
    }

    @Test
    @WithMockUser(authorities = ShopPermissions.CONFLICT_OVERRIDE)
    void executeOverride_alreadyOverridden_answers409WithApiErrorCode() throws Exception {
        when(conflictOverrideService.execute(eq(APPOINTMENT_ID), any()))
                .thenThrow(new ConflictOverrideStateException("Conflict already carries an override"));

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_ALREADY_OVERRIDDEN"));
    }

    /** Clock for {@link GlobalExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
