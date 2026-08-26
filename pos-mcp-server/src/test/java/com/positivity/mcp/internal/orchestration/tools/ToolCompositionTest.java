package com.positivity.mcp.internal.orchestration.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Unit tests for {@link ToolComposition}: per-leg outcome rendering and envelope shape.
 */
class ToolCompositionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RestClientResponseException httpError(int status, String responseBody) {
        return new RestClientResponseException(
                "downstream error",
                HttpStatusCode.valueOf(status),
                "status text",
                null,
                responseBody.getBytes(UTF_8),
                UTF_8);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Envelope is not valid JSON: " + json, exception);
        }
    }

    @Test
    @DisplayName("successful legs render ok sections with verbatim JSON data and full sources list")
    void successfulLegs_renderOkSectionsAndSources() {
        String rendered = ToolComposition.named("shopStatus")
                .call("schedule", () -> "{\"slots\":[1,2]}")
                .call("openWorkorders", () -> "[{\"id\":\"WO-1\"}]")
                .render();

        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("composition").asText()).isEqualTo("shopStatus");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        JsonNode schedule = envelope.get("sections").get("schedule");
        assertThat(schedule.get("status").asText()).isEqualTo("ok");
        assertThat(schedule.get("data").get("slots").get(1).asInt()).isEqualTo(2);
        JsonNode workorders = envelope.get("sections").get("openWorkorders");
        assertThat(workorders.get("data").isArray()).isTrue();
        assertThat(workorders.get("data").get(0).get("id").asText()).isEqualTo("WO-1");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("schedule", "openWorkorders");
    }

    @Test
    @DisplayName("a 403 leg renders not_authorized without the error body and is never retried")
    void forbiddenLeg_rendersNotAuthorizedWithoutBodyLeak() {
        AtomicInteger attempts = new AtomicInteger();
        String rendered = ToolComposition.named("shopStatus")
                .call("schedule", () -> "{\"open\":true}")
                .call("openWorkorders", () -> {
                    attempts.incrementAndGet();
                    throw httpError(403, "{\"secret\":\"TOP-SECRET-PAYLOAD\"}");
                })
                .render();

        assertThat(attempts).hasValue(1);
        assertThat(rendered).doesNotContain("TOP-SECRET-PAYLOAD").doesNotContain("secret");
        JsonNode envelope = parse(rendered);
        JsonNode denied = envelope.get("sections").get("openWorkorders");
        assertThat(denied.get("status").asText()).isEqualTo("not_authorized");
        assertThat(denied.has("data")).isFalse();
        assertThat(denied.has("reason")).isFalse();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("schedule");
    }

    @Test
    @DisplayName("a 401 leg renders not_authorized like a 403 leg")
    void unauthorizedLeg_rendersNotAuthorized() {
        String rendered = ToolComposition.named("summary")
                .call("balance", () -> {
                    throw httpError(401, "{\"error\":\"token expired\"}");
                })
                .render();

        JsonNode section = parse(rendered).get("sections").get("balance");
        assertThat(section.get("status").asText()).isEqualTo("not_authorized");
        assertThat(rendered).doesNotContain("token expired");
    }

    @Test
    @DisplayName("a 500 leg renders error with a short reason, no stack trace, no response body")
    void serverErrorLeg_rendersErrorWithShortReason() {
        String rendered = ToolComposition.named("summary")
                .call("balance", () -> "{\"total\":10}")
                .call("ledger", () -> {
                    throw httpError(500, "{\"detail\":\"NullPointerException at LedgerService.java:42\"}");
                })
                .render();

        JsonNode envelope = parse(rendered);
        JsonNode failed = envelope.get("sections").get("ledger");
        assertThat(failed.get("status").asText()).isEqualTo("error");
        assertThat(failed.get("reason").asText()).isEqualTo("HTTP 500");
        assertThat(rendered)
                .doesNotContain("LedgerService")
                .doesNotContain("NullPointerException")
                .doesNotContain("at ");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("balance");
    }

    @Test
    @DisplayName("a connect-failure leg renders error with the exception type as reason")
    void connectErrorLeg_rendersErrorReason() {
        String rendered = ToolComposition.named("summary")
                .call("ledger", () -> {
                    throw new ResourceAccessException("I/O error on GET request");
                })
                .render();

        JsonNode section = parse(rendered).get("sections").get("ledger");
        assertThat(section.get("status").asText()).isEqualTo("error");
        assertThat(section.get("reason").asText()).isEqualTo("ResourceAccessException");
    }

    @Test
    @DisplayName("a LegFailure leg renders error with the failure message as reason and degrades when required")
    void legFailure_rendersItsMessageAsReason() {
        String rendered = ToolComposition.named("taxCalculation")
                .call("location", () -> "{\"id\":\"LOC-1\"}")
                .call("tax", () -> {
                    throw new ToolComposition.LegFailure(
                            "Location LOC-1 has no usable address (missing postalCode or country); tax not calculated");
                })
                .require("tax")
                .render();

        JsonNode envelope = parse(rendered);
        JsonNode failed = envelope.get("sections").get("tax");
        assertThat(failed.get("status").asText()).isEqualTo("error");
        assertThat(failed.get("reason").asText()).contains("no usable address");
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("location");
    }

    @Test
    @DisplayName("a 404 leg renders error, not not_authorized")
    void notFoundLeg_rendersError() {
        JsonNode section = parse(ToolComposition.named("lookup")
                        .call("product", () -> {
                            throw httpError(404, "{\"message\":\"no such product\"}");
                        })
                        .render())
                .get("sections")
                .get("product");

        assertThat(section.get("status").asText()).isEqualTo("error");
        assertThat(section.get("reason").asText()).isEqualTo("HTTP 404");
    }

    @Test
    @DisplayName("a non-JSON body embeds as a JSON string; a blank body embeds as null")
    void nonJsonBody_embedsAsString() {
        String rendered = ToolComposition.named("mixed")
                .call("text", () -> "plain text response")
                .call("empty", () -> "")
                .render();

        JsonNode sections = parse(rendered).get("sections");
        assertThat(sections.get("text").get("data").isTextual()).isTrue();
        assertThat(sections.get("text").get("data").asText()).isEqualTo("plain text response");
        assertThat(sections.get("empty").get("data").isNull()).isTrue();
        assertThat(parse(rendered).get("sources")).extracting(JsonNode::asText).containsExactly("text", "empty");
    }

    @Test
    @DisplayName("a failed required leg degrades the envelope status but still returns all sections")
    void failedRequiredLeg_degradesEnvelopeStatus() {
        String rendered = ToolComposition.named("financialSummary")
                .call("incomeStatement", () -> {
                    throw httpError(503, "unavailable");
                })
                .require("incomeStatement")
                .call("balanceSheet", () -> "{\"assets\":100}")
                .render();

        JsonNode envelope = parse(rendered);
        assertThat(envelope.get("status").asText()).isEqualTo("degraded");
        assertThat(envelope.get("sections").get("incomeStatement").get("status").asText())
                .isEqualTo("error");
        assertThat(envelope.get("sections").get("balanceSheet").get("status").asText())
                .isEqualTo("ok");
        assertThat(envelope.get("sources")).extracting(JsonNode::asText).containsExactly("balanceSheet");
    }

    @Test
    @DisplayName("a not_authorized required leg also degrades the envelope status")
    void notAuthorizedRequiredLeg_degradesEnvelopeStatus() {
        String rendered = ToolComposition.named("financialSummary")
                .call("incomeStatement", () -> {
                    throw httpError(403, "denied");
                })
                .require("incomeStatement")
                .render();

        assertThat(parse(rendered).get("status").asText()).isEqualTo("degraded");
    }

    @Test
    @DisplayName("a succeeding required leg keeps the envelope status ok")
    void succeedingRequiredLeg_keepsStatusOk() {
        String rendered = ToolComposition.named("financialSummary")
                .call("incomeStatement", () -> "{\"revenue\":1}")
                .require("incomeStatement")
                .call("agedReceivables", () -> {
                    throw httpError(500, "boom");
                })
                .render();

        assertThat(parse(rendered).get("status").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("envelope has exactly composition, status, sections, sources")
    void envelope_hasExpectedShape() {
        JsonNode envelope = parse(ToolComposition.named("empty").render());

        java.util.List<String> fieldNames = new java.util.ArrayList<>();
        envelope.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly("composition", "status", "sections", "sources");
        assertThat(envelope.get("composition").asText()).isEqualTo("empty");
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.get("sections").isObject()).isTrue();
        assertThat(envelope.get("sources").isArray()).isTrue();
        assertThat(envelope.get("sources")).isEmpty();
    }

    @Test
    @DisplayName("requiring an undeclared leg fails fast")
    void requireUndeclaredLeg_throws() {
        ToolComposition composition = ToolComposition.named("x").call("a", () -> "{}");

        assertThatThrownBy(() -> composition.require("b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("b");
    }

    @Test
    @DisplayName("declaring the same leg name twice fails fast")
    void duplicateLegName_throws() {
        ToolComposition composition = ToolComposition.named("x").call("a", () -> "{}");

        assertThatThrownBy(() -> composition.call("a", () -> "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a");
    }
}
