package com.positivity.inventory.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.traceability.LotTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.SerialTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.TraceabilityUpstream;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.tracking.service.InventoryTraceabilityService;
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
 * Web-slice tests for InventoryTraceabilityController (odoo-parity E5, #1051): permission gating
 * on the traceability reads ({@code inventory:ledger:view}) and 404 propagation.
 */
@WebMvcTest(InventoryTraceabilityController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class InventoryTraceabilityControllerTest {

    private static final UUID LOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SERIAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final String VIEW = "inventory:ledger:view";
    private static final String OTHER = "inventory:on_hand:view";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    InventoryTraceabilityService traceabilityService;

    @org.junit.jupiter.api.BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-07-24T00:00:00Z"));
        when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    private LotTraceabilityResponse lotSample() {
        return LotTraceabilityResponse.builder()
                .lotId(LOT_ID)
                .stockItemId("PART-1")
                .lotNumber("LOT-A")
                .lotStatus(InventoryLotStatus.ACTIVE)
                .upstream(TraceabilityUpstream.builder().documents(List.of()).build())
                .downstream(List.of())
                .build();
    }

    private SerialTraceabilityResponse serialSample() {
        return SerialTraceabilityResponse.builder()
                .serialUnitId(SERIAL_ID)
                .stockItemId("PART-1")
                .serialNumber("SN-A")
                .serialStatus(InventorySerialStatus.IN_STOCK)
                .upstream(TraceabilityUpstream.builder().documents(List.of()).build())
                .downstream(List.of())
                .warrantyHolds(List.of())
                .build();
    }

    @Test
    void lot_withLedgerViewAuthority_returns200() throws Exception {
        when(traceabilityService.getLotTraceability(LOT_ID)).thenReturn(lotSample());

        mockMvc.perform(get("/v1/inventory/lots/" + LOT_ID + "/traceability").header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotId").value(LOT_ID.toString()))
                .andExpect(jsonPath("$.lotNumber").value("LOT-A"));
    }

    @Test
    void lot_missingLedgerViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/lots/" + LOT_ID + "/traceability").header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }

    @Test
    void lot_unknown_returns404() throws Exception {
        when(traceabilityService.getLotTraceability(LOT_ID))
                .thenThrow(new ResourceNotFoundException("InventoryLot", LOT_ID.toString()));

        mockMvc.perform(get("/v1/inventory/lots/" + LOT_ID + "/traceability").header("X-Authorities", VIEW))
                .andExpect(status().isNotFound());
    }

    @Test
    void serial_withLedgerViewAuthority_returns200() throws Exception {
        when(traceabilityService.getSerialTraceability(SERIAL_ID)).thenReturn(serialSample());

        mockMvc.perform(get("/v1/inventory/serial-units/" + SERIAL_ID + "/traceability")
                        .header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialUnitId").value(SERIAL_ID.toString()))
                .andExpect(jsonPath("$.serialNumber").value("SN-A"));
    }

    @Test
    void serial_missingLedgerViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/serial-units/" + SERIAL_ID + "/traceability")
                        .header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }

    @Test
    void serial_unknown_returns404() throws Exception {
        when(traceabilityService.getSerialTraceability(SERIAL_ID))
                .thenThrow(new ResourceNotFoundException("InventorySerialUnit", SERIAL_ID.toString()));

        mockMvc.perform(get("/v1/inventory/serial-units/" + SERIAL_ID + "/traceability")
                        .header("X-Authorities", VIEW))
                .andExpect(status().isNotFound());
    }
}
