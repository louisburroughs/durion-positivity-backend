package com.positivity.people.internal.enums;

/**
 * Actions {@link com.positivity.people.internal.service.EmployeeActionPolicy} can offer the
 * caller on an employee (durion#2159): a rendering hint the employee register uses to decide
 * whether to show its activate/deactivate switch, PII detail link, and edit control on a given
 * row — never the authorization gate itself. See {@code EmployeeActionPolicy}'s javadoc for the
 * full derivation and {@code EmployeeProfileDto.allowedActions}/{@code
 * EmployeeSummaryDto.allowedActions} for where it is surfaced.
 *
 * <p>This is a closed, typed vocabulary rather than free-form strings on purpose: the generated
 * OpenAPI spec and Angular SDK then carry the enum values as a real contract, so adding a new
 * action (or renaming one) is a visible, versioned change to that contract instead of a string a
 * frontend author could silently mistype or a backend author could silently drop. The whole point
 * of this issue is to stop the UI from inferring this vocabulary itself (DECISION-PEOPLE-013); a
 * loose {@code List<String>} would let that drift creep back in through the field it was meant to
 * remove.
 */
public enum AllowedAction {
    /** The caller may read this employee's personal contact detail (home address, personal phone, emergency contact). */
    VIEW_PII,

    /** The caller may submit a full-profile update for this employee. */
    UPDATE,

    /** The caller may disable (offboard) this employee from its current status. */
    DISABLE,

    /** The caller may re-enable this employee from its current status. */
    ENABLE
}
