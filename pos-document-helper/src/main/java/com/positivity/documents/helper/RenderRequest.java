package com.positivity.documents.helper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import org.jspecify.annotations.NonNull;

/**
 * Request to render a document via pos-documents service.
 * 
 * <p>Submit to POST /api/documents/render to generate PDF from template and data.
 * 
 * Issue: ADR-0020
 */
@Data
@Builder
public class RenderRequest {
    
    /**
     * Document format to render.
     */
    @NotNull
    @NonNull
    private DocumentFormat format;
    
    /**
     * Template identifier registered with pos-documents.
     * References a template previously registered via TemplateRegistration.
     */
    @NotBlank
    @NonNull
    private String templateId;
    
    /**
     * Content/data for template binding as JSON string.
     * Will be parsed and used to populate template variables.
     */
    @NotBlank
    @NonNull
    private String content;
    
    /**
     * Optional metadata for audit trail and observability.
     */
    private String requestedBy;
    
    /**
     * Optional correlation ID for request tracing.
     */
    private String correlationId;
}
