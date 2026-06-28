package com.positivity.openapivalidation.internal.policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.yaml.snakeyaml.Yaml;

public final class OpenApiValidationInventoryLoader {

    private OpenApiValidationInventoryLoader() {}

    public static @NonNull OpenApiValidationInventory load(@NonNull Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            Map<?, ?> root = requireMap(new Yaml().load(reader), path.toString());
            Object modulesValue = root.get("modules");
            if (!(modulesValue instanceof Map<?, ?> modulesMap)) {
                throw new IllegalArgumentException("Expected modules map in " + path);
            }
            Map<String, OpenApiModulePolicy> policies = new HashMap<>();
            for (Map.Entry<?, ?> entry : modulesMap.entrySet()) {
                if (entry.getKey() == null) {
                    throw new IllegalArgumentException("Expected string module key in " + path + ", got: null");
                }
                if (!(entry.getKey() instanceof String moduleName)) {
                    throw new IllegalArgumentException("Expected string module key in " + path + ", got: "
                            + entry.getKey().getClass().getSimpleName() + " (" + entry.getKey() + ")");
                }
                Map<?, ?> policyMap = requireMap(entry.getValue(), "policy for " + moduleName);
                policies.put(moduleName, toPolicy(policyMap, moduleName));
            }
            return new OpenApiValidationInventory(Map.copyOf(policies));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load " + path, exception);
        }
    }

    private static @NonNull OpenApiModulePolicy toPolicy(@NonNull Map<?, ?> policyMap, @NonNull String moduleName) {
        Object modeValue = policyMap.get("mode");
        if (!(modeValue instanceof String modeString) || modeString.isBlank()) {
            throw new IllegalArgumentException("Expected non-blank mode for module: " + moduleName);
        }
        OpenApiModulePolicy.Mode mode = OpenApiModulePolicy.Mode.valueOf(modeString);
        Object reasonValue = policyMap.get("reason");
        String reason;
        if (reasonValue == null) {
            reason = null;
        } else if (reasonValue instanceof String s) {
            reason = s;
        } else {
            throw new IllegalArgumentException("Expected string reason for module: " + moduleName + ", got: "
                    + reasonValue.getClass().getSimpleName());
        }
        return new OpenApiModulePolicy(mode, reason);
    }

    private static @NonNull Map<?, ?> requireMap(Object value, @NonNull String description) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected map for " + description);
        }
        return map;
    }
}
