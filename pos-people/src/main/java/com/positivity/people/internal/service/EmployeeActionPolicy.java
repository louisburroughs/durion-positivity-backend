package com.positivity.people.internal.service;

import com.positivity.people.internal.enums.AllowedAction;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.security.PeoplePermissions;
import com.positivity.security.common.SecurityContextHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The single place that computes which {@link AllowedAction}s an employee row may offer its
 * caller (durion#2159), from exactly two inputs: the caller's granted authorities and the
 * employee's current {@link EmployeeStatus}.
 *
 * <h2>Why one class</h2>
 *
 * Before this issue, "can this row's activate/deactivate switch be shown" was decided twice:
 * once by the backend's {@code @PreAuthorize} + service-guard pair (the actual gate), and again,
 * independently, by a status allow-list hand-maintained in the Angular register (DECISION-PEOPLE-
 * 013). The two only ever agreed by someone remembering to update both places; the day the
 * lifecycle grew {@code ON_LEAVE} and {@code SUSPENDED} nobody did, and the UI kept offering a
 * control the backend then refused. This class exists so there is exactly one transition table,
 * read by both {@code EmployeeProfileDto} and {@code EmployeeSummaryDto}, and it is deliberately
 * <em>not</em> spread across the DTO mapper, the service, or the controller — every call site
 * that needs the answer calls in here rather than re-deriving it.
 *
 * <h2>The matrix (caller holding {@code people:employee:activation})</h2>
 *
 * <table border="1">
 * <caption>DISABLE / ENABLE by current status</caption>
 * <tr><th>status</th><th>DISABLE</th><th>ENABLE</th></tr>
 * <tr><td>ACTIVE</td><td>yes</td><td>no</td></tr>
 * <tr><td>DISABLED</td><td>no</td><td>yes</td></tr>
 * <tr><td>ON_LEAVE</td><td>no</td><td>no</td></tr>
 * <tr><td>SUSPENDED</td><td>no</td><td>no</td></tr>
 * <tr><td>TERMINATED</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * This mirrors {@code EmployeeServiceImpl.disableEmployee} and {@code #enableEmployee} exactly:
 * {@code disableEmployee} requires status to be exactly {@code ACTIVE} (it 409s on every other
 * status, including {@code ON_LEAVE}/{@code SUSPENDED}, which is why they are "no" here too, not
 * just the obviously-terminal ones), and {@code enableEmployee} requires status to be exactly
 * {@code DISABLED} ({@code TERMINATED} is the irreversible terminal state per DECISION-PEOPLE-001,
 * and {@code ON_LEAVE}/{@code SUSPENDED} are rejected in favor of {@code updateEmployee}, which
 * alone can record the effective date and reason those transitions carry). A caller without
 * {@code people:employee:activation} gets neither flag, for every status.
 *
 * <p>{@link AllowedAction#VIEW_PII} and {@link AllowedAction#UPDATE} are permission-only: {@code
 * VIEW_PII} follows {@code people:employee_pii:view} exactly (the permission alone gates {@code
 * GET /v1/people/employees/{employeeId}}; there is no additional status guard on that read), and
 * {@code UPDATE} follows {@code people:employee:edit} exactly. On {@code UPDATE}: DECISION-
 * PEOPLE-025 documents that terminated employees should be read-only by default with edits
 * requiring an explicit HR-admin capability, but as of this issue {@code
 * EmployeeServiceImpl#updateEmployee} enforces no status guard at all — a {@code TERMINATED}
 * employee can be updated the same as any other. This policy intentionally tracks the guard the
 * service actually enforces, not the undocumented gap between it and DECISION-PEOPLE-025 (adding
 * a status rule here that the service does not itself apply would make this policy disagree with
 * {@code updateEmployee}, which is exactly the failure mode this class exists to prevent). Closing
 * that gap is separate follow-up work; when {@code updateEmployee} gains the DECISION-PEOPLE-025
 * guard, add the matching status check here in the same change.
 *
 * <h2>Rendering hint, not a gate</h2>
 *
 * Every flag this class returns is advisory. The backend continues to enforce authorization and
 * state transitions independently through {@code @PreAuthorize} on the controller and the
 * explicit status guards in {@code EmployeeServiceImpl} — a client must never treat an {@code
 * allowedActions} entry as proof an operation will succeed, only as a hint for which controls to
 * offer.
 *
 * <h2>Known limitation: no location-scope awareness</h2>
 *
 * The flags are computed from permissions and status only. This module also has location-scoped
 * access elsewhere ({@code StaffingAssignmentServiceImpl}, {@code PeopleAvailabilityServiceImpl},
 * {@code WorkSessionAccessPolicy}), so a location-scoped caller viewing an employee outside their
 * reach may see a flag for an action their scope would actually deny. This is a deliberate
 * boundary, not an oversight: making the flags scope-aware would require a per-row active-
 * assignment lookup, which is exactly the per-row cost the register's window-only enrichment
 * design (durion#2155, see {@code EmployeeServiceImpl#enrichWindow}) exists to avoid — enriching
 * against every row instead of the returned page turns a bounded, page-sized cost into one that
 * scales with tenant size. A scope-aware caller still gets a correct 403 from the real gate; the
 * only cost of this limitation is an occasionally-offered control that the backend then declines.
 */
@Component
public class EmployeeActionPolicy {

    /**
     * The actions {@code callerAuthorities} may take on an employee currently in {@code status},
     * per the matrix in this class's javadoc. {@code status} may be null (e.g. a profile served
     * from the identity replica with no local employment row yet) — no status-gated action is
     * offered in that case, since neither {@code disableEmployee} nor {@code enableEmployee} has
     * a status to compare against either.
     */
    public @NonNull List<AllowedAction> allowedActions(
            @Nullable EmployeeStatus status, @NonNull Set<String> callerAuthorities) {
        List<AllowedAction> actions = new ArrayList<>();

        if (callerAuthorities.contains(PeoplePermissions.EMPLOYEE_PII_VIEW)) {
            actions.add(AllowedAction.VIEW_PII);
        }
        if (callerAuthorities.contains(PeoplePermissions.EMPLOYEE_EDIT)) {
            actions.add(AllowedAction.UPDATE);
        }
        if (callerAuthorities.contains(PeoplePermissions.EMPLOYEE_ACTIVATION)) {
            if (status == EmployeeStatus.ACTIVE) {
                actions.add(AllowedAction.DISABLE);
            } else if (status == EmployeeStatus.DISABLED) {
                actions.add(AllowedAction.ENABLE);
            }
        }

        return List.copyOf(actions);
    }

    /** {@link #allowedActions} for the caller resolved from the request's security context. */
    public @NonNull List<AllowedAction> allowedActionsForCurrentCaller(@Nullable EmployeeStatus status) {
        return allowedActions(status, currentCallerAuthorities());
    }

    /**
     * The current caller's authorities, or an empty set when there is no authenticated security
     * context. Every controller path that reaches this policy is already behind a {@code
     * @PreAuthorize} gate, so in production there is always a caller; the empty-set fallback
     * exists for callers with no HTTP request in flight (and, in practice, for unit tests that
     * exercise {@code EmployeeServiceImpl} directly without wiring a security context, matching
     * {@code SecurityContextHelper.getCurrentUsernameOrDefault}'s existing fail-soft convention
     * for that situation). Because {@code allowedActions} is a rendering hint and never the actual
     * gate, degrading to "offers nothing" here weakens no authorization decision.
     *
     * <p>Public (rather than folded into {@link #allowedActionsForCurrentCaller}) so a caller
     * enriching many rows in one request -- {@code EmployeeServiceImpl#enrichWindow} -- can
     * resolve it once per page and pass it to {@link #allowedActions} per row, instead of
     * re-reading the security context once per row.
     */
    public @NonNull Set<String> currentCallerAuthorities() {
        try {
            return SecurityContextHelper.getAuthorities();
        } catch (IllegalStateException ex) {
            return Set.of();
        }
    }
}
