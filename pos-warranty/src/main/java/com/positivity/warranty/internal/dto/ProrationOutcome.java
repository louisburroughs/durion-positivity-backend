package com.positivity.warranty.internal.dto;

import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-line proration computation result (PRD §6 table). When a required input is missing or a
 * denominator is zero the outcome is {@code indeterminate} — {@code prorationPct} and/or
 * {@code amountRequested} are null and {@code inputs} carries a {@code missing} list naming the
 * absent facts. {@code inputs} freezes every number that fed the computation, for audit
 * (persisted as {@code prorationInputs} on the claim line).
 *
 * @param prorationPct credit fraction in [0, 1], scale 6, or null when indeterminate
 * @param amountRequested computed credit clamped to [0, originalUnitPrice x quantity], scale 4,
 *     or null when the price or the fraction is unknown
 * @param inputs audit snapshot of all computation inputs (JSON-friendly values)
 * @param indeterminate true when any required fact was missing or a denominator was invalid
 */
public record ProrationOutcome(
        @Nullable BigDecimal prorationPct,
        @Nullable BigDecimal amountRequested,
        Map<String, Object> inputs,
        boolean indeterminate) {}
