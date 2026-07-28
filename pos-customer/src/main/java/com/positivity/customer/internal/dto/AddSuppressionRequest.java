package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SuppressionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to hard-block an address from marketing")
public class AddSuppressionRequest {

    @Schema(description = "Channel the address belongs to", example = "EMAIL", requiredMode = REQUIRED)
    @NotNull
    private MarketingChannel channel;

    @Schema(
            description = "Raw address to suppress; stored only as a normalized hash plus a masked hint",
            example = "jane@example.com",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 320)
    private String address;

    @Schema(description = "Party the address belonged to, when known", requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID partyId;

    @Schema(description = "Why the address is blocked", example = "HARD_BOUNCE", requiredMode = REQUIRED)
    @NotNull
    private SuppressionReason reason;

    @Schema(description = "Where the block originated; defaults to CSR", requiredMode = NOT_REQUIRED)
    @Nullable
    private ConsentChangeSource source;
}
