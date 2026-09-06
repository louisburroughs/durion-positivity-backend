package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #1838: the streaming path applies the blocking path's reply classification. */
class StreamingAnswerGuardTest {

    private final List<ChatResponseText.Source> classified = new ArrayList<>();

    private List<String> run(String... tokens) {
        return StreamingAnswerGuard.collect(List.of(tokens), classified::add);
    }

    @Test
    @DisplayName("prose streams through token by token, once the first characters rule out markup")
    void prose_passesThroughIncrementally() {
        List<String> out = run("Revenue ", "for August ", "was $16,588.29.");

        assertThat(out).containsExactly("Revenue ", "for August ", "was $16,588.29.");
        assertThat(classified).containsExactly(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("harmony analysis markup is held back and replaced by the fallback (#1834 on the stream)")
    void harmonyMarkup_isReplacedByFallback() {
        // s03 turn 1 on 2026-09-06, as it would have streamed: the opening tokens are the protocol.
        List<String> out =
                run("<|channel|>", "analysis<|message|>", "The user asked: who are our ten largest customers?");

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.PROTOCOL_MARKUP);
    }

    @Test
    @DisplayName("markup split across tokens is still caught: '<' alone waits for what follows")
    void splitMarkupPrefix_isHeld() {
        List<String> out = run("<", "|channel|>final<|message|>", "Two invoices are open.", "<|return|>");

        assertThat(out).containsExactly("Two invoices are open.");
        assertThat(classified).containsExactly(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("a bare JSON payload becomes the fallback: the stream has no re-render or ladder")
    void jsonPayload_isReplacedByFallback() {
        List<String> out = run("{\"startDate\":", "\"2026-03-01\",\"rows\":[]}");

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.TOOL_PAYLOAD);
    }

    @Test
    @DisplayName("a reply that is only a think block yields the fallback, not silence")
    void thinkOnly_isFallback() {
        List<String> out = run("<think>", "weighing the options", "</think>");

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.BLANK);
    }

    @Test
    @DisplayName("prose after a think block is emitted once the block is stripped")
    void thinkThenProse_emitsTheProse() {
        List<String> out = run("<think>plan</think>", "\nUse tool X.");

        assertThat(out).containsExactly("Use tool X.");
        assertThat(classified).containsExactly(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("an empty stream yields the fallback so the client never gets a silent blank")
    void emptyStream_isFallback() {
        List<String> out = run();

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.BLANK);
    }

    @Test
    @DisplayName("prose that opens with an HTML tag is not mistaken for markup")
    void htmlTagPrefix_passesThrough() {
        List<String> out = run("<b>", "Harbor Tool", "</b> owes $2,000.");

        assertThat(String.join("", out)).isEqualTo("<b>Harbor Tool</b> owes $2,000.");
        assertThat(classified).containsExactly(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("leading whitespace does not decide anything")
    void leadingWhitespace_isIgnoredForTheDecision() {
        List<String> out = run("  \n", "{\"a\":1}");

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.TOOL_PAYLOAD);
    }

    @Test
    @DisplayName(
            "markup after a non-marker first token is still held: the template may eat only the first special token")
    void markupAfterLeadingWord_isHeldAndClassified() {
        List<String> out = run("analysis<|message|>", "The user asked: who are our ten largest customers?");

        assertThat(out).containsExactly(ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.PROTOCOL_MARKUP);
    }

    @Test
    @DisplayName("markup after a prose lead-in stops the stream there; a recovered final channel follows")
    void markupMidReply_holdsTheTailAndRecoversTheFinalChannel() {
        List<String> out = run("Sure ", "<|channel|>final<|message|>", "Two invoices are open.", "<|return|>");

        assertThat(out).containsExactly("Sure ", "Two invoices are open.");
        assertThat(classified).containsExactly(ChatResponseText.Source.CONTENT);
    }

    @Test
    @DisplayName("markup after a prose lead-in with no final channel: the lead-in stands, the source is honest")
    void markupMidReply_withoutFinal_isReportedAsMarkup() {
        List<String> out = run("Sure ", "<|channel|>analysis<|message|>thinking out loud");

        assertThat(out).containsExactly("Sure ", ChatResponseText.BLANK_RESPONSE_FALLBACK);
        assertThat(classified).containsExactly(ChatResponseText.Source.PROTOCOL_MARKUP);
    }
}
