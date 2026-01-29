package com.positivity.inventory.internal.exception;

/**
 * Exception thrown when a user attempts an action they don't have permission for.
 * 
 * <p>Used for recount permission validation per issue #27 clarification.
 */
public class InsufficientPermissionException extends RuntimeException {
    
    public InsufficientPermissionException(String message) {
        super(message);
    }
    
    public InsufficientPermissionException(String userId, String requiredPermission) {
        super(String.format(
            "User %s lacks required permission: %s",
            userId, requiredPermission
        ));
    }
}
