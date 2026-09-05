package com.positivity.openapivalidation.internal.validator;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Every 4xx/5xx response body is the ApiError envelope (ADR-0017 §3, issue #1720)")
class OpenApiErrorResponseSchemaValidatorTest {

    private final OpenApiErrorResponseSchemaValidator validator = new OpenApiErrorResponseSchemaValidator();

    @Test
    @DisplayName("a 400 typed as the endpoint's success DTO is reported")
    void reportsASuccessDtoPublishedAsAnErrorBody() {
        Operation operation = operation("200", ref("VehicleResponse"), "400", ref("VehicleResponse"));

        assertThat(validator.check(operation))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("400 response body is VehicleResponse, not ApiError");
    }

    @Test
    @DisplayName("a 500 inherited from a removed advice's return type is reported")
    void reportsA500TypedAsTheSuccessDto() {
        assertThat(validator.check(operation("200", ref("TokenPairResponse"), "500", ref("TokenPairResponse"))))
                .hasSize(1);
    }

    @Test
    @DisplayName("ApiError on every error status passes")
    void acceptsApiErrorOnEveryErrorStatus() {
        assertThat(validator.check(
                        operation("200", ref("VehicleResponse"), "400", ref("ApiError"), "404", ref("ApiError"))))
                .isEmpty();
    }

    @Test
    @DisplayName("ProblemDetail is reported like any other non-envelope type")
    void reportsProblemDetail() {
        assertThat(validator.check(operation("404", ref("ProblemDetail"))))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("404 response body is ProblemDetail, not ApiError");
    }

    @Test
    @DisplayName("a deliberately bodiless error is not a finding")
    void ignoresAnErrorResponseWithNoContent() {
        Operation operation = new Operation()
                .responses(new ApiResponses()
                        .addApiResponse("204", new ApiResponse())
                        .addApiResponse("404", new ApiResponse().description("Not found; the body is empty.")));

        assertThat(validator.check(operation)).isEmpty();
    }

    @Test
    @DisplayName("an inline or primitive error body is not a named component and is not reported")
    void ignoresSchemasThatAreNotNamedComponents() {
        assertThat(validator.check(operation("400", new StringSchema()))).isEmpty();
        assertThat(validator.check(operation("400", new ObjectSchema()))).isEmpty();
    }

    @Test
    @DisplayName("an array-of-DTO error body is unwrapped and reported")
    void unwrapsArrayItems() {
        Schema<?> arrayOfDtos = new ArraySchema().items(ref("VehicleResponse"));

        assertThat(validator.check(operation("422", arrayOfDtos)))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("422 response body is VehicleResponse, not ApiError");
    }

    @Test
    @DisplayName("2xx, 3xx and default responses are out of scope")
    void ignoresNonErrorStatuses() {
        Operation operation = new Operation()
                .responses(new ApiResponses()
                        .addApiResponse("200", jsonResponse(ref("VehicleResponse")))
                        .addApiResponse("302", jsonResponse(ref("VehicleResponse")))
                        .addApiResponse("default", jsonResponse(ref("VehicleResponse"))));

        assertThat(validator.check(operation)).isEmpty();
    }

    @Test
    @DisplayName("an operation with no responses at all is not a finding")
    void ignoresAnOperationWithNoResponses() {
        assertThat(validator.check(new Operation())).isEmpty();
    }

    private static Schema<?> ref(String componentName) {
        return new Schema<>().$ref("#/components/schemas/" + componentName);
    }

    private static Operation operation(Object... statusAndSchema) {
        ApiResponses responses = new ApiResponses();
        for (int i = 0; i < statusAndSchema.length; i += 2) {
            responses.addApiResponse((String) statusAndSchema[i], jsonResponse((Schema<?>) statusAndSchema[i + 1]));
        }
        return new Operation().responses(responses);
    }

    private static ApiResponse jsonResponse(Schema<?> schema) {
        return new ApiResponse()
                .content(new Content().addMediaType("application/json", new MediaType().schema(schema)));
    }
}
