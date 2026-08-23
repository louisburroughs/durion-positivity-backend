/**
 * Shared web MVC concerns for pos-* services.
 *
 * <p>
 * Provides the platform fallback exception handling required by ADR-0017: every non-2xx
 * response must carry the canonical {@code ApiError} envelope ({@code code}, {@code message},
 * {@code status}, {@code timestamp}, {@code correlationId}) and echo/generate
 * {@code X-Correlation-Id}. Issue #1471 documented unmapped exceptions escaping as Spring's
 * bare default 500 page; this module closes that gap once, centrally, instead of per service.
 * </p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.positivity.web.common.GlobalApiExceptionHandler} - lowest-precedence
 *       {@code @RestControllerAdvice} catch-all; service-specific advices always win for the
 *       exception types they map</li>
 *   <li>{@link com.positivity.web.common.DataIntegrityViolations} - SQLSTATE-based
 *       classification of {@code DataIntegrityViolationException} (unique, not-null, check,
 *       foreign-key)</li>
 *   <li>{@link com.positivity.web.common.WebCommonErrorAutoConfiguration} - registers the
 *       advice automatically for servlet web applications; disable with
 *       {@code pos.web.global-exception-handler.enabled=false}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * Add pos-web-common as a dependency (pos-security-common re-exports it, so services using
 * gateway security already have it). No further wiring is needed: the advice registers via
 * Spring Boot auto-configuration and only handles exceptions no service advice mapped.
 * </p>
 */
package com.positivity.web.common;
