package com.positivity.warranty.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shared.error.ApiError;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Concurrent-write conflicts on the versioned claim aggregate must map to a retryable 409
 * {@code CONFLICT} ApiError, never the 500 {@code INTERNAL_ERROR} catch-all
 * (docs/ERROR_ENVELOPE.md; mirrors pos-catalog).
 */
class WarrantyExceptionHandlerTest {

    private final WarrantyExceptionHandler handler =
            new WarrantyExceptionHandler(Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void objectOptimisticLockingFailureMapsTo409Conflict() {
        ResponseEntity<ApiError> response = handler.handleOptimisticLockConflict(
                new ObjectOptimisticLockingFailureException(WarrantyClaim.class, "id"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
        assertThat(response.getBody().status()).isEqualTo(409);
    }

    @Test
    void jpaOptimisticLockExceptionMapsTo409Conflict() {
        ResponseEntity<ApiError> response =
                handler.handleOptimisticLockConflict(new OptimisticLockException("stale"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONFLICT");
    }
}
