package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.WorkItemsResponse;
import com.positivity.accounting.internal.enums.WorkItemArea;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The to-do list (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"): items built on read from their
 * sources and filtered, server side, to what the caller may act on. Nothing is stored; an item exists while its source
 * is in the listed state. Read-only; the tenant comes from the security context and row-level security (ADR-0062).
 */
public interface WorkItemService {

    /**
     * One page of the caller's work items, oldest first (item id the tiebreak), with the caller's counts per area.
     *
     * @param area only this area's items, when given; the counts always cover every area
     * @param cursor the {@code nextCursor} of the previous page, issued for the same area
     * @param limit page size, 1 to {@value WorkItemPaging#MAX_LIMIT}; {@value WorkItemPaging#DEFAULT_LIMIT} when null
     * @throws com.positivity.accounting.internal.exception.InvalidRequestParameterException {@code VALIDATION_ERROR}
     *     (field {@code limit})
     * @throws com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException {@code
     *     WORK_ITEMS_CURSOR_INVALID}
     */
    @NonNull
    WorkItemsResponse list(@Nullable WorkItemArea area, @Nullable String cursor, @Nullable Integer limit);
}
