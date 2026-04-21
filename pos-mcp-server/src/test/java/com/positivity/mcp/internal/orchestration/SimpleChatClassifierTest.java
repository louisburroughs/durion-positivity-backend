package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleChatClassifierTest {

    @Test
    @DisplayName("simple greetings are routed to the no-tool path")
    void isSimpleChat_withGreeting_returnsTrue() {
        assertThat(SimpleChatClassifier.isSimpleChat("Say hello in one short sentence."))
                .isTrue();
        assertThat(SimpleChatClassifier.isSimpleChat("hello")).isTrue();
    }

    @Test
    @DisplayName("business requests stay on the agent path")
    void isSimpleChat_withBusinessRequest_returnsFalse() {
        assertThat(SimpleChatClassifier.isSimpleChat("Hello, can you check stock for SKU ABC?"))
                .isFalse();
        assertThat(SimpleChatClassifier.isSimpleChat("Find customer John Smith"))
                .isFalse();
    }
}
