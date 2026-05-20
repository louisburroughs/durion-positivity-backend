package com.positivity.openapivalidation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSourceLayoutTest {

    private static final Path TARGET_CLASSES = Path.of("target/classes");

    @Test
    void openApiModulePolicy_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.class"))
                .exists();
    }

    @Test
    void openApiValidationInventory_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.class"))
                .exists();
    }

    @Test
    void openApiValidationInventoryLoader_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.class"))
                .exists();
    }

    @Test
    void openApiValidationIssue_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.class"))
                .exists();
    }

    @Test
    void openApiRepositoryValidationResult_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.class"))
                .exists();
    }

    @Test
    void openApiValidationMode_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.class"))
                .exists();
    }

    @Test
    void openApiModuleValidator_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.class"))
                .exists();
    }

    @Test
    void openApiAggregateValidator_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.class"))
                .exists();
    }

    @Test
    void openApiRepositoryValidator_isCompiledIntoMainSources() {
        assertThat(classFile("com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.class"))
                .exists();
    }

    private static Path classFile(String relativePath) {
        return TARGET_CLASSES.resolve(relativePath);
    }
}
