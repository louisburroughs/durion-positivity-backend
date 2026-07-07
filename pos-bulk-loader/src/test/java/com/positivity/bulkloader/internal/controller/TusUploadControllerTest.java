package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.service.TusUploadService;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TusUploadController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
class TusUploadControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TusUploadService tusUploadService;

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_returnsGatewaySafeRelativeLocation() throws Exception {
        when(tusUploadService.createUpload(eq(JOB_ID), eq("upload.bin"), anyLong(), eq("test-operator")))
                .thenReturn(new TusUploadService.Created(UPLOAD_ID, Instant.parse("2026-07-08T12:00:00Z")));

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "1024"))
                .andExpect(status().isCreated())
                // Relative reference: resolves against the public creation URL regardless of
                // gateway/proxy path prefixes.
                .andExpect(header().string("Location", "../../tus/" + UPLOAD_ID))
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(header().string("Upload-Offset", "0"));
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_relativeLocation_resolvesToPublicTusUrl() throws Exception {
        when(tusUploadService.createUpload(eq(JOB_ID), eq("upload.bin"), anyLong(), eq("test-operator")))
                .thenReturn(new TusUploadService.Created(UPLOAD_ID, Instant.parse("2026-07-08T12:00:00Z")));

        String location = mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "1024"))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        // RFC 3986 resolution against the gateway-facing creation URL must land on the
        // public TUS resource URL, keeping the /api/bulk-loader prefix intact.
        URI publicCreationUrl = URI.create("https://durionpos.org/api/bulk-loader/v1/bulk-jobs/" + JOB_ID + "/tus");
        URI resolved = publicCreationUrl.resolve(location);
        org.assertj.core.api.Assertions.assertThat(resolved)
                .hasToString("https://durionpos.org/api/bulk-loader/v1/tus/" + UPLOAD_ID);
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_unsupportedTusVersion_returns412() throws Exception {
        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "0.2.2")
                        .header("Upload-Length", "1024"))
                .andExpect(status().isPreconditionFailed());
    }
}
