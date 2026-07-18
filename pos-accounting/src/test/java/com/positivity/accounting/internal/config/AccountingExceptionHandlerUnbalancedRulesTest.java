package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.exception.UnbalancedRulesException;
import com.positivity.accounting.internal.exception.UnbalancedRulesException.RuleViolation;
import com.positivity.shared.error.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit test for the {@code UNBALANCED_RULES} error mapping (story E1, issue
 * #945): split-group publish validation failures must surface as HTTP 422
 * with error code {@code UNBALANCED_RULES} and one {@code fieldError} per
 * violation.
 */
@DisplayName("AccountingExceptionHandler UNBALANCED_RULES mapping (E1)")
class AccountingExceptionHandlerUnbalancedRulesTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final AccountingExceptionHandler handler = new AccountingExceptionHandler(TEST_CLOCK);

    @Test
    @DisplayName("maps UnbalancedRulesException to 422 UNBALANCED_RULES with all violations as field errors")
    void mapsToUnprocessableContentWithFieldErrors() {
        UnbalancedRulesException ex = new UnbalancedRulesException(List.of(
                new RuleViolation(
                        "conditions[0].splitGroup[tax]",
                        "factorPercent values in split group 'tax' must sum to exactly 100 (lines [0, 1] sum to 90)"),
                new RuleViolation(
                        "conditions[1].lines[2].factorPercent",
                        "factorPercent requires a splitGroup — a line cannot declare a split factor "
                                + "outside a split group")));

        ResponseEntity<ApiError> response = handler.handleUnbalancedRules(ex, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ApiError body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("UNBALANCED_RULES");
        assertThat(body.status()).isEqualTo(422);
        assertThat(body.message()).contains("conditions[0].splitGroup[tax]");
        assertThat(body.fieldErrors()).hasSize(2);
        assertThat(body.fieldErrors().get(0).field()).isEqualTo("conditions[0].splitGroup[tax]");
        assertThat(body.fieldErrors().get(0).message()).contains("sum to exactly 100");
        assertThat(body.fieldErrors().get(1).field()).isEqualTo("conditions[1].lines[2].factorPercent");
        assertThat(body.correlationId()).isNotBlank();
    }
}
