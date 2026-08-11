package com.positivity.supplier.internal.domain.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * A protocol norm version key within a {@link ProtocolFamily} (ADR-0051 §3).
 *
 * <p>Version keys are <em>data, not an enum</em>: vendors add norm versions without a code
 * change — the vendor profile binding names the version, and a new codec registers under the
 * new key. Constants below cover the norms known from {@code docs/ediwheel/}.
 *
 * @param value the normalized version key, e.g. {@code "C1_1"}; never blank
 */
public record ProtocolVersion(@NonNull String value) {

    public static final ProtocolVersion A2_5 = new ProtocolVersion("A2_5");
    public static final ProtocolVersion B2_1 = new ProtocolVersion("B2_1");
    public static final ProtocolVersion B3_3 = new ProtocolVersion("B3_3");
    public static final ProtocolVersion B4_0 = new ProtocolVersion("B4_0");
    public static final ProtocolVersion C1_0 = new ProtocolVersion("C1_0");
    public static final ProtocolVersion C1_1 = new ProtocolVersion("C1_1");
    public static final ProtocolVersion C1_2 = new ProtocolVersion("C1_2");
    public static final ProtocolVersion S2S_V1 = new ProtocolVersion("S2S_V1");

    public ProtocolVersion {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ProtocolVersion value must not be blank");
        }
    }
}
