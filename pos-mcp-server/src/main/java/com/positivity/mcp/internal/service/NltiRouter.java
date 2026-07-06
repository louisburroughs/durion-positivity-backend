package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import dev.langchain4j.model.chat.ChatModel;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Gate 4 T1 router. Classifies a request (intent / risk / domain / complexity) with a small model,
 * then maps it to a {@link ModelTier} via {@link TierSelector}.
 *
 * <p>Safety: the classifier prompt demands strict JSON; any missing/invalid output yields
 * {@link RouterClassification#safeDefault()} and any model error yields {@link ModelTier#T2_COMPLEX}
 * — risky requests are never silently downgraded to a small model.
 */
@Component
@Profile("alpha")
public class NltiRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiRouter.class);

    static final String SYSTEM_PROMPT = """
            You are a request router for a tire/service ERP assistant. Classify the user request and
            reply with ONLY a JSON object, no prose, exactly these keys:
            {"intentType":"QUERY|ACTION|UNKNOWN","riskLevel":"LOW|MEDIUM|HIGH",\
            "complexity":"single-lookup|multi-domain","domain":"<one lowercase business domain>"}
            - intentType ACTION = the user wants to create/change/cancel something; QUERY = read/lookup.
            - riskLevel HIGH for money movement, postings, deletions, or irreversible changes.
            - complexity multi-domain when more than one entity/domain or several steps are involved.
            - domain examples: workorder, invoice, accounting, tax, inventory, pricing, customer, vehicle, admin, security, shopmanager, master.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final TierSelector tierSelector;

    public NltiRouter(
            @NonNull ChatModel chatModel, @NonNull ObjectMapper objectMapper, @NonNull TierSelector tierSelector) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.tierSelector = tierSelector;
    }

    /** Routes a request to a model tier. Any failure defaults to {@link ModelTier#T2_COMPLEX}. */
    public @NonNull ModelTier route(@NonNull String message) {
        try {
            String output = chatModel.chat(SYSTEM_PROMPT + "\n\nUser request:\n" + message);
            return tierSelector.select(parse(output));
        } catch (RuntimeException exception) {
            LOGGER.warn("MCP router classification failed; defaulting to T2_COMPLEX error={}", exception.toString());
            return ModelTier.T2_COMPLEX;
        }
    }

    /** Parses the router model's output into a classification, falling back to the safe default. */
    @NonNull
    RouterClassification parse(@NonNull String rawOutput) {
        try {
            String json = extractJson(rawOutput);
            if (json == null) {
                return RouterClassification.safeDefault();
            }
            JsonNode node = objectMapper.readTree(json);
            NltiIntentType intent =
                    enumOrNull(NltiIntentType.class, node.path("intentType").asText());
            NltiRiskLevel risk =
                    enumOrNull(NltiRiskLevel.class, node.path("riskLevel").asText());
            RequestComplexity complexity =
                    complexityOrNull(node.path("complexity").asText());
            String domain = node.path("domain").asText("master");
            if (intent == null || risk == null || complexity == null) {
                return RouterClassification.safeDefault();
            }
            return new RouterClassification(intent, risk, complexity, domain.isBlank() ? "master" : domain);
        } catch (JsonProcessingException | RuntimeException e) {
            return RouterClassification.safeDefault();
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RequestComplexity complexityOrNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "single_lookup" -> RequestComplexity.SINGLE_LOOKUP;
            case "multi_domain" -> RequestComplexity.MULTI_DOMAIN;
            default -> null;
        };
    }
}
