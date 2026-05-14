# OpenAPI Validation Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish `pos-openapi-validation` so reusable OpenAPI policy and validator code lives in `src/main/java` while Maven tests remain the repository enforcement surface.

**Architecture:** Keep `pos-openapi-validation` as a non-runtime repository policy module. Promote the shared inventory and validator types out of `src/test/java`, keep repository validation wired through tests, and update the module README so the code layout and commands match reality.

**Tech Stack:** Java 25, Maven, Spring Boot test support, SnakeYAML, swagger-parser, JUnit 5, AssertJ

---

## File Structure

- **Modify:** `pos-openapi-validation/pom.xml` — move parser dependencies needed by main-source validators out of test scope.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java` — production policy mode record.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java` — production inventory record.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java` — production YAML inventory loader.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java` — issue record shared by validators and tests.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java` — grouped blocking/report-only result record.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java` — `REPORT` vs `STRICT` mode parsing.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java` — module-level OpenAPI rules.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java` — aggregate duplicate-key and unresolved-ref rules.
- **Create:** `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java` — repository-wide orchestration across inventory, modules, and aggregate.
- **Create:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/ProductionSourceLayoutTest.java` — regression test that fails unless the validator support classes are compiled into `target/classes`.
- **Create:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/TestSourceDuplicateGuardTest.java` — regression test that fails while duplicate validator implementations remain in `src/test/java`.
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java`
- **Delete:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java`
- **Modify:** `pos-openapi-validation/README.md` — remove the stale “real logic lives under test sources” description and document the main/test split accurately.
- **Modify:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoaderTest.java` — align the stale `pos-location` expectation with the committed inventory.
- **Reuse without structural change:** `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/OpenApiRepositoryValidationTest.java`, `.../OpenApiModuleValidatorTest.java`, `.../OpenApiAggregateValidatorTest.java`, `.../OpenApiRepositoryValidatorTest.java` — these should continue to prove behavior, now against main-source implementations.

### Task 1: Promote validator support code into `src/main/java`

**Files:**
- Modify: `pos-openapi-validation/pom.xml`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java`
- Create: `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/ProductionSourceLayoutTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidatorTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidatorTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.positivity.openapivalidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionSourceLayoutTest {

    @Test
    void validatorSupportTypesAreCompiledIntoMainOutput() {
        Path targetClasses = Path.of("target/classes");

        List<String> requiredClassFiles = List.of(
                "com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.class",
                "com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.class",
                "com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.class",
                "com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.class");

        assertThat(requiredClassFiles)
                .allSatisfy(classFile -> assertThat(targetClasses.resolve(classFile)).exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl pos-openapi-validation -DskipTests=false -Dtest=ProductionSourceLayoutTest test`

Expected: `BUILD FAILURE` with an AssertJ message showing at least one missing `target/classes/com/positivity/openapivalidation/internal/...` class file.

- [ ] **Step 3: Write minimal implementation**

Update the dependency scopes in `pos-openapi-validation/pom.xml` so the validator code can compile from main sources:

```xml
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
</dependency>

<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser</artifactId>
    <version>2.1.22</version>
</dependency>
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java`:

```java
package com.positivity.openapivalidation.internal.policy;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OpenApiModulePolicy(@NonNull Mode mode, @Nullable String reason) {

    public enum Mode {
        STRICT, REPORT_ONLY, EXCEPTION, EXCLUDED
    }
}
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java`:

```java
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
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java`:

```java
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

    private OpenApiValidationInventoryLoader() {
    }

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
                    throw new IllegalArgumentException(
                            "Expected string module key in " + path + ", got: null");
                }
                if (!(entry.getKey() instanceof String moduleName)) {
                    throw new IllegalArgumentException(
                            "Expected string module key in " + path + ", got: "
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
            throw new IllegalArgumentException(
                    "Expected string reason for module: " + moduleName + ", got: "
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
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java`:

```java
package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode;
import org.jspecify.annotations.NonNull;

public record OpenApiValidationIssue(@NonNull String module, @NonNull Mode mode, @NonNull String message) {
}
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java`:

```java
package com.positivity.openapivalidation.internal.validator;

import java.util.List;
import org.jspecify.annotations.NonNull;

public record OpenApiRepositoryValidationResult(
        @NonNull List<OpenApiValidationIssue> blockingIssues,
        @NonNull List<OpenApiValidationIssue> reportOnlyIssues) {
}
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java`:

```java
package com.positivity.openapivalidation.internal.validator;

public enum OpenApiValidationMode {
    REPORT, STRICT;

    public static OpenApiValidationMode fromSystemProperty() {
        String value = System.getProperty("openapi.validation.mode", "REPORT");
        return switch (value.toUpperCase()) {
            case "STRICT" -> STRICT;
            default -> REPORT;
        };
    }
}
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java`:

```java
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
            if (pathItem.readOperationsMap() == null) {
                continue;
            }
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
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java`:

```java
package com.positivity.openapivalidation.internal.validator;

import com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

public class OpenApiAggregateValidator {

    public @NonNull List<OpenApiValidationIssue> validate(@NonNull Path aggregatePath) {
        try {
            return doValidate(aggregatePath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate aggregate " + aggregatePath, e);
        }
    }

    private List<OpenApiValidationIssue> doValidate(Path aggregatePath) throws IOException {
        List<OpenApiValidationIssue> issues = new ArrayList<>();

        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        Object root;
        try (var reader = Files.newBufferedReader(aggregatePath)) {
            root = new Yaml(loaderOptions).load(reader);
        } catch (DuplicateKeyException e) {
            issues.add(new OpenApiValidationIssue(
                    "aggregate",
                    OpenApiModulePolicy.Mode.STRICT,
                    "aggregate: duplicate key detected: " + e.getMessage()));
            return issues;
        }

        collectUnresolvedRefs(aggregatePath, root, root, issues, null);
        return List.copyOf(issues);
    }

    @SuppressWarnings("unchecked")
    private void collectUnresolvedRefs(
            Path aggregatePath,
            Object node,
            Object root,
            List<OpenApiValidationIssue> issues,
            String currentPath) throws IOException {
        if (node instanceof Map<?, ?> mapNode) {
            Object refValue = mapNode.get("$ref");
            if (refValue instanceof String ref) {
                validateRef(aggregatePath, root, currentPath, ref, issues);
            }

            for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String nextPath = currentPath;
                if ("paths".equals(currentPath)) {
                    nextPath = key;
                } else if (currentPath == null && "paths".equals(key)) {
                    nextPath = "paths";
                }
                collectUnresolvedRefs(aggregatePath, entry.getValue(), root, issues, nextPath);
            }
            return;
        }

        if (node instanceof Collection<?> collectionNode) {
            for (Object item : collectionNode) {
                collectUnresolvedRefs(aggregatePath, item, root, issues, currentPath);
            }
        }
    }

    private void validateRef(
            Path aggregatePath,
            Object root,
            String currentPath,
            String ref,
            List<OpenApiValidationIssue> issues) throws IOException {
        String[] parts = ref.split("#", 2);
        String filePart = parts[0];
        String fragmentPart = parts.length > 1 ? parts[1] : null;

        Object targetRoot = root;
        if (!filePart.isBlank()) {
            Path referencedPath = aggregatePath.getParent().resolve(filePart).normalize();
            if (!Files.exists(referencedPath)) {
                issues.add(unresolvedRefIssue(currentPath, ref));
                return;
            }
            try (var reader = Files.newBufferedReader(referencedPath)) {
                targetRoot = new Yaml().load(reader);
            }
        }

        if (fragmentPart != null && !fragmentPart.isBlank() && resolveFragment(targetRoot, fragmentPart) == null) {
            issues.add(unresolvedRefIssue(currentPath, ref));
        }
    }

    private OpenApiValidationIssue unresolvedRefIssue(String currentPath, String ref) {
        String pathDescription = currentPath == null ? "<unknown>" : currentPath;
        return new OpenApiValidationIssue(
                "aggregate",
                OpenApiModulePolicy.Mode.STRICT,
                "aggregate " + pathDescription + ": unresolved ref " + ref);
    }

    private Object resolveFragment(Object root, String fragment) {
        Object currentNode = root;
        for (String segment : fragment.replaceFirst("^/", "").split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            String key = segment.replace("~1", "/").replace("~0", "~");
            if (!(currentNode instanceof Map<?, ?> mapNode) || !mapNode.containsKey(key)) {
                return null;
            }
            currentNode = mapNode.get(key);
        }
        return currentNode;
    }
}
```

Create `pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java`:

```java
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
            OpenApiModuleValidator moduleValidator,
            OpenApiAggregateValidator aggregateValidator) {
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

        return new OpenApiRepositoryValidationResult(
                List.copyOf(blockingIssues),
                List.copyOf(reportOnlyIssues));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl pos-openapi-validation -DskipTests=false -Dtest=ProductionSourceLayoutTest,OpenApiModuleValidatorTest,OpenApiAggregateValidatorTest,OpenApiRepositoryValidatorTest test`

Expected: `BUILD SUCCESS` and all four targeted test classes pass.

- [ ] **Step 5: Commit**

```bash
git add pos-openapi-validation/pom.xml \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java \
  pos-openapi-validation/src/main/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/ProductionSourceLayoutTest.java
git commit -m "refactor: move openapi validators into main sources" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: Delete duplicate test-source implementations

**Files:**
- Create: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/TestSourceDuplicateGuardTest.java`
- Modify: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoaderTest.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java`
- Delete: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/TestSourceDuplicateGuardTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/OpenApiRepositoryValidationTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidatorTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidatorTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoaderTest.java`
- Test: `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidatorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.positivity.openapivalidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestSourceDuplicateGuardTest {

    @Test
    void validatorSupportClassesDoNotRemainInTestSources() {
        List<Path> duplicateSourceFiles = List.of(
                Path.of("src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java"),
                Path.of("src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java"));

        assertThat(duplicateSourceFiles)
                .allSatisfy(path -> assertThat(path).doesNotExist());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl pos-openapi-validation -DskipTests=false -Dtest=TestSourceDuplicateGuardTest test`

Expected: `BUILD FAILURE` with an AssertJ message showing at least one duplicate Java file still present under `src/test/java/com/positivity/openapivalidation/internal/...`.

- [ ] **Step 3: Write minimal implementation**

Delete the duplicate test-source implementations so tests compile against the new main-source classes:

```bash
git rm \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiModulePolicy.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventory.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoader.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationIssue.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidationResult.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiValidationMode.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidator.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidator.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidator.java
```

Update `pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoaderTest.java` so it matches the committed inventory:

```java
import static com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode.EXCEPTION;
import static com.positivity.openapivalidation.internal.policy.OpenApiModulePolicy.Mode.STRICT;

// ...

@Test
void loadsCommittedModulePolicies() {
    OpenApiValidationInventory inventory =
            OpenApiValidationInventoryLoader.load(Path.of("src/test/resources/openapi/module-inventory.yaml"));

    assertThat(inventory.policyFor("pos-accounting").mode()).isEqualTo(STRICT);
    assertThat(inventory.policyFor("pos-location").mode()).isEqualTo(STRICT);
    assertThat(inventory.policyFor("pos-api-gateway").mode()).isEqualTo(EXCEPTION);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl pos-openapi-validation -DskipTests=false -Dtest=TestSourceDuplicateGuardTest,OpenApiRepositoryValidationTest,OpenApiModuleValidatorTest,OpenApiAggregateValidatorTest,OpenApiValidationInventoryLoaderTest,OpenApiRepositoryValidatorTest test`

Expected: `BUILD SUCCESS` and the repository-validation test still reports zero blocking issues in report mode.

- [ ] **Step 5: Commit**

```bash
git add \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/TestSourceDuplicateGuardTest.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/OpenApiRepositoryValidationTest.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiModuleValidatorTest.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiAggregateValidatorTest.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/policy/OpenApiValidationInventoryLoaderTest.java \
  pos-openapi-validation/src/test/java/com/positivity/openapivalidation/internal/validator/OpenApiRepositoryValidatorTest.java
git commit -m "test: remove duplicate openapi validator test helpers" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: Refresh module documentation and run the full module suite

**Files:**
- Modify: `pos-openapi-validation/README.md`
- Test: `pos-openapi-validation/README.md`

- [ ] **Step 1: Write the failing documentation check**

Run:

```bash
rg -n 'Most of the real logic lives under `src/test/java`' pos-openapi-validation/README.md
```

Expected: one match, proving the README still describes the old layout incorrectly.

- [ ] **Step 2: Run the check to verify it fails**

Run:

```bash
rg -n 'Most of the real logic lives under `src/test/java`' pos-openapi-validation/README.md && false
```

Expected: the stale sentence is printed, then the shell exits non-zero.

- [ ] **Step 3: Write minimal implementation**

Replace the stale opening and flow description in `pos-openapi-validation/README.md` with:

```md
# pos-openapi-validation

`pos-openapi-validation` is the repository's OpenAPI policy module. It does not provide runtime behavior; it packages reusable validator code under `src/main/java` and Maven tests that read generated OpenAPI files from the worktree and fail when the repository violates ADR-0042 rules.

## How it works

The repository validation flow is:

1. Each producer module writes its generated spec to `<module>/openapi.yaml`.
2. The gateway aggregate spec is written to `pos-api-gateway/docs/openapi-aggregate.yaml`.
3. `OpenApiRepositoryValidationTest` loads the committed policy inventory from `src/test/resources/openapi/module-inventory.yaml`.
4. `OpenApiRepositoryValidator` in `src/main/java` validates each module in scope with `OpenApiModuleValidator`, then validates the aggregate spec with `OpenApiAggregateValidator`.
5. The tests are the enforcement entrypoint; the main-source classes are the reusable implementation.
```

- [ ] **Step 4: Run verification**

Run: `./mvnw -pl pos-openapi-validation -DskipTests=false test`

Expected: `BUILD SUCCESS`, all module tests pass, and the README no longer contains the stale `src/test/java` sentence.

- [ ] **Step 5: Commit**

```bash
git add pos-openapi-validation/README.md
git commit -m "docs: align openapi validation readme with source layout" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

## Self-Review Notes

- **Spec coverage:** the plan covers the approved design boundary (main-source validator logic, test-based enforcement, updated docs/commands) without introducing a runtime CLI or service surface.
- **Placeholder scan:** no placeholder language remains; each task lists exact files, commands, and code.
- **Type consistency:** all tasks use the same package names and class names already present in `pos-openapi-validation`, so tests can keep the current imports while switching to main-source implementations.
