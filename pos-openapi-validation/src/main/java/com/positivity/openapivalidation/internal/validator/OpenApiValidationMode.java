package com.positivity.openapivalidation.internal.validator;

public enum OpenApiValidationMode {
    REPORT,
    STRICT;

    public static OpenApiValidationMode fromSystemProperty() {
        String value = System.getProperty("openapi.validation.mode", "REPORT");
        return switch (value.toUpperCase()) {
            case "STRICT" -> STRICT;
            default -> REPORT;
        };
    }
}
