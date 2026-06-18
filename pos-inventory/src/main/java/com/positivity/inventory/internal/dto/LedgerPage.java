package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Schema(description = "A single page of ledger entries with an optional cursor to the next page")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerPage<T> {

    @Schema(description = "Ledger entries contained in this page", requiredMode = REQUIRED)
    @NotNull
    private List<T> entries;

    @Schema(
            description = "Opaque cursor for the next page; null when there are no more entries",
            example = "eyJvZmZzZXQiOjUwfQ==",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String nextPageToken;
}
