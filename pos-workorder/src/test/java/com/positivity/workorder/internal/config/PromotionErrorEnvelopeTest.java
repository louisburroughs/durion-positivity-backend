package com.positivity.workorder.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.exception.CustomerApprovalInvalidException;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.PromotionValidationException.PromotionErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Issue #1477 — every promotion refusal answers with the canonical envelope: a machine-readable
 * code, a correlation id that matches the response header, and a status that says whether
 * retrying is worth it.
 */
@DisplayName("GlobalExceptionHandler — promotion refusals carry a code and a correlation id (#1477)")
class PromotionErrorEnvelopeTest {

    private static final String CORRELATION_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01";
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a02");
    private static final UUID ESTIMATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a03");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a04");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(clockProvider());

    @Test
    @DisplayName("an unreplicated customer verdict is a retryable 503 with Retry-After")
    void unreplicatedVerdictIsRetryable503() {
        ResponseEntity<ApiError> response = handler.handleCustomerRequirementsNotMet(
                CustomerRequirementsNotMetException.verdictUnavailable(CUSTOMER_ID), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(CustomerRequirementsNotMetException.UNAVAILABLE_CODE);
        assertThat(response.getBody().nextAction()).isNotBlank();
        assertCorrelated(response);
    }

    @Test
    @DisplayName("a negative customer verdict is a 409 with no Retry-After")
    void negativeVerdictIsConflict() {
        ResponseEntity<ApiError> response = handler.handleCustomerRequirementsNotMet(
                CustomerRequirementsNotMetException.requirementsNotMet(CUSTOMER_ID), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody().code()).isEqualTo(CustomerRequirementsNotMetException.NOT_MET_CODE);
        assertCorrelated(response);
    }

    @Test
    @DisplayName("an unknown estimate is a 404 naming the estimate")
    void unknownEstimateIsNotFound() {
        ResponseEntity<ApiError> response =
                handler.handleEstimateNotFound(new EstimateNotFoundException(ESTIMATE_ID), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(EstimateNotFoundException.ERROR_CODE);
        assertThat(response.getBody().message()).contains(ESTIMATE_ID.toString());
        assertCorrelated(response);
    }

    @Test
    @DisplayName("an unbacked approval is a 409 with its own code")
    void unbackedApprovalIsConflict() {
        ResponseEntity<ApiError> response = handler.handleCustomerApprovalInvalid(
                new CustomerApprovalInvalidException("no approval", WORKORDER_ID), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo(CustomerApprovalInvalidException.ERROR_CODE);
        assertThat(response.getBody().referenceId()).isEqualTo(WORKORDER_ID.toString());
        assertCorrelated(response);
    }

    @Test
    @DisplayName("a promotion precondition answers with the precondition's own code")
    void promotionValidationCarriesItsCode() {
        ResponseEntity<ApiError> response = handler.handlePromotionValidation(
                new PromotionValidationException(PromotionErrorCode.APPROVAL_EXPIRED, "approval expired"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo(PromotionErrorCode.APPROVAL_EXPIRED.name());
        assertCorrelated(response);
    }

    @Test
    @DisplayName("an ALREADY_PROMOTED that cannot resolve its workorder still names it")
    void alreadyPromotedNamesTheExistingWorkorder() {
        ResponseEntity<ApiError> response = handler.handlePromotionValidation(
                new PromotionValidationException(PromotionErrorCode.ALREADY_PROMOTED, "already promoted", WORKORDER_ID),
                request());

        assertThat(response.getBody().code()).isEqualTo(PromotionErrorCode.ALREADY_PROMOTED.name());
        assertThat(response.getBody().referenceId()).isEqualTo(WORKORDER_ID.toString());
    }

    @Test
    @DisplayName("a promotion validation that names no estimate is a 404")
    void estimateNotFoundPromotionCodeIsNotFound() {
        ResponseEntity<ApiError> response = handler.handlePromotionValidation(
                new PromotionValidationException(PromotionErrorCode.ESTIMATE_NOT_FOUND, "gone"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an inconsistent idempotency record is an enveloped 500, not a bodiless one")
    void idempotencyInconsistencyIsEnveloped() {
        ResponseEntity<ApiError> response = handler.handlePromotionIdempotencyInconsistency(
                new PromotionIdempotencyInconsistencyException(WORKORDER_ID), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(PromotionIdempotencyInconsistencyException.ERROR_CODE);
        assertThat(response.getBody().supportAction()).isNotBlank();
        assertCorrelated(response);
    }

    /** The id in the body is the one in the header, which is the one the log line carries. */
    private static void assertCorrelated(ResponseEntity<ApiError> response) {
        assertThat(response.getBody().correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(CORRELATION_ID);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", CORRELATION_ID);
        return request;
    }

    private static ObjectProvider<Clock> clockProvider() {
        return new ObjectProvider<>() {
            @Override
            public Clock getObject() {
                return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
            }

            @Override
            public Clock getObject(Object... args) {
                return getObject();
            }

            @Override
            public Clock getIfAvailable() {
                return getObject();
            }

            @Override
            public Clock getIfUnique() {
                return getObject();
            }
        };
    }
}
