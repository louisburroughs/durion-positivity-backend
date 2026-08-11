package com.positivity.supplier.internal.spi;

/**
 * Capability port: AI tire identification from sidewall imagery (DOT code recognition,
 * TireSnap) ({@code TIRE_IDENTIFICATION}; vendor value-add, not an EDIWheel norm).
 *
 * <p><strong>Dormant placeholder</strong> (architecture doc §6.1, decision 10; durion#379):
 * DOT scanning is expected to be required by most service providers, so the port and registry
 * slot are defined up front, but <em>no adapter is implemented until the requirement is
 * confirmed</em>. Operations ({@code recognizeDotCode}, {@code recognizeSidewall}) are
 * defined when that happens.
 *
 * <p>Governing ADRs: ADR-0049 §1, ADR-0051 §3.
 */
public interface TireIdentificationPort {}
