package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * CI-safe unit coverage for {@link OfflineReplayEvaluator}'s stub tool
 * callback: canned-response
 * consumption order, argument-key presence checking, and exhausted-queue
 * behavior. No model, no
 * network — {@link OfflineReplayEvaluator#replay} itself is exercised only by
 * the runtime-gated
 * {@code OfflineReplayEvalIT}.
 */
class OfflineReplayEvaluatorTest {

    @Test
    @DisplayName("stub callback returns canned responses in queued order and records each call")
    void stubCallback_returnsResponsesInOrder() {
        List<OfflineReplayEvaluator.ObservedToolCall> observed = new ArrayList<>();
        Deque<OfflineReplayEvaluator.ToolResponseFixture> responses = OfflineReplayEvaluator.newResponseQueue();
        responses.add(new OfflineReplayEvaluator.ToolResponseFixture("getFoo", List.of("startDate"), "{\"a\":1}"));
        responses.add(new OfflineReplayEvaluator.ToolResponseFixture("getFoo", List.of("startDate"), "{\"a\":2}"));
        ToolCallback callback = OfflineReplayEvaluator.stubCallback("getFoo", "desc", "{}", responses, observed);

        assertThat(callback.call("{\"startDate\":\"2026-01-01\"}")).isEqualTo("{\"a\":1}");
        assertThat(callback.call("{\"startDate\":\"2026-02-01\"}")).isEqualTo("{\"a\":2}");

        assertThat(observed).hasSize(2);
        assertThat(observed.get(0).sequence()).isEqualTo(1);
        assertThat(observed.get(0).argumentsMatched()).isTrue();
        assertThat(observed.get(1).sequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call missing a required argument key is recorded as unmatched, not rejected")
    void stubCallback_missingRequiredKey_recordsUnmatched() {
        List<OfflineReplayEvaluator.ObservedToolCall> observed = new ArrayList<>();
        Deque<OfflineReplayEvaluator.ToolResponseFixture> responses = OfflineReplayEvaluator.newResponseQueue();
        responses.add(new OfflineReplayEvaluator.ToolResponseFixture("getFoo", List.of("startDate"), "{\"a\":1}"));
        ToolCallback callback = OfflineReplayEvaluator.stubCallback("getFoo", "desc", "{}", responses, observed);

        assertThat(callback.call("{\"wrongKey\":\"x\"}")).isEqualTo("{\"a\":1}");

        assertThat(observed).hasSize(1);
        assertThat(observed.get(0).argumentsMatched()).isFalse();
    }

    @Test
    @DisplayName("an exhausted response queue surfaces an error result instead of throwing")
    void stubCallback_exhaustedQueue_returnsErrorResult() {
        List<OfflineReplayEvaluator.ObservedToolCall> observed = new ArrayList<>();
        Deque<OfflineReplayEvaluator.ToolResponseFixture> responses = OfflineReplayEvaluator.newResponseQueue();
        ToolCallback callback = OfflineReplayEvaluator.stubCallback("getFoo", "desc", "{}", responses, observed);

        String result = callback.call("{}");

        assertThat(result).contains("no canned response remaining for 'getFoo'");
        assertThat(observed)
                .singleElement()
                .satisfies(call -> assertThat(call.error()).isNotBlank());
    }

    @Test
    @DisplayName("call order across multiple stub callbacks accumulates into one shared sequence")
    void multipleStubs_shareOneObservedSequence() {
        List<OfflineReplayEvaluator.ObservedToolCall> observed = new ArrayList<>();
        Deque<OfflineReplayEvaluator.ToolResponseFixture> fooResponses = OfflineReplayEvaluator.newResponseQueue();
        fooResponses.add(new OfflineReplayEvaluator.ToolResponseFixture("getFoo", List.of(), "{}"));
        Deque<OfflineReplayEvaluator.ToolResponseFixture> barResponses = OfflineReplayEvaluator.newResponseQueue();
        barResponses.add(new OfflineReplayEvaluator.ToolResponseFixture("getBar", List.of(), "{}"));
        ToolCallback foo = OfflineReplayEvaluator.stubCallback("getFoo", "d", "{}", fooResponses, observed);
        ToolCallback bar = OfflineReplayEvaluator.stubCallback("getBar", "d", "{}", barResponses, observed);

        foo.call("{}");
        bar.call("{}");

        assertThat(observed)
                .extracting(OfflineReplayEvaluator.ObservedToolCall::name)
                .containsExactly("getFoo", "getBar");
        assertThat(observed)
                .extracting(OfflineReplayEvaluator.ObservedToolCall::sequence)
                .containsExactly(1, 2);
    }
}
