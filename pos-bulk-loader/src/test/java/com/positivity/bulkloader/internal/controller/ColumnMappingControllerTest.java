package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ColumnMappingUpdateRequest;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.service.ColumnMappingService;
import java.util.List;
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

@WebMvcTest(ColumnMappingController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class ColumnMappingControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ColumnMappingService columnMappingService;

    // ─── GET /v1/bulk-jobs/{jobId}/mappings — 200 OK ─────────────────────────

    @Test
    @WithMockUser(authorities = "BULK_IMPORT_READ")
    void getMappings_returnsList() throws Exception {
        ColumnMappingResponse mapping = ColumnMappingResponse.builder()
                .id(MAPPING_ID)
                .jobId(JOB_ID)
                .sourceColumn("sku")
                .targetField("productSku")
                .confidence(0.9)
                .overriddenByUser(false)
                .build();

        when(columnMappingService.getMappingsForJob(eq(JOB_ID), any())).thenReturn(List.of(mapping));

        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/mappings", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].sourceColumn").value("sku"))
                .andExpect(jsonPath("$[0].targetField").value("productSku"));
    }

    @Test
    void getMappings_withoutReadAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/mappings", JOB_ID)
                        .header("X-Authorities", "BULK_IMPORT_EXECUTE")) // READ required
                .andExpect(status().isForbidden());
    }

    // ─── GET /v1/bulk-jobs/{jobId}/mappings — non-owner 403 ───────────────────

    @Test
    void getMappings_byNonOwner_returns403() throws Exception {
        when(columnMappingService.getMappingsForJob(eq(JOB_ID), eq("other-operator")))
                .thenThrow(new JobOwnershipViolationException(JOB_ID.toString()));

        mockMvc.perform(get("/v1/bulk-jobs/{jobId}/mappings", JOB_ID)
                        .header("X-User", "other-operator")
                        .header("X-Authorities", "BULK_IMPORT_READ"))
                .andExpect(status().isForbidden());
    }

    // ─── PUT /v1/bulk-jobs/{jobId}/mappings — 200 OK ─────────────────────────

    @Test
    @WithMockUser(authorities = "BULK_IMPORT_EXECUTE")
    void approveMappings_validPayload_returns200() throws Exception {
        ColumnMappingUpdateRequest update = new ColumnMappingUpdateRequest();
        update.setSourceColumn("name");
        update.setTargetField("productName");

        ColumnMappingApproveRequest request = new ColumnMappingApproveRequest();
        request.setMappings(List.of(update));

        ColumnMappingResponse saved = ColumnMappingResponse.builder()
                .id(MAPPING_ID)
                .jobId(JOB_ID)
                .sourceColumn("name")
                .targetField("productName")
                .confidence(1.0)
                .overriddenByUser(true)
                .build();

        when(columnMappingService.approveMappings(eq(JOB_ID), any(), any())).thenReturn(List.of(saved));

        mockMvc.perform(put("/v1/bulk-jobs/{jobId}/mappings", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].overriddenByUser").value(true))
                .andExpect(jsonPath("$[0].confidence").value(1.0));
    }

    @Test
    @WithMockUser(authorities = "BULK_IMPORT_EXECUTE")
    void approveMappings_emptyMappingsList_returns400() throws Exception {
        ColumnMappingApproveRequest request = new ColumnMappingApproveRequest();
        request.setMappings(List.of()); // @NotEmpty should fail

        mockMvc.perform(put("/v1/bulk-jobs/{jobId}/mappings", JOB_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
