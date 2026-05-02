package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.enums.SimpleChatRuleType;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

public final class SimpleChatRuleCatalog {

    private static final Pattern EMPTY_MATCH_PATTERN = Pattern.compile("(?!x)x");

    private final Pattern greetingPattern;
    private final Pattern thanksPattern;
    private final Pattern sayHelloPattern;
    private final Pattern capabilityPattern;
    private final Pattern socialQuestionPattern;
    private final Pattern taskRequestPattern;
    private final Pattern quantityQuestionPattern;
    private final Set<String> businessKeywords;
    private final Set<String> actionKeywords;
    private final Set<String> taskCueKeywords;

    private SimpleChatRuleCatalog(
            @NonNull Pattern greetingPattern,
            @NonNull Pattern thanksPattern,
            @NonNull Pattern sayHelloPattern,
            @NonNull Pattern capabilityPattern,
            @NonNull Pattern socialQuestionPattern,
            @NonNull Pattern taskRequestPattern,
            @NonNull Pattern quantityQuestionPattern,
            @NonNull Set<String> businessKeywords,
            @NonNull Set<String> actionKeywords,
            @NonNull Set<String> taskCueKeywords) {
        this.greetingPattern = greetingPattern;
        this.thanksPattern = thanksPattern;
        this.sayHelloPattern = sayHelloPattern;
        this.capabilityPattern = capabilityPattern;
        this.socialQuestionPattern = socialQuestionPattern;
        this.taskRequestPattern = taskRequestPattern;
        this.quantityQuestionPattern = quantityQuestionPattern;
        this.businessKeywords = businessKeywords;
        this.actionKeywords = actionKeywords;
        this.taskCueKeywords = taskCueKeywords;
    }

    public static @NonNull SimpleChatRuleCatalog from(@NonNull List<SimpleChatRuleSpec> rules) {
        Map<SimpleChatRuleType, Set<String>> grouped = rules.stream()
                .collect(Collectors.groupingBy(
                        SimpleChatRuleSpec::ruleType,
                        Collectors.mapping(rule -> normalize(rule.phrase()), Collectors.toSet())));

        return new SimpleChatRuleCatalog(
                buildExactPhrasePattern(grouped.get(SimpleChatRuleType.GREETING)),
                buildExactPhrasePattern(grouped.get(SimpleChatRuleType.THANKS)),
                buildSayHelloPattern(grouped.get(SimpleChatRuleType.SAY_HELLO_TARGET)),
                buildExactPhrasePattern(grouped.get(SimpleChatRuleType.CAPABILITY)),
                buildExactPhrasePattern(grouped.get(SimpleChatRuleType.SOCIAL_QUESTION)),
                buildContainsPhrasePattern(grouped.get(SimpleChatRuleType.TASK_REQUEST_PHRASE)),
                buildStartsWithPhrasePattern(grouped.get(SimpleChatRuleType.TASK_QUANTITY_PREFIX)),
                grouped.getOrDefault(SimpleChatRuleType.BUSINESS_KEYWORD, Set.of()),
                grouped.getOrDefault(SimpleChatRuleType.ACTION_KEYWORD, Set.of()),
                grouped.getOrDefault(SimpleChatRuleType.TASK_CUE_KEYWORD, Set.of()));
    }

    public boolean isPureSocialIntent(@NonNull String normalizedText) {
        return greetingPattern.matcher(normalizedText).matches()
                || thanksPattern.matcher(normalizedText).matches()
                || socialQuestionPattern.matcher(normalizedText).matches()
                || sayHelloPattern.matcher(normalizedText).matches()
                || capabilityPattern.matcher(normalizedText).matches();
    }

    public boolean isSocialQuestion(@NonNull String normalizedText) {
        return socialQuestionPattern.matcher(normalizedText).matches();
    }

    public boolean isCapability(@NonNull String normalizedText) {
        return capabilityPattern.matcher(normalizedText).matches();
    }

    public boolean matchesTaskRequest(@NonNull String normalizedText) {
        return taskRequestPattern.matcher(normalizedText).find();
    }

    public boolean matchesQuantityQuestion(@NonNull String normalizedText) {
        return quantityQuestionPattern.matcher(normalizedText).matches();
    }

    public boolean containsBusinessKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, businessKeywords);
    }

    public boolean containsActionKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, actionKeywords);
    }

    public boolean containsTaskCueKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, taskCueKeywords);
    }

    public static @NonNull String normalize(@NonNull String text) {
        String lowerCased = text.trim().toLowerCase(java.util.Locale.ROOT);
        String deAccented = Normalizer.normalize(lowerCased, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return deAccented.replaceAll("\\s+", " ");
    }

    static @NonNull List<String> tokenize(@NonNull String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static boolean containsAnyToken(@NonNull Set<String> inputTokens, @NonNull Set<String> candidateTokens) {
        for (String token : candidateTokens) {
            if (inputTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull Pattern buildExactPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return EMPTY_MATCH_PATTERN;
        }
        return Pattern.compile("^" + phraseAlternation(phrases) + "[?.! ]*$");
    }

    private static @NonNull Pattern buildContainsPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return EMPTY_MATCH_PATTERN;
        }
        return Pattern.compile("\\b" + phraseAlternation(phrases) + "\\b");
    }

    private static @NonNull Pattern buildStartsWithPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return EMPTY_MATCH_PATTERN;
        }
        return Pattern.compile("^" + phraseAlternation(phrases) + "\\b.*");
    }

    private static @NonNull Pattern buildSayHelloPattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return EMPTY_MATCH_PATTERN;
        }
        return Pattern.compile("^say " + phraseAlternation(phrases) + "\\b.*");
    }

    private static @NonNull String phraseAlternation(@NonNull Set<String> phrases) {
        return "(?:" + phrases.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")";
    }
}
