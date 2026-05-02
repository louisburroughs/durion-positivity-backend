package com.positivity.mcp.internal.classification;

import com.positivity.mcp.internal.enums.SimpleChatRuleType;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class SimpleChatRuleCatalog {

    private final PhrasePatterns phrasePatterns;
    private final KeywordSets keywordSets;

    private SimpleChatRuleCatalog(@NonNull PhrasePatterns phrasePatterns, @NonNull KeywordSets keywordSets) {
        this.phrasePatterns = phrasePatterns;
        this.keywordSets = keywordSets;
    }

    public static @NonNull SimpleChatRuleCatalog from(@NonNull List<SimpleChatRuleSpec> rules) {
        Map<SimpleChatRuleType, Set<String>> grouped = rules.stream()
                .collect(Collectors.groupingBy(
                        SimpleChatRuleSpec::ruleType,
                        Collectors.mapping(rule -> normalize(rule.phrase()), Collectors.toSet())));

        return new SimpleChatRuleCatalog(
                new PhrasePatterns(
                        buildExactPhrasePattern(grouped.get(SimpleChatRuleType.GREETING)),
                        buildExactPhrasePattern(grouped.get(SimpleChatRuleType.THANKS)),
                        buildSayHelloPattern(grouped.get(SimpleChatRuleType.SAY_HELLO_TARGET)),
                        buildExactPhrasePattern(grouped.get(SimpleChatRuleType.CAPABILITY)),
                        buildExactPhrasePattern(grouped.get(SimpleChatRuleType.SOCIAL_QUESTION)),
                        buildContainsPhrasePattern(grouped.get(SimpleChatRuleType.TASK_REQUEST_PHRASE)),
                        buildStartsWithPhrasePattern(grouped.get(SimpleChatRuleType.TASK_QUANTITY_PREFIX))),
                new KeywordSets(
                        grouped.getOrDefault(SimpleChatRuleType.BUSINESS_KEYWORD, Set.of()),
                        grouped.getOrDefault(SimpleChatRuleType.ACTION_KEYWORD, Set.of()),
                        grouped.getOrDefault(SimpleChatRuleType.TASK_CUE_KEYWORD, Set.of())));
    }

    public boolean isPureSocialIntent(@NonNull String normalizedText) {
        return matches(phrasePatterns.greetingPattern(), normalizedText)
                || matches(phrasePatterns.thanksPattern(), normalizedText)
                || matches(phrasePatterns.socialQuestionPattern(), normalizedText)
                || matches(phrasePatterns.sayHelloPattern(), normalizedText)
                || matches(phrasePatterns.capabilityPattern(), normalizedText);
    }

    public boolean isSocialQuestion(@NonNull String normalizedText) {
        return matches(phrasePatterns.socialQuestionPattern(), normalizedText);
    }

    public boolean isCapability(@NonNull String normalizedText) {
        return matches(phrasePatterns.capabilityPattern(), normalizedText);
    }

    public boolean matchesTaskRequest(@NonNull String normalizedText) {
        return finds(phrasePatterns.taskRequestPattern(), normalizedText);
    }

    public boolean matchesQuantityQuestion(@NonNull String normalizedText) {
        return matches(phrasePatterns.quantityQuestionPattern(), normalizedText);
    }

    public boolean containsBusinessKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, keywordSets.businessKeywords());
    }

    public boolean containsActionKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, keywordSets.actionKeywords());
    }

    public boolean containsTaskCueKeyword(@NonNull Set<String> tokens) {
        return containsAnyToken(tokens, keywordSets.taskCueKeywords());
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

    private static boolean matches(@Nullable Pattern pattern, @NonNull String text) {
        return pattern != null && pattern.matcher(text).matches();
    }

    private static boolean finds(@Nullable Pattern pattern, @NonNull String text) {
        return pattern != null && pattern.matcher(text).find();
    }

    private static boolean containsAnyToken(@NonNull Set<String> inputTokens, @NonNull Set<String> candidateTokens) {
        for (String token : candidateTokens) {
            if (inputTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Pattern buildExactPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return null;
        }
        return Pattern.compile("^" + phraseAlternation(phrases) + "[?.! ]*$");
    }

    private static @Nullable Pattern buildContainsPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return null;
        }
        return Pattern.compile("\\b" + phraseAlternation(phrases) + "\\b");
    }

    private static @Nullable Pattern buildStartsWithPhrasePattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return null;
        }
        return Pattern.compile("^" + phraseAlternation(phrases) + "\\b.*");
    }

    private static @Nullable Pattern buildSayHelloPattern(Set<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            return null;
        }
        return Pattern.compile("^say " + phraseAlternation(phrases) + "\\b.*");
    }

    private static @NonNull String phraseAlternation(@NonNull Set<String> phrases) {
        return "(?:" + phrases.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")";
    }

    private record PhrasePatterns(
            @Nullable Pattern greetingPattern,
            @Nullable Pattern thanksPattern,
            @Nullable Pattern sayHelloPattern,
            @Nullable Pattern capabilityPattern,
            @Nullable Pattern socialQuestionPattern,
            @Nullable Pattern taskRequestPattern,
            @Nullable Pattern quantityQuestionPattern) {}

    private record KeywordSets(
            @NonNull Set<String> businessKeywords,
            @NonNull Set<String> actionKeywords,
            @NonNull Set<String> taskCueKeywords) {}
}
