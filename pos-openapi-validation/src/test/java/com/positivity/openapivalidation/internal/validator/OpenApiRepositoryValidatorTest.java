package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import com.positivity.openapivalidation.internal.policy.OpenApiValidationInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiRepositoryValidatorTest {

    private final OpenApiRepositoryValidator validator =
            new OpenApiRepositoryValidator(new OpenApiModuleValidator(), new OpenApiAggregateValidator());

    @Test
    void keepsReportOnlyFindingsOutOfBlockingFailures() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.REPORT_ONLY, "baseline gap"),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.REPORT);

        assertThat(result.blockingIssues()).isEmpty();
        assertThat(result.reportOnlyIssues()).extracting(OpenApiValidationIssue::message)
                .contains(
                        "pos-location GET /v1/locations: missing summary",
                        "pos-location GET /v1/locations: missing description");
    }

    @Test
    void strictModuleIssuesAreBlockingRegardlessOfMode() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.REPORT);

        assertThat(result.blockingIssues()).isNotEmpty();
        assertThat(result.reportOnlyIssues()).isEmpty();
    }

    @Test
    void excludedModulesAreSkipped() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.EXCLUDED, "not yet onboarded"),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.REPORT);

        // pos-location is EXCLUDED so its missing summary/description are not validated
        // pos-accounting is clean, aggregate is clean
        assertThat(result.blockingIssues()).isEmpty();
        assertThat(result.reportOnlyIssues()).isEmpty();
    }

    @Test
    void exceptionModulesAreSkipped() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.EXCEPTION, "temporary waiver"),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.REPORT);

        assertThat(result.blockingIssues()).isEmpty();
        assertThat(result.reportOnlyIssues()).isEmpty();
    }

    @Test
    void strictModePromotesReportOnlyIssuesToBlocking() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.REPORT_ONLY, "baseline gap"),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.STRICT);

        assertThat(result.blockingIssues()).isNotEmpty();
        assertThat(result.reportOnlyIssues()).isEmpty();
    }

    @Test
    void aggregateIssuesAreAlwaysBlocking() {
        OpenApiValidationInventory inventory = new OpenApiValidationInventory(Map.of(
                "pos-location", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.EXCLUDED, "not yet onboarded"),
                "pos-accounting", new OpenApiModulePolicy(OpenApiModulePolicy.Mode.STRICT, null)));

        OpenApiRepositoryValidationResult result = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/repository"),
                Path.of("src/test/resources/openapi/fixtures/aggregate/broken-ref-aggregate.yaml"),
                inventory,
                OpenApiValidationMode.REPORT);

        assertThat(result.reportOnlyIssues()).isEmpty();
        assertThat(result.blockingIssues()).singleElement()
                .extracting(OpenApiValidationIssue::message)
                .asString()
                .contains("unresolved ref");
    }

    @Test
    void validationModeDefaultsToReport() {
        String originalValue = System.getProperty("openapi.validation.mode");
        try {
            System.clearProperty("openapi.validation.mode");

            assertThat(OpenApiValidationMode.fromSystemProperty()).isEqualTo(OpenApiValidationMode.REPORT);
        } finally {
            restoreValidationModeProperty(originalValue);
        }
    }

    @Test
    void validationModeReadsStrictSystemProperty() {
        String originalValue = System.getProperty("openapi.validation.mode");
        try {
            System.setProperty("openapi.validation.mode", "STRICT");

            assertThat(OpenApiValidationMode.fromSystemProperty()).isEqualTo(OpenApiValidationMode.STRICT);
        } finally {
            restoreValidationModeProperty(originalValue);
        }
    }

    private static void restoreValidationModeProperty(String originalValue) {
        if (originalValue == null) {
            System.clearProperty("openapi.validation.mode");
            return;
        }
        System.setProperty("openapi.validation.mode", originalValue);
    }
}
