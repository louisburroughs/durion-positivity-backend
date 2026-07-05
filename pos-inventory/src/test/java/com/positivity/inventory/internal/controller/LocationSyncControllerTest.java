package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.service.LocationSyncService;
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
 * Web-slice tests for LocationSyncController (CAP-214 #40).
 */
@WebMvcTest(LocationSyncController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class LocationSyncControllerTest {

    private static final UUID SYNC_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID SYNC_LOG_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    LocationSyncService locationSyncService;

    // ─── POST /v1/inventory/locations/sync ───────────────────────────────────

    @Test
    void triggerLocationSync_authorized_returns202WithRunSummary() throws Exception {
        when(locationSyncService.triggerSync(any(), eq("key-1"), isNull()))
                .thenReturn(LocationSyncRunResponse.builder()
                        .syncRunId(SYNC_RUN_ID)
                        .outcome("OK")
                        .locationsProcessed(3)
                        .locationsCreated(1)
                        .locationsUpdated(1)
                        .locationsUnchanged(1)
                        .locationsFailed(0)
                        .startedAt(Instant.parse("2026-07-05T12:00:00Z"))
                        .completedAt(Instant.parse("2026-07-05T12:00:05Z"))
                        .build());

        mockMvc.perform(post("/v1/inventory/locations/sync")
                        .header("X-Authorities", "inventory:location:sync")
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.syncRunId").value(SYNC_RUN_ID.toString()))
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.locationsProcessed").value(3));
    }

    @Test
    void triggerLocationSync_missingSyncAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/locations/sync").header("X-Authorities", "inventory:location:view"))
                .andExpect(status().isForbidden());
    }

    // ─── GET /v1/inventory/sync-logs ─────────────────────────────────────────

    @Test
    void listSyncLogs_withSdkPagingAliases_returnsPlainArray() throws Exception {
        when(locationSyncService.listSyncLogs(eq(LocationSyncOutcome.OK), eq(1), eq(20)))
                .thenReturn(List.of(SyncLogResponse.builder()
                        .syncLogId(SYNC_LOG_ID)
                        .syncRunId(SYNC_RUN_ID)
                        .scope("RUN")
                        .outcome("OK")
                        .correlationId("corr-1")
                        .createdAt(Instant.parse("2026-07-05T12:00:05Z"))
                        .build()));

        mockMvc.perform(get("/v1/inventory/sync-logs")
                        .header("X-Authorities", "inventory:location:view")
                        .param("outcome", "OK")
                        .param("pageIndex", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].syncLogId").value(SYNC_LOG_ID.toString()))
                .andExpect(jsonPath("$[0].syncRunId").value(SYNC_RUN_ID.toString()))
                .andExpect(jsonPath("$[0].outcome").value("OK"))
                .andExpect(jsonPath("$[0].correlationId").value("corr-1"));

        verify(locationSyncService).listSyncLogs(LocationSyncOutcome.OK, 1, 20);
    }

    @Test
    void listSyncLogs_noParams_defaultsToFirstPageOf50() throws Exception {
        when(locationSyncService.listSyncLogs(isNull(), eq(0), eq(50))).thenReturn(List.of());

        mockMvc.perform(get("/v1/inventory/sync-logs").header("X-Authorities", "inventory:location:view"))
                .andExpect(status().isOk());

        verify(locationSyncService).listSyncLogs(null, 0, 50);
    }

    // ─── GET /v1/inventory/sync-logs/{syncLogId} ─────────────────────────────

    @Test
    void getSyncLog_unknownId_returns404() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-05T12:00:00Z"));
        when(locationSyncService.getSyncLog(SYNC_LOG_ID))
                .thenThrow(new ResourceNotFoundException("Sync log", SYNC_LOG_ID.toString()));

        mockMvc.perform(get("/v1/inventory/sync-logs/{id}", SYNC_LOG_ID)
                        .header("X-Authorities", "inventory:location:view"))
                .andExpect(status().isNotFound());
    }
}
