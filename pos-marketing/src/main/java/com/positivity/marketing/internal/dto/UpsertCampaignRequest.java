package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.ScheduleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create or update a marketing campaign")
public class UpsertCampaignRequest {

    @Schema(
            description = "Stable campaign code; stamped onto redemptions for attribution",
            example = "SPRING-FLEET-2026",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 100)
    private String code;

    @Schema(description = "Campaign name", example = "Spring fleet alignment push", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String name;

    @Schema(description = "What the campaign is for", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 1000)
    private String description;

    @Schema(description = "Targeted party kind; immutable after creation", requiredMode = REQUIRED)
    @NotNull
    private AudienceType audienceType;

    @Schema(
            description = "Groups the commercial and individual arms of one initiative for comparison",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID campaignProgramId;

    @Schema(description = "Delivery channels", requiredMode = REQUIRED)
    @NotEmpty
    private Set<CampaignChannel> channels;

    @Schema(description = "pos-customer segment to target", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID segmentId;

    @Schema(description = "pos-price offer promoted by the campaign", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID promotionOfferId;

    @Schema(description = "pos-catalog reference the message points at", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 200)
    private String catalogFocusRef;

    @Schema(description = "Start of the campaign's active window", requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant windowStart;

    @Schema(description = "End of the campaign's active window", requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant windowEnd;

    @Schema(description = "IMMEDIATE or SCHEDULED; defaults to IMMEDIATE", requiredMode = NOT_REQUIRED)
    @Nullable
    private ScheduleType scheduleType;

    @Schema(description = "When to dispatch; required for SCHEDULED", requiredMode = NOT_REQUIRED)
    @Nullable
    private Instant scheduledAt;

    @Schema(description = "Email template to render", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID emailTemplateId;

    @Schema(description = "SMS template to render", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID smsTemplateId;
}
