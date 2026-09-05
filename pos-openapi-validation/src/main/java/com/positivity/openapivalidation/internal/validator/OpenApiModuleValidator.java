package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public class OpenApiModuleValidator {

    private final OpenApiAnnotationDepthValidator depthValidator;
    private final OpenApiErrorResponseSchemaValidator errorSchemaValidator;

    public OpenApiModuleValidator() {
        this(new OpenApiAnnotationDepthValidator(), new OpenApiErrorResponseSchemaValidator());
    }

    public OpenApiModuleValidator(@NonNull OpenApiAnnotationDepthValidator depthValidator) {
        this(depthValidator, new OpenApiErrorResponseSchemaValidator());
    }

    public OpenApiModuleValidator(
            @NonNull OpenApiAnnotationDepthValidator depthValidator,
            @NonNull OpenApiErrorResponseSchemaValidator errorSchemaValidator) {
        this.depthValidator = depthValidator;
        this.errorSchemaValidator = errorSchemaValidator;
    }

    public @NonNull List<OpenApiValidationIssue> validate(
            @NonNull String module, @NonNull Path specPath, @NonNull OpenApiModulePolicy policy) {
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
            // readOperationsMap() builds a fresh map from the operation fields on every call —
            // it can be empty but never null — so it is read once, not guarded and re-read.
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
                    pathEntry.getValue().readOperationsMap().entrySet()) {
                checkOperation(module, policy, pathEntry.getKey(), opEntry.getKey(), opEntry.getValue(), issues);
            }
        }
        return List.copyOf(issues);
    }

    /**
     * The per-operation checks: spec shape (summary, description), ADR-0042 depth, then
     * ADR-0017 §3's error-envelope rule (#1720).
     */
    private void checkOperation(
            String module,
            OpenApiModulePolicy policy,
            String path,
            PathItem.HttpMethod method,
            Operation operation,
            List<OpenApiValidationIssue> issues) {
        String prefix = module + " " + method.name() + " " + path + ":";
        if (isBlank(operation.getSummary())) {
            issues.add(new OpenApiValidationIssue(module, policy.mode(), prefix + " missing summary"));
        }
        if (isBlank(operation.getDescription())) {
            issues.add(new OpenApiValidationIssue(module, policy.mode(), prefix + " missing description"));
        }
        if (policy.annotationDepth() != OpenApiModulePolicy.DepthMode.EXEMPT) {
            for (String finding : depthValidator.check(operation)) {
                issues.add(new OpenApiValidationIssue(module, policy.depthIssueMode(), prefix + " " + finding));
            }
        }
        if (policy.errorSchema() != OpenApiModulePolicy.ErrorSchemaMode.EXEMPT) {
            for (String finding : errorSchemaValidator.check(operation)) {
                issues.add(new OpenApiValidationIssue(module, policy.errorSchemaIssueMode(), prefix + " " + finding));
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
