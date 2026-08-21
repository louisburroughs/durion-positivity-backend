package com.positivity.workorder.internal.security;

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
public final class WorkorderPermissions {
    /** Create approval configurations. */
    public static final String APPROVAL_CONFIG_CREATE = "workorder:approval_config:create";

    /** Delete approval configurations. */
    public static final String APPROVAL_CONFIG_DELETE = "workorder:approval_config:delete";

    /** Edit approval configurations. */
    public static final String APPROVAL_CONFIG_EDIT = "workorder:approval_config:edit";

    /** View approval configurations. */
    public static final String APPROVAL_CONFIG_VIEW = "workorder:approval_config:view";

    /** Approve change requests. */
    public static final String CHANGE_REQUEST_APPROVE = "workorder:change_request:approve";

    /** Create change requests. */
    public static final String CHANGE_REQUEST_CREATE = "workorder:change_request:create";

    /** Decline change requests. */
    public static final String CHANGE_REQUEST_DECLINE = "workorder:change_request:decline";

    /** Apply emergency override to change requests (Manager only). */
    public static final String CHANGE_REQUEST_EMERGENCY_OVERRIDE = "workorder:change_request:emergency_override";

    /** View change requests. */
    public static final String CHANGE_REQUEST_VIEW = "workorder:change_request:view";

    /** View dashboard. */
    public static final String DASHBOARD_VIEW = "workorder:dashboard:view";

    /** Approve estimates. */
    public static final String ESTIMATE_APPROVE = "workorder:estimate:approve";

    /** Calculate estimate totals and taxes. */
    public static final String ESTIMATE_CALCULATE = "workorder:estimate:calculate";

    /** Create estimates. */
    public static final String ESTIMATE_CREATE = "workorder:estimate:create";

    /** Decline estimates. */
    public static final String ESTIMATE_DECLINE = "workorder:estimate:decline";

    /** Delete estimates. */
    public static final String ESTIMATE_DELETE = "workorder:estimate:delete";

    /** Edit estimates. */
    public static final String ESTIMATE_EDIT = "workorder:estimate:edit";

    /** Promote estimate. */
    public static final String ESTIMATE_PROMOTE = "workorder:estimate:promote";

    /** Reopen declined estimates. */
    public static final String ESTIMATE_REOPEN = "workorder:estimate:reopen";

    /** Submit estimate. */
    public static final String ESTIMATE_SUBMIT = "workorder:estimate:submit";

    /** View estimates. */
    public static final String ESTIMATE_VIEW = "workorder:estimate:view";

    /** Add items to estimates. */
    public static final String ESTIMATE_ITEM_ADD = "workorder:estimate_item:add";

    /** Remove items from estimates. */
    public static final String ESTIMATE_ITEM_DELETE = "workorder:estimate_item:delete";

    /** Edit estimate items. */
    public static final String ESTIMATE_ITEM_EDIT = "workorder:estimate_item:edit";

    /** Create estimate snapshots. */
    public static final String ESTIMATE_SNAPSHOT_CREATE = "workorder:estimate_snapshot:create";

    /** Re-emit published domain events from the outbox (replica seeding and drift repair). */
    public static final String EVENTS_REPLAY = "workorder:events:replay";

    /** Add labor entries. */
    public static final String LABOR_ADD = "workorder:labor:add";

    /** Start/record labor for a technician other than the authenticated actor (Manager only). */
    public static final String LABOR_ADD_ON_BEHALF = "workorder:labor:add_on_behalf";

    /** View labor entries. */
    public static final String LABOR_VIEW = "workorder:labor:view";

    /** Override operationalContext. */
    public static final String OPERATIONALCONTEXT_OVERRIDE = "workorder:operationalContext:override";

    /** Add parts to workorder. */
    public static final String PARTS_ADD = "workorder:parts:add";

    /** Consume picked items into workorder via pick facade. */
    public static final String PARTS_CONSUME = "workorder:parts:consume";

    /** View parts on workorder. */
    public static final String PARTS_VIEW = "workorder:parts:view";

    /** Start workorder. */
    public static final String START = "workorder:start";

    /** Approve timeEntry. */
    public static final String TIMEENTRY_APPROVE = "workorder:timeEntry:approve";

    /** Reject timeEntry. */
    public static final String TIMEENTRY_REJECT = "workorder:timeEntry:reject";

    /** View work-in-progress dashboard for workorders. */
    public static final String WIP_VIEW = "workorder:wip:view";

    /** Approve workorders. */
    public static final String WORKORDER_APPROVE = "workorder:workorder:approve";

    /** Assign technician workorder. */
    public static final String WORKORDER_ASSIGN_TECHNICIAN = "workorder:workorder:assign-technician";

    /** Complete workorders. */
    public static final String WORKORDER_COMPLETE = "workorder:workorder:complete";

    /** Create workorders. */
    public static final String WORKORDER_CREATE = "workorder:workorder:create";

    /** Delete workorders (hard delete of mistakenly created records). */
    public static final String WORKORDER_DELETE = "workorder:workorder:delete";

    /** Generate invoice workorder. */
    public static final String WORKORDER_GENERATE_INVOICE = "workorder:workorder:generate_invoice";

    /** Reopen completed workorders. */
    public static final String WORKORDER_REOPEN_COMPLETED = "workorder:workorder:reopen_completed";

    /** View workorders. */
    public static final String WORKORDER_VIEW = "workorder:workorder:view";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong elsewhere. Their definition, bit assignment and description live with their owner —
    // this is a reference, not a claim of ownership.

    /** Owned by the inventory domain. */
    public static final String INVENTORY_PICK_LIST_EXECUTE = "inventory:pick_list:execute";

    /** Owned by the inventory domain. */
    public static final String INVENTORY_PICK_LIST_VIEW = "inventory:pick_list:view";

    /** Owned by the tax domain. */
    public static final String TAX_CALCULATE = "tax:calculate";

    /** Owned by the timekeeping domain. */
    public static final String TIMEKEEPING_WORK_SESSION_BREAK_START = "timekeeping:work_session:break_start";

    /** Owned by the timekeeping domain. */
    public static final String TIMEKEEPING_WORK_SESSION_BREAK_STOP = "timekeeping:work_session:break_stop";

    /** Owned by the timekeeping domain. */
    public static final String TIMEKEEPING_WORK_SESSION_CREATE = "timekeeping:work_session:create";

    /** Owned by the timekeeping domain. */
    public static final String TIMEKEEPING_WORK_SESSION_STOP = "timekeeping:work_session:stop";

    /**
     * Requesting a fleet payment authorization, and reading its status.
     *
     * <p>Requesting is its own permission rather than folded into general workorder editing: it
     * reaches another domain and can affect whether a job may start, and the people who may
     * investigate a stuck authorization are not necessarily the people who may edit a workorder.
     */
    public static final String FLEET_AUTHORIZATION_REQUEST = "workorder:fleet_auth:request";

    /**
     * Resolving a fleet payment authorization that did not come back granted.
     *
     * <p>Separate from requesting, per the domain ruling that a refusal is resolved explicitly by an
     * advisor selecting revised scope or cancellation, each needing its own permission and audit
     * record.
     */
    public static final String FLEET_AUTHORIZATION_RESOLVE = "workorder:fleet_auth:resolve";

    private WorkorderPermissions() {
        // Utility class - prevent instantiation
    }
}
