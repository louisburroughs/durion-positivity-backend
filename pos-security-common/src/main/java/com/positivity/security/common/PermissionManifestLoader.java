package com.positivity.security.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Loads permission registration manifests from classpath YAML resources.
 */
public final class PermissionManifestLoader {

    private PermissionManifestLoader() {}

    /**
     * Load a permission manifest from a classpath resource.
     *
     * @param classpathLocation classpath location (for example:
     *                          {@code permissions.yaml})
     * @return parsed permission manifest
     */
    public static PermissionManifest loadFromClasspath(@NonNull String classpathLocation) {
        Resource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException("Permission manifest not found on classpath: " + classpathLocation);
        }

        YamlMapFactoryBean yamlFactory = new YamlMapFactoryBean();
        yamlFactory.setResources(resource);
        Map<String, Object> root = yamlFactory.getObject();
        if (root == null || root.isEmpty()) {
            throw new IllegalStateException("Permission manifest is empty: " + classpathLocation);
        }

        String domain = requireString(root, "domain", classpathLocation);
        String serviceName = requireString(root, "serviceName", classpathLocation);
        String version = optionalString(root, "version");
        if (version == null || version.isBlank()) {
            version = "1.0";
        }

        Object permissionsNode = root.get("permissions");
        if (!(permissionsNode instanceof List<?> permissionEntries) || permissionEntries.isEmpty()) {
            throw new IllegalStateException(
                    "Permission manifest must define a non-empty permissions list: " + classpathLocation);
        }

        List<PermissionDefinition> permissions = new ArrayList<>(permissionEntries.size());
        Set<String> names = new LinkedHashSet<>();
        int index = 0;
        for (Object entry : permissionEntries) {
            index++;
            PermissionDefinition permission = parsePermission(entry, classpathLocation, index);
            if (!names.add(permission.name())) {
                throw new IllegalStateException(
                        "Duplicate permission name '" + permission.name() + "' in manifest: " + classpathLocation);
            }
            permissions.add(permission);
        }

        return new PermissionManifest(domain, serviceName, version, List.copyOf(permissions));
    }

    private static PermissionDefinition parsePermission(Object entry, String classpathLocation, int index) {
        if (!(entry instanceof Map<?, ?> mapEntry)) {
            throw new IllegalStateException(
                    "Permission entry #" + index + " must be a map in manifest: " + classpathLocation);
        }

        Object nameNode = mapEntry.get("name");
        if (!(nameNode instanceof String name) || name.isBlank()) {
            throw new IllegalStateException(
                    "Permission entry #" + index + " must define non-empty 'name' in manifest: " + classpathLocation);
        }

        Object descriptionNode = mapEntry.get("description");
        String description = descriptionNode instanceof String value ? value : null;

        return PermissionDefinition.of(name, description);
    }

    private static String requireString(Map<String, Object> root, String key, String classpathLocation) {
        Object value = root.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(
                    "Permission manifest must define non-empty '" + key + "': " + classpathLocation);
        }
        return text;
    }

    private static String optionalString(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    /**
     * Parsed representation of {@code permissions.yaml}.
     */
    public record PermissionManifest(
            @NonNull String domain,
            @NonNull String serviceName,
            @NonNull String version,
            @NonNull List<PermissionDefinition> permissions) {}
}
