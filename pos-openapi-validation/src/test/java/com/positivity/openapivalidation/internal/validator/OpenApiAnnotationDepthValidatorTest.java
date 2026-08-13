package com.positivity.openapivalidation.internal.validator;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

class OpenApiAnnotationDepthValidatorTest {

    private static final String COMPLIANT_DESCRIPTION = """
            Creates a purchasing account that binds one supplier profile to one location.
            Use this tool when a location is authorised to buy from a supplier for the first time; do not use \
            updateSupplierAccount, which changes an account that already exists.
            Preconditions: the supplier profile must exist and be ACTIVE.
            Required inputs: supplierId (UUID) and accountNumber; creditLimit defaults to null.
            Emits a SUPPLIER_ACCOUNT_CREATE event.
            Returns 409 when an account already exists for the supplier and location pair.
            """;

    private final OpenApiAnnotationDepthValidator validator = new OpenApiAnnotationDepthValidator();

    @Test
    void acceptsADescriptionCoveringAllSevenElements() {
        assertThat(validator.check(new Operation().description(COMPLIANT_DESCRIPTION)))
                .isEmpty();
    }

    @Test
    void countsSentencesRatherThanLines() {
        assertThat(OpenApiAnnotationDepthValidator.splitSentences(COMPLIANT_DESCRIPTION))
                .hasSize(6);
    }

    @Test
    void doesNotTreatAbbreviationsAsSentenceBoundaries() {
        assertThat(OpenApiAnnotationDepthValidator.splitSentences(
                        "Creates a record, e.g. an invoice line. Returns 404 when absent."))
                .hasSize(2);
    }

    @Test
    void rejectsADescriptionThatOpensWithWhenToUse() {
        String description = COMPLIANT_DESCRIPTION.substring(COMPLIANT_DESCRIPTION.indexOf("Use this tool"));

        assertThat(validator.check(new Operation().description(description)))
                .contains("description does not open with a primary-action sentence");
    }

    @Test
    void rejectsADescriptionLongerThanEightSentences() {
        String description = COMPLIANT_DESCRIPTION + "Also note this. And this. And this more.";

        assertThat(validator.check(new Operation().description(description)))
                .contains("description has 9 sentences (ADR-0042 §1 requires 4-8)");
    }

    @Test
    void skipsDepthChecksWhenDescriptionIsAbsentBecausePresenceIsReportedElsewhere() {
        assertThat(validator.check(new Operation())).isEmpty();
    }

    @Test
    void matchesLeadInsSplitAcrossLineBreaks() {
        String description = """
                Replaces the stored details of an auth config on a vendor profile.
                Use this tool to rotate credential references; do
                not use it to rename a config that bindings reference.
                Preconditions: the auth config must exist.
                Required inputs: authConfigId (UUID) and the full body.
                Emits a SUPPLIER_AUTHCONFIG_UPDATE event.
                Returns 404 when the config does not exist.
                """;

        assertThat(validator.check(new Operation().description(description))).isEmpty();
    }

    @Test
    void acceptsARequestBodyWithDescriptionRequiredFlagAndExample() {
        Operation operation = new Operation()
                .description(COMPLIANT_DESCRIPTION)
                .requestBody(new RequestBody()
                        .description("Supplier account to create.")
                        .required(true)
                        .content(new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType().addExamples("minimal", new Example().value("{}")))));

        assertThat(validator.check(operation)).isEmpty();
    }

    @Test
    void acceptsASchemaLevelExample() {
        Operation operation = new Operation()
                .description(COMPLIANT_DESCRIPTION)
                .requestBody(new RequestBody()
                        .description("Supplier account to create.")
                        .required(true)
                        .content(new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType()
                                                .schema(new io.swagger.v3.oas.models.media.Schema<>().example("{}")))));

        assertThat(validator.check(operation)).isEmpty();
    }

    @Test
    void reportsRequestBodyGapsIndependentlyOfTheDescription() {
        Operation operation = new Operation()
                .description(COMPLIANT_DESCRIPTION)
                .requestBody(
                        new RequestBody().content(new Content().addMediaType("application/json", new MediaType())));

        assertThat(validator.check(operation))
                .containsExactly(
                        "request body missing description (ADR-0042 §3)", "request body missing example (ADR-0042 §3)");
    }
}
