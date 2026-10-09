package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The AP payment hold in a vendor AP settings PUT (CAP:550 #2615). {@code onHold: true} sets or keeps the hold and
 * needs a reason of 10-500 characters; {@code onHold: false} clears it, and a reason sent with it is ignored. The
 * reason is staff free text handled as CONFIDENTIAL (ADR-0072): this class has no {@code toString} that prints it, and
 * no refusal echoes it. An unknown key is recorded by name only, its value dropped, and the PUT answers 400.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
        name = "VendorApHoldRequest",
        description = "Sets (onHold true, with a reason) or clears (onHold false) the vendor's AP payment hold")
public class VendorApHoldRequest {

    @Schema(
            description = "true holds every AP payment to the vendor; false releases the hold",
            example = "true",
            requiredMode = REQUIRED)
    private @Nullable Boolean onHold;

    @Schema(
            description = "Why the vendor is held, 10-500 characters once trimmed; required with onHold true and"
                    + " ignored with onHold false. Shown to payers; never in a log or an error message",
            example = "Disputed delivery 4471, awaiting credit",
            maxLength = 500,
            requiredMode = NOT_REQUIRED)
    private @Nullable String reason;

    @JsonIgnore
    private boolean onHoldPresent;

    @JsonIgnore
    private boolean reasonPresent;

    @JsonIgnore
    private final List<String> unknownProperties = new ArrayList<>();

    @JsonCreator
    public VendorApHoldRequest() {
        // Bound through the setters, so a key's presence is seen.
    }

    /** Records an unknown key by name; its value is dropped unread, so it can never be echoed or logged. */
    @JsonAnySetter
    void unknown(String name, Object value) {
        unknownProperties.add(name);
    }

    public @Nullable Boolean getOnHold() {
        return onHold;
    }

    public void setOnHold(@Nullable Boolean onHold) {
        this.onHold = onHold;
        this.onHoldPresent = true;
    }

    /** Whether the body named {@code onHold}, null included. */
    @JsonIgnore
    public boolean hasOnHold() {
        return onHoldPresent;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public void setReason(@Nullable String reason) {
        this.reason = reason;
        this.reasonPresent = true;
    }

    /** Whether the body named {@code reason}, null included. */
    @JsonIgnore
    public boolean hasReason() {
        return reasonPresent;
    }

    /** The unknown keys the body carried inside {@code apHold}, by name. */
    @JsonIgnore
    public @NonNull List<String> unknownProperties() {
        return List.copyOf(unknownProperties);
    }
}
