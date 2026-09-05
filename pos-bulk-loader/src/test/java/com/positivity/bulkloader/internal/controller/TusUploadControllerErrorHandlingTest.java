package com.positivity.bulkloader.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkloader.config.TestSecurityConfig;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.bulkloader.internal.service.TusUploadService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.HttpEncodingAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The TUS error contract after #1716 moved this module's advice onto the {@link
 * com.positivity.shared.error.ApiError} envelope.
 *
 * <p>These two responses are the only ones in {@link BulkLoaderExceptionHandler} that set a
 * protocol header of their own ({@code Tus-Resumable}) alongside the envelope, so they are the
 * ones where the envelope conversion could plausibly have dropped it. The tus.io resumable-upload
 * protocol requires {@code Tus-Resumable} on every response, error responses included — a client
 * that does not see it must treat the server as not speaking TUS at all — so the header and the
 * machine-readable {@code code} are asserted together.
 */
@WebMvcTest(controllers = TusUploadController.class, excludeAutoConfiguration = HttpEncodingAutoConfiguration.class)
@Import({TestSecurityConfig.class, WebCommonErrorAutoConfiguration.class})
@ActiveProfiles("test")
@DisplayName("TUS upload errors keep the ApiError envelope and the Tus-Resumable header (#1716)")
@SuppressWarnings({"java:S6813", "java:S1192"})
class TusUploadControllerErrorHandlingTest {

    private static final UUID UPLOAD_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";

    private static final byte[] CHUNK = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    /**
     * Sets the chunk Content-Type on the built request, paired with excluding
     * {@code HttpEncodingAutoConfiguration} above.
     *
     * <p>{@code MockHttpServletRequest.setCharacterEncoding} rewrites the Content-Type header to
     * append {@code ;charset=UTF-8}, and {@code CharacterEncodingFilter} calls it on every request,
     * after any builder or post-processor has run. {@link TusUploadController} compares that header
     * for exact equality — the tus.io protocol mandates a bare
     * {@code application/offset+octet-stream} — so the request would answer 415 and never reach the
     * error path under test. This is a mock-only artifact: a real container's
     * {@code setCharacterEncoding} affects parameter decoding and leaves the header alone.
     */
    private static RequestPostProcessor offsetOctetStream() {
        return request -> {
            request.setContentType(OFFSET_OCTET_STREAM);
            return request;
        };
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TusUploadService tusUploadService;

    @Test
    @WithMockUser(authorities = "bulkImport:upload:execute")
    @DisplayName("an offset conflict answers 409 TUS_OFFSET_CONFLICT and still carries Tus-Resumable")
    void anOffsetConflictAnswers409WithTheEnvelopeAndTheTusHeader() throws Exception {
        when(tusUploadService.appendChunk(eq(UPLOAD_ID), anyLong(), any(), anyLong()))
                .thenThrow(new TusOffsetConflictException(100L, 64L));

        mockMvc.perform(patch("/v1/tus/{uploadId}", UPLOAD_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", "100")
                        .header("Content-Length", "16")
                        .header("X-Correlation-Id", "corr-tus-conflict")
                        .content(CHUNK)
                        .with(offsetOctetStream()))
                .andExpect(status().isConflict())
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(jsonPath("$.code").value("TUS_OFFSET_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").value("corr-tus-conflict"))
                .andExpect(header().string("X-Correlation-Id", "corr-tus-conflict"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "bulkImport:upload:execute")
    @DisplayName("an expired upload answers 410 TUS_UPLOAD_EXPIRED and still carries Tus-Resumable")
    void anExpiredUploadAnswers410WithTheEnvelopeAndTheTusHeader() throws Exception {
        when(tusUploadService.appendChunk(eq(UPLOAD_ID), anyLong(), any(), anyLong()))
                .thenThrow(new TusUploadExpiredException(UPLOAD_ID));

        mockMvc.perform(patch("/v1/tus/{uploadId}", UPLOAD_ID)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", "0")
                        .header("Content-Length", "16")
                        .header("X-Correlation-Id", "corr-tus-expired")
                        .content(CHUNK)
                        .with(offsetOctetStream()))
                .andExpect(status().isGone())
                .andExpect(header().string("Tus-Resumable", "1.0.0"))
                .andExpect(jsonPath("$.code").value("TUS_UPLOAD_EXPIRED"))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.correlationId").value("corr-tus-expired"))
                .andExpect(header().string("X-Correlation-Id", "corr-tus-expired"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }
}
