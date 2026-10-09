package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The information-return forms configured for the tenant's tax country (CAP:550 #2615), relayed from pos-tax's stub
 * ({@code GET /v1/tax/information-return-forms}) through accounting's front door (ADR-0071, AW59). Every form, box and
 * payee-id scheme is pos-tax configuration held for expert advice (AW48, OI-4); no code names one.
 *
 * @param countryCode the tax country, ISO 3166-1 alpha-2
 * @param source      {@code STUB} while the values are placeholders
 * @param forms       the configured forms; empty when the country configures none
 */
@Schema(
        name = "InformationReturnFormsResponse",
        description = "The information-return forms, boxes and payee-id schemes configured for the tax country")
public record InformationReturnFormsResponse(
        @Schema(description = "The tax country, ISO 3166-1 alpha-2", example = "US", requiredMode = REQUIRED)
        String countryCode,

        @Schema(
                description = "Where the lists come from; STUB while they are placeholders held for expert advice",
                example = "STUB",
                requiredMode = REQUIRED)
        String source,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The configured forms; empty when the country configures none, so no"
                                        + " vendor can be made reportable there",
                                requiredMode = REQUIRED))
        List<Form> forms) {

    public InformationReturnFormsResponse {
        forms = forms == null ? List.of() : List.copyOf(forms);
    }

    /** The form with {@code code}, if configured. */
    public @Nullable Form form(@NonNull String code) {
        return forms.stream().filter(f -> code.equals(f.form())).findFirst().orElse(null);
    }

    /**
     * One configured form.
     *
     * @param form           the form code
     * @param label          its label, for pickers
     * @param boxes          its boxes; never empty
     * @param payeeIdSchemes the tax-registration schemes a payee may be reported under
     */
    @Schema(name = "InformationReturnForm", description = "One configured information-return form")
    public record Form(
            @Schema(description = "The form code", example = "ZZ_FORM_A", requiredMode = REQUIRED)
            String form,

            @Schema(description = "The form's label", example = "Form A", requiredMode = REQUIRED)
            String label,

            @ArraySchema(arraySchema = @Schema(description = "The form's boxes", requiredMode = REQUIRED))
            List<Box> boxes,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "The tax-registration schemes a payee may be reported under",
                                    requiredMode = REQUIRED),
                    schema = @Schema(example = "ZZ_BUSINESS_ID"))
            List<String> payeeIdSchemes) {

        public Form {
            boxes = boxes == null ? List.of() : List.copyOf(boxes);
            payeeIdSchemes = payeeIdSchemes == null ? List.of() : List.copyOf(payeeIdSchemes);
        }

        /** Whether the form has a box {@code code}. */
        public boolean hasBox(@NonNull String code) {
            return boxes.stream().anyMatch(b -> code.equals(b.box()));
        }
    }

    /**
     * One box of a form.
     *
     * @param box   the box code
     * @param label its label
     */
    @Schema(name = "InformationReturnBox", description = "One box of an information-return form")
    public record Box(
            @Schema(description = "The box code", example = "1", requiredMode = REQUIRED)
            String box,

            @Schema(description = "The box's label", example = "Box one", requiredMode = REQUIRED)
            String label) {}
}
