package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.WorkItemArea;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A page of the to-do list (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"), oldest first. */
@Schema(name = "WorkItemsResponse", description = "A page of the caller's to-do items, oldest first, with counts")
public record WorkItemsResponse(
        @Schema(description = "The items of this page, oldest first", requiredMode = Schema.RequiredMode.REQUIRED)
        List<WorkItem> items,

        @Schema(
                description = "Opaque cursor of the next page; null on the last page. Valid only with the same area",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String nextCursor,

        @Schema(
                description = "How many items the caller may act on in each area, before the area filter and paging",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<WorkItemArea, Integer> countsByArea,

        @Schema(
                description = "When the list was computed",
                example = "2026-10-17T15:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant asOf) {}
