package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.enums.OptOutReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Partial update of a party's marketing consent. Every field is optional; only the
 * fields present are changed, so a CSR toggling SMS never silently resets email.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to update per-channel marketing consent for a party")
public class UpdateMarketingConsentRequest {

    @Schema(description = "New marketing consent for email", example = "OPT_IN", requiredMode = NOT_REQUIRED)
    @Nullable
    private MarketingConsent marketingEmailConsent;

    @Schema(description = "New marketing consent for SMS", example = "OPT_OUT", requiredMode = NOT_REQUIRED)
    @Nullable
    private MarketingConsent marketingSmsConsent;

    @Schema(
            description = "Reason recorded when a channel moves to OPT_OUT",
            example = "TOO_FREQUENT",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private OptOutReason optOutReason;

    @Schema(description = "Where the change originated; defaults to CSR", requiredMode = NOT_REQUIRED)
    @Nullable
    private ConsentChangeSource source;

    @Schema(
            description = "Start of the local-time window during which marketing sends are held",
            example = "21:00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private LocalTime quietHoursStart;

    @Schema(
            description = "End of the local-time window during which marketing sends are held",
            example = "08:00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private LocalTime quietHoursEnd;

    @Schema(
            description = "Cadence cap on marketing sends per calendar month",
            example = "4",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @Min(1)
    @Max(365)
    private Integer maxMarketingSendsPerMonth;
}
