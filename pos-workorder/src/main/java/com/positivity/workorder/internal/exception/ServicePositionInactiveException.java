package com.positivity.workorder.internal.exception;

import com.positivity.workorder.internal.enums.ResourceType;
import java.util.UUID;

/**
 * A bay or mobile unit that pos-location has not marked active was named as a service position
 * (#2001).
 *
 * <p>Distinct from {@link ServicePositionInvalidException} on purpose even though both answer 422:
 * an unknown or foreign position is a mistake in the request, while an inactive one is a real
 * position at the right site that is simply out of service or not deployed. A dispatcher can act on
 * the second — bring the bay back into service, deploy the van — and the SDK and the dispatch board
 * both want to say so rather than "invalid position".
 *
 * <p>The status comes from the {@code ext_bay} / {@code ext_mobile_unit} replicas pos-location feeds
 * ({@code active}), not from a synchronous call into that service (ADR-0044 §6). A position whose
 * replica row has not arrived yet is not this: it stays the unknown-position refusal
 * {@link ServicePositionInvalidException} raises today.
 */
public class ServicePositionInactiveException extends RuntimeException {

    public static final String ERROR_CODE = "SERVICE_POSITION_INACTIVE";

    public ServicePositionInactiveException(ResourceType resourceType, UUID resourceId, String name) {
        super(resourceType + " " + resourceId + (name == null || name.isBlank() ? "" : " (" + name + ")")
                + " is INACTIVE and cannot take a workorder");
    }
}
