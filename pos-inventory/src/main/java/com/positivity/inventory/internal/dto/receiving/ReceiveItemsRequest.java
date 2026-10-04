package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record received quantities for one or more receiving session lines")
public class ReceiveItemsRequest {
    @Schema(
            description = "Receiving lines with their received quantities; at least one line is required",
            requiredMode = REQUIRED)
    @NotEmpty(message = "At least one line must be provided")
    @Valid
    private List<ReceiveLineRequest> lines;

    @Schema(
            description = "Optional idempotency key, the body fallback for the Idempotency-Key header (the header wins;"
                    + " both present and different is a 400). A retry with the same key and the same payload posts"
                    + " nothing and returns the original response; the same key with a different payload is a 409"
                    + " IDEMPOTENCY_CONFLICT. Generated server-side when absent, in which case a retry is not"
                    + " recognised",
            example = "receive-2026-10-04-dock3-0001",
            requiredMode = NOT_REQUIRED)
    @Size(max = 200)
    private String idempotencyKey;

    /** Pre-#2455 arity kept for existing callers/tests: no idempotency key. */
    public ReceiveItemsRequest(List<ReceiveLineRequest> lines) {
        this(lines, null);
    }
}
