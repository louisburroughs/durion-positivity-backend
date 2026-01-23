package com.positivity.inventory.putaway;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of putaway validation checks.
 * Contains validation status and any errors or warnings.
 */
public class ValidationResult {
    private boolean valid;
    private List<ValidationError> errors;
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
    
    public static class ValidationError {
        private String errorCode;
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
    
    public static class ValidationWarning {
        private String code;
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
