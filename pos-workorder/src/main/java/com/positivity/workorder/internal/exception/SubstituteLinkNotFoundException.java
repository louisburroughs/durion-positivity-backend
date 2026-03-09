package com.positivity.workorder.internal.exception;

import java.util.UUID;

public class SubstituteLinkNotFoundException extends RuntimeException {
    public SubstituteLinkNotFoundException(UUID linkId) {
        super("SubstituteLink not found with id " + linkId);
    }

    public SubstituteLinkNotFoundException(UUID productId, UUID substitutePartId) {
        super("SubstituteLink not found for product " + productId + " and substitute " + substitutePartId);
    }
}