package com.positivity.documents.helper;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Utility methods for working with document templates.
 * 
 * Issue: ADR-0020
 */
public final class TemplateUtils {
    
    private TemplateUtils() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * Load template content from classpath resource.
     * 
     * @param resourcePath path to template resource (e.g., "templates/invoice.html")
     * @return template content as string
     * @throws IOException if resource cannot be read
     */
    public static String loadTemplateFromClasspath(@NonNull String resourcePath) throws IOException {
        Objects.requireNonNull(resourcePath, "resourcePath cannot be null");
        
        try (var inputStream = TemplateUtils.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Template resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Validate template ID format.
     * Template IDs must be uppercase alphanumeric with underscores and hyphens.
     * 
     * @param templateId the template ID to validate
     * @throws IllegalArgumentException if template ID is invalid
     */
    public static void validateTemplateId(@NonNull String templateId) {
        Objects.requireNonNull(templateId, "templateId cannot be null");
        
        if (templateId.isBlank()) {
            throw new IllegalArgumentException("Template ID cannot be blank");
        }
        
        if (!templateId.matches("[A-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "Template ID must contain only uppercase letters, numbers, underscores, and hyphens: "
                            + templateId);
        }
    }
    
    /**
     * Validate semantic version format (major.minor.patch).
     * 
     * @param version the version to validate
     * @throws IllegalArgumentException if version is invalid
     */
    public static void validateVersion(@NonNull String version) {
        Objects.requireNonNull(version, "version cannot be null");
        
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException(
                    "Version must follow semantic versioning (major.minor.patch): " + version);
        }
    }
    
    /**
     * Create a standard template registration builder with common defaults.
     * 
     * @param templateId unique template identifier
     * @param owner module/service name that owns this template
     * @return builder with defaults set
     */
    public static TemplateRegistration.TemplateRegistrationBuilder templateBuilder(
            @NonNull String templateId,
            @NonNull String owner) {
        
        validateTemplateId(templateId);
        
        return TemplateRegistration.builder()
                .templateId(templateId)
                .owner(Objects.requireNonNull(owner, "owner cannot be null"))
                .version("1.0.0")
                .active(true);
    }
}
