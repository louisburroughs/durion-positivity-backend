package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.DeclaredType;

/**
 * What the parent asks the child to do: written to {@code <callDir>/request.json}.
 *
 * @param operation {@code inspect} or {@code render}
 * @param declaredType the type the caller declared; the parent already matched its magic bytes
 * @param page the 1-based page to render; ignored by {@code inspect}
 * @param limits the limits to enforce
 */
public record SandboxRequest(String operation, DeclaredType declaredType, int page, SandboxLimits limits) {

    public static final String INSPECT = "inspect";
    public static final String RENDER = "render";
}
