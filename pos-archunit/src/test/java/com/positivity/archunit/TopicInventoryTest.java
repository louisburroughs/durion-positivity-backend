package com.positivity.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Issue #1537 topic inventory guard: every in-repository {@code *.events.v1}, {@code
 * *.commands.v1}, and {@code *.manifest.v1} Kafka topic must have at least one production
 * producer and one production consumer, unless it is named in {@link #EXTERNAL_TOPIC_ALLOWLIST}
 * with a contract test that actually exists on disk.
 *
 * <p><strong>Producer vs. consumer is decided per occurrence, not per file.</strong> A manifest
 * listener class legitimately both consumes {@code x.manifest.v1} (its {@code @KafkaListener})
 * and produces {@code x.commands.v1} (a replay-request {@code @Value}-injected field sent from
 * the same class) — see {@code WorkorderManifestListener} in half the domain modules. So this
 * scanner walks every production source line and classifies each topic-shaped string it finds by
 * where on the line it sits: inside an open {@code @KafkaListener(...)} argument list, or not.
 * Tracking "open" is a running paren-depth counter seeded the moment a line contains {@code
 * @KafkaListener(} and decremented back to zero as the (possibly multi-line) annotation call
 * closes; every topic found while that counter is above zero is a consumer occurrence, everything
 * else — {@code @Value} fields, constructor/field initialisers, {@link
 * com.positivity.domainevents.DomainTopics} calls, bare literals — is a producer occurrence. This
 * is a source scan, not a compiler, so it trusts the repo's actual layout (one topic per {@code
 * topics} attribute, annotation arguments balanced on their own parens) rather than parsing Java;
 * that layout is checked empirically in the comments below and re-checked every run because a
 * topic that silently stops matching would silently stop being counted.
 *
 * <p>Topic names appear in three forms, all handled:
 *
 * <ol>
 *   <li>{@code ${some.prop:some.topic.v1}} — the dominant form, in both {@code @Value} fields and
 *       {@code @KafkaListener(topics = ...)}. The default after the colon is taken.
 *   <li>Bare string literals, e.g. {@code "workorder.events.v1"}. Because {@link #TOPIC_PATTERN}
 *       matches any versioned two-segment string (not just the {@code events}/{@code commands}/
 *       {@code manifest} suffixes — see below), a bare literal alone is a weak signal: nothing
 *       stops an unrelated string like an event-type tag (e.g. {@code getEventType()} returning
 *       {@code "estimate.created.v1"}) from having the same shape. So a bare-literal match only
 *       counts when its source line also contains the substring {@code "topic"} (case-insensitive)
 *       — true of every real topic-holding constant/field in this repo (e.g. {@code
 *       SETTLEMENT_CONFIG_TOPIC}, {@code eventsTopic}) and false of the event-type-tag false
 *       positives found when this guard was added. The {@code ${...}} placeholder form above needs
 *       no such guard: it is only ever used here for Kafka topic properties.
 *   <li>{@link com.positivity.domainevents.DomainTopics#events(String)} / {@code #commands} /
 *       {@code #manifest} calls, and the {@code WORKORDER_EVENTS_V1} / {@code
 *       WORKORDER_COMMANDS_V1} / {@code WORKORDER_MANIFEST_V1} constants it also exposes. A
 *       {@code DomainTopics.events("catalog")} call resolves directly; a {@code
 *       DomainTopics.events(SOME_CONSTANT)} call resolves by finding a {@code String SOME_CONSTANT
 *       = "literal";} field declared in the <em>same file</em> — every such call site in the repo
 *       defines its domain constant locally, so a same-file lookup is sufficient and doesn't risk
 *       resolving the wrong file's constant of the same name. (At the time of writing this is five
 *       call sites over three same-file constants: {@code SupplierPriceCatalogEventsListener.OWNER}
 *       and {@code SettlementEventPublisher.PAYMENT_DOMAIN} used once each, plus {@code
 *       OrderDomainEventPublisher.ORDER_DOMAIN} used three times in that one file. That count is
 *       illustrative, not an invariant this test checks — the same-file convention is what new call
 *       sites must follow, not a fixed roster, so an added or removed site doesn't need this
 *       comment updated to stay correct.)
 * </ol>
 *
 * <p><strong>{@link com.positivity.domainevents.DomainTopics} itself is excluded from the
 * scan</strong> ({@link #EXCLUDED_SOURCE_FILE}): its constant declarations
 * ({@code WORKORDER_EVENTS_V1 = "workorder.events.v1"}) and its {@code validated(domain) +
 * ".events.v1"} builders are the canonical definitions the other two forms above resolve
 * against, not a production usage — scanning them too would misreport {@code pos-domain-events}
 * as a producer of every topic in the repo.
 *
 * <p><strong>yml is corroborating, not decisive</strong> (per design doc D7): a module's {@code
 * application.yml} naming a topic doesn't by itself prove production or consumption, the Java
 * code does. The design calls for falling back to yml only when Java references a topic property
 * by name with no inline default. A repo-wide search found exactly one such case —
 * {@code WorkorderEventHandler} in {@code pos-customer}, whose {@code
 * @KafkaListener(topics = "${pos.customer.kafka.workorder-events-topic}")} has no {@code
 * :default}. Its {@code application.yml} resolves that property to {@code workorder-events} (no
 * dots, clearly a different, legacy topic name and not this test's concern) — so even fully
 * resolved it would never match {@link #TOPIC_PATTERN} and the yml lookup changes nothing. Given
 * that, this scanner stays Java-only, which is simpler and doesn't add a second file format to
 * the trust boundary for a case that can't affect the result.
 *
 * <p>DLQ topics ({@code {topic}.dlq}) are out of scope per the issue and are never captured: every
 * extraction pattern used here is anchored so its match ends exactly at {@code .v1} (the closing
 * {@code }} or {@code "}), and in the repo DLQ topics are never spelled out as a literal anyway —
 * every {@code KafkaErrorHandlingConfig} builds the DLQ name at runtime with {@code
 * record.topic() + ".dlq"}.
 *
 * <p><strong>Two limits worth knowing before trusting a green (or red) run:</strong>
 *
 * <ol>
 *   <li><strong>This is a naming check, not a wiring check.</strong> It proves a topic name is
 *       spelled somewhere outside an {@code @KafkaListener(...)} argument list (producer) and
 *       somewhere inside one (consumer) — it does not prove either side actually sends or receives
 *       a message. A bare {@code @Value("${x:foo.commands.v1}")} field with no {@code
 *       kafkaTemplate.send}/outbox call anywhere in the file satisfies "has a producer"; adding a
 *       config field with the right default is enough on its own to turn this test green. Treat a
 *       pass as "the topic is named on both sides", not "the topic is proven to flow".
 *   <li><strong>Comment filtering is prefix-only.</strong> {@link #classify} and {@link
 *       #localStringConstants} skip a line only when its stripped form <em>starts with</em> {@code
 *       *}, {@code //}, or {@code /*} — there is no trailing-comment stripping. A line like {@code
 *       someField = "real.commands.v1"; // was "invoice.commands.v1"} would register a phantom
 *       occurrence from the trailing comment. Zero lines in the repo do this today (verified by
 *       inspection when this limit was documented), so it is latent, not live. It is intentionally
 *       left undocumented-but-unfixed rather than patched with a naive {@code //}-strip: a comment
 *       stripper that doesn't understand string literals would itself corrupt any topic literal or
 *       URL containing {@code //}, which is a worse failure mode than the latent one it would close.
 * </ol>
 */
class TopicInventoryTest {

    /**
     * Topics of interest: any versioned two-segment domain topic, e.g. {@code
     * {domain}.events.v1}, {@code {domain}.commands.v1}, {@code {domain}.manifest.v1}, {@code
     * sender.outcomes.v1}, {@code payment.cleared.v1}, {@code payment.settlement-config.v1}. Not
     * restricted to the {@code events}/{@code commands}/{@code manifest} suffix set: issue #1537's
     * own named orphans ({@code sender.outcomes.v1}, {@code payment.cleared.v1}) don't use that
     * suffix set, so a narrower pattern would silently exclude the very topics the gate exists to
     * catch — a repo-wide scan when this was widened (recorded in the finding notes on the PR)
     * found no non-topic string of this shape reachable by {@link #PLACEHOLDER_DEFAULT} or the
     * guarded {@link #BARE_LITERAL} (see its javadoc for the one false-positive class found and how
     * it's excluded). Anchored full-match so a DLQ-suffixed spelling (if one ever appeared as a
     * literal) could never satisfy it.
     */
    private static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[0-9]+$");

    /** Form 1: {@code ${some.prop:some.topic.v1}} — the default after the colon. */
    private static final Pattern PLACEHOLDER_DEFAULT =
            Pattern.compile("\\$\\{[A-Za-z0-9._-]*:([a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[0-9]+)}");

    /**
     * Form 2: a bare string literal spelling out the whole topic name. Matched candidates are only
     * recorded by {@link #classify} when their source line also contains {@code "topic"}
     * (case-insensitive) — see the class javadoc's discussion of this form for why.
     */
    private static final Pattern BARE_LITERAL = Pattern.compile("\"([a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.v[0-9]+)\"");

    /** Form 3a: {@code DomainTopics.events("catalog")} etc. with an inline string literal. */
    private static final Pattern DOMAIN_TOPICS_LITERAL_CALL =
            Pattern.compile("DomainTopics\\.(events|commands|manifest)\\(\"([a-z][a-z0-9-]*)\"\\)");

    /** Form 3b: {@code DomainTopics.events(SOME_CONSTANT)} — resolved against the same file's constants. */
    private static final Pattern DOMAIN_TOPICS_IDENTIFIER_CALL =
            Pattern.compile("DomainTopics\\.(events|commands|manifest)\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

    /** A {@code String NAME = "value";} field/local declaration, used to resolve form 3b within one file. */
    private static final Pattern LOCAL_STRING_CONSTANT =
            Pattern.compile("String\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([a-z][a-z0-9-]*)\"");

    /** Form 3c: the three named constants {@link com.positivity.domainevents.DomainTopics} exposes directly. */
    private static final Pattern DOMAIN_TOPICS_CONSTANT_REF = Pattern.compile("DomainTopics\\.([A-Z][A-Z0-9_]*)");

    private static final Map<String, String> DOMAIN_TOPICS_CONSTANTS = Map.of(
            "WORKORDER_EVENTS_V1", "workorder.events.v1",
            "WORKORDER_COMMANDS_V1", "workorder.commands.v1",
            "WORKORDER_MANIFEST_V1", "workorder.manifest.v1");

    /** Marks the start of a (possibly multi-line) {@code @KafkaListener(...)} argument list. */
    private static final String KAFKA_LISTENER_OPEN = "@KafkaListener(";

    /**
     * {@link com.positivity.domainevents.DomainTopics} is the canonical definer of topic name
     * literals (see class javadoc) and must not itself be scanned as a producer/consumer.
     */
    private static final String EXCLUDED_SOURCE_FILE = "DomainTopics.java";

    /**
     * External topics: named producer/consumer pairs that this repo doesn't own both sides of.
     * Each entry must carry an {@code owner} (who does own the missing side, and why that's
     * legitimate) and a {@code contractTestClass} path <em>relative to the repo root</em> whose
     * existence is asserted below — a stale entry (contract test deleted or renamed) fails the
     * build rather than silently keeping a topic exempted forever.
     *
     * <p>{@code sender.outcomes.v1}: {@code pos-marketing} consumes delivery/bounce/complaint
     * outcomes from the shared platform sender, a system outside this repo (see
     * docs/PLATFORM_SENDER_CONTRACT.md). There is and never will be an in-repo producer for it.
     * This topic does match {@link #TOPIC_PATTERN} and is reached by the scanner as a
     * consumer-only occurrence in {@code DeliveryOutcomeListener} — this entry is what keeps
     * {@link #everyInternalTopicHasAProducerAndAConsumer} from failing on it, not the naming
     * pattern. (Removing this entry reproduces that failure — see the finding notes on the PR that
     * widened {@link #TOPIC_PATTERN} for the exact failure output this entry guards against.)
     */
    private static final Map<String, ExternalTopic> EXTERNAL_TOPIC_ALLOWLIST = Map.of(
            "sender.outcomes.v1",
            new ExternalTopic(
                    "shared platform sender (external; see docs/PLATFORM_SENDER_CONTRACT.md)",
                    "pos-marketing/src/test/java/com/positivity/marketing/internal/service/PlatformSenderContractTest.java"));

    private record ExternalTopic(String owner, String contractTestClass) {}

    @Test
    void externalTopicAllowlistEntriesHaveContractTests() {
        Path repoRoot = repoRoot();
        for (Map.Entry<String, ExternalTopic> entry : EXTERNAL_TOPIC_ALLOWLIST.entrySet()) {
            Path contractTest = repoRoot.resolve(entry.getValue().contractTestClass());
            assertThat(Files.isRegularFile(contractTest))
                    .as(
                            "allowlist entry for %s names a contract test that must exist on disk: %s (owner: %s)",
                            entry.getKey(),
                            entry.getValue().contractTestClass(),
                            entry.getValue().owner())
                    .isTrue();
        }
    }

    /**
     * Main guard. Scans every {@code pos-*} module's {@code src/main/java} and builds the
     * producer/consumer module sets per topic (see class javadoc for the classification rule and
     * its two documented limits), then fails if any non-allowlisted topic has producers with no
     * consumers, or consumers with no producers.
     *
     * <p>This test intentionally asserts zero orphans rather than pinning a known-orphan set: any
     * one-sided topic that isn't in {@link #EXTERNAL_TOPIC_ALLOWLIST} is a bug, full stop, so a new
     * orphan introduced by a future change fails the build immediately instead of needing this
     * javadoc updated first. If this test is failing, the report it prints (topic name plus the
     * module(s) on the side that exists) is the starting point — either add a real producer/consumer
     * in-repo, or add an {@link #EXTERNAL_TOPIC_ALLOWLIST} entry with a contract test if the missing
     * side is genuinely outside this repo.
     */
    @Test
    void everyInternalTopicHasAProducerAndAConsumer() throws IOException {
        Path repoRoot = repoRoot();
        Map<String, Set<String>> producers = new TreeMap<>();
        Map<String, Set<String>> consumers = new TreeMap<>();

        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(TopicInventoryTest::isPosModule).toList()) {
                String moduleName = module.getFileName().toString();
                for (Path source : mainJavaSources(module)) {
                    if (source.getFileName().toString().equals(EXCLUDED_SOURCE_FILE)) {
                        continue;
                    }
                    classify(moduleName, Files.readAllLines(source), producers, consumers);
                }
            }
        }

        Map<String, Set<String>> producerOnly = new TreeMap<>();
        Map<String, Set<String>> consumerOnly = new TreeMap<>();
        for (String topic : unionOfKeys(producers, consumers)) {
            if (EXTERNAL_TOPIC_ALLOWLIST.containsKey(topic)) {
                continue;
            }
            boolean hasProducer = !producers.getOrDefault(topic, Set.of()).isEmpty();
            boolean hasConsumer = !consumers.getOrDefault(topic, Set.of()).isEmpty();
            if (hasProducer && !hasConsumer) {
                producerOnly.put(topic, producers.get(topic));
            } else if (hasConsumer && !hasProducer) {
                consumerOnly.put(topic, consumers.get(topic));
            }
        }

        StringBuilder report = new StringBuilder("Topic inventory (issue #1537):\n");
        report.append("  producer-only (no consumer anywhere): ")
                .append(producerOnly.size())
                .append('\n');
        producerOnly.forEach((topic, mods) -> report.append("    ")
                .append(topic)
                .append(" <- producers: ")
                .append(mods)
                .append('\n'));
        report.append("  consumer-only (no producer anywhere): ")
                .append(consumerOnly.size())
                .append('\n');
        consumerOnly.forEach((topic, mods) -> report.append("    ")
                .append(topic)
                .append(" <- consumers: ")
                .append(mods)
                .append('\n'));
        System.out.println(report);

        Assertions.assertTrue(
                producerOnly.isEmpty() && consumerOnly.isEmpty(),
                "issue #1537: every internal *.events.v1/*.commands.v1/*.manifest.v1 topic needs a producer AND a "
                        + "consumer, or an EXTERNAL_TOPIC_ALLOWLIST entry\n" + report);
    }

    /**
     * Pins the classifier's ability to actually detect a one-sided topic, independent of the
     * repo's current state. Runs the same {@link #classify} method the main test uses, against a
     * small synthetic fixture built in-memory: one topic with a producer and no consumer, one
     * with a consumer and no producer, and (for good measure) one with both, which must NOT be
     * reported as an orphan. If this test ever goes green while quietly detecting nothing, that's
     * the bug the assertions below are aimed at.
     */
    @Test
    void classifierDetectsOneSidedTopics() {
        Map<String, Set<String>> producers = new TreeMap<>();
        Map<String, Set<String>> consumers = new TreeMap<>();

        List<String> producerOnlyFixture = List.of(
                "class FixtureProducer {",
                "    @Value(\"${pos.fixture.kafka.orphan-events-topic:fixture-orphan.events.v1}\")",
                "    private String orphanEventsTopic;",
                "}");
        List<String> consumerOnlyFixture = List.of(
                "class FixtureConsumer {",
                "    @KafkaListener(",
                "            topics = \"${pos.fixture.kafka.orphan-commands-topic:fixture-orphan.commands.v1}\",",
                "            groupId = \"pos-fixture-orphan-commands\")",
                "    public void onCommand(String message) {}",
                "}");
        List<String> healthyFixture = List.of(
                "class FixtureHealthy {",
                "    @Value(\"${pos.fixture.kafka.events-topic:fixture-healthy.events.v1}\")",
                "    private String eventsTopic;",
                "",
                "    @KafkaListener(",
                "            topics = \"${pos.fixture.kafka.events-topic:fixture-healthy.events.v1}\",",
                "            groupId = \"pos-fixture-healthy-events\")",
                "    public void onEvent(String message) {}",
                "}");

        classify("pos-fixture-a", producerOnlyFixture, producers, consumers);
        classify("pos-fixture-b", consumerOnlyFixture, producers, consumers);
        classify("pos-fixture-c", healthyFixture, producers, consumers);

        assertThat(producers.getOrDefault("fixture-orphan.events.v1", Set.of()))
                .as("producer-only fixture must be recorded as a producer")
                .containsExactly("pos-fixture-a");
        assertThat(consumers.getOrDefault("fixture-orphan.events.v1", Set.of()))
                .as("producer-only fixture must NOT be recorded as a consumer")
                .isEmpty();

        assertThat(consumers.getOrDefault("fixture-orphan.commands.v1", Set.of()))
                .as("consumer-only fixture must be recorded as a consumer")
                .containsExactly("pos-fixture-b");
        assertThat(producers.getOrDefault("fixture-orphan.commands.v1", Set.of()))
                .as("consumer-only fixture must NOT be recorded as a producer")
                .isEmpty();

        assertThat(producers.getOrDefault("fixture-healthy.events.v1", Set.of()))
                .as("healthy fixture has both sides: producer")
                .containsExactly("pos-fixture-c");
        assertThat(consumers.getOrDefault("fixture-healthy.events.v1", Set.of()))
                .as("healthy fixture has both sides: consumer")
                .containsExactly("pos-fixture-c");
    }

    /**
     * Pins the paren-depth heuristic against three shapes {@link #classify}'s "open {@code
     * @KafkaListener(...)}" tracking must keep handling correctly: an annotation whose attributes
     * span more than the two lines the other fixtures use, one containing a nested parenthesised
     * expression (so naive "stop at the first close paren" logic would misfire), and a topic-shaped
     * literal sitting in an attribute other than {@code topics} (which must still classify as a
     * consumer occurrence — classification is per-occurrence-inside-the-open-parens, not
     * per-attribute-name; see class javadoc).
     */
    @Test
    void classifierHandlesMultiLineAndNestedListenerAnnotations() {
        Map<String, Set<String>> producers = new TreeMap<>();
        Map<String, Set<String>> consumers = new TreeMap<>();

        List<String> multiLineFixture = List.of(
                "class FixtureMultiLineListener {",
                "    @KafkaListener(",
                "            id = \"fixture-multiline-listener\",",
                "            topics = \"${pos.fixture.kafka.multiline-topic:fixture-multiline.events.v1}\",",
                "            groupId = \"pos-fixture-multiline\",",
                "            containerFactory = \"kafkaListenerContainerFactory\")",
                "    public void onMultiLine(String message) {}",
                "}");
        List<String> nestedParensFixture = List.of(
                "class FixtureNestedParens {",
                "    @KafkaListener(",
                "            topics = \"${pos.fixture.kafka.nested-topic:fixture-nested.commands.v1}\",",
                "            groupId = resolveGroupId(DEFAULT_PREFIX, \"nested\"))",
                "    public void onNested(String message) {}",
                "}");
        List<String> nonTopicsAttributeFixture = List.of(
                "class FixtureNonTopicsAttribute {",
                "    @KafkaListener(",
                "            topics = \"${pos.fixture.kafka.real-topic:fixture-realattr.events.v1}\",",
                "            groupId = \"${pos.fixture.kafka.non-topics-attr-topic:fixture-nontopics.commands.v1}\")",
                "    public void onNonTopicsAttr(String message) {}",
                "}");

        classify("pos-fixture-multiline", multiLineFixture, producers, consumers);
        classify("pos-fixture-nested", nestedParensFixture, producers, consumers);
        classify("pos-fixture-nontopics-attr", nonTopicsAttributeFixture, producers, consumers);

        assertThat(consumers.getOrDefault("fixture-multiline.events.v1", Set.of()))
                .as("topic inside a @KafkaListener whose attributes span several lines is a consumer")
                .containsExactly("pos-fixture-multiline");
        assertThat(producers.getOrDefault("fixture-multiline.events.v1", Set.of()))
                .as("multi-line listener topic must NOT be recorded as a producer")
                .isEmpty();

        assertThat(consumers.getOrDefault("fixture-nested.commands.v1", Set.of()))
                .as("topic inside a @KafkaListener with a nested parenthesised expression is a consumer")
                .containsExactly("pos-fixture-nested");
        assertThat(producers.getOrDefault("fixture-nested.commands.v1", Set.of()))
                .as("nested-parens listener topic must NOT be recorded as a producer")
                .isEmpty();

        assertThat(consumers.getOrDefault("fixture-realattr.events.v1", Set.of()))
                .as("the topics= attribute value is a consumer")
                .containsExactly("pos-fixture-nontopics-attr");
        assertThat(consumers.getOrDefault("fixture-nontopics.commands.v1", Set.of()))
                .as("a topic-shaped literal in a non-topics attribute (groupId) is STILL a consumer occurrence")
                .containsExactly("pos-fixture-nontopics-attr");
        assertThat(producers.getOrDefault("fixture-nontopics.commands.v1", Set.of()))
                .as("non-topics-attribute topic must NOT be recorded as a producer")
                .isEmpty();
    }

    /**
     * Classifies every topic-shaped occurrence in {@code lines} (one file's worth of source) as a
     * producer or consumer reference for {@code moduleName}, per the per-occurrence rule described
     * in the class javadoc: an occurrence found while a {@code @KafkaListener(...)} argument list
     * is open is a consumer; everything else is a producer.
     */
    private static void classify(
            String moduleName,
            List<String> lines,
            Map<String, Set<String>> producers,
            Map<String, Set<String>> consumers) {
        Map<String, String> fileConstants = localStringConstants(lines);
        int listenerDepth = 0;
        for (String rawLine : lines) {
            String code = rawLine.strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            boolean startsListener = code.contains(KAFKA_LISTENER_OPEN);
            boolean insideListener = startsListener || listenerDepth > 0;

            Set<String> topicsOnLine = new TreeSet<>();
            addMatches(PLACEHOLDER_DEFAULT, code, 1, topicsOnLine);
            // Guarded per the class javadoc's discussion of form 2: a bare literal only counts as
            // a topic occurrence when its line also mentions "topic", so an unrelated same-shaped
            // string (e.g. an event-type tag returned by getEventType()) isn't swept in.
            if (code.toLowerCase(Locale.ROOT).contains("topic")) {
                addMatches(BARE_LITERAL, code, 1, topicsOnLine);
            }

            Matcher literalCall = DOMAIN_TOPICS_LITERAL_CALL.matcher(code);
            while (literalCall.find()) {
                topicsOnLine.add(literalCall.group(2) + "." + literalCall.group(1) + ".v1");
            }
            Matcher identifierCall = DOMAIN_TOPICS_IDENTIFIER_CALL.matcher(code);
            while (identifierCall.find()) {
                String resolved = fileConstants.get(identifierCall.group(2));
                if (resolved != null) {
                    topicsOnLine.add(resolved + "." + identifierCall.group(1) + ".v1");
                }
            }
            Matcher constantRef = DOMAIN_TOPICS_CONSTANT_REF.matcher(code);
            while (constantRef.find()) {
                String resolved = DOMAIN_TOPICS_CONSTANTS.get(constantRef.group(1));
                if (resolved != null) {
                    topicsOnLine.add(resolved);
                }
            }

            for (String topic : topicsOnLine) {
                if (!TOPIC_PATTERN.matcher(topic).matches()) {
                    continue;
                }
                Map<String, Set<String>> target = insideListener ? consumers : producers;
                target.computeIfAbsent(topic, k -> new TreeSet<>()).add(moduleName);
            }

            if (startsListener || listenerDepth > 0) {
                listenerDepth += countChar(code, '(') - countChar(code, ')');
                if (listenerDepth < 0) {
                    listenerDepth = 0;
                }
            }
        }
    }

    private static void addMatches(Pattern pattern, String code, int group, Set<String> out) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            out.add(matcher.group(group));
        }
    }

    /** {@code String NAME = "value";} declarations in one file, for resolving form 3b (see class javadoc). */
    private static Map<String, String> localStringConstants(List<String> lines) {
        Map<String, String> constants = new LinkedHashMap<>();
        for (String rawLine : lines) {
            String code = rawLine.strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            Matcher matcher = LOCAL_STRING_CONSTANT.matcher(code);
            while (matcher.find()) {
                constants.put(matcher.group(1), matcher.group(2));
            }
        }
        return constants;
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> unionOfKeys(Map<String, Set<String>> a, Map<String, Set<String>> b) {
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    private static List<Path> mainJavaSources(Path module) throws IOException {
        Path srcRoot = module.resolve("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return List.of();
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        return sources;
    }

    private static boolean isPosModule(Path dir) {
        return Files.isDirectory(dir)
                && dir.getFileName().toString().startsWith("pos-")
                && Files.exists(dir.resolve("pom.xml"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("pos-archunit")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root with pos-archunit not found above "
                + Path.of("").toAbsolutePath());
    }
}
