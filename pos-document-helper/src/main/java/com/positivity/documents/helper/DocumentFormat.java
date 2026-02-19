package com.positivity.documents.helper;

/**
 * Supported document formats for rendering.
 * 
 * Issue: ADR-0020
 */
public enum DocumentFormat {
    /**
     * Portable Document Format (PDF) - primary format.
     */
    PDF,
    
    /**
     * HTML format for web display.
     */
    HTML,
    
    /**
     * Plain text format.
     */
    TEXT,
    
    /**
     * Markdown format.
     */
    MARKDOWN,
    
    /**
     * CSV format for tabular data export.
     */
    CSV
}
