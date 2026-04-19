package com.positivity.mcp.internal.orchestration;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;

final class SimpleChatClassifier {

    private static final int MAX_SIMPLE_CHAT_LENGTH = 160;

    private static final Set<String> BUSINESS_KEYWORDS = Set.of(
            "accounting",
            "audit",
            "catalog",
            "customer",
            "employee",
            "event",
            "invoice",
            "inventory",
            "location",
            "order",
            "part",
            "payment",
            "price",
            "product",
            "report",
            "sales",
            "shop",
            "sku",
            "stock",
            "tax",
            "vehicle",
            "vin",
            "workorder");

    private static final Set<String> ACTION_KEYWORDS = Set.of(
            "calculate",
            "check",
            "create",
            "delete",
            "find",
            "get",
            "list",
            "lookup",
            "search",
            "show",
            "summarize",
            "update");

    private SimpleChatClassifier() {}

    static boolean isSimpleChat(@NonNull String message) {
        String text = normalize(message);
        if (text.isBlank() || text.length() > MAX_SIMPLE_CHAT_LENGTH) {
            return false;
        }
        if (containsAnyToken(text, BUSINESS_KEYWORDS) || containsAnyToken(text, ACTION_KEYWORDS)) {
            return false;
        }

        return isGreeting(text)
                || isThanks(text)
                || text.matches("^say hello\\b.*")
                || text.matches("^(who are you|what can you do|help)[?.! ]*$");
    }

    private static boolean isGreeting(@NonNull String text) {
        return text.matches("^(hi|hello|hey|good morning|good afternoon|good evening)[!. ]*$");
    }

    private static boolean isThanks(@NonNull String text) {
        return text.matches("^(thanks|thank you)[!. ]*$");
    }

    private static boolean containsAnyToken(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull String normalize(@NonNull String message) {
        return message.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
