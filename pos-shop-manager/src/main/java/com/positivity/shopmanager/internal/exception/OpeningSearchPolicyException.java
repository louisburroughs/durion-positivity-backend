package com.positivity.shopmanager.internal.exception;

/**
 * A syntactically valid {@code GET /v1/schedules/openings} request that exceeds a documented policy
 * bound (#2022 AC11): the forward horizon, the result limit, or the requested services.
 *
 * <p>Mapped to {@code 422 Unprocessable Content}, like {@link ScheduleCapacityRangeExceededException}
 * and for the same reason: DECISION-SHOPMGMT-011 puts policy failures at 422 and syntactic ones at
 * 400. The code names which bound.
 */
public class OpeningSearchPolicyException extends RuntimeException {

    public static final String HORIZON_EXCEEDED = "OPENING_HORIZON_EXCEEDED";
    public static final String LIMIT_EXCEEDED = "OPENING_LIMIT_EXCEEDED";
    public static final String TOO_MANY_SERVICES = "OPENING_TOO_MANY_SERVICES";
    public static final String LOCATION_HOURS_UNKNOWN = "LOCATION_HOURS_UNKNOWN";

    private final String code;

    public OpeningSearchPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
