package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.service.MechanicRosterQueryService;
import com.positivity.shopmanager.internal.service.TechnicianPersonService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@WebMvcTest(TechnicianController.class)
class TechnicianControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MechanicRosterQueryService mechanicRosterQueryService;

    @MockitoBean
    private TechnicianPersonService technicianPersonService;

    @Test
    @WithMockUser(authorities = "shop:technician:view")
    void listLocationTechniciansMapsFiltersAndReturnsPage() throws Exception {
        UUID technicianId = UUID.fromString("01960011-0000-7000-8000-000000000004");
        UUID personId = UUID.fromString("01960011-0000-7000-8000-000000000002");
        LocationTechnicianRosterEntryResponse entry = LocationTechnicianRosterEntryResponse.builder()
                .technicianId(technicianId)
                .locationId(LOCATION_ID)
                .personId(personId)
                .status(MechanicStatus.ON_LEAVE)
                .skills(List.of("BRAKES"))
                .build();
        when(mechanicRosterQueryService.listLocationTechnicians(
                        eq(LOCATION_ID), eq(MechanicStatus.ON_LEAVE), eq("BRAKES"), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(entry)));

        mockMvc.perform(get("/v1/shop-manager/{locationId}/technicians", LOCATION_ID)
                        .param("status", "ON_LEAVE")
                        .param("skillCode", "BRAKES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].technicianId").value(technicianId.toString()))
                .andExpect(jsonPath("$.content[0].locationId").value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("ON_LEAVE"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void listLocationTechniciansWithoutTechnicianViewAuthorityReturnsForbidden() throws Exception {
        mockMvc.perform(get("/v1/shop-manager/{locationId}/technicians", LOCATION_ID))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
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
}
