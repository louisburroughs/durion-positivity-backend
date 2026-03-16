package com.positivity.securityservice.internal.exception;

import java.util.UUID;

public class SelfRegistrationReviewCaseNotFoundException extends RuntimeException {

    public SelfRegistrationReviewCaseNotFoundException(UUID caseId) {
        super("Self-registration review case not found: " + caseId);
    }
}
