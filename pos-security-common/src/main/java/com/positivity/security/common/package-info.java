/**
 * Shared security components for gateway-based JWT authentication.
 *
 * <h2>Architecture</h2>
 * <p>
 * This module provides security infrastructure for pos-* services that rely on
 * the API gateway for JWT validation. The gateway validates tokens and injects
 * headers that services trust.
 * </p>
 *
 * <pre>
 * ┌─────────────┐    JWT     ┌───────────────┐  validates  ┌────────────────────┐
 * │   Client    │───────────▶│ pos-api-      │────────────▶│ pos-security-      │
 * │             │            │ gateway       │             │ service            │
 * └─────────────┘            └───────┬───────┘             └────────────────────┘
 *                                    │
 *                    injects X-Authorities, X-User
 *                                    │
 *                                    ▼
 *                            ┌───────────────┐
 *                            │   pos-*       │  ◀── Uses this module
 *                            │   services    │
 *                            └───────────────┘
 * </pre>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li>{@link com.positivity.security.common.GatewayAuthoritiesFilter} -
 *       Reads gateway headers and populates SecurityContext</li>
 *   <li>{@link com.positivity.security.common.GatewaySecurityConfig} -
 *       Base security configuration for services</li>
 *   <li>{@link com.positivity.security.common.PermissionRegistrationSupport} -
 *       Register service permissions at startup</li>
 *   <li>{@link com.positivity.security.common.SecurityContextHelper} -
 *       Utility for accessing current user/authorities</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * Services should:
 * </p>
 * <ol>
 *   <li>Add pos-security-common as a dependency</li>
 *   <li>Import {@link com.positivity.security.common.GatewaySecurityConfig}</li>
 *   <li>Create a permission registration class extending
 *       {@link com.positivity.security.common.PermissionRegistrationSupport}</li>
 *   <li>Use {@code @PreAuthorize} annotations on controllers</li>
 * </ol>
 *
 * @see com.positivity.security.common.GatewaySecurityConstants
 */
package com.positivity.security.common;
