package com.positivity.marketing.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.marketing.internal.enums.AudienceType;
import com.positivity.marketing.internal.enums.CampaignChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create or update a message template")
public class UpsertMessageTemplateRequest {

    @Schema(description = "Unique template name", example = "Spring fleet — email", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 150)
    private String name;

    @Schema(description = "Delivery channel", requiredMode = REQUIRED)
    @NotNull
    private CampaignChannel channel;

    @Schema(description = "Audience whose token vocabulary applies", requiredMode = REQUIRED)
    @NotNull
    private AudienceType audienceType;

    @Schema(description = "Subject line; required for EMAIL, rejected for SMS", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 255)
    private String subject;

    @Schema(
            description = "Message body with {{token}} placeholders from the audience's token catalog",
            example = "Hi {{contactFirstName}}, your {{vehicleMake}} is due for service. Use {{offerCode}}.",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 20000)
    private String body;
}
