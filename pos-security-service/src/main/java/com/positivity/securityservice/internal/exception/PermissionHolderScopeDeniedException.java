package com.positivity.securityservice.internal.exception;

import java.util.List;

/**
 * A caller allowed the permission-holders read only through a scope entry asked about a code
 * outside that scope (#2669, decision D2). Raised before anything is read.
 *
 * <p>{@code GlobalExceptionHandler} answers {@code 403 PERMISSION_HOLDER_SCOPE_DENIED}: ADR-0017
 * §2 question 1, the request is well formed but the actor may not have this answer. The message
 * names the out-of-scope codes the caller itself sent, which are already shape-validated.
 */
public class PermissionHolderScopeDeniedException extends RuntimeException {

    private final transient List<String> outOfScope;

    /**
     * @param outOfScope the requested codes the caller's scope does not cover, in request order
     */
    public PermissionHolderScopeDeniedException(List<String> outOfScope) {
        super("Your permissions do not cover the holders of: " + String.join(", ", outOfScope));
        this.outOfScope = List.copyOf(outOfScope);
    }

    /**
     * @return the requested codes the caller's scope does not cover, in request order
     */
    public List<String> outOfScope() {
        return outOfScope;
    }
}
