package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.FileStorageService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileUploadController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class FileUploadControllerTest {

        private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        BulkLoadJobService bulkLoadJobService;

        @MockitoBean
        FileStorageService fileStorageService;

        // ─── POST /v1/bulk-jobs/{jobId}/upload ───────────────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "BULK_IMPORT_EXECUTE")
        void uploadFile_validMultipart_returns200() throws Exception {
                MockMultipartFile file = new MockMultipartFile("file", "products.csv", "text/csv",
                                "sku,name\nABC-001,Widget".getBytes());
                BulkLoadJobResponse jobResponse = BulkLoadJobResponse.builder()
                                .id(JOB_ID)
                                .operatorId("test-operator")
                                .build();
                when(bulkLoadJobService.getJob(eq(JOB_ID), any())).thenReturn(jobResponse);
                when(fileStorageService.store(eq(JOB_ID), any(), any(), anyLong())).thenReturn("/uploads/products.csv");

                mockMvc.perform(multipart("/v1/bulk-jobs/{jobId}/upload", JOB_ID).file(file))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                                .andExpect(jsonPath("$.fileName").value("products.csv"));
        }

        @Test
        void uploadFile_withoutExecuteAuthority_returns403() throws Exception {
                MockMultipartFile file = new MockMultipartFile("file", "products.csv", "text/csv",
                                "sku,name\nABC-001,Widget".getBytes());

                mockMvc.perform(multipart("/v1/bulk-jobs/{jobId}/upload", JOB_ID)
                                .file(file)
                                .header("X-Authorities", "BULK_IMPORT_READ"))
                                .andExpect(status().isForbidden());
        }

        // ─── POST /v1/bulk-jobs/{jobId}/process ──────────────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "BULK_IMPORT_EXECUTE")
        void startProcessing_whenJobExists_returns200() throws Exception {
                BulkLoadJobResponse response = BulkLoadJobResponse.builder()
                                .id(JOB_ID)
                                .operatorId("test-operator")
                                .domainType(DomainType.CATALOG_PRODUCT)
                                .status(JobStatus.PROCESSING)
                                .build();

                when(bulkLoadJobService.getJob(eq(JOB_ID), any())).thenReturn(response);

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("PROCESSING"));
        }
}
