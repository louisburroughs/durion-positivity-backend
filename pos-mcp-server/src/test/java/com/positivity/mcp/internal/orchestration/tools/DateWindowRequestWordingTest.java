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
 * #1675, second attempt. The first fix asked the model to pass the caller's wording as a tool
 * argument. It does pass it — on every call — but it normalises it first: asked about "Of the
 * invoices we issued <b>in</b> the last six months" it sends {@code phrase="last six months"}, and
 * "…<b>over</b> the last six months" sends the identical string. The preposition it drops is the
 * entire discriminator between a rolling window and a calendar one, so the classifier saw an
 * ambiguous fragment, abstained exactly as designed, and the model's own wrong shape stood. That
 * was measured on the 2026-09-05 gate traces, where q09/q12/q15 failed unchanged.
 *
 * <p>A model-supplied copy of the user's words is still a model output. The request already
 * carries the real thing, so the tool reads it from there.
 */
@DisplayName("resolveDateWindow — classifying the request's wording, not the model's copy of it")
class DateWindowRequestWordingTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestScopedUserContext requestContext = new RequestScopedUserContext();

    @AfterEach
    void clear() {
        requestContext.clear();
    }

    @Test
    @DisplayName("q12: the request says `in the last six months`, so ROLLING is corrected")
    void requestWordingBeatsTheModelsNormalisedCopy() throws Exception {
        requestContext.recordUserMessage(
                "Of the invoices we issued in the last six months, how many were paid within 30 days?");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        // Exactly what the live trace recorded: the right count and unit, the wrong shape, and a
        // phrase with the deciding word already gone.
        JsonNode node = MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "NONE", "last six months"));

        assertThat(node.get("shape").asText()).isEqualTo("CALENDAR_SPAN");
    }

    @Test
    @DisplayName("`over` in the request still resolves rolling — the request is read, not overridden")
    void rollingRequestWordingStaysRolling() throws Exception {
        requestContext.recordUserMessage("What was revenue over the last six months?");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node = MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "NONE", "last six months"));

        assertThat(node.get("shape").asText()).isEqualTo("ROLLING");
    }

    @Test
    @DisplayName("the model's normalised phrase alone decides nothing")
    void thePhraseArgumentAloneCannotDecide() throws Exception {
        // No request wording recorded. "last six months" is genuinely ambiguous, so the classifier
        // must abstain rather than guess — guessing would silently redefine the caller's window.
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node = MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "NONE", "last six months"));

        assertThat(node.get("shape").asText()).isEqualTo("ROLLING");
    }

    @Test
    @DisplayName("no request context at all falls back to the model's phrase")
    void noRequestContextFallsBack() throws Exception {
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED);

        JsonNode node =
                MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "NONE", "in the last six months"));

        assertThat(node.get("shape").asText()).isEqualTo("CALENDAR_SPAN");
    }

    @Test
    @DisplayName("q15: the request's named comparison pulls a rolling primary onto the calendar")
    void mixedComparisonReadFromTheRequest() throws Exception {
        requestContext.recordUserMessage("Who were our largest vendors by spend over the last six months "
                + "compared with the same six months last year?");
        DateWindowFacadeTool tool = new DateWindowFacadeTool(FIXED, requestContext);

        JsonNode node =
                MAPPER.readTree(tool.resolveDateWindow("ROLLING", "MONTH", 6, "PRIOR_PERIOD", "last six months"));

        assertThat(node.get("shape").asText()).isEqualTo("CALENDAR_SPAN");
    }
}
