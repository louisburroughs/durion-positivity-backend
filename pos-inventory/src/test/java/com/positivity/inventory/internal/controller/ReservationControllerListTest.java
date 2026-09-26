package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.reservation.WorkorderReservationAllocationResponse;
import com.positivity.inventory.internal.dto.reservation.WorkorderReservationResponse;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.reservation.service.ReservationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@code ReservationController.listReservationsForWorkorder} (issue #2233):
 * permission gating on the quantity-sensitive allocation-id lookup the shortage page uses (reused
 * {@code inventory:shortage:view}, mirroring {@code BackorderController}).
 */
@WebMvcTest(ReservationController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class ReservationControllerListTest {

    private static final String VIEW = "inventory:shortage:view";
    private static final String OTHER = "inventory:on_hand:view";
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000004001");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    ReservationService reservationService;

    @org.junit.jupiter.api.BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-07-23T00:00:00Z"));
    }

    private WorkorderReservationResponse sample() {
        return WorkorderReservationResponse.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000004002"))
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-000000004003"))
                .sku("00000000-0000-0000-0000-000000004004")
                .requiredQuantity(new BigDecimal("10"))
                .allocatedQuantity(new BigDecimal("4"))
                .shortQuantity(new BigDecimal("6"))
                .status(ReservationStatus.PARTIALLY_FULFILLED)
                .allocations(List.of(WorkorderReservationAllocationResponse.builder()
                        .allocationId(UUID.fromString("00000000-0000-0000-0000-000000004005"))
                        .locationId(UUID.fromString("00000000-0000-0000-0000-000000004006"))
                        .allocatedQuantity(new BigDecimal("4"))
                        .allocationState(AllocationState.HARD)
                        .status(AllocationStatus.ALLOCATED)
                        .build()))
                .build();
    }

    @Test
    void list_withViewAuthority_returns200() throws Exception {
        when(reservationService.listReservationsForWorkorder(any())).thenReturn(List.of(sample()));

        mockMvc.perform(get("/v1/inventory/reservations")
                        .param("workorderId", WORKORDER_ID.toString())
                        .header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reservationId").value("00000000-0000-0000-0000-000000004002"))
                .andExpect(jsonPath("$[0].status").value("PARTIALLY_FULFILLED"))
                .andExpect(jsonPath("$[0].shortQuantity").value(6))
                .andExpect(jsonPath("$[0].allocations[0].allocationId").value("00000000-0000-0000-0000-000000004005"));
    }

    @Test
    void list_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/reservations")
                        .param("workorderId", WORKORDER_ID.toString())
                        .header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }
}
