package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.dto.AuditRecordResponse;
import com.positivity.bulkloader.internal.dto.BulkCorrectionItem;
import com.positivity.bulkloader.internal.dto.BulkCorrectionRequest;
import com.positivity.bulkloader.internal.dto.BulkCorrectionResponse;
import com.positivity.bulkloader.internal.dto.CorrectionResultDto;
import com.positivity.bulkloader.internal.enums.CorrectionStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.ReviewQueueService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewQueueController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({ "java:S6813", "java:S100" })
class ReviewQueueControllerTest {

        private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");
        private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000015");

        @Autowired
        MockMvc mockMvc;

        @Autowired
        ObjectMapper objectMapper;

        @MockitoBean
        BulkLoadJobService bulkLoadJobService;

        @MockitoBean
        ReviewQueueService reviewQueueService;

        // ─── GET /v1/bulk-jobs/{jobId}/audit ─────────────────────────────────────

        @Test
        @WithMockUser(authorities = "bulkImport:status:read")
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
                                .header("X-Authorities", "bulkImport:upload:execute")) // READ required
                                .andExpect(status().isForbidden());
        }

        // ─── GET /v1/bulk-jobs/{jobId}/error-report ───────────────────────────────

        @Test
        @WithMockUser(authorities = "bulkImport:status:read")
        void downloadErrorReport_returns200WithContentDispositionAttachment() throws Exception {
                String csv = "row_number,entity_type,review_status,reason_codes,original_values\n";
                when(reviewQueueService.generateErrorReport(JOB_ID)).thenReturn(new ByteArrayResource(csv.getBytes()));

                mockMvc.perform(get("/v1/bulk-jobs/{jobId}/error-report", JOB_ID))
                                .andExpect(status().isOk())
                                .andExpect(header().string(
                                                "Content-Disposition",
                                                "attachment; filename=\"error-report-" + JOB_ID + ".csv\""));
        }

        @Test
        void downloadErrorReport_withoutReadAuthority_returns403() throws Exception {
                mockMvc.perform(get("/v1/bulk-jobs/{jobId}/error-report", JOB_ID)
                                .header("X-Authorities", "bulkImport:upload:execute")) // READ required
                                .andExpect(status().isForbidden());
        }

        // ─── POST /v1/bulk-jobs/{jobId}/corrections ───────────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitCorrections_whenValid_returns201() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                                .corrections(List.of(item))
                                .build();
                BulkCorrectionResponse response = BulkCorrectionResponse.builder()
                                .jobId(JOB_ID)
                                .submittedCount(1)
                                .acceptedCount(1)
                                .rejectedCount(0)
                                .rejections(Collections.emptyList())
                                .build();

                when(reviewQueueService.submitCorrections(eq(JOB_ID), any(), eq("test-operator")))
                                .thenReturn(response);

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                                .andExpect(jsonPath("$.submittedCount").value(1))
                                .andExpect(jsonPath("$.acceptedCount").value(1));
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitCorrections_whenJobInWrongState_returns409() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                                .corrections(List.of(item))
                                .build();

                when(reviewQueueService.submitCorrections(eq(JOB_ID), any(), eq("test-operator")))
                                .thenThrow(new IllegalStateException(
                                                "Corrections can only be submitted for jobs in FAILED state"));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isConflict());
        }

        @Test
        void submitCorrections_withoutExecuteAuthority_returns403() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                                .corrections(List.of(item))
                                .build();

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Authorities", "bulkImport:status:read") // EXECUTE required
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitCorrections_whenJobNotFound_returns404() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                                .corrections(List.of(item))
                                .build();

                when(reviewQueueService.submitCorrections(eq(JOB_ID), any(), eq("test-operator")))
                                .thenThrow(new NoSuchElementException("BulkLoadJob not found: " + JOB_ID));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isNotFound());
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitCorrections_withEmptyCorrections_returns400() throws Exception {
                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "corrections": []
                                                }
                                                """))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void submitCorrections_whenNotOwner_returns403() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                BulkCorrectionRequest request = BulkCorrectionRequest.builder()
                                .corrections(List.of(item))
                                .build();

                when(reviewQueueService.submitCorrections(eq(JOB_ID), any(), eq("other-operator")))
                                .thenThrow(new JobOwnershipViolationException(JOB_ID.toString()));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User", "other-operator")
                                .header("X-Authorities", "bulkImport:upload:execute")
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }

        // ─── POST /v1/bulk-jobs/{jobId}/corrections/single ────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitSingleCorrection_whenValid_returns201WithAccepted() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();
                CorrectionResultDto result = CorrectionResultDto.builder()
                                .auditRecordId(AUDIT_ID)
                                .status(CorrectionStatus.ACCEPTED)
                                .build();

                when(reviewQueueService.submitSingleCorrection(eq(JOB_ID), any(), eq("test-operator")))
                                .thenReturn(result);

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections/single", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(item)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitSingleCorrection_whenJobInWrongState_returns409() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();

                when(reviewQueueService.submitSingleCorrection(eq(JOB_ID), any(), eq("test-operator")))
                                .thenThrow(new IllegalStateException("Job is not in FAILED state"));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections/single", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(item)))
                                .andExpect(status().isConflict());
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void submitSingleCorrection_whenJobNotFound_returns404() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();

                when(reviewQueueService.submitSingleCorrection(eq(JOB_ID), any(), eq("test-operator")))
                                .thenThrow(new NoSuchElementException("BulkLoadJob not found: " + JOB_ID));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections/single", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(item)))
                                .andExpect(status().isNotFound());
        }

        @Test
        void submitSingleCorrection_withoutExecuteAuthority_returns403() throws Exception {
                BulkCorrectionItem item = BulkCorrectionItem.builder()
                                .auditRecordId(AUDIT_ID)
                                .correctedData(Map.of("sku", "PROD-001"))
                                .build();

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/corrections/single", JOB_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Authorities", "bulkImport:status:read") // EXECUTE required
                                .content(objectMapper.writeValueAsString(item)))
                                .andExpect(status().isForbidden());
        }
}
