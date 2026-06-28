package com.positivity.openapivalidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TestSourceDuplicateGuardTest {

    private static final Path TEST_SOURCES = Path.of("src/test/java/com/positivity/openapivalidation");

    @Test
    void openApiModulePolicy_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/policy/OpenApiModulePolicy.java"))
                .doesNotExist();
    }

    @Test
    void openApiValidationInventory_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/policy/OpenApiValidationInventory.java"))
                .doesNotExist();
    }

    @Test
    void openApiValidationInventoryLoader_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/policy/OpenApiValidationInventoryLoader.java"))
                .doesNotExist();
    }

    @Test
    void openApiValidationIssue_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiValidationIssue.java"))
                .doesNotExist();
    }

    @Test
    void openApiRepositoryValidationResult_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiRepositoryValidationResult.java"))
                .doesNotExist();
    }

    @Test
    void openApiValidationMode_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiValidationMode.java"))
                .doesNotExist();
    }

    @Test
    void openApiModuleValidator_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiModuleValidator.java"))
                .doesNotExist();
    }

    @Test
    void openApiAggregateValidator_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiAggregateValidator.java"))
                .doesNotExist();
    }

    @Test
    void openApiRepositoryValidator_isNotDuplicatedInTestSources() {
        assertThat(TEST_SOURCES.resolve("internal/validator/OpenApiRepositoryValidator.java"))
                .doesNotExist();
    }
}
