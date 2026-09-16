package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.positivity.shopmanager.internal.config.EventTypes;
import com.positivity.shopmanager.internal.dto.ScheduleCapacityResponse;
import com.positivity.shopmanager.internal.enums.ScheduleCapacityDayStatus;
import com.positivity.shopmanager.internal.exception.ScheduleCapacityRangeExceededException;
import com.positivity.shopmanager.internal.exception.ShopManagerValidationException;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import com.positivity.shopmanager.internal.service.ScheduleCapacityService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Web-layer contract for {@code GET /v1/schedules/capacity} (issue #2023). */
@WebMvcTest(ScheduleController.class)
@Import({GlobalExceptionHandler.class, ScheduleCapacityControllerTest.FixedClockConfig.class})
@DisplayName("ScheduleController - schedules/capacity")
class ScheduleCapacityControllerTest {

    private static final UUID LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");
    private static final UUID BAY_ID = UUID.fromString("01960005-0000-7000-8000-0000000000b1");

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {}
    }

    @Autowired
    private MockMvc mockMvc;

    // Required by ScheduleController's constructor even though this test only exercises the
    // capacity endpoint; viewSchedule is covered by ScheduleViewContractBehaviorIT.
    @MockitoBean
    private AppointmentsService appointmentsService;

    @MockitoBean
    private ScheduleCapacityService scheduleCapacityService;

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("#2023 AC2 - renders the per-day, per-bay occupancy shape")
    void returnsCapacityView() throws Exception {
        ScheduleCapacityResponse.BayCapacityView bay = new ScheduleCapacityResponse.BayCapacityView();
        bay.setBayId(BAY_ID);
        bay.setName("Bay 1");
        bay.setOccupiedMinutes(120);
        bay.setOccupancy(List.of(0, 0, 1, 1, 0));

        ScheduleCapacityResponse.DayCapacityView day = new ScheduleCapacityResponse.DayCapacityView();
        day.setDate(LocalDate.of(2026, 10, 5));
        day.setStatus(ScheduleCapacityDayStatus.OK);
        day.setDayStartAt(Instant.parse("2026-10-05T08:00:00Z"));
        day.setDayEndAt(Instant.parse("2026-10-05T17:00:00Z"));
        day.setBays(List.of(bay));

        ScheduleCapacityResponse response = new ScheduleCapacityResponse();
        response.setLocationId(LOCATION_ID);
        response.setFrom(LocalDate.of(2026, 10, 5));
        response.setTo(LocalDate.of(2026, 10, 5));
        response.setTimezone("UTC");
        response.setViewGeneratedAt(Instant.parse("2026-09-03T12:00:00Z"));
        response.setDays(List.of(day));

        when(scheduleCapacityService.getCapacity(
                        eq(LOCATION_ID), eq(LocalDate.of(2026, 10, 5)), eq(LocalDate.of(2026, 10, 5))))
                .thenReturn(response);

        mockMvc.perform(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-05")
                        .param("to", "2026-10-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.days[0].status").value("OK"))
                .andExpect(jsonPath("$.days[0].bays[0].bayId").value(BAY_ID.toString()))
                .andExpect(jsonPath("$.days[0].bays[0].occupiedMinutes").value(120))
                .andExpect(jsonPath("$.days[0].bays[0].occupancy").isArray())
                // AC2: no appointment identifiers, customer snapshots, titles or conflict details.
                .andExpect(jsonPath("$.days[0].bays[0].events").doesNotExist())
                .andExpect(jsonPath("$.days[0].bays[0].appointmentId").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("#2023 AC3 - to before from surfaces as a 400 VALIDATION_ERROR-shaped ApiError")
    void toBeforeFromIsBadRequest() throws Exception {
        when(scheduleCapacityService.getCapacity(
                        eq(LOCATION_ID), eq(LocalDate.of(2026, 10, 5)), eq(LocalDate.of(2026, 10, 1))))
                .thenThrow(new ShopManagerValidationException("to must not be before from"));

        mockMvc.perform(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-05")
                        .param("to", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("#2023 AC3 - a span over the policy limit is 422 CAPACITY_RANGE_EXCEEDED")
    void rangeTooLargeIsUnprocessable() throws Exception {
        when(scheduleCapacityService.getCapacity(
                        eq(LOCATION_ID), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31))))
                .thenThrow(new ScheduleCapacityRangeExceededException(42, 365));

        mockMvc.perform(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_RANGE_EXCEEDED"));
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    @DisplayName("#2023 - a malformed locationId is a 400 in the ApiError envelope")
    void malformedLocationIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/schedules/capacity")
                        .param("locationId", "not-a-uuid")
                        .param("from", "2026-10-05")
                        .param("to", "2026-10-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(authorities = "shop:technician:view")
    @DisplayName("#2023 - a caller without shop:schedule:view is forbidden")
    void otherShopPermissionsAreNotEnough() throws Exception {
        mockMvc.perform(get("/v1/schedules/capacity")
                        .param("locationId", LOCATION_ID.toString())
                        .param("from", "2026-10-05")
                        .param("to", "2026-10-05"))
                .andExpect(status().isForbidden());
    }

    /**
     * An {@code @EmitEvent} id that is not in {@link EventTypes} drops out of the startup
     * registration PUT, so pos-event-receiver never learns the endpoint's latency thresholds.
     * Annotation and registry are pinned together for that reason (mirrors #1658's
     * {@code ShopDashboardControllerTest}). One annotation on the method also is the AC5 guarantee
     * that exactly one audit event fires per call, never one per day.
     */
    @Test
    @DisplayName("#2023 AC5 - the capacity read emits exactly one registered audit event per call")
    void emitsRegisteredAuditEvent() throws NoSuchMethodException {
        Method operation = ScheduleController.class.getMethod(
                "getScheduleCapacity", UUID.class, LocalDate.class, LocalDate.class, UUID.class);

        EmitEvent emitEvent = operation.getAnnotation(EmitEvent.class);

        org.assertj.core.api.Assertions.assertThat(emitEvent)
                .as("getScheduleCapacity must carry @EmitEvent")
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(emitEvent.id()).isEqualTo("SHOPMGR_SCHEDULE_CAPACITY_VIEW");
        org.assertj.core.api.Assertions.assertThat(EventTypes.all().stream().map(EventTypeRegistration::getTypeCode))
                .contains("SHOPMGR_SCHEDULE_CAPACITY_VIEW");
    }
}
