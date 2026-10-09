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
 * The information-return reportable flag in a vendor AP settings PUT (CAP:550 #2615). The forms, boxes and payee-id
 * schemes are pos-tax configuration for the tenant's tax country ({@code GET /v1/accounting/information-return-forms});
 * no code here names one. There is no field for a taxpayer number: an unknown key such as {@code tin} is recorded by
 * name only, its value dropped unread, and the PUT answers 400.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(
        name = "VendorInformationReturnRequest",
        description = "Whether the vendor's payments are reportable on the tax country's information return, and in"
                + " which configured form and box")
public class VendorInformationReturnRequest {

    @Schema(
            description = "true: the vendor is reportable, and form and box are required; false: not reportable, and"
                    + " form, box and payeeTaxRegistrationScheme must be absent or null (stored values are cleared)",
            example = "true",
            requiredMode = REQUIRED)
    private @Nullable Boolean reportable;

    @Schema(
            description = "A form code of the tax country's configured information-return forms",
            example = "ZZ_FORM_A",
            requiredMode = NOT_REQUIRED)
    private @Nullable String form;

    @Schema(description = "A box code of that form", example = "1", requiredMode = NOT_REQUIRED)
    private @Nullable String box;

    @Schema(
            description = "Optional: the tax-registration scheme the payee is reported under, one of the form's"
                    + " payeeIdSchemes; it need not be on the vendor yet. Never the number itself",
            example = "ZZ_BUSINESS_ID",
            requiredMode = NOT_REQUIRED)
    private @Nullable String payeeTaxRegistrationScheme;

    @JsonIgnore
    private boolean reportablePresent;

    @JsonIgnore
    private boolean formPresent;

    @JsonIgnore
    private boolean boxPresent;

    @JsonIgnore
    private boolean payeeTaxRegistrationSchemePresent;

    @JsonIgnore
    private final List<String> unknownProperties = new ArrayList<>();

    @JsonCreator
    public VendorInformationReturnRequest() {
        // Bound through the setters, so a key's presence is seen.
    }

    /** Records an unknown key by name; its value is dropped unread, so it can never be echoed or logged. */
    @JsonAnySetter
    void unknown(String name, Object value) {
        unknownProperties.add(name);
    }

    public @Nullable Boolean getReportable() {
        return reportable;
    }

    public void setReportable(@Nullable Boolean reportable) {
        this.reportable = reportable;
        this.reportablePresent = true;
    }

    /** Whether the body named {@code reportable}, null included. */
    @JsonIgnore
    public boolean hasReportable() {
        return reportablePresent;
    }

    public @Nullable String getForm() {
        return form;
    }

    public void setForm(@Nullable String form) {
        this.form = form;
        this.formPresent = true;
    }

    /** Whether the body named {@code form}, null included. */
    @JsonIgnore
    public boolean hasForm() {
        return formPresent;
    }

    public @Nullable String getBox() {
        return box;
    }

    public void setBox(@Nullable String box) {
        this.box = box;
        this.boxPresent = true;
    }

    /** Whether the body named {@code box}, null included. */
    @JsonIgnore
    public boolean hasBox() {
        return boxPresent;
    }

    public @Nullable String getPayeeTaxRegistrationScheme() {
        return payeeTaxRegistrationScheme;
    }

    public void setPayeeTaxRegistrationScheme(@Nullable String payeeTaxRegistrationScheme) {
        this.payeeTaxRegistrationScheme = payeeTaxRegistrationScheme;
        this.payeeTaxRegistrationSchemePresent = true;
    }

    /** Whether the body named {@code payeeTaxRegistrationScheme}, null included. */
    @JsonIgnore
    public boolean hasPayeeTaxRegistrationScheme() {
        return payeeTaxRegistrationSchemePresent;
    }

    /** The unknown keys the body carried inside {@code informationReturn}, by name. */
    @JsonIgnore
    public @NonNull List<String> unknownProperties() {
        return List.copyOf(unknownProperties);
    }
}
