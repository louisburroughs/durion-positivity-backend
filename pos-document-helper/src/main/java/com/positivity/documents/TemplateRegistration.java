package com.positivity.documents;

import lombok.Builder;
import lombok.Data;

/**
 * DTO for registering document templates with the pos-documents service.
 * Used by module initializers to register their templates at startup.
 *
 * Issue: ADR-0020
 */
@Data
@Builder
public class TemplateRegistration {

    /** Unique identifier for the template (e.g., INVOICE_TEMPLATE, ESTIMATE_TEMPLATE) */
    private String templateId;

    /** Human-readable description of the template */
    private String description;

    /** HTML template content with Handlebars-style placeholders */
    private String content;

    /** Template version for change tracking */
    @Builder.Default
    private String version = "1.0";

    /** Whether the template is active */
    @Builder.Default
    private boolean active = true;

    /** Content type for the template (default: text/html) */
    @Builder.Default
    private String contentType = "text/html";

    /**
     * Create a basic HTML template registration.
     *
     * @param templateId unique template identifier
     * @param description human-readable description
     * @param content HTML template content
     * @return builder for further customization
     */
    public static TemplateRegistrationBuilder htmlTemplate(String templateId, String description, String content) {
        return TemplateRegistration.builder()
                .templateId(templateId)
                .description(description)
                .content(content)
                .contentType("text/html");
    }
}
