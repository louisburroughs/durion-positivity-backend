package com.positivity.people.internal.security;

/**
 * Permission names this module enforces, as constants rather than string literals at each call
 * site.
 *
 * <h2>Why constants and not literals</h2>
 *
 * A literal is invisible to a reader looking for everywhere a permission is used, and it is one
 * typo away from an authority nobody holds — {@code @PreAuthorize} fails closed, so a misspelling
 * does not break the build or the test suite, it silently locks the endpoint. Naming the permission
 * once means the compiler checks every use of it.
 *
 * <p>The repo-wide permission tooling reads these too:
 * {@code scripts/generate-permissions.sh --sync} resolves constant references when it decides
 * whether a permission is registered in the catalogs, so a permission introduced here is picked up
 * without a manual bit assignment.
 */
public final class PeoplePermissions {
    /** View availability. */
    public static final String AVAILABILITY_VIEW = "people:availability:view";

    /** View identity-compliance reports (active users linked to inactive persons). */
    public static final String COMPLIANCE_VIEW = "people:compliance:view";

    /** Create employee records. */
    public static final String EMPLOYEE_CREATE = "people:employee:create";

    /** Deactivate employee records. */
    public static final String EMPLOYEE_DEACTIVATE = "people:employee:deactivate";

    /** Edit employee records. */
    public static final String EMPLOYEE_EDIT = "people:employee:edit";

    /** View employee records. */
    public static final String EMPLOYEE_VIEW = "people:employee:view";

    /** Approve timeAdjustment. */
    public static final String TIMEADJUSTMENT_APPROVE = "people:timeAdjustment:approve";

    /** Create timeAdjustment. */
    public static final String TIMEADJUSTMENT_CREATE = "people:timeAdjustment:create";

    /** View timeAdjustment. */
    public static final String TIMEADJUSTMENT_VIEW = "people:timeAdjustment:view";

    /** Approve timeEntry. */
    public static final String TIMEENTRY_APPROVE = "people:timeEntry:approve";

    /** Reject timeEntry. */
    public static final String TIMEENTRY_REJECT = "people:timeEntry:reject";

    /** View timeEntry. */
    public static final String TIMEENTRY_VIEW = "people:timeEntry:view";

    /** Acknowledge timeException. */
    public static final String TIMEEXCEPTION_ACKNOWLEDGE = "people:timeException:acknowledge";

    /** Create timeException. */
    public static final String TIMEEXCEPTION_CREATE = "people:timeException:create";

    /** Resolve timeException. */
    public static final String TIMEEXCEPTION_RESOLVE = "people:timeException:resolve";

    /** View timeException. */
    public static final String TIMEEXCEPTION_VIEW = "people:timeException:view";

    /** Create pay periods (time periods) for timekeeping approval. */
    public static final String TIMEPERIOD_CREATE = "people:timePeriod:create";

    /** Transition a pay period's lifecycle status (open, close submissions, close payroll). */
    public static final String TIMEPERIOD_TRANSITION = "people:timePeriod:transition";

    /** Approve timekeeping period entries. */
    public static final String TIMEKEEPING_APPROVE = "people:timekeeping:approve";

    /** Reject timekeeping period entries. */
    public static final String TIMEKEEPING_REJECT = "people:timekeeping:reject";

    /** View timekeeping entries and periods. */
    public static final String TIMEKEEPING_VIEW = "people:timekeeping:view";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong elsewhere. Their definition, bit assignment and description live with their owner —
    // this is a reference, not a claim of ownership.

    /** Owned by the accounting domain. */
    public static final String ACCOUNTING_TIME_EXPORT = "accounting:time:export";

    private PeoplePermissions() {
        // Utility class - prevent instantiation
    }
}
