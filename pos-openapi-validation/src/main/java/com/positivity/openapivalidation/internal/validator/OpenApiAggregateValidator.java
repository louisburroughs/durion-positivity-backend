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

    private static final String PATHS = "paths";

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

    private void collectUnresolvedRefs(
            Path aggregatePath, Object node, Object root, List<OpenApiValidationIssue> issues, String currentPath)
            throws IOException {
        if (node instanceof Map<?, ?> mapNode) {
            processMapNode(aggregatePath, mapNode, root, issues, currentPath);
            return;
        }

        if (node instanceof Collection<?> collectionNode) {
            processCollectionNode(aggregatePath, collectionNode, root, issues, currentPath);
        }
    }

    private void processMapNode(
            Path aggregatePath, Map<?, ?> mapNode, Object root, List<OpenApiValidationIssue> issues, String currentPath)
            throws IOException {
        Object refValue = mapNode.get("$ref");
        if (refValue instanceof String ref) {
            validateRef(aggregatePath, root, currentPath, ref, issues);
        }

        for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            String nextPath = computeNextPath(currentPath, key);
            collectUnresolvedRefs(aggregatePath, entry.getValue(), root, issues, nextPath);
        }
    }

    private void processCollectionNode(
            Path aggregatePath,
            Collection<?> collectionNode,
            Object root,
            List<OpenApiValidationIssue> issues,
            String currentPath)
            throws IOException {
        for (Object item : collectionNode) {
            collectUnresolvedRefs(aggregatePath, item, root, issues, currentPath);
        }
    }

    private String computeNextPath(String currentPath, String key) {
        if (PATHS.equals(currentPath)) {
            return key;
        }
        if (currentPath == null && PATHS.equals(key)) {
            return PATHS;
        }
        return currentPath;
    }

    private void validateRef(
            Path aggregatePath, Object root, String currentPath, String ref, List<OpenApiValidationIssue> issues)
            throws IOException {
        String[] parts = ref.split("#", 2);
        String filePart = parts[0];
        String fragmentPart = parts.length > 1 ? parts[1] : null;

        Object targetRoot = root;
        if (!filePart.isBlank()) {
            Path baseDir = aggregatePath.getParent() != null
                    ? aggregatePath.getParent()
                    : aggregatePath.toAbsolutePath().getParent();
            Path referencedPath = baseDir.resolve(filePart).normalize();
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
