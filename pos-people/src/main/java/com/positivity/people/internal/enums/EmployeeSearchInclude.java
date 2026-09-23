package com.positivity.people.internal.enums;

/**
 * Optional enrichment categories for {@code GET /v1/people/employees} (durion#2155): each token
 * turns on one extra field/group on {@link com.positivity.people.internal.dto.EmployeeSummaryDto}
 * for the page returned.
 *
 * <p>Repeatable on the query string ({@code ?include=CONTACT_INFO&include=ROLE_ASSIGNMENTS}) --
 * matching how this same endpoint already handles {@code status}, its other multi-valued filter
 * (see {@code EmployeeController#searchEmployees}), rather than a single comma-separated value.
 * One query-param convention for every repeatable parameter on this endpoint beats introducing a
 * second one just for this parameter; Spring's default enum converter binds each repeated
 * {@code include} occurrence the same way it already binds {@code status}, with no extra
 * converter to write or maintain.
 *
 * <p>Omitted entirely, the response is byte-for-byte the pre-#2155 thin row: every field this
 * enum can turn on is null on {@code EmployeeSummaryDto} until its token is requested, so an
 * existing caller (e.g. {@code HrFacadeTool.searchEmployees}) that never passes {@code include}
 * sees no change.
 */
public enum EmployeeSearchInclude {
    /** {@code EmployeeSummaryDto.username}, resolved from {@code ext_people_contact_user_link}. */
    USERNAME,

    /**
     * {@code EmployeeSummaryDto.contactInfo} (email and phone only). Gated separately behind
     * {@code people:employee_pii:view} (#1898): requesting this token without that permission
     * still returns 200 with the field simply absent from the row, never a 403 on the row or the
     * request.
     */
    CONTACT_INFO,

    /**
     * {@code EmployeeSummaryDto.roleAssignments}: the employee's active (DECISION-PEOPLE-026)
     * application-role assignments, batched in one query per page via
     * {@code RoleAssignmentReplicaService}.
     */
    ROLE_ASSIGNMENTS,

    /**
     * {@code EmployeeSummaryDto.primaryLocation} and {@code otherLocationCount}
     * (DECISION-PEOPLE-004): the single primary staffing assignment plus a count of the rest.
     */
    LOCATION,

    /** {@code EmployeeSummaryDto.jobRole}, from the tenant's job-role list (durion#2157). */
    JOB_ROLE
}
