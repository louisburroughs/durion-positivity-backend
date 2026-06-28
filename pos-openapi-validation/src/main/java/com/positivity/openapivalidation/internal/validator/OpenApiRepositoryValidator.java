package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import com.positivity.openapivalidation.internal.policy.OpenApiValidationInventory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;

public class OpenApiRepositoryValidator {

    private final OpenApiModuleValidator moduleValidator;
    private final OpenApiAggregateValidator aggregateValidator;

    public OpenApiRepositoryValidator(
            OpenApiModuleValidator moduleValidator, OpenApiAggregateValidator aggregateValidator) {
        this.moduleValidator = moduleValidator;
        this.aggregateValidator = aggregateValidator;
    }

    public @NonNull OpenApiRepositoryValidationResult validate(
            @NonNull Path repositoryRoot,
            @NonNull Path aggregatePath,
            @NonNull OpenApiValidationInventory inventory,
            @NonNull OpenApiValidationMode validationMode) {

        List<OpenApiValidationIssue> blockingIssues = new ArrayList<>();
        List<OpenApiValidationIssue> reportOnlyIssues = new ArrayList<>();

        inventory.modules().entrySet().stream()
                .filter(entry -> entry.getValue().mode() != OpenApiModulePolicy.Mode.EXCLUDED)
                .filter(entry -> entry.getValue().mode() != OpenApiModulePolicy.Mode.EXCEPTION)
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String moduleName = entry.getKey();
                    OpenApiModulePolicy policy = entry.getValue();
                    Path specPath = repositoryRoot.resolve(moduleName).resolve("openapi.yaml");

                    for (OpenApiValidationIssue issue : moduleValidator.validate(moduleName, specPath, policy)) {
                        if (issue.mode() == OpenApiModulePolicy.Mode.REPORT_ONLY
                                && validationMode == OpenApiValidationMode.REPORT) {
                            reportOnlyIssues.add(issue);
                        } else {
                            blockingIssues.add(issue);
                        }
                    }
                });

        blockingIssues.addAll(aggregateValidator.validate(aggregatePath));

        return new OpenApiRepositoryValidationResult(List.copyOf(blockingIssues), List.copyOf(reportOnlyIssues));
    }
}
