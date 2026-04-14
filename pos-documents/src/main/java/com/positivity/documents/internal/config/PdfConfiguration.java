package com.positivity.documents.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "documents.pdf")
public record PdfConfiguration(int maxInputCharacters, String templateBasePath, int maxTableRows) {

    public PdfConfiguration {
        if (maxInputCharacters <= 0) {
            maxInputCharacters = 1_000_000;
        }
        if (templateBasePath == null || templateBasePath.isBlank()) {
            throw new IllegalArgumentException("documents.pdf.template-base-path must be configured");
        }
        if (maxTableRows <= 0) {
            maxTableRows = 200;
        }
    }
}
