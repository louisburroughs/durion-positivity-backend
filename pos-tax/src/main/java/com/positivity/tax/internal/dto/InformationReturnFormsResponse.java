package com.positivity.tax.internal.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response of the information-return forms read ({@code GET /v1/tax/information-return-forms}, CAP:550 #2615).
 * <p>
 * Projects the information-return forms a country configures, with their boxes and the payee-id schemes a
 * payee may be reported under. Every value is configuration held for expert advice, never tax law, so
 * {@code source} is always {@code STUB}. A country with no configured form answers an empty list.
 *
 * @param countryCode the queried country, echoed
 * @param source      always {@code STUB}
 * @param forms       the configured forms, in configured order
 */
@Schema(
        name = "TaxInformationReturnFormsResponse",
        description = "The information-return forms configured for one country")
public record InformationReturnFormsResponse(
        @Schema(
                description = "Country code in ISO 3166-1 alpha-2 format, echoed from the request",
                example = "ZZ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,

        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source,

        @Schema(
                description = "Forms configured for the country, in configured order; empty when it has none",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<FormEntry> forms) {

    /**
     * One form.
     *
     * @param form           the form code
     * @param label          its label
     * @param boxes          its boxes, in configured order
     * @param payeeIdSchemes the tax-registration schemes a payee may be reported under
     */
    @Schema(name = "TaxInformationReturnForm", description = "One configured information-return form")
    public record FormEntry(
            @Schema(description = "Form code", example = "ZZ_FORM_A", requiredMode = Schema.RequiredMode.REQUIRED)
            String form,

            @Schema(description = "Form label", example = "Form A", requiredMode = Schema.RequiredMode.REQUIRED)
            String label,

            @Schema(description = "Boxes of the form, never empty", requiredMode = Schema.RequiredMode.REQUIRED)
            List<BoxEntry> boxes,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "Tax-registration schemes a payee may be reported under",
                                    example = "[\"ZZ_BUSINESS_ID\", \"ZZ_PERSON_ID\"]",
                                    requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(example = "ZZ_BUSINESS_ID"))
            List<String> payeeIdSchemes) {}

    /**
     * One box of a form.
     *
     * @param box   the box code
     * @param label its label
     */
    @Schema(name = "TaxInformationReturnBox", description = "One box of an information-return form")
    public record BoxEntry(
            @Schema(description = "Box code", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            String box,

            @Schema(description = "Box label", example = "Box one", requiredMode = Schema.RequiredMode.REQUIRED)
            String label) {}
}
