package com.positivity.workorder.internal.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discriminator for the physical resource a workorder is assigned to (#1656).
 *
 * <p>pos-location owns two genuinely distinct resource aggregates — {@code BayEntity}
 * (a fixed service bay at a site) and {@code MobileUnitEntity} (a field-service vehicle based at a
 * site). They have separate identity and lifecycle and share no table, so a bare
 * {@code Workorder.resourceId} UUID cannot say which one it points at. This enum is that missing
 * type tag; it rides the assignment chain
 * ({@code AssignmentUpdatePayload} → {@code AssignmentUpdatedEvent} → {@code Workorder}) and is
 * what the dispatch dashboard branches on when it resolves resource identity from the
 * {@code ext_bay} / {@code ext_mobile_unit} replicas.
 */
public enum ResourceType {
    /** A fixed service bay owned by pos-location ({@code GET /v1/locations/{locationId}/bays}). */
    BAY,

    /** A mobile service unit owned by pos-location ({@code GET /v1/mobile-units}). */
    MOBILE_UNIT;

    private static final Logger log = LoggerFactory.getLogger(ResourceType.class);

    /**
     * Lenient JSON binding for the inbound assignment chain (#1656).
     *
     * <p>The only producer of this field is upstream and outside this module's control, and the
     * value arrives inside a payload that also carries the location, the resource id and the
     * mechanics. Strict enum binding would make one unrecognised or lower-case token throw out of
     * {@code treeToValue} and cost the <em>entire</em> assignment update, not just the type — the
     * caller is a log-and-swallow Kafka listener, so the loss would be silent. Accepting any case
     * and downgrading an unrecognised token to "absent" keeps the rest of the update intact, and
     * the token is named in a warning so a real contract drift is still visible in the logs.
     *
     * <p>Returning {@code null} rather than {@link #BAY} directly is deliberate: an unknown value
     * is treated exactly like an omitted one, so the fallback stays in the single place that owns
     * it, {@link #orDefault(ResourceType)}.
     *
     * @param value the raw JSON token, possibly {@code null}, blank, mis-cased or unknown
     * @return the matching constant, or {@code null} when absent or unrecognised
     */
    @JsonCreator
    public static ResourceType fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ResourceType candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        log.warn(
                "Unrecognised resourceType '{}' on an inbound assignment; treating it as absent and applying the {} fallback",
                value,
                BAY);
        return null;
    }

    /**
     * The fallback applied when an inbound assignment carries a resource id but no usable
     * {@code resourceType} (#1656).
     *
     * <p>The upstream pos-shop-manager assignment publisher does not yet emit {@code resourceType};
     * until it does, every assignment that reaches this module untyped is a bay, because the whole
     * assignment chain was bay-only before this change. Defaulting to {@link #BAY} therefore
     * preserves today's behaviour exactly rather than silently reclassifying existing assignments.
     * This is the single place that decision is made — resolve through here, never by
     * re-implementing the null check at a call site. {@link #fromJson(String)} funnels an
     * unrecognised token into the same decision by mapping it to {@code null}.
     *
     * @param resourceType the inbound type, possibly {@code null}
     * @return {@code resourceType} when present, otherwise {@link #BAY}
     */
    public static ResourceType orDefault(ResourceType resourceType) {
        return resourceType != null ? resourceType : BAY;
    }
}
