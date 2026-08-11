package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record a CSR-initiated interaction on a party's timeline")
public class RecordInteractionRequest {

    @Schema(description = "Kind of touch", example = "CALL", requiredMode = REQUIRED)
    @NotNull
    private InteractionType type;

    @Schema(description = "Contact reached, when the touch targeted one", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID contactId;

    @Schema(description = "Channel used", example = "EMAIL", requiredMode = NOT_REQUIRED)
    @Nullable
    private MarketingChannel channel;

    @Schema(description = "Who initiated the touch; defaults to OUTBOUND", requiredMode = NOT_REQUIRED)
    @Nullable
    private InteractionDirection direction;

    @Schema(description = "Short subject line", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 255)
    private String subject;

    @Schema(description = "Human summary of the touch", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 1000)
    private String summary;

    @Schema(description = "Body text", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 4000)
    private String body;

    @Schema(description = "When the touch occurred; defaults to now", requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant occurredAt;
}
