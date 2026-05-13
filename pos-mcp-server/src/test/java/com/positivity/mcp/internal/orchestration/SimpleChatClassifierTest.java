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
}
