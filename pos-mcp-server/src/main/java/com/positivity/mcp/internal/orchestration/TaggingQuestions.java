package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.scopegraph.EdgeType;
import com.positivity.mcp.internal.scopegraph.NodeId;
import com.positivity.mcp.internal.scopegraph.NodeType;
import com.positivity.mcp.internal.scopegraph.ScopeGraph;
import com.positivity.mcp.internal.scopegraph.ScopeGraphHolder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §1 / spec §2.3, §2.4: the closed question set of one tagging request, built from one list
 * of tag definitions in code. The fixed tags never change; the {@code domain} options and the entity
 * groups follow the scope-graph snapshot (ADR-0069 supplies both option sets), so the set is cached
 * per graph hash and rebuilt when the snapshot changes.
 *
 * <p>The instructions are fixed English text about the shop-management context and the decision,
 * phrased as a question ({@code nimble}'s guidance). They never include the message, the caller or
 * the tenant (ADR-0068 §4). The {@code workflow_state} option descriptions are the phrases {@code
 * deriveWorkflowState} matches, so the two taggers describe the same thing (spec §3).
 */
@Component
public class TaggingQuestions {

    /** Ollama's per-request cap (ADR-0068, Drivers). */
    public static final int MAX_QUESTIONS = 64;

    /** Both local models' per-Choice/Score option cap (spec §2.2). */
    public static final int MAX_OPTIONS = 26;

    /** Spec §2.4: at most 24 entity keys plus {@value QuestionTags#NO_ENTITY} per group. */
    public static final int ENTITY_GROUP_SIZE = 24;

    /** The RAG scope that is always a {@code domain} option (spec §2.3). */
    public static final String MASTER_DOMAIN = "master";

    private static final String CONTEXT =
            "The text is one message a staff member typed to the assistant of a tire and automotive service"
                    + " shop's management system (customers, vehicles, work orders, invoices, inventory, purchase"
                    + " orders, suppliers, pricing, accounting, tax, users and roles). ";

    private final @Nullable ScopeGraphHolder graphHolder;
    private final @Nullable StaticRagPreloadProperties preloadProperties;

    /** The last built set and the graph hash it was built for; rebuilt when the hash differs. */
    private volatile @Nullable Cached cached;

    private record Cached(
            @NonNull String graphHash, @NonNull List<TagQuestion> questions) {}

    @Autowired
    public TaggingQuestions(
            @Nullable ScopeGraphHolder graphHolder, @Nullable StaticRagPreloadProperties preloadProperties) {
        this.graphHolder = graphHolder;
        this.preloadProperties = preloadProperties;
    }

    /** For tests and hand-built constructions: no graph and no preload list. */
    public TaggingQuestions() {
        this(null, null);
    }

    /** The current question set, in wire order: the fixed tags, then {@code domain}, then the entity groups. */
    public @NonNull List<TagQuestion> questions() {
        ScopeGraph graph = graphHolder == null ? ScopeGraph.empty() : graphHolder.current();
        Cached current = cached;
        if (current != null && current.graphHash().equals(graph.contentHash())) {
            return current.questions();
        }
        List<TagQuestion> built = build(graph);
        cached = new Cached(graph.contentHash(), built);
        return built;
    }

    /** The question set for {@code graph}, uncached. */
    @NonNull
    List<TagQuestion> build(@NonNull ScopeGraph graph) {
        List<TagQuestion> questions = new ArrayList<>(fixedQuestions());
        TagQuestion domain = domainQuestion(graph);
        // A Choice needs at least two options (spec §2.2); with no graph and no preload list only
        // master is left, and a one-option question would fail the whole request.
        if (domain.options().size() >= 2) {
            questions.add(domain);
        }
        List<List<String>> groups = entityGroups(graph);
        for (int index = 0; index < groups.size(); index++) {
            questions.add(entityGroupQuestion(index + 1, groups.get(index)));
        }
        return List.copyOf(questions);
    }

    /** The tags every request asks, whatever the graph: everything but {@code domain} and {@code entity}. */
    static @NonNull List<TagQuestion> fixedQuestions() {
        List<TagQuestion> questions = new ArrayList<>();
        questions.add(TagQuestion.noul(
                TagName.FOLLOWS_PREVIOUS_TURN,
                CONTEXT + "Does this message lean on an earlier turn of the conversation, for example by referring"
                        + " to 'those', 'them', 'the same ones', 'the previous' results or asking for something"
                        + " 'again' or 'instead', so that it cannot be answered from this message alone?"));
        questions.add(TagQuestion.noul(
                TagName.SIMPLE_CHAT,
                CONTEXT + "Is this message pure social chat with no business task in it: a greeting, thanks,"
                        + " small talk, or a question about what the assistant can do?"));
        questions.add(TagQuestion.choice(TagName.WORKFLOW_STATE, workflowInstructions(), workflowCriteria()));
        questions.add(TagQuestion.noul(
                TagName.NEEDS_WEB_SEARCH,
                CONTEXT + "Does answering this message need current information from the public internet, such"
                        + " as news, recalls, tariffs, prices or events outside the shop's own records?"));
        questions.add(TagQuestion.noul(
                TagName.ABOUT_INVENTORY,
                CONTEXT + "Is this message about inventory: stock on hand, availability, a part, product or SKU,"
                        + " a store or storage location, or replenishment?"));
        questions.add(TagQuestion.noul(
                TagName.ABOUT_ORDERS,
                CONTEXT + "Is this message about orders: a sales order, a purchase order to a supplier, or sales"
                        + " figures?"));
        questions.add(TagQuestion.noul(
                TagName.IMPLIES_DATE_WINDOW,
                CONTEXT + "Does answering this message require a date range, either named (last month, this"
                        + " quarter, year to date, July, 2025, Q3) or implied by a metric such as revenue,"
                        + " totals, top or largest customers, spend, growth or a trend?"));
        questions.add(TagQuestion.noul(
                TagName.ADMIN_ACCOUNT_QUESTION,
                CONTEXT + "Is this message an administration question about user accounts, roles, permissions,"
                        + " access, logins, registrations or the audit log, and not about a business record such"
                        + " as an invoice, a ledger, receivables, payables or a work order?"));
        questions.add(TagQuestion.noul(
                TagName.COMPOUND_QUESTION,
                CONTEXT + "Does this message ask two or more separate things that need separate answers, rather"
                        + " than one question?"));
        SequencedMap<String, String> intent = new LinkedHashMap<>();
        intent.put(NltiIntentType.QUERY.name(), "The user wants to read, look up, list or report on something.");
        intent.put(NltiIntentType.ACTION.name(), "The user wants to create, change, post, cancel or delete something.");
        intent.put(
                NltiIntentType.UNKNOWN.name(), "It is not clear whether the user wants to read or to change anything.");
        questions.add(TagQuestion.choice(
                TagName.INTENT,
                CONTEXT + "Is the user asking to read something or asking to change something?",
                intent));
        SequencedMap<String, String> complexity = new LinkedHashMap<>();
        complexity.put(
                RequestComplexity.SINGLE_LOOKUP.name(),
                "One lookup in one area of the business; one tool call would answer it.");
        complexity.put(
                RequestComplexity.MULTI_DOMAIN.name(),
                "Several steps, several records, or more than one area of the business are involved.");
        questions.add(TagQuestion.choice(
                TagName.COMPLEXITY,
                CONTEXT + "How much work does answering this message take: one lookup, or several steps across"
                        + " areas of the business?",
                complexity));
        questions.add(TagQuestion.score(
                TagName.RISK,
                CONTEXT + "How risky is what the user asks for? LOW is a read-only question; MEDIUM changes a record"
                        + " that can be corrected later; HIGH moves money, posts to accounting, deletes or"
                        + " irreversibly changes something.",
                List.of(NltiRiskLevel.LOW.name(), NltiRiskLevel.MEDIUM.name(), NltiRiskLevel.HIGH.name())));
        return List.copyOf(questions);
    }

    private static @NonNull String workflowInstructions() {
        return CONTEXT + "Which operational workflow, if any, is the user in the middle of or starting with this"
                + " message?";
    }

    /** Spec §3: the descriptions are the phrases {@code deriveWorkflowState} matches today. */
    private static @NonNull SequencedMap<String, String> workflowCriteria() {
        SequencedMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put(
                WorkflowState.IDLE.name(),
                "No particular workflow: a question, a lookup or a task outside the ones below.");
        criteria.put(
                WorkflowState.CREATING_PO.name(),
                "Creating a purchase order: 'purchase order', 'create po', 'new po', 'po for vendor'.");
        criteria.put(
                WorkflowState.RECEIVING_ASN.name(),
                "Receiving a shipment against an advanced shipment notice: 'receiving asn', 'receive asn',"
                        + " 'receive shipment', 'receive order', 'receiving shipment', 'advanced shipment notice',"
                        + " 'asn'.");
        criteria.put(
                WorkflowState.INVENTORY_RECON.name(),
                "Reconciling inventory: 'inventory recon', 'inventory reconciliation', 'reconcile inventory',"
                        + " 'stock reconciliation', 'cycle count'.");
        criteria.put(
                WorkflowState.PROCESSING_RETURN.name(),
                "Processing a customer return or refund of goods already sold.");
        return criteria;
    }

    /**
     * Spec §2.3: the graph's {@code domainOptions()} when the graph is built, else the distinct {@code
     * rag-scope} values of {@code mcp.rag.preload.docs}; {@value #MASTER_DOMAIN} is always an option.
     */
    @NonNull
    TagQuestion domainQuestion(@NonNull ScopeGraph graph) {
        TreeSet<String> options = new TreeSet<>();
        if (!graph.isEmpty() && !graph.domainOptions().isEmpty()) {
            options.addAll(graph.domainOptions());
        } else if (preloadProperties != null) {
            preloadProperties.docs().stream()
                    .map(StaticRagPreloadProperties.StaticDocEntry::ragScope)
                    .filter(Objects::nonNull)
                    .filter(scope -> !scope.isBlank())
                    .forEach(options::add);
        }
        options.add(MASTER_DOMAIN);
        SequencedMap<String, String> criteria = new LinkedHashMap<>();
        for (String option : options) {
            criteria.put(
                    option,
                    MASTER_DOMAIN.equals(option)
                            ? "No single business area: general, cross-cutting, or about the assistant itself."
                            : "The message is mainly about the '" + option + "' area of the business.");
        }
        return TagQuestion.choice(
                TagName.DOMAIN,
                CONTEXT + "Which one area of the business is this message mainly about? Choose '" + MASTER_DOMAIN
                        + "' when no single area fits.",
                criteria);
    }

    /**
     * Spec §2.4: entity keys sorted by owning domain then key, filled into groups of at most {@value
     * #ENTITY_GROUP_SIZE} in that order; a domain is never split across two groups unless it alone
     * exceeds the group size. Deterministic for one snapshot; empty for an empty graph, which asks
     * no entity question (ADR-0068 §1).
     */
    static @NonNull List<List<String>> entityGroups(@NonNull ScopeGraph graph) {
        if (graph.isEmpty() || graph.entityOptions().isEmpty()) {
            return List.of();
        }
        // TreeMap: domains in key order; entities within a domain in key order.
        TreeMap<String, TreeSet<String>> byDomain = new TreeMap<>();
        for (String entity : graph.entityOptions()) {
            String domain = graph.outgoing(NodeId.of(NodeType.ENTITY, entity), EdgeType.OWNED_BY).stream()
                    .map(edge -> edge.to().key())
                    .sorted()
                    .findFirst()
                    .orElse("");
            byDomain.computeIfAbsent(domain, ignored -> new TreeSet<>()).add(entity);
        }
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (TreeSet<String> block : byDomain.values()) {
            if (!current.isEmpty() && current.size() + block.size() > ENTITY_GROUP_SIZE) {
                groups.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            for (String entity : block) {
                if (current.size() == ENTITY_GROUP_SIZE) {
                    // Only a domain larger than a whole group is split.
                    groups.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                current.add(entity);
            }
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return List.copyOf(groups);
    }

    private static @NonNull TagQuestion entityGroupQuestion(int index, @NonNull List<String> entities) {
        SequencedMap<String, String> criteria = new LinkedHashMap<>();
        for (String entity : entities) {
            criteria.put(entity, "The message is about the '" + entity.replace('-', ' ') + "' record type.");
        }
        criteria.put(QuestionTags.NO_ENTITY, "The message is about none of the record types listed here.");
        return TagQuestion.entityGroup(
                index,
                CONTEXT + "Which one of these record types, if any, is this message about? The message may be"
                        + " about none of them; choose '" + QuestionTags.NO_ENTITY + "' then.",
                criteria);
    }
}
