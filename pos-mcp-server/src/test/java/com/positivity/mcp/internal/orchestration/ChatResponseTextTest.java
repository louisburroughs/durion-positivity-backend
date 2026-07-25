package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
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
}
