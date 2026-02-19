package com.positivity.documents.helper.exception;

/**
 * Exception thrown when template registration fails.
 * 
 * Issue: ADR-0020
 */
public class TemplateRegistrationException extends DocumentHelperException {
    
    private final String templateId;
    
    public TemplateRegistrationException(String templateId, String message) {
        super(String.format("Template registration failed for '%s': %s", templateId, message));
        this.templateId = templateId;
    }
    
    public TemplateRegistrationException(String templateId, String message, Throwable cause) {
        super(String.format("Template registration failed for '%s': %s", templateId, message), cause);
        this.templateId = templateId;
    }
    
    public String getTemplateId() {
        return templateId;
    }
}
