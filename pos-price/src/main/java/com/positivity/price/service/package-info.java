/**
 * Public contract surface of the pos-price module (ADR-0026 D1–D5).
 *
 * <p>This package contains <em>interfaces only</em>; implementations live under
 * {@code com.positivity.price.internal..} and are invisible to other modules. Contract DTO
 * records live in {@link com.positivity.price.service.model}. Nothing in this package may
 * reference {@code com.positivity.price.internal..} types (ADR-0026 D4, enforced by the module
 * {@code ArchitectureTest}).
 *
 * <p>Per ADR-0026 D2 this package holds only the module's granted contract surface. The single
 * grant is {@link com.positivity.price.service.ShopLaborRateService} — the hourly rate half of a
 * labor line, resolved at quote time (#1575 Tier 0, ADR-0044 amendment 2026-09-07). It mirrors
 * the pos-catalog labor-time edge deliberately: pos-catalog answers how long, pos-price answers
 * how much per hour, and pos-workorder multiplies them. Everything else other modules need from
 * pos-price rides events.
 */
package com.positivity.price.service;
