package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class SimpleChatClassifier {

    private static final int MAX_SIMPLE_CHAT_CHAR_LENGTH = 160;
    private static final int MAX_SIMPLE_CHAT_TOKEN_COUNT = 12;

    /**
     * Words that lean on the previous turn (#1836). The T0 path answers with the master prompt and
     * the bare message — no conversation history — so a message that continues an earlier one can
     * never be answered there, whatever else it says: "Now rank those same ten by outstanding balance
     * instead" reached T0 on the 2026-09-06 sequences run and was answered with "which ten?". Tokens
     * are compared after {@link SimpleChatRuleCatalog#normalize}, hence the de-accented forms.
     */
    private static final Set<String> CONTINUATION_CUES = Set.of(
            // en
            "those",
            "them",
            "same",
            "instead",
            "also",
            "ones",
            "previous",
            "earlier",
            "above",
            "again",
            // fr
            "ceux",
            "celles",
            "memes",
            "aussi",
            "plutot",
            "precedent",
            "precedente",
            "encore",
            // es
            "esos",
            "esas",
            "mismos",
            "mismas",
            "tambien",
            "anterior",
            "anteriores",
            "otra",
            "vez");

    private final Supplier<SimpleChatRuleCatalog> catalogSupplier;

    @Autowired
    SimpleChatClassifier(@NonNull SimpleChatCatalogProvider catalogProvider) {
        this(catalogProvider::currentCatalog);
    }

    // Test-only constructor for supplying a fixed in-memory catalog.
    SimpleChatClassifier(@NonNull SimpleChatRuleCatalog catalog) {
        this(() -> catalog);
    }

    private SimpleChatClassifier(@NonNull Supplier<SimpleChatRuleCatalog> catalogSupplier) {
        this.catalogSupplier = catalogSupplier;
    }

    boolean isSimpleChat(@NonNull String message) {
        String text = SimpleChatRuleCatalog.normalize(message);
        if (text.isBlank() || text.length() > MAX_SIMPLE_CHAT_CHAR_LENGTH) {
            return false;
        }

        MessageFeatures features = MessageFeatures.from(text);
        if (features.tokens().isEmpty() || features.tokenCount() > MAX_SIMPLE_CHAT_TOKEN_COUNT) {
            return false;
        }

        SimpleChatRuleCatalog catalog = catalogSupplier.get();
        if (catalog.isPureSocialIntent(text)) {
            return true;
        }

        return !hasStrongTaskSignal(text, features, catalog);
    }

    private static boolean hasStrongTaskSignal(
            @NonNull String text, @NonNull MessageFeatures features, @NonNull SimpleChatRuleCatalog catalog) {
        if (features.hasQuestionMark() && !catalog.isSocialQuestion(text) && !catalog.isCapability(text)) {
            return true;
        }
        if (features.tokenSet().stream().anyMatch(CONTINUATION_CUES::contains)) {
            return true;
        }
        if (catalog.matchesQuantityQuestion(text)) {
            return true;
        }
        if (catalog.matchesTaskRequest(text)) {
            return true;
        }
        return catalog.containsBusinessKeyword(features.tokenSet())
                || catalog.containsActionKeyword(features.tokenSet())
                || catalog.containsTaskCueKeyword(features.tokenSet());
    }

    private record MessageFeatures(
            @NonNull List<String> tokens, @NonNull Set<String> tokenSet, boolean hasQuestionMark) {

        static @NonNull MessageFeatures from(@NonNull String text) {
            List<String> tokens = SimpleChatRuleCatalog.tokenize(text);
            return new MessageFeatures(tokens, new HashSet<>(tokens), text.indexOf('?') >= 0);
        }

        int tokenCount() {
            return tokens.size();
        }
    }
}
