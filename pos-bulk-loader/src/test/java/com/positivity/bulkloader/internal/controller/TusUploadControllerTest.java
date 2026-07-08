package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.service.TusUploadService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.ForwardedHeaderFilter;

@WebMvcTest(TusUploadController.class)
@Import({TestSecurityConfig.class, TusUploadControllerTest.ForwardedHeaderTestConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100"})
class TusUploadControllerTest {

    /**
     * The production app registers {@link ForwardedHeaderFilter} via
     * {@code server.forward-headers-strategy: framework} (web-server auto-configuration, not part
     * of the {@code @WebMvcTest} slice), so register it here to exercise X-Forwarded-* handling.
     */
    @TestConfiguration
    static class ForwardedHeaderTestConfig {
        @Bean
        ForwardedHeaderFilter forwardedHeaderFilter() {
            return new ForwardedHeaderFilter();
        }
    }

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID UPLOAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TusUploadService tusUploadService;

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_withoutForwardedHeaders_returnsAbsoluteLocationForThisServer() throws Exception {
        when(tusUploadService.createUpload(eq(JOB_ID), eq("upload.bin"), anyLong(), eq("test-operator")))
                .thenReturn(new TusUploadService.Created(UPLOAD_ID, Instant.parse("2026-07-08T12:00:00Z")));

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "1024"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/v1/tus/" + UPLOAD_ID))
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(header().string("Upload-Offset", "0"));
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_withForwardedHeaders_returnsPublicAbsoluteLocation() throws Exception {
        when(tusUploadService.createUpload(eq(JOB_ID), eq("upload.bin"), anyLong(), eq("test-operator")))
                .thenReturn(new TusUploadService.Created(UPLOAD_ID, Instant.parse("2026-07-08T12:00:00Z")));

        // Headers as set by the reverse proxy (/api) + API gateway (/bulk-loader StripPrefix):
        // the Location must be the public TUS resource URL with the full prefix chain intact.
        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "1024")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "durionpos.org")
                        .header("X-Forwarded-Prefix", "/api,/bulk-loader"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "https://durionpos.org/api/bulk-loader/v1/tus/" + UPLOAD_ID));
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void createUpload_withGatewayOnlyForwardedHeaders_returnsGatewayAbsoluteLocation() throws Exception {
        when(tusUploadService.createUpload(eq(JOB_ID), eq("upload.bin"), anyLong(), eq("test-operator")))
                .thenReturn(new TusUploadService.Created(UPLOAD_ID, Instant.parse("2026-07-08T12:00:00Z")));

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/tus", JOB_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "1024")
                        .header("X-Forwarded-Proto", "http")
                        .header("X-Forwarded-Host", "gateway:8080")
                        .header("X-Forwarded-Prefix", "/bulk-loader"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://gateway:8080/bulk-loader/v1/tus/" + UPLOAD_ID));
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
