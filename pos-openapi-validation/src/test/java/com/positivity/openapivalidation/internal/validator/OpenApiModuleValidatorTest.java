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
    void reportsAnExplicitlyEmptyPathsSection() {
        // `paths: {}` parses to an empty map rather than null — a different arm of the same
        // guard as missing-paths.yaml, and the shape a generator emits for a service with no
        // endpoints yet. Both must read as "missing paths", not as a compliant module.
        var issues = validator.validate(
                "pos-api-gateway",
                Path.of("src/test/resources/openapi/fixtures/module/empty-paths.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null));

        assertThat(issues)
                .singleElement()
                .extracting(OpenApiValidationIssue::message)
                .isEqualTo("pos-api-gateway: missing paths section");
    }

    @Test
    void reportsABlankSummaryAsMissing() {
        // summary: "" is present in the YAML but says nothing; treating it as provided would
        // let a module pass the gate with empty strings.
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/blank-summary.yaml"),
                new OpenApiModulePolicy(OpenApiModulePolicy.Mode.REPORT_ONLY, "baseline gap"));

        assertThat(issues)
                .extracting(OpenApiValidationIssue::message)
                .contains("pos-documents GET /v1/documents: missing summary")
                .doesNotContain("pos-documents GET /v1/documents: missing description");
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
    void reportsDepthAndRequestBodyGapsWhenAnnotationDepthIsStrict() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/shallow-module.yaml"),
                new OpenApiModulePolicy(
                        OpenApiModulePolicy.Mode.STRICT, null, OpenApiModulePolicy.DepthMode.STRICT, null));

        assertThat(issues)
                .extracting(OpenApiValidationIssue::message)
                .contains(
                        "pos-documents POST /v1/documents: description has 1 sentence (ADR-0042 §1 requires 4-8)",
                        "pos-documents POST /v1/documents: description does not open with a primary-action sentence",
                        "pos-documents POST /v1/documents: description missing when-to-use guidance"
                                + " (\"Use this tool ...\")",
                        "pos-documents POST /v1/documents: description missing preconditions"
                                + " (\"Preconditions: ...\")",
                        "pos-documents POST /v1/documents: description missing input expectations"
                                + " (\"Required inputs: ...\")",
                        "pos-documents POST /v1/documents: description missing side effects"
                                + " (\"Emits ...\" or \"No events are emitted\")",
                        "pos-documents POST /v1/documents: description missing error conditions"
                                + " (\"Returns <code> when ...\")",
                        "pos-documents POST /v1/documents: description missing negative guidance"
                                + " (\"do not use ...\" / \"... instead\")",
                        "pos-documents POST /v1/documents: request body missing description (ADR-0042 §3)",
                        "pos-documents POST /v1/documents: request body missing example (ADR-0042 §3)");
        assertThat(issues).allSatisfy(issue -> assertThat(issue.mode()).isEqualTo(OpenApiModulePolicy.Mode.STRICT));
    }

    @Test
    void tagsDepthIssuesAsReportOnlyWhenAnnotationDepthIsReportOnly() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/shallow-module.yaml"),
                new OpenApiModulePolicy(
                        OpenApiModulePolicy.Mode.STRICT, null, OpenApiModulePolicy.DepthMode.REPORT_ONLY, null));

        assertThat(issues).isNotEmpty();
        assertThat(issues)
                .allSatisfy(issue -> assertThat(issue.mode()).isEqualTo(OpenApiModulePolicy.Mode.REPORT_ONLY));
    }

    @Test
    void skipsDepthChecksWhenAnnotationDepthIsExempt() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/shallow-module.yaml"),
                new OpenApiModulePolicy(
                        OpenApiModulePolicy.Mode.STRICT, null, OpenApiModulePolicy.DepthMode.EXEMPT, "fixture"));

        assertThat(issues).isEmpty();
    }

    @Test
    void returnsNoIssuesForModuleMeetingTheDescriptionStandard() {
        var issues = validator.validate(
                "pos-documents",
                Path.of("src/test/resources/openapi/fixtures/module/deep-module.yaml"),
                new OpenApiModulePolicy(
                        OpenApiModulePolicy.Mode.STRICT, null, OpenApiModulePolicy.DepthMode.STRICT, null));

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
