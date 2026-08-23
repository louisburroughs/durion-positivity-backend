package com.positivity.web.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalApiExceptionHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-23T12:00:00Z");

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalApiExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)))
                .build();
    }

    @Test
    void unmappedRuntimeExceptionReturnsEnvelopedInternalErrorWithGeneratedCorrelationId() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.timestamp").value(FIXED_INSTANT.toString()))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void internalErrorBodyDoesNotLeakExceptionDetails() throws Exception {
        String body =
                mockMvc.perform(get("/test/boom")).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret detail");
    }

    @Test
    void clientCorrelationIdIsEchoedInHeaderAndBody() throws Exception {
        mockMvc.perform(get("/test/boom").header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("X-Correlation-Id", "corr-123"))
                .andExpect(jsonPath("$.correlationId").value("corr-123"));
    }

    @Test
    void uniqueConstraintViolationReturns409NamingTheConstraint() throws Exception {
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.message")
                        .value("Duplicate value violates unique constraint 'uq_party_customer_number'"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void notNullViolationOnClientColumnReturns422NamingTheColumn() throws Exception {
        mockMvc.perform(get("/test/missing-value"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_VALUE"))
                .andExpect(jsonPath("$.message").value("Required value is missing for column 'customer_number'"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void notNullViolationOnServerPopulatedAuditColumnStaysEnveloped500() throws Exception {
        mockMvc.perform(get("/test/missing-audit-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void checkConstraintViolationReturns422() throws Exception {
        mockMvc.perform(get("/test/check-violation"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"))
                .andExpect(jsonPath("$.message").value("Value violates check constraint 'chk_quantity_positive'"));
    }

    @Test
    void foreignKeyViolationReturns409() throws Exception {
        mockMvc.perform(get("/test/fk-violation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_CONFLICT"));
    }

    @Test
    void unclassifiableIntegrityViolationStaysEnveloped500() throws Exception {
        mockMvc.perform(get("/test/unknown-integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void beanValidationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void malformedRequestBodyReturns400NotAnEnveloped500() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void invalidEnumValueInBodyReturns400() throws Exception {
        mockMvc.perform(post("/test/enum")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"NOT_A_LEVEL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void parameterTypeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/test/typed/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid parameter value"));
    }

    @Test
    void frameworkErrorResponseExceptionKeepsItsStatusInsteadOfCollapsingTo500() throws Exception {
        mockMvc.perform(post("/test/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("HTTP method not supported for this path"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void springSecurityExceptionsAreRethrownForTheSecurityFilterChain() {
        assertThatThrownBy(() -> mockMvc.perform(get("/test/denied")))
                .isInstanceOf(jakarta.servlet.ServletException.class)
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    private static DataIntegrityViolationException integrityViolation(String message, String sqlState) {
        return new DataIntegrityViolationException("could not execute statement", new SQLException(message, sqlState));
    }

    record ValidatedRequest(@NotBlank String name) {}

    enum TrackingLevel {
        NONE,
        SERIAL
    }

    record EnumRequest(TrackingLevel level) {}

    @RestController
    static class ThrowingController {

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("secret detail");
        }

        @GetMapping("/test/duplicate")
        String duplicate() {
            throw integrityViolation(
                    "ERROR: duplicate key value violates unique constraint \"uq_party_customer_number\"", "23505");
        }

        @GetMapping("/test/missing-value")
        String missingValue() {
            throw integrityViolation(
                    "ERROR: null value in column \"customer_number\" of relation \"party\" violates not-null"
                            + " constraint",
                    "23502");
        }

        @GetMapping("/test/missing-audit-value")
        String missingAuditValue() {
            throw integrityViolation(
                    "ERROR: null value in column \"created_by\" of relation \"purchase_order\" violates not-null"
                            + " constraint",
                    "23502");
        }

        @GetMapping("/test/check-violation")
        String checkViolation() {
            throw integrityViolation(
                    "ERROR: new row for relation \"order_line\" violates check constraint \"chk_quantity_positive\"",
                    "23514");
        }

        @GetMapping("/test/fk-violation")
        String fkViolation() {
            throw integrityViolation(
                    "ERROR: insert or update on table \"order_line\" violates foreign key constraint"
                            + " \"fk_order_line_order\"",
                    "23503");
        }

        @GetMapping("/test/unknown-integrity")
        String unknownIntegrity() {
            throw new DataIntegrityViolationException("no sql cause", new RuntimeException("boom"));
        }

        @PostMapping("/test/validated")
        String validated(@jakarta.validation.Valid @RequestBody ValidatedRequest request) {
            return request.name();
        }

        @PostMapping("/test/enum")
        String enumBody(@RequestBody EnumRequest request) {
            return request.level().name();
        }

        @GetMapping("/test/typed/{count}")
        String typed(@org.springframework.web.bind.annotation.PathVariable int count) {
            return Integer.toString(count);
        }

        @GetMapping("/test/denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }
    }
}
