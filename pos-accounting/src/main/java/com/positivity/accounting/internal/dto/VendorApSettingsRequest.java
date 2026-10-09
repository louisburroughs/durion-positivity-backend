package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Sets a vendor's AP defaults (CAP:550 S24, #2517, rule 10; AW39), its AP payment hold and its information-return
 * reportable flag (#2615). A field left out of the body is unchanged; a default sent as JSON {@code null} clears it,
 * while {@code apHold} and {@code informationReturn} are objects that clear through their own fields ({@code null} is
 * refused). The actor is the caller (ADR-0018); no body field names one. S43 adds {@code acceptTaxOnResaleGoods} here,
 * as another optional field with its presence flag and its own fingerprint entry.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
        description = "The vendor's AP settings to change (defaults, AP hold, information-return flag), the"
                + " justification and the request id. A field left out is unchanged; a default sent as null clears"
                + " it.")
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
            description = "Why the settings change, at least 10 characters; recorded on every audit row, and the record"
                    + " of why a hold is released",
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

    @Schema(
            description = "Optional: sets (onHold true, with a reason of 10-500 characters) or clears (onHold false)"
                    + " the vendor's AP payment hold; absent leaves it unchanged, null is refused",
            requiredMode = NOT_REQUIRED)
    private @Nullable VendorApHoldRequest apHold;

    @JsonIgnore
    private boolean apHoldPresent;

    @Schema(
            description = "Optional: whether the vendor is reportable on the tax country's information return, and in"
                    + " which configured form and box; absent leaves it unchanged, null is refused",
            requiredMode = NOT_REQUIRED)
    private @Nullable VendorInformationReturnRequest informationReturn;

    @JsonIgnore
    private boolean informationReturnPresent;

    @JsonIgnore
    private final List<String> unknownProperties = new ArrayList<>();

    @JsonCreator
    public VendorApSettingsRequest() {
        // Bound through the setters, so a key's presence is seen.
    }

    /**
     * Records any other key by name, its value dropped unread, so the PUT refuses it with 400 naming the key and never
     * its value (#2615: a {@code tin} is refused, never echoed). {@code ignoreUnknown = false} only defers to the
     * mapper, which Spring configures not to fail on unknown properties, so a misspelt or not-yet-supported field
     * (e.g. S43's) would otherwise be dropped silently.
     */
    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        unknownProperties.add(name);
    }

    /**
     * Every unknown key the body carried, by name: the top level's, then {@code apHold.<key>} and {@code
     * informationReturn.<key>}. Never a value.
     */
    @JsonIgnore
    public @NonNull List<String> unknownProperties() {
        List<String> all = new ArrayList<>(unknownProperties);
        if (apHold != null) {
            apHold.unknownProperties().forEach(name -> all.add("apHold." + name));
        }
        if (informationReturn != null) {
            informationReturn.unknownProperties().forEach(name -> all.add("informationReturn." + name));
        }
        return List.copyOf(all);
    }

    public @Nullable VendorApHoldRequest getApHold() {
        return apHold;
    }

    public void setApHold(@Nullable VendorApHoldRequest apHold) {
        this.apHold = apHold;
        this.apHoldPresent = true;
    }

    /** Whether the body named {@code apHold}, null included. */
    @JsonIgnore
    public boolean hasApHold() {
        return apHoldPresent;
    }

    public @Nullable VendorInformationReturnRequest getInformationReturn() {
        return informationReturn;
    }

    public void setInformationReturn(@Nullable VendorInformationReturnRequest informationReturn) {
        this.informationReturn = informationReturn;
        this.informationReturnPresent = true;
    }

    /** Whether the body named {@code informationReturn}, null included. */
    @JsonIgnore
    public boolean hasInformationReturn() {
        return informationReturnPresent;
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
