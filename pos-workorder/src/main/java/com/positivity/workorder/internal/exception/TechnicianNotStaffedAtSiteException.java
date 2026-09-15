package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * A technician was assigned or reassigned to a workorder at a site they are not staffed at
 * (#1990).
 *
 * <p>Staffed = at least one {@code ext_people_staffing_assignment} row for the technician with
 * {@code status = ACTIVE} at the workorder's site, effective today. pos-people owns the
 * technician-to-site relation; this module reads it only from the replica the event chain
 * populates, never by a synchronous call into that service (ADR-0044 §6). {@code is_primary} is
 * not considered — a technician can be staffed at more than one site and any of them is enough —
 * and role is not filtered on, because {@code EmployeeLocationAssignment.role} is free text and
 * filtering on it would refuse a real technician over a typo. A technician with no ACTIVE staffing
 * rows at all is allowed: replica lag, bootstrap and a stalled DLQ must not take a shop offline.
 *
 * <p>There is no override for this refusal and none is planned: a technician must be staffed at
 * the workorder's site, full stop.
 *
 * <p>422, not 409 and not 404: the technician exists ({@link TechnicianNotFoundException} covers
 * "does not exist") and the request is well-formed. What fails is a cross-entity rule between the
 * technician and the site the workorder stands at, which ADR-0017 §2 places at 422. The
 * workorder's site rides as {@code referenceId} so a dispatch board can link straight to it, and a
 * {@code fieldErrors} entry marks {@code technicianId} so a form can highlight the offending field.
 */
public class TechnicianNotStaffedAtSiteException extends RuntimeException {

    public static final String ERROR_CODE = "TECHNICIAN_NOT_STAFFED_AT_SITE";

    public static final String FIELD = "technicianId";

    public static final String NEXT_ACTION =
            "Have this technician assigned to this site in People, effective today, then retry.";

    public static final String SUPPORT_ACTION =
            "A People administrator or location manager can add or correct the technician's site staffing.";

    private final transient UUID technicianId;
    private final transient UUID siteId;

    public TechnicianNotStaffedAtSiteException(UUID technicianId, UUID siteId) {
        super("Technician " + technicianId + " is not staffed at site " + siteId);
        this.technicianId = technicianId;
        this.siteId = siteId;
    }

    /** The technician who was refused. */
    public UUID getTechnicianId() {
        return technicianId;
    }

    /** The workorder's site, carried as {@code referenceId} on the {@code ApiError}. */
    public UUID getSiteId() {
        return siteId;
    }
}
