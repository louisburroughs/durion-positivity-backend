package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.classification.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.config.CompoundRerankProperties;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.service.ToolRegistryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §2: today's rules, moved behind the {@link QuestionTagger} seam unchanged. The
 * test-profile implementation, the fallback on any decision-model failure, and (Wave 2) the per-tag
 * fallback below threshold.
 *
 * <p>Every word list and regex here is the one its consumer used before ADR-0068 ({@code
 * ToolSelectionEngine.deriveWorkflowState}, {@code fallbackToolsForMessage}, {@code
 * mentionsDateWindow}; {@code SimpleChatClassifier} for {@code simple_chat} and the continuation
 * cues; {@code ToolRegistryService}'s admin lists, read from there over the existing {@code
 * orchestration → service} edge; the #1180 split for {@code compound_question}). The fixture test
 * {@code TaggingBehaviourPreservationTest} pins every decision to the pre-refactor value.
 *
 * <p>A rule either fires or does not, so every answer carries confidence {@code 1.0} and source
 * {@link com.positivity.mcp.internal.domain.TagSource#HEURISTIC}. The router-derived tags answer
 * {@link RouterClassification#safeDefault()}; no entity tag is answered (the lexicon term match
 * already seeds the scope, ADR-0069 §5.1).
 */
@Component
public class HeuristicQuestionTagger implements QuestionTagger {

    /**
     * Vocabulary that makes a question a dated one, and so makes {@code resolveDateWindow}
     * mandatory rather than merely likely (#1684). Broad on purpose: the tool is additive to the
     * semantic top-K rather than competing for a slot in it, so a false positive costs one tool
     * schema in the prompt while a false negative costs the whole date-window contract — the
     * DATE_WINDOW layer would be instructing the model to call a tool it cannot see, leaving it to
     * compute the dates itself, which is the failure #1675 and #1684 exist to remove.
     *
     * <p>Broad is not unbounded, and the schema is not free: this tool's description and parameters
     * are roughly 440 tokens, more than the ~305 the #1684 prompt-layer shrink saves. On a question
     * that matches a token but needs no window the assembled prompt is therefore <em>larger</em>
     * than before, which is the opposite of what the shrink was for. So a token earns its place only
     * by naming a window the resolver can actually resolve. Four were cut on that test: {@code
     * recent}, {@code recently} and {@code lately} are the phrases the layer itself singles out as
     * having no conventional reading and tells the model to ask about, so offering a resolver for
     * them is incoherent (and {@code recent} already pulls in the web-search tool below, making two
     * extra schemas); {@code period} is accounting vocabulary far more often than it is a window,
     * and it names the {@code period} shortcut that is a shape bypass rather than a resolver call.
     */
    private static final List<Pattern> DATE_WINDOW_WORD_PATTERNS = compileWordPatterns(Set.of(
            "annual",
            "daily",
            "day",
            "days",
            "month",
            "monthly",
            "months",
            "mtd",
            "quarter",
            "quarterly",
            "quarters",
            "qtd",
            "since",
            "today",
            "week",
            "weekly",
            "weeks",
            "year",
            "yearly",
            "years",
            "yesterday",
            "ytd"));

    /**
     * Vocabulary of a metric question that names no window at all (#1840). The DATE_WINDOW layer
     * tells the model that a windowless report question still has a window — the contract's default
     * — and to resolve it, so "who are our ten largest customers by revenue?" is a dated question
     * even though it contains none of the words above. On the 2026-09-06 sequences run that exact
     * question lost {@code resolveDateWindow} to the candidate cut and the model called the tool it
     * could not see. A metric word earns its place here when the prompt would send the model to the
     * resolver for it; the near misses guarded by the test suite ("phone number for NAPA", "recent
     * notes on this vehicle") name no metric and stay out.
     */
    private static final List<Pattern> IMPLIED_WINDOW_WORD_PATTERNS = compileWordPatterns(Set.of(
            "average",
            "avg",
            "billed",
            "biggest",
            "collected",
            "count",
            "growth",
            "invoiced",
            "largest",
            "least",
            "margin",
            "most",
            "payables",
            "profit",
            "rank",
            "ranking",
            "receivables",
            "revenue",
            "sales",
            "spend",
            "spending",
            "spent",
            "top",
            "total",
            "totals",
            "trend"));

    /**
     * Vocabulary that names an ABSOLUTE period rather than a relative one — a four-digit year, a
     * calendar quarter, or a month name (#1684).
     *
     * <p>These were not in {@link #DATE_WINDOW_WORD_PATTERNS} because when that list was written the
     * only resolver expressed relative shapes, so a bare "2025" named no window it could resolve —
     * which is the exact test that list applies. {@code resolveNamedPeriod} changed that: "in 2025",
     * "Q3 2026" and "July 2026" are now resolvable, so the tokens earn their place under the same
     * rule rather than in spite of it.
     *
     * <p>Adding them is load-bearing, not tidying. Removing the {@code period} shortcut made a
     * resolver call mandatory before any dated report call, and {@code ReportingPeriods} now hard
     * rejects a missing range with a message telling the model to call {@code resolveNamedPeriod}.
     * Without these tokens "what did we spend with Michelin in 2025?" matches nothing here, the
     * resolver is left to compete in the embedding ranking (which this class already documents it
     * can lose), and the turn can dead-end being told to call a tool it was never offered — in
     * precisely the case {@code resolveNamedPeriod} exists to serve.
     */
    private static final List<Pattern> NAMED_PERIOD_PATTERNS = List.of(
            Pattern.compile("\\b(?:19|20)\\d{2}\\b"),
            Pattern.compile("\\bq[1-4]\\b"),
            Pattern.compile("\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
                    + "(?:uary|ruary|ch|il|e|y|ust|tember|ober|ember)?\\b"));

    /** Multi-word date vocabulary, matched as plain substrings rather than on word boundaries. */
    private static final Set<String> DATE_WINDOW_PHRASES = Set.of("to date", "so far this");

    /** The keyword guards of the three tag-added facade tools (formerly {@code fallbackToolsForMessage}). */
    private static final Set<String> WEB_SEARCH_TOKENS =
            Set.of("current", "internet", "news", "online", "recent", "web");

    private static final Set<String> INVENTORY_TOKENS =
            Set.of("availability", "inventory", "location", "part", "product", "sku", "stock", "store");
    private static final Set<String> ORDER_TOKENS = Set.of("order", "po", "purchase", "sale", "sales");

    /** The phrases of {@code deriveWorkflowState}, in the order it tested them. */
    private static final Set<String> CREATING_PO_PHRASES =
            Set.of("purchase order", "create po", "new po", "po for vendor");

    private static final Set<String> RECEIVING_ASN_PHRASES = Set.of(
            "receiving asn",
            "receive asn",
            "receive shipment",
            "receive order",
            "receiving shipment",
            "advanced shipment notice",
            "asn");
    private static final Set<String> INVENTORY_RECON_PHRASES = Set.of(
            "inventory recon",
            "inventory reconciliation",
            "reconcile inventory",
            "stock reconciliation",
            "cycle count");

    /** {@code CompoundRerankProperties.maxSubQueries} when no properties are wired. */
    private static final int DEFAULT_MAX_SUB_QUERIES = 3;

    private final SimpleChatClassifier simpleChatClassifier;
    private final int maxSubQueries;

    @Autowired
    HeuristicQuestionTagger(
            @NonNull SimpleChatClassifier simpleChatClassifier, @Nullable CompoundRerankProperties rerankProperties) {
        this(
                simpleChatClassifier,
                rerankProperties == null ? DEFAULT_MAX_SUB_QUERIES : rerankProperties.maxSubQueries());
    }

    HeuristicQuestionTagger(@NonNull SimpleChatClassifier simpleChatClassifier, int maxSubQueries) {
        this.simpleChatClassifier = simpleChatClassifier;
        this.maxSubQueries = Math.max(1, maxSubQueries);
    }

    /**
     * A tagger over the shipped simple-chat rule catalog, for hand-built constructions that have no
     * catalog provider (the many test-built {@code ToolSelectionEngine}s).
     */
    public static @NonNull HeuristicQuestionTagger withDefaultCatalog() {
        return new HeuristicQuestionTagger(
                new SimpleChatClassifier(SimpleChatRuleDefaults.defaultCatalog()), DEFAULT_MAX_SUB_QUERIES);
    }

    @Override
    public @NonNull QuestionTags tag(@NonNull String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        Map<String, TagAnswer> answers = new LinkedHashMap<>();
        answers.put(
                TagName.FOLLOWS_PREVIOUS_TURN.wireName(),
                TagAnswer.heuristic(SimpleChatClassifier.followsPreviousTurn(message)));
        answers.put(TagName.SIMPLE_CHAT.wireName(), TagAnswer.heuristic(simpleChatClassifier.isSimpleChat(message)));
        answers.put(
                TagName.WORKFLOW_STATE.wireName(),
                TagAnswer.heuristic(deriveWorkflowState(lower).name()));
        answers.put(TagName.NEEDS_WEB_SEARCH.wireName(), TagAnswer.heuristic(containsAny(lower, WEB_SEARCH_TOKENS)));
        answers.put(TagName.ABOUT_INVENTORY.wireName(), TagAnswer.heuristic(containsAny(lower, INVENTORY_TOKENS)));
        answers.put(TagName.ABOUT_ORDERS.wireName(), TagAnswer.heuristic(containsAny(lower, ORDER_TOKENS)));
        answers.put(TagName.IMPLIES_DATE_WINDOW.wireName(), TagAnswer.heuristic(mentionsDateWindow(lower)));
        answers.put(
                TagName.ADMIN_ACCOUNT_QUESTION.wireName(),
                TagAnswer.heuristic(ToolRegistryService.isAdminAccountQuestion(message)));
        answers.put(
                TagName.COMPOUND_QUESTION.wireName(),
                TagAnswer.heuristic(RerankedContentRetriever.splitSubQueries(message, maxSubQueries)
                                .size()
                        >= 2));
        RouterClassification safe = RouterClassification.safeDefault();
        answers.put(
                TagName.INTENT.wireName(), TagAnswer.heuristic(safe.intentType().name()));
        answers.put(
                TagName.COMPLEXITY.wireName(),
                TagAnswer.heuristic(safe.complexity().name()));
        answers.put(
                TagName.RISK.wireName(), TagAnswer.heuristic(safe.riskLevel().name()));
        answers.put(TagName.DOMAIN.wireName(), TagAnswer.heuristic(safe.domain()));
        return QuestionTags.heuristic(answers);
    }

    /**
     * Heuristic workflow-state derivation from message text (formerly {@code
     * ToolSelectionEngine.deriveWorkflowState}). Used only for session-less callers; the
     * authoritative state is the persisted {@code NltiSession} value (ADR-0068 §3.3).
     */
    static @NonNull WorkflowState deriveWorkflowState(@NonNull String lower) {
        if (containsAny(lower, CREATING_PO_PHRASES)) {
            return WorkflowState.CREATING_PO;
        } else if (containsAny(lower, RECEIVING_ASN_PHRASES)) {
            return WorkflowState.RECEIVING_ASN;
        } else if (containsAny(lower, INVENTORY_RECON_PHRASES)) {
            return WorkflowState.INVENTORY_RECON;
        }
        return WorkflowState.IDLE;
    }

    /**
     * Whether {@code text} names a date window (formerly {@code ToolSelectionEngine.mentionsDateWindow}).
     *
     * <p>Deliberately not {@link #containsAny}: that builds {@code ".*\\btoken\\b.*"} and calls
     * {@link String#matches}, which anchors the whole input and — without {@code DOTALL} — has
     * {@code .} exclude {@code \n}, so no single-word token matches a message containing a line
     * break at all. A pasted or multi-paragraph question is ordinary in a chat surface, and this
     * guard is the one whose false negative costs the whole date-window contract, so it uses
     * {@code Matcher.find} on precompiled patterns instead. Precompiling also keeps the added
     * vocabulary off the per-request regex-compilation path.
     */
    static boolean mentionsDateWindow(@NonNull String text) {
        for (String phrase : DATE_WINDOW_PHRASES) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        for (Pattern pattern : DATE_WINDOW_WORD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : IMPLIED_WINDOW_WORD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        for (Pattern pattern : NAMED_PERIOD_PATTERNS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull List<Pattern> compileWordPatterns(@NonNull Set<String> words) {
        return words.stream()
                .map(word -> Pattern.compile("\\b" + Pattern.quote(word) + "\\b"))
                .toList();
    }

    private static boolean containsAny(@NonNull String text, @NonNull Set<String> tokens) {
        for (String token : tokens) {
            if (token.contains(" ")) {
                if (text.contains(token)) {
                    return true;
                }
            } else if (text.matches(".*\\b" + token + "\\b.*")) {
                return true;
            }
        }
        return false;
    }
}
