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
import com.positivity.shopmanager.internal.service.AssignmentService;
import com.positivity.shopmanager.internal.service.dto.AssignedMechanicInfo;
import com.positivity.shopmanager.internal.service.dto.AssignmentResponse;
import com.positivity.shopmanager.internal.service.dto.CreateAssignmentRequest;
import com.positivity.shopmanager.internal.service.enums.AssignmentStatus;
import com.positivity.shopmanager.internal.service.enums.MechanicRole;
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
 * Regression coverage for issue #1699: {@link CreateAssignmentRequest} and its nested {@link
 * com.positivity.shopmanager.internal.service.dto.MechanicAssignmentItem} are Lombok {@code
 * @Value @Builder} classes, which without {@code @Jacksonized} have no creator Jackson can use, so
 * the production {@code ObjectMapper} (Jackson 3 / {@code tools.jackson}) rejected every real
 * request body before {@link AssignmentController#createAssignment} ever ran. These tests POST
 * real JSON — including the nested {@code mechanics} array — through {@link MockMvc} rather than
 * constructing the DTOs in Java, so they fail again if {@code @Jacksonized} is ever removed from
 * either class.
 */
@WebMvcTest(AssignmentController.class)
class AssignmentControllerCreateAssignmentJsonBodyTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID APPOINTMENT_ID = UUID.fromString("01960003-0000-7000-8000-000000000001");
    private static final UUID MECHANIC_ID = UUID.fromString("01960003-0000-7000-8000-000000000010");
    private static final UUID RESOURCE_ID = UUID.fromString("01960003-0000-7000-8000-000000000005");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssignmentService assignmentService;

    @Test
    @WithMockUser(authorities = ShopPermissions.BAY_ASSIGN)
    void createAssignment_deserializesJsonBodyWithNestedMechanicsAndReachesTheService() throws Exception {
        AssignmentResponse mockResponse = AssignmentResponse.builder()
                .assignmentId(UUID.fromString("01960003-0000-7000-8000-000000000099"))
                .appointmentId(APPOINTMENT_ID)
                .mechanics(List.of(AssignedMechanicInfo.builder()
                        .mechanicId(MECHANIC_ID)
                        .role(MechanicRole.LEAD)
                        .build()))
                .resourceId(RESOURCE_ID)
                .resourceType("BAY")
                .status(AssignmentStatus.CONFIRMED)
                .override(false)
                .assignedAt(Instant.parse("2026-09-04T12:00:00Z"))
                .build();
        when(assignmentService.create(any())).thenReturn(mockResponse);

        String body = """
                {"appointmentId":"01960003-0000-7000-8000-000000000001",
                 "mechanics":[{"mechanicPersonId":"01960003-0000-7000-8000-000000000010","role":"LEAD"}],
                 "resourceId":"01960003-0000-7000-8000-000000000005",
                 "resourceType":"BAY",
                 "override":false}""";

        mockMvc.perform(post("/v1/appointments/{appointmentId}/assignments", APPOINTMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignmentId").value("01960003-0000-7000-8000-000000000099"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        ArgumentCaptor<CreateAssignmentRequest> captor = ArgumentCaptor.forClass(CreateAssignmentRequest.class);
        verify(assignmentService).create(captor.capture());
        CreateAssignmentRequest captured = captor.getValue();
        assertThat(captured.getAppointmentId()).isEqualTo(APPOINTMENT_ID);
        assertThat(captured.getMechanics()).hasSize(1);
        assertThat(captured.getMechanics().get(0).getMechanicPersonId()).isEqualTo(MECHANIC_ID.toString());
        assertThat(captured.getMechanics().get(0).getRole()).isEqualTo(MechanicRole.LEAD);
        assertThat(captured.getResourceId()).isEqualTo(RESOURCE_ID);
        assertThat(captured.getResourceType()).isEqualTo("BAY");
        assertThat(captured.isOverride()).isFalse();
    }

    /**
     * The HTTP-level 404 assertion PR #1695 had to defer because this Jackson deserialization
     * failure meant no request body ever reached the controller to exercise the exception mapping.
     */
    @Test
    @WithMockUser(authorities = ShopPermissions.BAY_ASSIGN)
    void createAssignment_whenAppointmentNotFound_answers404WithAppointmentNotFoundCode() throws Exception {
        when(assignmentService.create(any())).thenThrow(new AppointmentNotFoundException(APPOINTMENT_ID));

        String body = """
                {"appointmentId":"01960003-0000-7000-8000-000000000001",
                 "mechanics":[{"mechanicPersonId":"01960003-0000-7000-8000-000000000010","role":"LEAD"}]}""";

        mockMvc.perform(post("/v1/appointments/{appointmentId}/assignments", APPOINTMENT_ID)
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
