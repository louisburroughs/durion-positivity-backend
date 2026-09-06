package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimpleChatClassifierTest {

    private final SimpleChatClassifier classifier = new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog());

    @Test
    @DisplayName("simple greetings are routed to the no-tool path")
    void isSimpleChat_withGreeting_returnsTrue() {
        assertThat(classifier.isSimpleChat("Say hello in one short sentence.")).isTrue();
        assertThat(classifier.isSimpleChat("hello")).isTrue();
        assertThat(classifier.isSimpleChat("bonjour")).isTrue();
        assertThat(classifier.isSimpleChat("salut!")).isTrue();
        assertThat(classifier.isSimpleChat("hola")).isTrue();
        assertThat(classifier.isSimpleChat("buenos días")).isTrue();
        assertThat(classifier.isSimpleChat("buenas tardes")).isTrue();
        assertThat(classifier.isSimpleChat("thanks")).isTrue();
        assertThat(classifier.isSimpleChat("thank you")).isTrue();
        assertThat(classifier.isSimpleChat("merci")).isTrue();
        assertThat(classifier.isSimpleChat("gracias")).isTrue();
        assertThat(classifier.isSimpleChat("muchas gracias!")).isTrue();
        assertThat(classifier.isSimpleChat("how are you?")).isTrue();
        assertThat(classifier.isSimpleChat("comment ça va?")).isTrue();
        assertThat(classifier.isSimpleChat("cómo estás?")).isTrue();
        assertThat(classifier.isSimpleChat("what can you do?")).isTrue();
    }

    @Test
    @DisplayName("tool and RAG requests stay on the agent path even when short or prefixed with greetings")
    void isSimpleChat_withTaskRequest_returnsFalse() {
        assertThat(classifier.isSimpleChat("Hello, can you check stock for SKU ABC?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("Find customer John Smith")).isFalse();
        assertThat(classifier.isSimpleChat("thanks, can you summarize the invoice for order 123?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("bonjour, pouvez-vous explain the return policy?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("hola, por favor search for battery warranty details"))
                .isFalse();
        assertThat(classifier.isSimpleChat("trouver le client jean dupont")).isFalse();
        assertThat(classifier.isSimpleChat("buscar cliente juan perez")).isFalse();
        assertThat(classifier.isSimpleChat("rapport des ventes")).isFalse();
        assertThat(classifier.isSimpleChat("informe de ventas")).isFalse();
        assertThat(classifier.isSimpleChat("how many open orders do we have?")).isFalse();
        assertThat(classifier.isSimpleChat("how much inventory is available for sku abc?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("combien de factures sont en attente ?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("cuántos pedidos están abiertos?")).isFalse();
        assertThat(classifier.isSimpleChat("cuánto stock tenemos?")).isFalse();
        assertThat(classifier.isSimpleChat("explain the inventory valuation procedure"))
                .isFalse();
    }

    @Test
    @DisplayName("a follow-up that leans on the previous turn never takes the history-less T0 path (#1836)")
    void isSimpleChat_withContinuationCue_returnsFalse() {
        // The MULTI_TURN corpus follow-ups. s03 turn 2 reached T0 on 2026-09-06 and was answered
        // "which ten?" because the fast path has no conversation history.
        assertThat(classifier.isSimpleChat("Now rank those same ten by outstanding balance instead."))
                .isFalse();
        assertThat(classifier.isSimpleChat("Which of those has the most outstanding AR?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("Which three of them have the oldest past-due invoices?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("And the month before that?")).isFalse();
        assertThat(classifier.isSimpleChat("What is their outstanding balance?"))
                .isFalse();
        assertThat(classifier.isSimpleChat("How does that compare with the same quarter last year?"))
                .isFalse();
        // Cues alone, without a question mark or business word: still a continuation.
        assertThat(classifier.isSimpleChat("Show me those again")).isFalse();
        assertThat(classifier.isSimpleChat("Rank them by balance instead")).isFalse();
        assertThat(classifier.isSimpleChat("montre-moi ceux-la aussi")).isFalse();
        assertThat(classifier.isSimpleChat("muestrame esos tambien")).isFalse();
        // Accented forms reach the cue set through normalize(): mêmes → memes, también → tambien.
        assertThat(classifier.isSimpleChat("montre-moi les mêmes plutôt")).isFalse();
        assertThat(classifier.isSimpleChat("muéstrame los mismos también")).isFalse();
    }

    @Test
    @DisplayName("analytics vocabulary is a task signal on its own")
    void isSimpleChat_withAnalyticsVocabulary_returnsFalse() {
        assertThat(classifier.isSimpleChat("rank customers by revenue")).isFalse();
        assertThat(classifier.isSimpleChat("outstanding balance for Harbor Tool"))
                .isFalse();
    }
}
