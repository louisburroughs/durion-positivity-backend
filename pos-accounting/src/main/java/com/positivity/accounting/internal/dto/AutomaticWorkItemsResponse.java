package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A page of what the system did on its own (CAP:550 S19, #2515), oldest first. */
@Schema(name = "AutomaticWorkItemsResponse", description = "A page of what was done automatically since a given time, oldest first")
public record AutomaticWorkItemsResponse(
        @Schema(description = "The items of this page, oldest first", requiredMode = Schema.RequiredMode.REQUIRED)
        List<AutomaticWorkItem> items,

        @Schema(
                description = "Opaque cursor of the next page; null on the last page. Valid only with the same since",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable
        String nextCursor,

        @Schema(description = "When the list was computed", example = "2026-10-17T15:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant asOf) {}
