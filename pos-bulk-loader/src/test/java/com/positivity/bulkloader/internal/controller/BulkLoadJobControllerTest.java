package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.service.BulkLoadJobService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(BulkLoadJobController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({ "java:S6813", "java:S100", "java:S1192" })
class BulkLoadJobControllerTest {

        private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

        @Autowired
        MockMvc mockMvc;

        @Autowired
        ObjectMapper objectMapper;

        @MockitoBean
        BulkLoadJobService bulkLoadJobService;

        // ─── POST /v1/bulk-jobs — 201 Created ────────────────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void createJob_validRequest_returns201() throws Exception {
                BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
                request.setFileName("products.csv");
                request.setDomainType(DomainType.CATALOG_PRODUCT);

                BulkLoadJobResponse response = BulkLoadJobResponse.builder()
                                .id(JOB_ID)
                                .operatorId("test-operator")
                                .fileName("products.csv")
                                .domainType(DomainType.CATALOG_PRODUCT)
                                .status(JobStatus.CREATED)
                                .build();

                when(bulkLoadJobService.createJob(any(), eq("test-operator"))).thenReturn(response);

                mockMvc.perform(post("/v1/bulk-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                                .andExpect(jsonPath("$.status").value("CREATED"));
        }

        @Test
        void createJob_withoutExecuteAuthority_returns403() throws Exception {
                BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
                request.setFileName("products.csv");
                request.setDomainType(DomainType.CATALOG_PRODUCT);

                mockMvc.perform(post("/v1/bulk-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Authorities", "bulkImport:status:read") // EXECUTE required
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void createJob_missingFileName_returns400() throws Exception {
                BulkLoadJobCreateRequest request = new BulkLoadJobCreateRequest();
                request.setDomainType(DomainType.CATALOG_PRODUCT);
                // fileName is missing — @NotBlank should fail validation

                mockMvc.perform(post("/v1/bulk-jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest());
        }

        // ─── GET /v1/bulk-jobs/{jobId} — 200 OK ──────────────────────────────────

        @Test
        @WithMockUser(authorities = "bulkImport:status:read")
        void getJob_whenExists_returns200() throws Exception {
                BulkLoadJobResponse response = BulkLoadJobResponse.builder()
                                .id(JOB_ID)
                                .operatorId("test-operator")
                                .status(JobStatus.UPLOADING)
                                .build();

                when(bulkLoadJobService.getJob(eq(JOB_ID), any())).thenReturn(response);

                mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                                .andExpect(jsonPath("$.status").value("UPLOADING"));
        }

        @Test
        @WithMockUser(authorities = "bulkImport:status:read")
        void getJob_whenNotFound_returns404() throws Exception {
                when(bulkLoadJobService.getJob(eq(JOB_ID), any()))
                                .thenThrow(new NoSuchElementException("BulkLoadJob not found: " + JOB_ID));

                mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID)).andExpect(status().isNotFound());
        }

        @Test
        void getJob_withoutReadAuthority_returns403() throws Exception {
                mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID)
                                .header("X-Authorities", "bulkImport:upload:execute")) // READ required
                                .andExpect(status().isForbidden());
        }

        // ─── GET /v1/bulk-jobs/{jobId} — cross-operator 404 ──────────────────────

        @Test
        void getJob_crossOperator_returns404() throws Exception {
                when(bulkLoadJobService.getJob(JOB_ID, "operator-b"))
                                .thenThrow(new NoSuchElementException("BulkLoadJob not found: " + JOB_ID));

                mockMvc.perform(get("/v1/bulk-jobs/{jobId}", JOB_ID)
                                .header("X-User", "operator-b")
                                .header("X-Authorities", "bulkImport:status:read"))
                                .andExpect(status().isNotFound());
        }

        // ─── POST /v1/bulk-jobs/{jobId}/cancel ───────────────────────────────────

        @Test
        @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
        void cancelJob_whenValid_returns200() throws Exception {
                BulkLoadJobResponse response = BulkLoadJobResponse.builder()
                                .id(JOB_ID)
                                .operatorId("test-operator")
                                .status(JobStatus.CANCELLED)
                                .build();

                when(bulkLoadJobService.cancelJob(JOB_ID, "test-operator")).thenReturn(response);

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/cancel", JOB_ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        // ─── POST /v1/bulk-jobs/{jobId}/cancel — non-owner 403 ───────────────────

        @Test
        void cancelJob_byNonOwner_returns403() throws Exception {
                when(bulkLoadJobService.cancelJob(JOB_ID, "other-operator"))
                                .thenThrow(new JobOwnershipViolationException(JOB_ID.toString()));

                mockMvc.perform(post("/v1/bulk-jobs/{jobId}/cancel", JOB_ID)
                                .header("X-User", "other-operator")
                                .header("X-Authorities", "bulkImport:upload:execute"))
                                .andExpect(status().isForbidden());
        }
}
