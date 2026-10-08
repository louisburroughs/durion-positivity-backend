package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Sets a vendor's AP defaults (CAP:550 S24, #2517, rule 10; AW39). A field left out of the body is unchanged; a field
 * sent as JSON {@code null} clears it. The actor is the caller (ADR-0018); no body field names one. S43 adds {@code
 * acceptTaxOnResaleGoods} here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
        description = "The vendor's AP defaults to change, the justification and the request id. A field left out is"
                + " unchanged; a field sent as null clears it.")
public class VendorApSettingsRequest {

    @Schema(
            description = "GOODS, EXPENSE, or null to clear: the class a bill whose lines are not stored posts with"
                    + " when nobody names one. EXPENSE needs a defaultExpenseMappingKey (sent or already set)",
            example = "EXPENSE",
            allowableValues = {"GOODS", "EXPENSE"},
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private @Nullable String defaultDebitClass;

    @JsonIgnore
    private boolean defaultDebitClassPresent;

    @Schema(
            description = "An active VENDOR_BILL expense key EXPENSE_<CODE>, or null to clear: used for EXPENSE and"
                    + " non-stock lines when nobody names one",
            example = "EXPENSE_SHOP_SUPPLIES",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    @Size(max = 100)
    private @Nullable String defaultExpenseMappingKey;

    @JsonIgnore
    private boolean defaultExpenseMappingKeyPresent;

    @Schema(
            description = "Why the defaults change, at least 10 characters; recorded on the audit row",
            example = "Shop supplies vendor: header-only bills post to shop supplies",
            requiredMode = REQUIRED)
    @Size(max = 1000)
    private @Nullable String justification;

    @Schema(
            description = "A UUID generated once per change: a request id already recorded writes nothing and returns"
                    + " the vendor as it is",
            example = "0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f",
            requiredMode = REQUIRED)
    private @Nullable UUID requestId;

    @JsonCreator
    public VendorApSettingsRequest() {
        // Bound through the setters, so a key's presence is seen.
    }

    public @Nullable String getDefaultDebitClass() {
        return defaultDebitClass;
    }

    public void setDefaultDebitClass(@Nullable String defaultDebitClass) {
        this.defaultDebitClass = defaultDebitClass;
        this.defaultDebitClassPresent = true;
    }

    /** Whether the body named {@code defaultDebitClass}, null included. */
    @JsonIgnore
    public boolean hasDefaultDebitClass() {
        return defaultDebitClassPresent;
    }

    public @Nullable String getDefaultExpenseMappingKey() {
        return defaultExpenseMappingKey;
    }

    public void setDefaultExpenseMappingKey(@Nullable String defaultExpenseMappingKey) {
        this.defaultExpenseMappingKey = defaultExpenseMappingKey;
        this.defaultExpenseMappingKeyPresent = true;
    }

    /** Whether the body named {@code defaultExpenseMappingKey}, null included. */
    @JsonIgnore
    public boolean hasDefaultExpenseMappingKey() {
        return defaultExpenseMappingKeyPresent;
    }

    public @Nullable String getJustification() {
        return justification;
    }

    public void setJustification(@Nullable String justification) {
        this.justification = justification;
    }

    public @Nullable UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(@Nullable UUID requestId) {
        this.requestId = requestId;
    }
}
