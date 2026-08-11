package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Gate 4: router JSON parsing, safe-default, and routing. */
class NltiRouterTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final NltiRouter router = new NltiRouter(chatModel, new ObjectMapper(), new TierSelector());

    @Test
    @DisplayName("parses strict JSON")
    void parsesValidJson() {
        RouterClassification c = router.parse(
                "{\"intentType\":\"QUERY\",\"riskLevel\":\"LOW\",\"complexity\":\"single-lookup\",\"domain\":\"workorder\"}");
        assertThat(c.intentType()).isEqualTo(NltiIntentType.QUERY);
        assertThat(c.riskLevel()).isEqualTo(NltiRiskLevel.LOW);
        assertThat(c.complexity()).isEqualTo(RequestComplexity.SINGLE_LOOKUP);
        assertThat(c.domain()).isEqualTo("workorder");
    }

    @Test
    @DisplayName("extracts JSON embedded in prose / code fences")
    void parsesFencedJson() {
        RouterClassification c = router.parse(
                "Sure!\n```json\n{\"intentType\":\"ACTION\",\"riskLevel\":\"HIGH\",\"complexity\":\"multi-domain\",\"domain\":\"invoice\"}\n```");
        assertThat(c.intentType()).isEqualTo(NltiIntentType.ACTION);
        assertThat(c.domain()).isEqualTo("invoice");
    }

    @Test
    @DisplayName("invalid enum or non-JSON -> safe default")
    void invalidFallsBackToSafeDefault() {
        RouterClassification bad = router.parse("{\"intentType\":\"WAT\",\"riskLevel\":\"LOW\",\"complexity\":\"x\"}");
        assertThat(bad).isEqualTo(RouterClassification.safeDefault());
        assertThat(router.parse("not json at all")).isEqualTo(RouterClassification.safeDefault());
    }

    @Test
    @DisplayName("route: simple read -> T2_SIMPLE; garbage model output -> T2_COMPLEX (safe)")
    void routeSelectsTier() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        chatResponse(
                                "{\"intentType\":\"QUERY\",\"riskLevel\":\"LOW\",\"complexity\":\"single-lookup\",\"domain\":\"customer\"}"));
        assertThat(router.route("look up customer ACME")).isEqualTo(ModelTier.T2_SIMPLE);

        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("garbage"));
        assertThat(router.route("anything")).isEqualTo(ModelTier.T2_COMPLEX);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text))));
    }
}
