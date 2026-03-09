package com.positivity.workorder.internal.exception;

import java.util.UUID;

public class DuplicateSubstituteLinkException extends RuntimeException {
    public DuplicateSubstituteLinkException(UUID productId, UUID substitutePartId) {
        super("Substitute link already exists for product " + productId + " and substitute " + substitutePartId);
    }
}