package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.service.BackorderService;
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
 * Web-slice tests for BackorderController (odoo-parity G1, #1046): permission gating on the
 * quantity-sensitive backorder reads (reused {@code inventory:shortage:view}).
 */
@WebMvcTest(BackorderController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class BackorderControllerTest {

    private static final UUID BACKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String VIEW = "inventory:shortage:view";
    private static final String OTHER = "inventory:on_hand:view";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    BackorderService backorderService;

    @org.junit.jupiter.api.BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(java.time.Instant.parse("2026-07-23T00:00:00Z"));
    }

    private BackorderResponse sample() {
        return BackorderResponse.builder()
                .backorderId(BACKORDER_ID)
                .workorderLineId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
                .sku("PART-BRAKE-PAD-01")
                .locationId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
                .quantityShort(5)
                .status(BackorderStatus.OPEN)
                .build();
    }

    @Test
    void list_withViewAuthority_returns200() throws Exception {
        when(backorderService.listBackorders(any(), any(), any(), any(), any())).thenReturn(List.of(sample()));

        mockMvc.perform(get("/v1/inventory/backorders").header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].backorderId").value(BACKORDER_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void list_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/backorders").header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOne_withViewAuthority_returns200() throws Exception {
        when(backorderService.getBackorder(BACKORDER_ID)).thenReturn(sample());

        mockMvc.perform(get("/v1/inventory/backorders/" + BACKORDER_ID).header("X-Authorities", VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityShort").value(5));
    }

    @Test
    void getOne_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/backorders/" + BACKORDER_ID).header("X-Authorities", OTHER))
                .andExpect(status().isForbidden());
    }
}
