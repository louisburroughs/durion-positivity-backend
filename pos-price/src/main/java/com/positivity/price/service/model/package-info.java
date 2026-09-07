/**
 * Contract DTO records for the pos-price grant surface (ADR-0026 D1–D5).
 *
 * <p>Records only, and no {@code com.positivity.price.internal..} references: these types cross
 * the module wall, so an internal enum or entity leaking into one would drag the whole internal
 * package after it.
 */
package com.positivity.price.service.model;
