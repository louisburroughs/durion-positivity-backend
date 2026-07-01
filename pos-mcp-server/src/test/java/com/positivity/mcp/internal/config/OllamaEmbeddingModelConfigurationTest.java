package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OllamaEmbeddingModelConfigurationTest {

    @Test
    void capToContext_leavesShortTextUnchanged() {
        String text = "What tools are available?";
        assertThat(OllamaEmbeddingModelConfiguration.capToContext(text)).isEqualTo(text);
    }

    @Test
    void capToContext_truncatesOverlongTextToTheCap() {
        String text = "x".repeat(OllamaEmbeddingModelConfiguration.MAX_INPUT_CHARS + 500);

        String capped = OllamaEmbeddingModelConfiguration.capToContext(text);

        assertThat(capped).hasSize(OllamaEmbeddingModelConfiguration.MAX_INPUT_CHARS);
    }

    @Test
    void capToContext_nullBecomesEmpty() {
        assertThat(OllamaEmbeddingModelConfiguration.capToContext(null)).isEmpty();
    }
}
