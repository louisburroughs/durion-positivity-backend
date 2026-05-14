package com.positivity.openapivalidation.internal.policy;

import java.util.Map;
import org.jspecify.annotations.NonNull;

public record OpenApiValidationInventory(@NonNull Map<String, OpenApiModulePolicy> modules) {

    public @NonNull OpenApiModulePolicy policyFor(@NonNull String module) {
        OpenApiModulePolicy policy = modules.get(module);
        if (policy == null) {
            throw new IllegalArgumentException("No policy defined for module: " + module);
        }
        return policy;
    }
}
