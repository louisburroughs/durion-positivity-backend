package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.service.RequestScopedUserContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1774: q15 asks for "the last six months compared with <b>the same six months last year</b>" and
 * the model sent {@code comparison=PRIOR_PERIOD} — the six months immediately before, not the same
 * span a year earlier. Those are different windows (2025-09..2026-02 versus 2025-03..2025-08), so
 * the year-on-year figure was computed against the wrong baseline.
 *
 * <p>#1675 taught the resolver to correct a SHAPE the wording contradicts, but deliberately only
 * <em>filled in</em> a comparison the model had left NONE — never overrode one it supplied. That
 * caution was written for lack of evidence about comparison wording. This is the evidence: the
 * phrase names a specific period outright, and the classifier already recognises that family.
 */
@DisplayName("resolveDateWindow — correcting a comparison the wording contradicts")
class ComparisonCorrectionTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestScopedUserContext requestContext = new RequestScopedUserContext();

    @AfterEach
    void clear() {
        requestContext.clear();
    }

    @Test
    @DisplayName("q15: `the same six months last year` overrides a supplied PRIOR_PERIOD")
    void namedComparisonOverridesTheModelsChoice() throws Exception {
        requestContext.recordUserMessage("Who were our largest vendors by spend over the last six months "
                + "compared with the same six months last year?");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node =
                MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "PRIOR_PERIOD", "last six months"));

        assertThat(node.get("comparison").get("startDate").asText()).isEqualTo("2025-03-01");
        assertThat(node.get("comparison").get("endDate").asText()).isEqualTo("2025-08-31");
    }

    @Test
    @DisplayName("wording naming no comparison leaves the model's choice alone")
    void unnamedComparisonIsNotOverridden() throws Exception {
        // The classifier abstains on comparison wording it does not recognise, exactly as it does
        // for shape. Overriding here would be inventing a rule rather than applying one.
        requestContext.recordUserMessage("Revenue over the last six months versus the period before that");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node =
                MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "PRIOR_PERIOD", "last six months"));

        assertThat(node.get("comparison").get("startDate").asText()).isEqualTo("2025-09-06");
    }

    @Test
    @DisplayName("a comparison the model omitted is still filled in, as before")
    void anOmittedComparisonIsStillFilledIn() throws Exception {
        requestContext.recordUserMessage(
                "Vendor spend over the last six months compared with " + "the same six months last year");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node = MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "NONE", "last six months"));

        assertThat(node.has("comparison")).isTrue();
        assertThat(node.get("comparison").get("startDate").asText()).isEqualTo("2025-03-01");
    }
}
