package com.positivity.workorder.internal.exception;

/**
 * A service position was named that this workorder cannot be placed on (#1983, #1984).
 *
 * <p>Three cases, one code: the bay or mobile unit is unknown to the {@code ext_bay} /
 * {@code ext_mobile_unit} replicas, it belongs to a different site than the workorder, or a
 * {@link com.positivity.workorder.internal.enums.ResourceType#HOLD} position was named with an id
 * that is not the workorder's own site.
 *
 * <p>422, not 400 and not 404: the payload is well-formed and every field is within its declared
 * type, so the request parses; and the position is not the thing being addressed by the URL, so it
 * is not the missing resource. What fails is a cross-entity rule — this position is not one this
 * workorder may occupy — which ADR-0017 §2 places at 422.
 */
public class ServicePositionInvalidException extends RuntimeException {

    public static final String ERROR_CODE = "SERVICE_POSITION_INVALID";

    public ServicePositionInvalidException(String message) {
        super(message);
    }
}
