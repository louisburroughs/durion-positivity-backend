package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenApiModuleValidator {

    public @NonNull List<OpenApiValidationIssue> validate(
            @NonNull String module,
            @NonNull Path specPath,
            @NonNull OpenApiModulePolicy policy) {
        if (!Files.exists(specPath)) {
            throw new IllegalStateException(module + ": spec file not found: " + specPath);
        }
        OpenAPI openApi = new OpenAPIV3Parser().read(specPath.toString());
        if (openApi == null) {
            throw new IllegalStateException(module + ": spec file could not be parsed: " + specPath);
        }
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return List.of(new OpenApiValidationIssue(module, policy.mode(), module + ": missing paths section"));
        }

        List<OpenApiValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, PathItem> pathEntry : openApi.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem.readOperationsMap() == null) continue;
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : pathItem.readOperationsMap().entrySet()) {
                String method = opEntry.getKey().name();
                Operation operation = opEntry.getValue();
                String prefix = module + " " + method + " " + path + ":";
                if (isBlank(operation.getSummary())) {
                    issues.add(new OpenApiValidationIssue(module, policy.mode(), prefix + " missing summary"));
                }
                if (isBlank(operation.getDescription())) {
                    issues.add(new OpenApiValidationIssue(module, policy.mode(), prefix + " missing description"));
                }
            }
        }
        return List.copyOf(issues);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
