package com.positivity.image.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.image.internal.dto.StoreImageRequest;
import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A malformed store request is the caller's problem, and says so (CAP-324 #1257).
 *
 * <p>A 500 for a bad body is worse than untidy: it tells the caller to retry a request that will
 * fail identically, and it files a client error wherever server errors are alerted on.
 */
@DisplayName("ImageExceptionHandler — a bad request is a 400, not a 500 (#1257)")
class ImageExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private final ImageExceptionHandler handler = new ImageExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("content that is not base64 answers 400 in the standard envelope")
    void invalidBase64IsABadRequest() {
        StoreImageRequest request =
                new StoreImageRequest("tread.jpg", "image/jpeg", "not base64 at all!!", null, List.of());

        ResponseEntity<ApiError> response =
                handler.handleBadRequest(catchImageValidation(request::decodedContent), servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_REQUEST_INVALID");
        assertThat(response.getBody().message()).contains("base64");
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().timestamp()).isEqualTo(NOW.toString());
    }

    @Test
    @DisplayName("an empty body answers 400, since refusing it is the point")
    void emptyContentIsABadRequest() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(
                new ImageValidationException("refusing to store an empty image; it would never be retried"),
                servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("a supplied correlation id is echoed rather than replaced")
    void correlationIdIsPreserved() {
        MockHttpServletRequest request = servletRequest();
        request.addHeader("X-Correlation-Id", "corr-from-caller");

        ResponseEntity<ApiError> response = handler.handleBadRequest(new ImageValidationException("bad"), request);

        // Replacing it would break the one thread a caller has for following its own request
        // through the platform.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-from-caller");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-from-caller");
    }

    @Test
    @DisplayName("a record pointing at content nobody holds is a 500, not a client error")
    void brokenStateIsAServerError() {
        // A broken row must not send whoever hit it looking at their own request.
        ResponseEntity<ApiError> response = handler.handleBrokenState(
                new IllegalStateException("Image 7 references content abc which is not stored"), servletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IMAGE_STATE_INVALID");
    }

    private static MockHttpServletRequest servletRequest() {
        return new MockHttpServletRequest("POST", "/v1/images");
    }

    private static ImageValidationException catchImageValidation(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected an ImageValidationException");
        } catch (ImageValidationException e) {
            return e;
        }
    }
}
