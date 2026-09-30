package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A technician who is no longer employed was assigned or reassigned to a workorder (#2120).
 *
 * <p>"No longer employed" = the person's latest {@code ext_people_employee} row has a status of
 * TERMINATED, DISABLED or SUSPENDED. pos-people owns employment; this module reads it only from
 * the replica {@code people.employee.updated} populates, never by a synchronous call into that
 * service (ADR-0044 §6). A person with no employee row at all is allowed: replica lag, bootstrap
 * and a stalled DLQ must not take a shop offline.
 *
 * <p>422, not 409 and not 404: the technician exists ({@link TechnicianNotFoundException} covers
 * "does not exist") and the request is well-formed. What fails is a cross-entity rule between the
 * technician and their employment, which ADR-0017 §2 places at 422. A {@code fieldErrors} entry
 * marks {@code technicianId} so a form can highlight the offending field.
 */
public class TechnicianNotActiveException extends RuntimeException {

    public static final String ERROR_CODE = "TECHNICIAN_NOT_ACTIVE";

    public static final String FIELD = "technicianId";

    public static final String NEXT_ACTION =
            "Choose a technician who is currently employed, or have the employment status corrected in People and retry.";

    public static final String SUPPORT_ACTION =
            "A People administrator can review or correct the technician's employment status.";

    private final transient UUID technicianId;
    private final String employmentStatus;

    public TechnicianNotActiveException(UUID technicianId, String employmentStatus) {
        super("Technician " + technicianId + " cannot be assigned: employment status is " + employmentStatus);
        this.technicianId = technicianId;
        this.employmentStatus = employmentStatus;
    }

    /** The technician who was refused. */
    public UUID getTechnicianId() {
        return technicianId;
    }

    /** The employment status that caused the refusal, e.g. {@code TERMINATED}. */
    public String getEmploymentStatus() {
        return employmentStatus;
    }
}
