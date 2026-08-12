package com.positivity.supplier.internal.domain.model;

/**
 * Wire-format families, one adapter package per family (ADR-0051 §2). Two vendors speaking
 * the same norm share the family adapter with different profile bindings.
 *
 * <p>{@link #TEST} exists solely for test fixtures exercising the adapter registry; no
 * production codec may register under it.
 */
public enum ProtocolFamily {
    /** EDIWheel A2.x XML (legacy order/status/stock inquiry norms). */
    EDIWHEEL_A25,
    /** EDIWheel C1.0 / C1.1 XML (current message implementation guidelines). */
    EDIWHEEL_C1,
    /** EDIWheel B-series report-style services (B2.1 stock report, B3.3 invoice, B4.0 PRICAT). */
    EDIWHEEL_B,
    /** EDIWheel C1.2 JSON (emerging ediwheel.net resource+message API, MKCAT). */
    EDIWHEEL_JSON,
    /** Michelin proprietary S2S JSON (fleet workorder authorization, OAuth2). */
    MICHELIN_S2S,
    /** Test-only family for registry fixtures. */
    TEST
}
