package com.positivity.mcp.internal.service;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Adaptive tool-priority tuning mode (Gate 7, #1195). Replaces the legacy boolean
 * {@code mcp.tuning.enabled} with a three-state {@code mcp.tuning.mode} knob:
 *
 * <ul>
 *   <li>{@link #OFF} — the nightly cron is a no-op (default).</li>
 *   <li>{@link #SHADOW} — proposed priority changes are computed and logged (structured logger
 *       {@code mcp.tuning.shadow}) and counted ({@code mcp.tuning.proposals}) but never written.</li>
 *   <li>{@link #LIVE} — proposals are written, gated on a fresh, passing eval baseline; a
 *       missing/stale/failed baseline downgrades the run to shadow behavior.</li>
 * </ul>
 */
public enum TuningMode {
    OFF,
    SHADOW,
    LIVE;

    /**
     * Resolves the effective mode from {@code mcp.tuning.mode} with backward compatibility for the
     * deprecated {@code mcp.tuning.enabled} boolean: when the mode is unset and the legacy flag is
     * {@code true}, the effective mode is {@link #LIVE} (callers should log a deprecation warning).
     */
    public static @NonNull TuningMode resolve(@Nullable String configuredMode, @Nullable String legacyEnabled) {
        if (configuredMode != null && !configuredMode.isBlank()) {
            String normalized = configuredMode.trim().toUpperCase(Locale.ROOT);
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Invalid mcp.tuning.mode value '" + configuredMode + "'; expected one of: off, shadow, live",
                        exception);
            }
        }
        if (legacyEnabled != null && "true".equalsIgnoreCase(legacyEnabled.trim())) {
            return LIVE;
        }
        return OFF;
    }

    /** True when the legacy {@code mcp.tuning.enabled} flag (not the mode) selected this run. */
    public static boolean isLegacyActivation(@Nullable String configuredMode, @Nullable String legacyEnabled) {
        return (configuredMode == null || configuredMode.isBlank())
                && legacyEnabled != null
                && "true".equalsIgnoreCase(legacyEnabled.trim());
    }
}
