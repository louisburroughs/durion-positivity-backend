package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One staffing assignment.
 *
 * <p>The employee number is passed through rather than resolved here: pos-people owns employee
 * numbers, so it resolves them itself when the batch arrives. Only the location, which belongs to
 * another service, has to be resolved on the way.
 */
@Data
public class StaffingAssignmentLoaderRecord {

    private String employeeNumber;
    private String locationCode;
    private String role;
    private String primary;
    private String effectiveFrom;
    private String effectiveTo;

    /** Resolved from {@code locationCode}, or supplied directly. */
    private String locationId;
}
