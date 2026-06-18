package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of putaway validation checks.
 * Contains validation status and any errors or warnings.
 */
@Schema(description = "Result of putaway validation checks, including status, errors, and warnings")
public class ValidationResult {

    @Schema(description = "Whether the validation passed with no errors", example = "true", requiredMode = REQUIRED)
    private boolean valid;

    @Schema(description = "Validation errors that caused the check to fail", requiredMode = REQUIRED)
    @NotNull
    private List<ValidationError> errors;

    @Schema(description = "Non-blocking validation warnings", requiredMode = REQUIRED)
    @NotNull
    private List<ValidationWarning> warnings;

    public ValidationResult() {
        this.valid = true;
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
    }

    public static ValidationResult success() {
        return new ValidationResult();
    }

    public static ValidationResult failure(String errorCode, String message) {
        ValidationResult result = new ValidationResult();
        result.addError(errorCode, message);
        return result;
    }

    public void addError(String errorCode, String message) {
        this.valid = false;
        this.errors.add(new ValidationError(errorCode, message));
    }

    public void addWarning(String code, String message) {
        this.warnings.add(new ValidationWarning(code, message));
    }

    public boolean isValid() {
        return valid;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    public List<ValidationWarning> getWarnings() {
        return warnings;
    }

    @Schema(description = "A validation error with a code and human-readable message")
    public static class ValidationError {

        @Schema(description = "Machine-readable error code", example = "CAPACITY_EXCEEDED", requiredMode = REQUIRED)
        @NotNull
        private String errorCode;

        @Schema(
                description = "Human-readable error message",
                example = "Storage location capacity exceeded",
                requiredMode = REQUIRED)
        @NotNull
        private String message;

        public ValidationError(String errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getMessage() {
            return message;
        }
    }

    @Schema(description = "A non-blocking validation warning with a code and human-readable message")
    public static class ValidationWarning {

        @Schema(description = "Machine-readable warning code", example = "NEAR_CAPACITY", requiredMode = REQUIRED)
        @NotNull
        private String code;

        @Schema(
                description = "Human-readable warning message",
                example = "Storage location is approaching capacity",
                requiredMode = REQUIRED)
        @NotNull
        private String message;

        public ValidationWarning(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
