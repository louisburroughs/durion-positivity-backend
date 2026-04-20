package com.positivity.bulkloader.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.ReviewQueueService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewQueueController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class ReviewQueueControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");
    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000015");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BulkLoadJobService bulkLoadJobService;

    @MockitoBean
    ReviewQueueService reviewQueueService;

    // ─── GET /v1/bulk-jobs/{jobId}/audit ─────────────────────────────────────

    @Test
    @WithMockUser(authorities = "BULK_IMPORT_READ")
    void getAuditRecords_returnsList() throws Exception {
        AuditRecordResponse auditRecord = AuditRecordResponse.builder()
                .id(AUDIT_ID)
                .jobId(JOB_ID)
                .entityType("PRODUCT")
                .rowNumber(1L)
                .reviewStatus(ReviewStatus.PENDING)
                .build();

        when(reviewQueueService.getAuditRecords(JOB_ID)).thenReturn(List.of(auditRecord));

        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/audit", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].entityType").value("PRODUCT"))
                .andExpect(jsonPath("$[0].reviewStatus").value("PENDING"));
    }

    @Test
    void getAuditRecords_withoutReadAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/audit", JOB_ID)
                .header("X-Authorities", "BULK_IMPORT_EXECUTE")) // READ required
                .andExpect(status().isForbidden());
    }

    // ─── GET /v1/bulk-jobs/{jobId}/error-report ───────────────────────────────

    @Test
    @WithMockUser(authorities = "BULK_IMPORT_READ")
    void downloadErrorReport_returns200WithContentDispositionAttachment() throws Exception {
        String csv = "row_number,entity_type,review_status,reason_codes,original_values\n";
        when(reviewQueueService.generateErrorReport(JOB_ID)).thenReturn(new ByteArrayResource(csv.getBytes()));

        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/error-report", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition", "attachment; filename=\"error-report-" + JOB_ID + ".csv\""));
    }

    @Test
    void downloadErrorReport_withoutReadAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/error-report", JOB_ID)
                .header("X-Authorities", "BULK_IMPORT_EXECUTE")) // READ required
                .andExpect(status().isForbidden());
    }
}
