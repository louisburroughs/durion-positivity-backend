package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AutomaticWorkItemsResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What the system did without a person (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Done automatically"):
 * payments applied automatically, settlement lines matched automatically and bills approved by the system approver.
 * Bank-reconciliation proposals are never listed; they stay to-do items. Read-only.
 */
public interface WorkItemAutomaticService {

    /** How far back {@code since} may reach, in days on the tenant accounting date. */
    int MAX_LOOK_BACK_DAYS = 31;

    /**
     * One page of what was done at or after {@code since}, oldest first (item id the tiebreak).
     *
     * @param since an ISO instant, or a date read in the tenant's accounting time zone as its start; the start of today
     *     in that zone when null
     * @param cursor the {@code nextCursor} of the previous page, issued for the same {@code since}
     * @param limit page size, 1 to {@value WorkItemPaging#MAX_LIMIT}; {@value WorkItemPaging#DEFAULT_LIMIT} when null
     * @throws com.positivity.accounting.internal.exception.InvalidRequestParameterException {@code VALIDATION_ERROR}
     *     (field {@code since}: unreadable, in the future or more than {@value #MAX_LOOK_BACK_DAYS} days back; field
     *     {@code limit})
     * @throws com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException {@code
     *     WORK_ITEMS_CURSOR_INVALID}
     */
    @NonNull
    AutomaticWorkItemsResponse list(@Nullable String since, @Nullable String cursor, @Nullable Integer limit);
}
