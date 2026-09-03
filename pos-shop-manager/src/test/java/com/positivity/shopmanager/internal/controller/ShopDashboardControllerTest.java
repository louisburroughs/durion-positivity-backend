package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.events.EmitEvent;
import com.positivity.events.EventTypeRegistration;
import com.positivity.shopmanager.internal.config.EventTypes;
import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import com.positivity.shopmanager.internal.dto.ShopDashboardUnit;
import com.positivity.shopmanager.internal.dto.ShopDashboardVehicle;
import com.positivity.shopmanager.internal.dto.ShopDashboardWorkorder;
import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.service.ShopDashboardService;
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

/** Web-layer contract for {@code GET /v1/shop-dashboard} (#1658 AC1, AC12). */
@WebMvcTest(ShopDashboardController.class)
@Import({GlobalExceptionHandler.class, ShopDashboardControllerTest.FixedClockConfig.class})
@DisplayName("ShopDashboardController")
class ShopDashboardControllerTest {

    private static final UUID LOCATION_ID = UUID.fromString("018e1c9f-6b5a-7890-abcd-1234567890ab");
    private static final UUID BAY_ID = UUID.fromString("01960005-0000-7000-8000-0000000000b1");
    private static final UUID WORKORDER_ID = UUID.fromString("01960003-0000-7000-8000-000000000002");

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

    @MockitoBean
    private ShopDashboardService shopDashboardService;

    @Test
    @WithMockUser(authorities = "shop:dashboard:view")
    @DisplayName("#1658 AC1 - renders units, their assignments and openWorkorders in one payload")
    void returnsTheWholeBoard() throws Exception {
        ShopDashboardWorkorder assigned = new ShopDashboardWorkorder(
                WORKORDER_ID,
                "WO-2026-1001",
                "WORK_IN_PROGRESS",
                BAY_ID,
                "Front Bay 1",
                ShopDashboardUnitType.BAY,
                new ShopDashboardVehicle(null, "1HGCM82633A004352", 2024, "Ford", "F-150"),
                "Ada Lovelace",
                List.of("Ada Lovelace"),
                null);
        when(shopDashboardService.getDashboard(eq(LOCATION_ID), eq(LocalDate.of(2026, 9, 3))))
                .thenReturn(new ShopDashboardResponse(
                        LOCATION_ID,
                        LocalDate.of(2026, 9, 3),
                        List.of(new ShopDashboardUnit(BAY_ID, ShopDashboardUnitType.BAY, "Front Bay 1", assigned)),
                        List.of(assigned),
                        false));

        mockMvc.perform(get("/v1/shop-dashboard")
                        .param("locationId", LOCATION_ID.toString())
                        .param("date", "2026-09-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.date").value("2026-09-03"))
                .andExpect(jsonPath("$.units[0].unitType").value("BAY"))
                .andExpect(jsonPath("$.units[0].unitName").value("Front Bay 1"))
                .andExpect(jsonPath("$.units[0].assignment.status").value("WORK_IN_PROGRESS"))
                .andExpect(jsonPath("$.units[0].assignment.vehicle.vin").value("1HGCM82633A004352"))
                .andExpect(jsonPath("$.units[0].assignment.mechanicName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.openWorkorders[0].workorderId").value(WORKORDER_ID.toString()))
                .andExpect(jsonPath("$.openWorkordersTruncated").value(false));
    }

    @Test
    @WithMockUser(authorities = "shop:dashboard:view")
    @DisplayName("#1658 AC2 - an omitted date reaches the service as null so it resolves the location's today")
    void omittedDateIsPassedThroughAsNull() throws Exception {
        when(shopDashboardService.getDashboard(eq(LOCATION_ID), isNull()))
                .thenReturn(
                        new ShopDashboardResponse(LOCATION_ID, LocalDate.of(2026, 9, 3), List.of(), List.of(), false));

        mockMvc.perform(get("/v1/shop-dashboard").param("locationId", LOCATION_ID.toString()))
                .andExpect(status().isOk());

        verify(shopDashboardService).getDashboard(LOCATION_ID, null);
    }

    @Test
    @WithMockUser(authorities = "shop:dashboard:view")
    @DisplayName("#1658 AC12 - a malformed locationId is a 400 in the ApiError envelope")
    void malformedLocationIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/shop-dashboard").param("locationId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(authorities = "shop:dashboard:view")
    @DisplayName("#1658 AC12 - a malformed date is a 400 in the ApiError envelope")
    void malformedDateIsBadRequest() throws Exception {
        mockMvc.perform(get("/v1/shop-dashboard")
                        .param("locationId", LOCATION_ID.toString())
                        .param("date", "03-09-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @WithMockUser(authorities = "shop:dashboard:view")
    @DisplayName("#1658 AC12 - an unknown location is a 404 in the ApiError envelope")
    void unknownLocationIsNotFound() throws Exception {
        when(shopDashboardService.getDashboard(eq(LOCATION_ID), isNull()))
                .thenThrow(new LocationNotFoundException(LOCATION_ID));

        mockMvc.perform(get("/v1/shop-dashboard").param("locationId", LOCATION_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCATION_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = "shop:technician:view")
    @DisplayName("#1658 AC14 - another shop permission does not open the dashboard")
    void otherShopPermissionsAreNotEnough() throws Exception {
        mockMvc.perform(get("/v1/shop-dashboard").param("locationId", LOCATION_ID.toString()))
                .andExpect(status().isForbidden());
    }

    /**
     * An {@code @EmitEvent} id that is not in {@link EventTypes} drops out of the startup
     * registration PUT, so pos-event-receiver never learns the endpoint's latency thresholds and
     * the endpoint silently vanishes from audit reporting. Annotation and registry are pinned
     * together for that reason.
     */
    @Test
    @DisplayName("#1658 - the dashboard read emits a registered audit event")
    void emitsRegisteredAuditEvent() throws NoSuchMethodException {
        Method operation = ShopDashboardController.class.getMethod("getShopDashboard", UUID.class, LocalDate.class);

        EmitEvent emitEvent = operation.getAnnotation(EmitEvent.class);

        org.assertj.core.api.Assertions.assertThat(emitEvent)
                .as("getShopDashboard must carry @EmitEvent")
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(emitEvent.id()).isEqualTo("SHOPMGR_SHOP_DASHBOARD_VIEW");
        org.assertj.core.api.Assertions.assertThat(EventTypes.all().stream().map(EventTypeRegistration::getTypeCode))
                .contains("SHOPMGR_SHOP_DASHBOARD_VIEW");
    }
}
