package com.positivity.openapivalidation.internal.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiAggregateValidatorTest {

    private final OpenApiAggregateValidator validator = new OpenApiAggregateValidator();

    @Test
    void cleanAggregate_returnsNoIssues() {
        var issues = validator.validate(Path.of("src/test/resources/openapi/fixtures/aggregate/clean-aggregate.yaml"));

        assertThat(issues).isEmpty();
    }

    @Test
    void duplicatePathAggregate_returnsIssue() {
        var issues = validator.validate(
                Path.of("src/test/resources/openapi/fixtures/aggregate/duplicate-path-aggregate.yaml"));

        assertThat(issues).isNotEmpty();
        assertThat(issues.get(0).message()).containsIgnoringCase("duplicate");
    }

    @Test
    void brokenRefAggregate_returnsIssue() {
        var issues =
                validator.validate(Path.of("src/test/resources/openapi/fixtures/aggregate/broken-ref-aggregate.yaml"));

        assertThat(issues)
                .singleElement()
                .extracting(OpenApiValidationIssue::message)
                .asString()
                .contains("/v1/documents")
                .contains("unresolved ref")
                .contains("../missing-module/openapi.yaml#/paths/~1v1~1documents");
    }

    @Test
    void relativeAggregatePathWithNullParent_doesNotThrowNullPointerException() {
        // Fixture lives at the module root; Path.of with only a filename has getParent() == null
        var path = Path.of("broken-ref-aggregate.yaml");
        assertThat(path.getParent()).isNull();

        // Before fix: NullPointerException wrapped in IllegalStateException
        // After fix: returns an unresolved-ref issue without any exception
        var issues = validator.validate(path);

        assertThat(issues)
                .singleElement()
                .extracting(OpenApiValidationIssue::message)
                .asString()
                .contains("unresolved ref");
    }

    @Test
    void unexpectedFailures_areWrapped() {
        Path aggregatePath = Path.of("src/test/resources/openapi/fixtures/aggregate/does-not-exist.yaml");

        assertThatThrownBy(() -> validator.validate(aggregatePath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to validate aggregate " + aggregatePath)
                .hasCauseInstanceOf(Exception.class);
    }
}
