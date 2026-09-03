package com.positivity.shopmanager.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.BaseContractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0042 §1 depth checks for {@code GET /v1/shop-dashboard}, run against the live springdoc
 * spec (#1658 AC15).
 *
 * <p>The canonical enforcement of this rule lives in {@code pos-openapi-validation}, which reads
 * the committed {@code openapi.yaml}. That file is regenerated out of band, so a description that
 * fails the rule is only discovered on the next regeneration — potentially in someone else's
 * change. Asserting the same rules here, against the spec springdoc builds from the annotations at
 * runtime, moves that failure to the commit that causes it.
 *
 * <p>The lead-in patterns are the ones {@code OpenApiAnnotationDepthValidator} matches on; they are
 * repeated rather than imported because this module does not depend on the validation module.
 */
@DisplayName("GET /v1/shop-dashboard — ADR-0042 annotation depth")
class ShopDashboardOpenApiContractTest extends BaseContractIntegrationTest {

    private static final int MIN_SENTENCES = 4;
    private static final int MAX_SENTENCES = 8;
    private static final int MIN_PRIMARY_ACTION_LENGTH = 30;

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z(\"])");
    private static final Pattern WHEN_TO_USE = Pattern.compile("(?i)\\buse this (tool|operation|endpoint)\\b");
    private static final Pattern PRECONDITIONS = Pattern.compile("(?i)\\bpreconditions?\\b\\s*:");
    private static final Pattern INPUT_EXPECTATIONS = Pattern.compile("(?i)\\b(required )?inputs?\\b\\s*:");
    private static final Pattern SIDE_EFFECTS =
            Pattern.compile("(?i)\\bemits\\b|\\bno events are emitted\\b|\\bside[ -]effects?\\b\\s*:");
    private static final Pattern ERROR_CONDITIONS = Pattern.compile("(?i)\\breturns\\s+\\d{3}\\b");
    private static final Pattern NEGATIVE_GUIDANCE =
            Pattern.compile("(?i)\\bdo not\\b|\\bdon't\\b|\\binstead\\b|\\brather than\\b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("#1658 AC15 - the operation carries a description of the required depth")
    void descriptionMeetsAdr0042Depth() throws Exception {
        Map<String, Object> operation = shopDashboardGetOperation();

        assertThat(operation.get("operationId")).isEqualTo("getShopDashboard");
        assertThat((String) operation.get("summary")).isNotBlank();

        String description =
                ((String) operation.get("description")).replaceAll("\\s+", " ").strip();
        List<String> sentences = List.of(SENTENCE_BOUNDARY.split(description));

        assertThat(sentences.size())
                .as("ADR-0042 §1 requires %d-%d sentences, got: %s", MIN_SENTENCES, MAX_SENTENCES, sentences)
                .isBetween(MIN_SENTENCES, MAX_SENTENCES);
        assertThat(sentences.get(0))
                .as("must open with a primary-action sentence")
                .hasSizeGreaterThanOrEqualTo(MIN_PRIMARY_ACTION_LENGTH)
                .doesNotContainIgnoringCase("use this tool");
        assertThat(WHEN_TO_USE.matcher(description).find())
                .as("when-to-use guidance")
                .isTrue();
        assertThat(PRECONDITIONS.matcher(description).find())
                .as("preconditions")
                .isTrue();
        assertThat(INPUT_EXPECTATIONS.matcher(description).find())
                .as("input expectations")
                .isTrue();
        assertThat(SIDE_EFFECTS.matcher(description).find()).as("side effects").isTrue();
        assertThat(ERROR_CONDITIONS.matcher(description).find())
                .as("error conditions")
                .isTrue();
        assertThat(NEGATIVE_GUIDANCE.matcher(description).find())
                .as("negative guidance")
                .isTrue();
    }

    @Test
    @DisplayName("#1658 AC15 - the description names the replica staleness the caller has to expect")
    void descriptionDeclaresEventualConsistency() throws Exception {
        String description = (String) shopDashboardGetOperation().get("description");

        assertThat(description.replaceAll("\\s+", " "))
                .as("a read model over an at-least-once feed must say so in its own contract")
                .contains("replicated")
                .contains("behind");
    }

    @Test
    @DisplayName("#1658 AC12 - the documented responses cover 400, 403 and 404")
    @SuppressWarnings("unchecked")
    void documentedResponsesCoverTheErrorContract() throws Exception {
        Map<String, Object> responses =
                (Map<String, Object>) shopDashboardGetOperation().get("responses");

        assertThat(responses).containsKeys("200", "400", "403", "404");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shopDashboardGetOperation() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> spec = objectMapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get("/v1/shop-dashboard");
        assertThat(pathItem)
                .as("GET /v1/shop-dashboard must be in the generated spec")
                .isNotNull();
        return (Map<String, Object>) pathItem.get("get");
    }
}
