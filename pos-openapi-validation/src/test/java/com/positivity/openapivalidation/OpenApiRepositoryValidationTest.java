package com.positivity.openapivalidation;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.openapivalidation.internal.policy.OpenApiValidationInventoryLoader;
import com.positivity.openapivalidation.internal.validator.OpenApiAggregateValidator;
import com.positivity.openapivalidation.internal.validator.OpenApiModuleValidator;
import com.positivity.openapivalidation.internal.validator.OpenApiRepositoryValidationResult;
import com.positivity.openapivalidation.internal.validator.OpenApiRepositoryValidator;
import com.positivity.openapivalidation.internal.validator.OpenApiValidationIssue;
import com.positivity.openapivalidation.internal.validator.OpenApiValidationMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpenApiRepositoryValidationTest {

    @Test
    void currentRepositoryPassesBlockingValidationWithReportOnlyBaselineGaps() {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        OpenApiRepositoryValidator validator =
                new OpenApiRepositoryValidator(new OpenApiModuleValidator(), new OpenApiAggregateValidator());

        OpenApiRepositoryValidationResult result = validator.validate(
                repoRoot,
                repoRoot.resolve("pos-api-gateway/docs/openapi-aggregate.yaml"),
                OpenApiValidationInventoryLoader.load(Path.of("src/test/resources/openapi/module-inventory.yaml")),
                OpenApiValidationMode.fromSystemProperty());

        assertThat(result.blockingIssues()).isEmpty();
        assertThat(result.reportOnlyIssues())
                .extracting(OpenApiValidationIssue::message)
                .allSatisfy(message -> assertThat(message).contains("pos-"));
    }
}
