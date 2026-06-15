package com.positivity.location.internal.controller;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Module-wide exception advice rendering RFC 9457 ProblemDetail bodies for
 * {@code ResponseStatusException} and standard Spring MVC exceptions.
 *
 * <p>Without an advice, MockMvc-observed error responses have empty bodies
 * (the servlet /error dispatch is not followed in tests) and runtime bodies
 * fall back to the Boot default error JSON. Consumers such as the
 * pos-inventory rollup client rely on a deterministic error envelope
 * (CAP-214 #655 — "404 ProblemDetail"; PR #661 review finding 1).
 */
@RestControllerAdvice
public class LocationGlobalExceptionHandler extends ResponseEntityExceptionHandler {}
