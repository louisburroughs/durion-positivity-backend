package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.receiving.CrossDockWorkorderSearchResultDto;
import com.positivity.inventory.internal.receiving.service.ReceivingService;
import java.time.Instant;
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
 * Web-slice tests for {@code ReceivingController.searchCrossDockWorkorders} (#2211): the same
 * dual-authority gate ({@code inventory:receiving:complete} AND {@code inventory:issue:parts}) as
 * {@code crossDockLineToWorkorder}.
 */
@WebMvcTest(ReceivingController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S1192"})
class ReceivingWorkorderSearchControllerTest {

    private static final String RECEIVING_COMPLETE = "inventory:receiving:complete";
    private static final String ISSUE_PARTS = "inventory:issue:parts";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    ReceivingService receivingService;

    private CrossDockWorkorderSearchResultDto sample() {
        return CrossDockWorkorderSearchResultDto.builder()
                .workorderId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
                .workorderNumber("WO-0042")
                .status("IN_PROGRESS")
                .partLineCount(2)
                .updatedAt(Instant.parse("2026-01-15T09:30:00Z"))
                .build();
    }

    @Test
    void search_withBothAuthorities_returns200() throws Exception {
        when(receivingService.searchCrossDockWorkorders(isNull())).thenReturn(List.of(sample()));

        mockMvc.perform(get("/v1/inventory/receiving/workorders")
                        .header("X-Authorities", RECEIVING_COMPLETE + "," + ISSUE_PARTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workorderNumber").value("WO-0042"))
                .andExpect(jsonPath("$[0].partLineCount").value(2));
    }

    @Test
    void search_missingIssuePartsAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/receiving/workorders").header("X-Authorities", RECEIVING_COMPLETE))
                .andExpect(status().isForbidden());
    }

    @Test
    void search_missingReceivingCompleteAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/receiving/workorders").header("X-Authorities", ISSUE_PARTS))
                .andExpect(status().isForbidden());
    }

    @Test
    void search_withQueryParam_passesQueryThrough() throws Exception {
        when(receivingService.searchCrossDockWorkorders("WO-004")).thenReturn(List.of(sample()));

        mockMvc.perform(get("/v1/inventory/receiving/workorders")
                        .param("query", "WO-004")
                        .header("X-Authorities", RECEIVING_COMPLETE + "," + ISSUE_PARTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workorderId").value("00000000-0000-0000-0000-0000000000a1"));
    }

    @Test
    void search_noMatches_returnsEmptyArray() throws Exception {
        when(receivingService.searchCrossDockWorkorders(any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/inventory/receiving/workorders")
                        .header("X-Authorities", RECEIVING_COMPLETE + "," + ISSUE_PARTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
