package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.config.ScopeGraphProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TagQuestion;
import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.scopegraph.EntityLexicon;
import com.positivity.mcp.internal.scopegraph.EntityLexicon.EntityDefinition;
import com.positivity.mcp.internal.scopegraph.EntityLexiconException;
import com.positivity.mcp.internal.scopegraph.EntityLexiconLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §1 / spec §2.3: the closed question set of one tagging request, built from one list of tag
 * definitions in code. Twelve fixed tags, then the {@code domain} Choice, then one {@code entity_<key>}
 * Noul per lexicon entity (45 questions for today's lexicon; 13 without them, the default). The entity
 * Nouls are asked only when {@code mcp.scope-graph.mode} is not {@code off} (they seed the ADR-0069
 * scope, their only consumer) <em>and</em> {@code mcp.tagging.entity-questions} is true (the model's
 * context must hold the wide request).
 *
 * <ul>
 *   <li>The {@code domain} options are permanently the curated RAG-scope vocabulary: the distinct
 *       {@code rag-scope} values of {@code mcp.rag.preload.docs} plus {@code master} (15 today), never
 *       the tool catalog's domains (33, over the 26-option cap, and spelled for tools). Each option's
 *       criteria sentence comes from the lexicon's {@code domains} block ({@code entities.yaml}).
 *   <li>The entity question is one Noul per entity, named by the lexicon key ({@code
 *       entity_workorder}), whose instructions list the entity's en, fr and es terms. No option cap
 *       applies, and the request stays well under the 64-question cap (a test pins it).
 * </ul>
 *
 * <p>The instructions are fixed English text about the shop-management context and the decision,
 * phrased as a question. They never include the message, the caller or the tenant (ADR-0068 §4).
 * The set is built once per lexicon and preload list; {@link QuestionSet#optionListHash()} identifies
 * the option lists asked, so agreement on those tags is comparable within one hash.
 */
@Component
public class TaggingQuestions {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaggingQuestions.class);

    /** Ollama's per-request cap (ADR-0068, Drivers). */
    public static final int MAX_QUESTIONS = 64;

    /** Both local models' per-Choice/Score option cap (spec §2.2). */
    public static final int MAX_OPTIONS = 26;

    /** The RAG scope that is always a {@code domain} option (spec §2.3). */
    public static final String MASTER_DOMAIN = "master";

    /** The prefix of every instruction (verbatim, NLTI domain review). */
    static final String CONTEXT =
            "Message from staff at a tire and auto service shop to its management assistant; may be"
                    + " in English, French or Spanish. ";

    /**
     * One request's questions and what identifies them.
     *
     * @param questions in wire order
     * @param optionListHash SHA-256 (first 16 hex) over the domain options and the entity keys asked; a
     *     skipped domain question contributes no options
     */
    public record QuestionSet(
            @NonNull List<TagQuestion> questions, @NonNull String optionListHash) {
        public QuestionSet {
            questions = List.copyOf(questions);
        }

        public int size() {
            return questions.size();
        }
    }

    private final QuestionSet questionSet;

    @Autowired
    public TaggingQuestions(
            @Nullable StaticRagPreloadProperties preloadProperties,
            @Nullable TaggingProperties taggingProperties,
            @Nullable ScopeGraphProperties scopeGraphProperties) {
        this(loadLexicon(), preloadProperties, asksEntityQuestions(taggingProperties, scopeGraphProperties));
    }

    /**
     * ADR-0068 (NLTI domain review): the entity Nouls are asked only when the scope graph is on, the
     * one consumer of their answers, and {@code mcp.tagging.entity-questions} allows the wide request.
     * An absent properties bean takes that property's default (off), and an absent scope-graph bean
     * means the graph is off.
     */
    static boolean asksEntityQuestions(
            @Nullable TaggingProperties taggingProperties, @Nullable ScopeGraphProperties scopeGraphProperties) {
        boolean entityQuestions = taggingProperties == null
                ? TaggingProperties.DEFAULT_ENTITY_QUESTIONS
                : taggingProperties.entityQuestions();
        boolean scopeGraphOn = scopeGraphProperties != null && scopeGraphProperties.enabled();
        return entityQuestions && scopeGraphOn;
    }

    /** For tests: an explicit lexicon and preload list, entity Nouls asked. */
    public TaggingQuestions(@NonNull EntityLexicon lexicon, @Nullable StaticRagPreloadProperties preloadProperties) {
        this(lexicon, preloadProperties, true);
    }

    /**
     * @param entityQuestions {@code mcp.tagging.entity-questions}: whether the {@code entity_<key>} Nouls
     *     are asked; off fits a small-context model during the bake-off
     */
    public TaggingQuestions(
            @NonNull EntityLexicon lexicon,
            @Nullable StaticRagPreloadProperties preloadProperties,
            boolean entityQuestions) {
        this.questionSet = build(lexicon, preloadProperties, entityQuestions);
    }

    /** For tests and hand-built constructions: the shipped lexicon, no preload list, entity Nouls asked. */
    public TaggingQuestions() {
        this(loadLexicon(), null, true);
    }

    private static @NonNull EntityLexicon loadLexicon() {
        try {
            return EntityLexiconLoader.loadDefault();
        } catch (EntityLexiconException exception) {
            // The scope-graph loader reports the fault itself; here it costs the entity questions only.
            LOGGER.warn("Entity lexicon unavailable for the tagging questions: {}", exception.getMessage());
            return new EntityLexicon(java.util.Map.of(), List.of(), List.of());
        }
    }

    /** The question set of this context, in wire order. */
    public @NonNull QuestionSet questionSet() {
        return questionSet;
    }

    /** The questions of this context, in wire order. */
    public @NonNull List<TagQuestion> questions() {
        return questionSet.questions();
    }

    /** The set for {@code lexicon} and {@code preloadProperties}, entity Nouls included. */
    static @NonNull QuestionSet build(
            @NonNull EntityLexicon lexicon, @Nullable StaticRagPreloadProperties preloadProperties) {
        return build(lexicon, preloadProperties, true);
    }

    /**
     * The set for {@code lexicon} and {@code preloadProperties}. With {@code entityQuestions} false no
     * {@code entity_<key>} Noul is asked and the option-list hash covers the domain options alone.
     */
    static @NonNull QuestionSet build(
            @NonNull EntityLexicon lexicon,
            @Nullable StaticRagPreloadProperties preloadProperties,
            boolean entityQuestions) {
        List<TagQuestion> questions = new ArrayList<>(fixedQuestions());
        TagQuestion domain = domainQuestion(lexicon, preloadProperties);
        // The hash names the option lists actually asked: a skipped domain question hashes as no
        // domain options, so its agreement is never compared with a set that asked it.
        List<String> askedDomainOptions = List.of();
        if (asksDomain(domain.options().size())) {
            questions.add(domain);
            askedDomainOptions = domain.options();
        }
        List<String> entityKeys = new ArrayList<>();
        if (entityQuestions) {
            for (EntityDefinition entity : sortedEntities(lexicon)) {
                questions.add(entityQuestion(entity));
                entityKeys.add(entity.key());
            }
        }
        return new QuestionSet(questions, optionListHash(askedDomainOptions, entityKeys));
    }

    /**
     * Whether a {@code domain} Choice of {@code domainOptions} options can be asked, logging one WARN
     * (counts only) when it cannot. A Choice needs at least two options (spec §2.2): with no preload
     * list only master is left, and a one-option question would fail the whole request. Above the
     * local models' option cap the provider would reject every request, so every turn would fall
     * back. The set is built once per context, so either warning is logged once.
     */
    static boolean asksDomain(int domainOptions) {
        if (domainOptions > MAX_OPTIONS) {
            LOGGER.warn(
                    "Tagging domain question skipped: {} rag-scope options exceed the local-model limit of {}",
                    domainOptions,
                    MAX_OPTIONS);
            return false;
        }
        if (domainOptions < 2) {
            LOGGER.warn(
                    "Tagging domain question skipped: {} rag-scope option(s), a Choice needs at least 2",
                    domainOptions);
            return false;
        }
        return true;
    }

    /** The tags every request asks, whatever the lexicon: everything but {@code domain} and {@code entity}. */
    static @NonNull List<TagQuestion> fixedQuestions() {
        List<TagQuestion> questions = new ArrayList<>();
        questions.add(TagQuestion.noul(
                TagName.FOLLOWS_PREVIOUS_TURN,
                CONTEXT + "Does this message depend on something said earlier in the conversation to be"
                        + " understood: a reference with no antecedent in the message itself (those, them, the same"
                        + " ones, that customer, the second one), a bare answer or confirmation to a question the"
                        + " assistant asked (yes, no, go ahead, not that one), or a request to redo or change a"
                        + " previous result (again, instead, also, now sort by …)? A greeting, thanks or a closing"
                        + " remark on its own does not count, nor does a word that points at the user's own shop or"
                        + " location (this store, here)."));
        questions.add(TagQuestion.noul(
                TagName.SIMPLE_CHAT,
                CONTEXT + "Is this message only social or about the assistant itself, with no business data"
                        + " requested and nothing to do: a greeting, thanks, a closing remark, small talk, or a"
                        + " general question about who the assistant is or what it can do? A message that names a"
                        + " record or a task (\"can you show invoices?\", \"can you create a PO?\") is not simple"
                        + " chat, and neither is a bare answer such as \"yes\" or \"the first one\"."));
        questions.add(TagQuestion.choice(
                TagName.WORKFLOW_STATE,
                CONTEXT + "Which multi-step operational workflow, if any, is the user starting or in the middle of"
                        + " with this message? Mentioning a document is not enough; the user must be carrying out the"
                        + " workflow. Choose IDLE when unsure.",
                workflowCriteria()));
        questions.add(TagQuestion.noul(
                TagName.NEEDS_WEB_SEARCH,
                CONTEXT + "Does answering this message need information from outside the shop's own records, such"
                        + " as news, a recall, a manufacturer's specification, a tariff, weather, or an external"
                        + " company's website? The shop's own current stock, recent invoices or online orders are its"
                        + " own records and do not count."));
        questions.add(TagQuestion.noul(
                TagName.ABOUT_INVENTORY,
                CONTEXT + "Is this message about the shop's stock: what is on hand or available, a part, product or"
                        + " SKU and where it is stored, receiving, transfers, adjustments or counts? A question about"
                        + " a store's address or hours alone does not count."));
        questions.add(TagQuestion.noul(
                TagName.ABOUT_ORDERS,
                CONTEXT + "Is this message about an order document: a customer's sales order or cart, or a purchase"
                        + " order placed with a supplier (its lines, status, receipt or history)? A question about"
                        + " revenue or sales totals alone does not count."));
        questions.add(TagQuestion.noul(
                TagName.IMPLIES_DATE_WINDOW,
                CONTEXT + "Does this message ask about data over a period of time, either named (last month, this"
                        + " quarter, year to date, since January, July, 2025, Q3) or implied by a metric that only"
                        + " makes sense over a period (revenue, totals, top or largest customers, spend, growth, trend,"
                        + " average)? A single date or time for booking or moving something, a current value (right"
                        + " now, today's rate), or a vehicle's model year is not a period."));
        questions.add(TagQuestion.noul(
                TagName.ADMIN_ACCOUNT_QUESTION,
                CONTEXT + "Is this message only a lookup of the platform's own administration data: its user"
                        + " accounts and logins, which roles or permissions a user or role has, who can access what,"
                        + " or the platform's audit log? A request to change any of these (disable an account, assign"
                        + " a role, reset a password, grant a permission, add an audit event) is not a lookup. A"
                        + " customer's, supplier's or vendor's account, a bank, GL or ledger account, invoices,"
                        + " receivables, payables, workorders, a business approval limit, the sign-in or password"
                        + " policy, or a staff member's own sign-in problem are not administration."));
        questions.add(TagQuestion.noul(
                TagName.COMPOUND_QUESTION,
                CONTEXT + "Does this message ask two or more separate questions or requests that need different"
                        + " information to answer (for example a workorder's status and who has access to the audit"
                        + " log), rather than one request with several conditions or a list of related items such"
                        + " as \"returns and refunds\"?"));
        SequencedMap<String, String> intent = new LinkedHashMap<>();
        intent.put(
                NltiIntentType.QUERY.name(),
                "Look up, list, count, summarise, compare or report on existing records, or ask how something"
                        + " works; nothing changes.");
        intent.put(
                NltiIntentType.ACTION.name(),
                "Create, change, cancel, approve, post, send, schedule, import or delete something, or have the"
                        + " assistant do it, even when the request is short (\"cancel it\", \"delete that one\").");
        intent.put(
                NltiIntentType.UNKNOWN.name(),
                "Social chat, or a bare answer or confirmation (\"yes\", \"go ahead\", \"the first one\") whose"
                        + " purpose the message alone does not show. A short request to change something is ACTION,"
                        + " not UNKNOWN.");
        questions.add(TagQuestion.choice(
                TagName.INTENT, CONTEXT + "Does the user want to read something, or to change something?", intent));
        SequencedMap<String, String> complexity = new LinkedHashMap<>();
        complexity.put(
                RequestComplexity.SINGLE_LOOKUP.name(),
                "One record or one list from one area of the business answers it, for example a status, a"
                        + " balance, or one customer's vehicles.");
        complexity.put(
                RequestComplexity.MULTI_DOMAIN.name(),
                "It needs data from more than one area of the business, or several steps combined, for example"
                        + " joining customers with invoices and workorders, comparing periods, or ranking and then"
                        + " filtering.");
        questions.add(TagQuestion.choice(
                TagName.COMPLEXITY,
                CONTEXT + "How much work does this message need: one lookup or one change in one area, or several"
                        + " steps or areas?",
                complexity));
        questions.add(TagQuestion.score(
                TagName.RISK,
                CONTEXT + "How risky is what the user asks for? LOW: reading, reporting or social chat; nothing"
                        + " changes. MEDIUM: a change that can be corrected later, such as a note, an appointment, a"
                        + " draft, a status update, a price change, or approving an estimate or purchase order. HIGH:"
                        + " money moves or is recorded (a payment, refund, credit or bill), a posting to accounting"
                        + " (including recording received stock or count adjustments), a deletion, voiding or"
                        + " cancelling an order or invoice, a change to who can access the platform (accounts, roles,"
                        + " permissions, passwords), writing an audit event, something sent outside the shop, or a"
                        + " change that cannot be undone. A bare confirmation or selection (\"yes\", \"go ahead\", \"confirm\", \"the first"
                        + " one\") may approve a change you cannot see: rate it HIGH. When unsure between two levels,"
                        + " choose the higher.",
                List.of(NltiRiskLevel.LOW.name(), NltiRiskLevel.MEDIUM.name(), NltiRiskLevel.HIGH.name())));
        return List.copyOf(questions);
    }

    /** Every {@link WorkflowState} value, described as the workflow being carried out (spec §3). */
    private static @NonNull SequencedMap<String, String> workflowCriteria() {
        SequencedMap<String, String> criteria = new LinkedHashMap<>();
        criteria.put(
                WorkflowState.IDLE.name(),
                "Not in a specific workflow: a question, lookup, report or any other task, including questions"
                        + " about purchase orders, shipments, counts or returns.");
        criteria.put(
                WorkflowState.CREATING_PO.name(),
                "Creating a purchase order to a supplier right now: choosing items and quantities, picking the"
                        + " vendor, submitting or approving the new order.");
        criteria.put(
                WorkflowState.RECEIVING_ASN.name(),
                "Receiving a supplier shipment right now against an advance shipping notice (ASN) or purchase order:"
                        + " checking in delivered items, quantities received, discrepancies.");
        criteria.put(
                WorkflowState.INVENTORY_RECON.name(),
                "Reconciling or counting inventory right now: a cycle count, a stock reconciliation, entering"
                        + " counted quantities, resolving variances.");
        criteria.put(
                WorkflowState.PROCESSING_RETURN.name(),
                "Processing a customer's return or refund right now: taking back goods sold, issuing the refund"
                        + " or credit, restocking.");
        for (WorkflowState state : WorkflowState.values()) {
            if (!criteria.containsKey(state.name())) {
                throw new IllegalStateException("WorkflowState." + state + " has no workflow_state criteria");
            }
        }
        return criteria;
    }

    /**
     * Spec §2.3 (as revised): the distinct {@code rag-scope} values of {@code mcp.rag.preload.docs} plus
     * {@value #MASTER_DOMAIN}, described by the lexicon's {@code domains} sentences. A scope the lexicon
     * does not describe gets a generic sentence; {@code ScopeGraphRealConfigValidationTest} fails the
     * build on one.
     */
    static @NonNull TagQuestion domainQuestion(
            @NonNull EntityLexicon lexicon, @Nullable StaticRagPreloadProperties preloadProperties) {
        TreeSet<String> options = new TreeSet<>();
        if (preloadProperties != null) {
            preloadProperties.docs().stream()
                    .map(StaticRagPreloadProperties.StaticDocEntry::ragScope)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(scope -> !scope.isEmpty())
                    .forEach(options::add);
        }
        options.add(MASTER_DOMAIN);
        SequencedMap<String, String> criteria = new LinkedHashMap<>();
        for (String option : options) {
            String sentence = lexicon.domains().get(option);
            criteria.put(
                    option,
                    sentence != null
                            ? sentence
                            : "The message is mainly about the '" + option + "' area of the business.");
        }
        return TagQuestion.choice(
                TagName.DOMAIN,
                CONTEXT + "Which one area of the business is this message mainly about? Choose \"master\" when no"
                        + " single area fits, when several fit equally, or when the message is social chat, or the"
                        + " assistant itself.",
                criteria);
    }

    /** Entities in key order, so the wire order and the option-list hash are deterministic. */
    private static @NonNull List<EntityDefinition> sortedEntities(@NonNull EntityLexicon lexicon) {
        TreeMap<String, EntityDefinition> byKey = new TreeMap<>();
        lexicon.entities().forEach(entity -> byKey.put(entity.key(), entity));
        return List.copyOf(byKey.values());
    }

    /**
     * One Noul per entity: "Is this message about any of these: &lt;en terms&gt; (French: &lt;fr&gt;;
     * Spanish: &lt;es&gt;)?"
     */
    static @NonNull TagQuestion entityQuestion(@NonNull EntityDefinition entity) {
        String instructions = CONTEXT + "Is this message about any of these: " + terms(entity, "en") + " (French: "
                + terms(entity, "fr") + "; Spanish: " + terms(entity, "es") + ")?";
        return TagQuestion.entity(entity.key(), instructions);
    }

    private static @NonNull String terms(@NonNull EntityDefinition entity, @NonNull String language) {
        List<String> terms = entity.terms().getOrDefault(language, List.of());
        return terms.isEmpty() ? entity.key().replace('-', ' ') : String.join(", ", terms);
    }

    /** SHA-256 over the domain options then the entity keys, first 16 hex characters. */
    static @NonNull String optionListHash(@NonNull List<String> domainOptions, @NonNull List<String> entityKeys) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("domain:".getBytes(StandardCharsets.UTF_8));
            for (String option : domainOptions) {
                digest.update(option.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            digest.update("entity:".getBytes(StandardCharsets.UTF_8));
            for (String key : entityKeys) {
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }
}
