package com.positivity.image.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.image.internal.exception.ImageValidationException;
import com.positivity.image.internal.security.ImagePermissions;
import com.positivity.image.internal.service.ImageService;
import com.positivity.image.internal.service.ImageStorageService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link ImageController#storeImage}: {@link
 * ImageValidationException} keeps the genuine-client-error contract (400 {@code
 * IMAGE_REQUEST_INVALID}, message echoed), while a bare {@code IllegalArgumentException} —
 * what Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on
 * malformed stored data, what a JPA attribute converter throws on corrupt stored JSON — is no
 * longer caught by this module's {@link com.positivity.image.internal.exception.ImageExceptionHandler}:
 * it falls through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler},
 * which answers a generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code
 * @AutoConfiguration} (it is not on the curated slice-test allowlist — an unrelated
 * {@code @AutoConfiguration} from another artifact is simply not imported by the slice), so
 * {@link WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real
 * fallback chain rather than asserting a weaker substitute.
 */
@WebMvcTest(ImageController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class ImageControllerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC);
    private static final String VALID_STORE_BODY = """
            {"filename":"tread.jpg","contentType":"image/jpeg","content":"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAY="}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageService imageService;

    @MockitoBean
    private ImageStorageService imageStorageService;

    @Test
    @WithMockUser(authorities = ImagePermissions.IMAGE_STORE)
    void anImageValidationFailureAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(imageStorageService.store(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new ImageValidationException("content is not valid base64: Illegal base64 character 2d"));

        mockMvc.perform(post("/v1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_STORE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("content is not valid base64: Illegal base64 character 2d"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message. It is an unexpected server-side
     * failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @WithMockUser(authorities = ImagePermissions.IMAGE_STORE)
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary =
                "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'contentHash' of "
                        + "'com.positivity.image.internal.entity.ImageEntity'";
        when(imageStorageService.store(anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(post("/v1/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_STORE_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("contentHash");
    }

    /** Clock for {@code ImageExceptionHandler} and {@code pos-web-common}'s advice, plus method security. */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
