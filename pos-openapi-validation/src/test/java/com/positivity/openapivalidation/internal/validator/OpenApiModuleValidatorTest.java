package com.positivity.openapivalidation.internal.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiModuleValidatorTest {

    private final OpenApiModuleValidator validator = new OpenApiModuleValidator();

    @Test
    void reportsMissingSummaryAndDescription() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/missing-summary.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.REPORT_ONLY, "baseline gap"));

        assertThat(issues)
                .extracting(OpenApiValidationIssue::message)
                .contains(
                        "pos-documents GET /v1/documents: missing summary",
                        "pos-documents GET /v1/documents: missing description");
    }

    @Test
    void reportsMissingDescriptionWhenSummaryIsPresent() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/missing-description.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.REPORT_ONLY, "baseline gap"));

        assertThat(issues)
                .extracting(OpenApiValidationIssue::message)
                .contains("pos-documents GET /v1/documents: missing description")
                .doesNotContain("pos-documents GET /v1/documents: missing summary");
    }

    @Test
    void reportsMissingPathsForRequiredProducer() {
        var issues = validator.validate(
                "pos-api-gateway",
                Path.of("src/test/resources/openapi/fixtures/module/missing-paths.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null));

        assertThat(issues)
                .singleElement()
                .extracting(OpenApiValidationIssue::message)
                .isEqualTo("pos-api-gateway: missing paths section");
    }

    @Test
    void throwsForMissingSpecFile() {
        assertThatThrownBy(() -> validator.validate(
                        "pos-order",
                        Path.of("src/test/resources/openapi/fixtures/module/does-not-exist.yaml"),
                        new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos-order")
                .hasMessageContaining("not found");
    }

    @Test
    void returnsNoIssuesForCompliantModule() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/clean-module.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null));

        assertThat(issues).isEmpty();
    }

    @Test
    void throwsForMalformedSpecFile() {
        assertThatThrownBy(() -> validator.validate(
                        "pos-order",
                        Path.of("src/test/resources/openapi/fixtures/module/malformed.yaml"),
                        new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos-order")
                .hasMessageContaining("could not be parsed");
    }
}
