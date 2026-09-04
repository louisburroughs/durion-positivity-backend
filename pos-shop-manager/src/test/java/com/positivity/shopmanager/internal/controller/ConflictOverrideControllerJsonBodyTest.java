package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.exception.AppointmentNotFoundException;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.ConflictOverrideService;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Regression coverage for issue #1699: {@link ConflictOverrideRequest} is a Lombok {@code @Value
 * @Builder} class, which without {@code @Jacksonized} has no creator Jackson can use, so the
 * production {@code ObjectMapper} (Jackson 3 / {@code tools.jackson}) rejected every real request
 * body before {@link ConflictOverrideController#executeOverride} ever ran. These tests POST real
 * JSON through {@link MockMvc} rather than constructing the DTO in Java, so they fail again if
 * {@code @Jacksonized} is ever removed.
 */
@WebMvcTest(ConflictOverrideController.class)
class ConflictOverrideControllerJsonBodyTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConflictOverrideService conflictOverrideService;

    @Test
    @WithMockUser(authorities = ShopPermissions.SCHEDULE_EDIT)
    void executeOverride_deserializesJsonBodyAndReachesTheService() throws Exception {
        ConflictOverrideResponse mockResponse = ConflictOverrideResponse.builder()
                .overrideId(UUID.fromString("01960003-0000-7000-8000-000000000099"))
                .appointmentId(APPOINTMENT_ID)
                .overriddenByUserId("user-42")
                .overrideTimestamp(Instant.parse("2026-09-04T12:00:00Z"))
                .overrideReason("Customer waiting on-site")
                .build();
        when(conflictOverrideService.execute(any())).thenReturn(mockResponse);

        String body = """
                {"appointmentId":"01960003-0000-7000-8000-000000000001",
                 "overrideReason":"Customer waiting on-site",
                 "conflictDetails":"{\\"type\\":\\"TECHNICIAN_DOUBLE_BOOKED\\"}"}""";

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overrideId").value("01960003-0000-7000-8000-000000000099"))
                .andExpect(jsonPath("$.overrideReason").value("Customer waiting on-site"));

        ArgumentCaptor<ConflictOverrideRequest> captor = ArgumentCaptor.forClass(ConflictOverrideRequest.class);
        verify(conflictOverrideService).execute(captor.capture());
        assertThat(captor.getValue().getAppointmentId()).isEqualTo(APPOINTMENT_ID);
        assertThat(captor.getValue().getOverrideReason()).isEqualTo("Customer waiting on-site");
        assertThat(captor.getValue().getConflictDetails()).isEqualTo("{\"type\":\"TECHNICIAN_DOUBLE_BOOKED\"}");
    }

    /**
     * The HTTP-level 404 assertion PR #1695 had to defer because this Jackson deserialization
     * failure meant no request body ever reached the controller to exercise the exception mapping.
     */
    @Test
    @WithMockUser(authorities = ShopPermissions.SCHEDULE_EDIT)
    void executeOverride_whenAppointmentNotFound_answers404WithAppointmentNotFoundCode() throws Exception {
        when(conflictOverrideService.execute(any())).thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));

        String body = """
                {"appointmentId":"01960003-0000-7000-8000-000000000001",
                 "overrideReason":"Customer waiting on-site"}""";

        mockMvc.perform(post("/v1/appointments/{appointmentId}/conflict-override", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
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
