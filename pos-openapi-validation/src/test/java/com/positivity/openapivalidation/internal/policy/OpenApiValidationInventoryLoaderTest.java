package com.positivity.openapivalidation.internal.policy;

import static com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode.EXCEPTION;
import static com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode.STRICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiValidationInventoryLoaderTest {

    @Test
    void loadsCommittedModulePolicies() {
        OpenApiValidationInventory inventory =
                OpenApiValidationInventoryLoader.load(Path.of("src/test/resources/openapi/module-inventory.yaml"));

        assertThat(inventory.policyFor("pos-accounting").mode()).isEqualTo(STRICT);
        assertThat(inventory.policyFor("pos-location").mode()).isEqualTo(STRICT);
        assertThat(inventory.policyFor("pos-api-gateway").mode()).isEqualTo(EXCEPTION);
    }

    @Test
    void defaultsAnnotationDepthToReportOnlyAndReadsExplicitStrict() {
        OpenApiValidationInventory inventory =
                OpenApiValidationInventoryLoader.load(Path.of("src/test/resources/openapi/module-inventory.yaml"));

        assertThat(inventory.policyFor("pos-accounting").annotationDepth())
                .isEqualTo(OpenApiModulePolicy.DepthMode.REPORT_ONLY);
        assertThat(inventory.policyFor("pos-tax").annotationDepth()).isEqualTo(OpenApiModulePolicy.DepthMode.STRICT);
        assertThat(inventory.policyFor("pos-supplier").annotationDepth())
                .isEqualTo(OpenApiModulePolicy.DepthMode.STRICT);
    }

    @Test
    void throwsWhenAnnotationDepthIsExemptWithoutReason() {
        Path path = Path.of("src/test/resources/openapi/depth-exempt-without-reason.yaml");

        assertThatThrownBy(() -> OpenApiValidationInventoryLoader.load(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("annotationDepthReason");
    }

    @Test
    void throwsWhenReasonIsNotString() {
        Path path = Path.of("src/test/resources/openapi/invalid-reason-type.yaml");

        assertThatThrownBy(() -> OpenApiValidationInventoryLoader.load(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void throwsWhenModuleKeyIsNotString() {
        Path path = Path.of("src/test/resources/openapi/non-string-module-key.yaml");

        assertThatThrownBy(() -> OpenApiValidationInventoryLoader.load(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module key")
                .hasMessageContaining("42");
    }

    @Test
    void throwsWhenModuleKeyIsNull() {
        Path path = Path.of("src/test/resources/openapi/null-module-key.yaml");

        assertThatThrownBy(() -> OpenApiValidationInventoryLoader.load(path))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module key")
                .hasMessageContaining("null");
    }

    @Test
    void throwsForUnknownModule() {
        OpenApiValidationInventory inventory =
                OpenApiValidationInventoryLoader.load(Path.of("src/test/resources/openapi/module-inventory.yaml"));

        assertThatThrownBy(() -> inventory.policyFor("pos-nonexistent")).isInstanceOf(IllegalArgumentException.class);
    }
}
