package com.positivity.documents;

/**
 * Supported document output formats.
 *
 * Issue: ADR-0020
 */
public enum DocumentFormat {
    /** Portable Document Format (binary) */
    PDF,

    /** HyperText Markup Language */
    HTML,

    /** Plain text */
    TEXT,

    /** Comma-Separated Values */
    CSV
}
