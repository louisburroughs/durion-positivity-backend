package com.positivity.platformsender.internal.dto;

import com.positivity.platformsender.internal.enums.MessageChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One fully rendered message to deliver (FI-2 §1). The sender does no templating and the caller
 * sends no address: the recipient's address is resolved here, from replicas of pos-customer and
 * pos-people-contact.
 */
@Schema(description = "One rendered message to deliver to a CRM party (FI-2 send request)")
public record SendMessageRequest(
        @Schema(
                description = "Caller's idempotency key; a replay never delivers twice",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        UUID messageId,

        @Schema(description = "Delivery channel", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        MessageChannel channel,

        @Schema(description = "pos-customer party to contact", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        UUID recipientPartyId,

        @Schema(description = "pos-customer person party whose address is used, when it differs from the recipient")
        @Nullable
        UUID contactId,

        @Schema(
                description = "Stable campaign code, carried as provider metadata",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 100)
        String campaignCode,

        @Schema(description = "Email subject; required for EMAIL, ignored for SMS") @Nullable @Size(max = 998)
        String subject,

        @Schema(
                description = "Fully rendered body; HTML when it starts with '<'",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String body) {}
