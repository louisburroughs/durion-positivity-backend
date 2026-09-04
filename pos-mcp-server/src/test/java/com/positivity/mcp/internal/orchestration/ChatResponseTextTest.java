package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class ChatResponseTextTest {

    @Test
    void extract_returnsContent_whenPresent() {
        AssistantMessage message = new AssistantMessage("The order total is $42.00.");

        assertThat(ChatResponseText.extract(message)).isEqualTo("The order total is $42.00.");
    }

    @Test
    void extract_stripsInlineThinkBlock_fromContent() {
        AssistantMessage message = new AssistantMessage("<think>weigh the options</think>\nUse tool X.");

        assertThat(ChatResponseText.extract(message)).isEqualTo("Use tool X.");
    }

    @Test
    void extract_recoversThinkingChannel_whenContentBlank() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("thinking", "The answer is 7."))
                .build();

        assertThat(ChatResponseText.extract(message)).isEqualTo("The answer is 7.");
    }

    @Test
    void extract_returnsFallback_whenContentAndThinkingBlank() {
        AssistantMessage message = AssistantMessage.builder()
                .content("   ")
                .properties(Map.of("thinking", "  "))
                .build();

        assertThat(ChatResponseText.extract(message)).isEqualTo(ChatResponseText.BLANK_RESPONSE_FALLBACK);
    }

    @Test
    void extract_returnsFallback_whenMessageNull() {
        assertThat(ChatResponseText.extract(null)).isEqualTo(ChatResponseText.BLANK_RESPONSE_FALLBACK);
    }

    // ── #1708: a bare tool payload is not an answer ──────────────────────────

    @Test
    @DisplayName("a reply that is nothing but a tool payload is not treated as a direct answer")
    void extractDetailed_bareToolPayload_isNotContent() {
        // q04's actual reply on the 2026-09-04 gate run. The tools ran and the windows were right;
        // the model emitted the first result verbatim instead of aggregating six months.
        String payload = "{\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-31\","
                + "\"rows\":[{\"avgDaysWoCreationToInvoice\":2.0,\"count\":6}]}";

        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(new AssistantMessage(payload));

        assertThat(extracted.source()).isEqualTo(ChatResponseText.Source.TOOL_PAYLOAD);
    }

    @Test
    @DisplayName("a JSON array reply is caught too")
    void extractDetailed_bareJsonArray_isNotContent() {
        ChatResponseText.Extracted extracted =
                ChatResponseText.extractDetailed(new AssistantMessage("[{\"vendorId\":\"v1\"}]"));

        assertThat(extracted.source()).isEqualTo(ChatResponseText.Source.TOOL_PAYLOAD);
    }

    @Test
    @DisplayName("an answer that merely CONTAINS json stays a direct answer")
    void extractDetailed_proseContainingJson_isStillContent() {
        // The guard must not discard a legitimate answer. This is the boundary that matters: an
        // over-eager check would silently replace real answers with the ladder's fallback, which
        // is a worse failure than the one being fixed.
        String prose = "Vendor spend for the window was $6,720.00. The raw row was "
                + "{\"vendorId\":\"v1\",\"paidAmount\":6720.00} if you need the identifier.";

        ChatResponseText.Extracted extracted = ChatResponseText.extractDetailed(new AssistantMessage(prose));

        assertThat(extracted.source()).isEqualTo(ChatResponseText.Source.CONTENT);
        assertThat(extracted.text()).isEqualTo(prose);
    }

    @Test
    @DisplayName("prose that opens and closes with braces is not a payload, even with a quoted name in it")
    void extractDetailed_braceWrappedProse_isStillContent() {
        assertThat(ChatResponseText.extractDetailed(new AssistantMessage("{see the attached summary}"))
                        .source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
        // The sample that matters: the earlier character-based check flagged this as a payload
        // because it starts with '{', ends with '}' and contains a quote — silently replacing a
        // real answer with the ladder's fallback, which is the worst outcome this guard can cause.
        assertThat(ChatResponseText.extractDetailed(
                                new AssistantMessage("{Top vendor is \"Cascade Parts\" at $12,000}"))
                        .source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("a fenced JSON payload is caught too")
    void extractDetailed_fencedPayload_isNotContent() {
        // A model told not to reply with bare JSON is at least as likely to fence it.
        String fenced = "```json\n{\"startDate\":\"2026-03-01\",\"rows\":[]}\n```";

        assertThat(ChatResponseText.extractDetailed(new AssistantMessage(fenced))
                        .source())
                .isEqualTo(ChatResponseText.Source.TOOL_PAYLOAD);
    }

    @Test
    @DisplayName("malformed JSON-looking text is not a payload")
    void extractDetailed_truncatedJson_isStillContent() {
        // A parse decides this now. Truncated output is a different defect and must not be routed
        // to the ladder on the strength of looking a bit like JSON.
        assertThat(ChatResponseText.extractDetailed(new AssistantMessage("{\"startDate\":\"2026-03-01\","))
                        .source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("a bare scalar is an answer, not a payload")
    void extractDetailed_bareScalar_isStillContent() {
        // "42" is a plausible answer to "how many open work orders?". Scalars are out of scope by
        // decision, not by accident; every payload observed in #1708 was an object.
        assertThat(ChatResponseText.extractDetailed(new AssistantMessage("42")).source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
        assertThat(ChatResponseText.extractDetailed(new AssistantMessage("\"OK\""))
                        .source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("a markdown table answer is unaffected")
    void extractDetailed_tableAnswer_isStillContent() {
        String table = "| Vendor | Spend |\n|---|---|\n| Cascade | $12,000.00 |";

        assertThat(ChatResponseText.extractDetailed(new AssistantMessage(table)).source())
                .isEqualTo(ChatResponseText.Source.CONTENT);
    }
}
