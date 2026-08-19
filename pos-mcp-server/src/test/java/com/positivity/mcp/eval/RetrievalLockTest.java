package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties;
import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import com.positivity.mcp.internal.domain.RagScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Gate 5 retrieval lock — offline half (#1217, Wave 0.2 of
 * {@code docs/gate-closeout-plan-1212-1219.md}).
 *
 * <p>The Gate 5 block in {@code docs/implementation_checklist.md} states the retrieval lock as:
 * <em>"every doc has: deterministic ID, content hash, {@code rag-scope}, permission metadata,
 * documented chunking"</em>. This test asserts that lock over the whole static corpus declared under
 * {@code mcp.rag.preload.docs}, with no running Spring context, no database, and no embedding
 * backend — so a doc that is added, renamed, dropped, or de-tagged fails the normal module build
 * rather than being discovered on alpha.
 *
 * <p>The corpus is bound through the exact production path: {@link YamlPropertySourceLoader} plus
 * {@link Binder} onto {@link StaticRagPreloadProperties}, the same record
 * {@code StaticRagPreloadServiceImpl} consumes. Nothing here hand-parses YAML, so a binding change
 * (relaxed nullability, renamed key, dropped {@code @ConstructorBinding}) surfaces here too.
 *
 * <p>The live half of the sweep — on-disk content hash versus the {@code mcp_rag_preload_record}
 * rows actually embedded in the alpha database — is {@code scripts/rag_lock_sweep.py}, run in Wave 1
 * step 2. This test deliberately owns only what can be proven offline.
 *
 * <h2>Ruling: what an absent {@code required-permissions} key means</h2>
 *
 * <p>Six entries carry no {@code required-permissions} key at all. Production semantics were read
 * before deciding, not assumed:
 *
 * <ul>
 *   <li>{@code StaticRagPreloadProperties.StaticDocEntry}'s {@code @ConstructorBinding} compact
 *       constructor maps a missing key to {@code List.of()} — binding does not fail.
 *   <li>{@code StaticRagPreloadServiceImpl.preloadDocument} writes the {@code required_permissions}
 *       ingest-metadata key <em>only when the list is non-empty</em>, so those docs reach pgvector
 *       with no permission metadata at all.
 *   <li>{@code PermissionAwareMetadataFilter.isVisible} returns {@code true} when
 *       {@code required_permissions} is absent or blank — the comment on that branch reads
 *       "public / unrestricted".
 * </ul>
 *
 * <p>So absent is <strong>legal and load-bearing</strong>: it means "visible to every caller that
 * reaches the retriever", not "unconfigured". It is therefore not asserted as a hard violation.
 *
 * <p>But absence is also indistinguishable from an authoring omission, and the Gate 5 drift checks
 * demand "no doc added without permission tags". The lock this test enforces is consequently
 * <em>deliberateness</em>: {@link #PUBLIC_DOC_IDS} pins the exact set of docs that are public, and
 * the assertion is an equality both ways. A new doc that forgets the key fails; a doc that silently
 * loses its permission tags fails; and removing a doc from the public set without adding tags fails.
 *
 * <p><strong>#1396 resolved (2026-08-19):</strong> {@code security.guide} was the recorded
 * exception here — an admin-and-security-operator guide shipped world-readable. The owner decision
 * was to tag it ({@code security:permission:view} + {@code security:role:view} in both
 * {@code application.yml} and {@code application-alpha.yml}), so it left this set. The remaining
 * five public docs are pre-Gate-5 operational guides with no restricted content.
 */
class RetrievalLockTest {

    /** Base config; also the file the lock is defined over. */
    private static final String BASE_YAML = "application.yml";

    /**
     * {@code StaticRagPreloadServiceImpl} is {@code @Profile("alpha")}, so the alpha overlay is the
     * list that actually gets embedded. Spring replaces (never merges) a list from a higher-priority
     * source, so the two files must stay in lockstep.
     */
    private static final String ALPHA_YAML = "application-alpha.yml";

    private static final String PRELOAD_PREFIX = "mcp.rag.preload";
    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final Path MAIN_RESOURCES = Paths.get(System.getProperty("user.dir"), "src/main/resources");

    /**
     * Gate 5 corpus size (#1124: "corpus grown to 39 docs"). Hard floor — a silently dropped doc
     * must fail the build, not quietly shrink recall on alpha.
     */
    private static final int MIN_STATIC_DOCS = 39;

    /** Minimum {@code ## } sections per doc for the heading-aware splitter to engage (see below). */
    private static final int MIN_MARKDOWN_SECTIONS = 2;

    /**
     * The exact, deliberate public corpus — docs that reach every caller unfiltered. See the ruling
     * in this class's javadoc. Any change to this set is a permission-visibility change and must be
     * argued in review.
     */
    private static final Set<String> PUBLIC_DOC_IDS = Set.of(
            // Pre-Gate-5 operational guides, no restricted content.
            "accounting.de-bookkeeping",
            "inventory.inv-cntrl",
            "people.human-resources",
            "shop.management",
            "shop.management.guidelines");

    /**
     * {@code domain:resource:action}, snake_case, plus the synthetic {@code AUTHENTICATED} code that
     * {@code PermissionAwareMetadataFilter} treats as "any authenticated caller".
     */
    private static final Pattern PERMISSION_CODE = Pattern.compile("^[a-z][a-z0-9_-]*(:[a-z0-9_-]+){2}$");

    private static final String AUTHENTICATED = "AUTHENTICATED";

    @Test
    @DisplayName("retrieval lock: the static corpus binds and carries at least the Gate 5 doc count")
    void staticCorpusBindsWithExpectedSize() {
        List<StaticDocEntry> docs = docs(BASE_YAML);

        assertThat(docs)
                .as(
                        "%s: mcp.rag.preload.docs must bind to at least %d entries (Gate 5 corpus)",
                        BASE_YAML, MIN_STATIC_DOCS)
                .hasSizeGreaterThanOrEqualTo(MIN_STATIC_DOCS);
    }

    @Test
    @DisplayName("retrieval lock: every doc has a deterministic, unique id")
    void everyDocHasDeterministicUniqueId() {
        List<String> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            String id = doc.id();
            if (id == null || id.isBlank()) {
                violations.add("<blank id> for source-path " + doc.sourcePath());
                continue;
            }
            if (!id.equals(id.trim()) || id.chars().anyMatch(Character::isWhitespace)) {
                violations.add(id + ": id must not contain whitespace");
            }
            if (!seen.add(id)) {
                // A duplicate id makes the preload skip/replace check
                // (findFirstByDocumentIdAndRagScopeAndStatus...) ambiguous and lets one doc's rows
                // shadow another's.
                violations.add(id + ": duplicate document id");
            }
        }

        assertThat(violations)
                .as("docs with non-deterministic or duplicate ids")
                .isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: every doc declares a rag-scope that survives RagScope.normalize")
    void everyDocDeclaresRagScope() {
        List<String> violations = new ArrayList<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            String scope = doc.ragScope();
            if (scope == null || scope.isBlank()) {
                // RagScope.normalize would silently coerce this to "master", widening the doc's
                // retrieval scope without anyone declaring it.
                violations.add(doc.id() + ": missing rag-scope");
                continue;
            }
            if (!scope.equals(RagScope.normalize(scope))) {
                violations.add("%s: rag-scope '%s' is not already normalized (expected '%s')"
                        .formatted(doc.id(), scope, RagScope.normalize(scope)));
            }
        }

        assertThat(violations)
                .as("docs with missing or non-normalized rag-scope")
                .isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: permission metadata is an explicit decision on every doc")
    void everyDocDeclaresAnExplicitPermissionDecision() {
        Set<String> declaredPublic = new LinkedHashSet<>();
        Set<String> allIds = new LinkedHashSet<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            allIds.add(doc.id());
            List<String> permissions = doc.requiredPermissions();
            if (permissions == null || permissions.isEmpty()) {
                declaredPublic.add(doc.id());
            }
        }

        assertThat(allIds)
                .as("PUBLIC_DOC_IDS references a doc that no longer exists in the corpus")
                .containsAll(PUBLIC_DOC_IDS);
        assertThat(declaredPublic)
                .as("docs with no required-permissions are public to every caller "
                        + "(PermissionAwareMetadataFilter.isVisible -> true); the public set is pinned, "
                        + "so a new doc missing the key, or an existing doc losing its tags, fails here")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_DOC_IDS);
    }

    @Test
    @DisplayName("retrieval lock: declared permission codes are well-formed domain:resource:action")
    void permissionCodesAreWellFormed() {
        List<String> violations = new ArrayList<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            for (String code : doc.requiredPermissions()) {
                if (AUTHENTICATED.equals(code)) {
                    continue;
                }
                if (code == null
                        || code.isBlank()
                        || !PERMISSION_CODE.matcher(code).matches()) {
                    // A malformed code can never intersect the caller's codes, so the doc becomes
                    // permanently invisible instead of permission-gated — a silent recall loss.
                    violations.add(doc.id() + ": malformed permission code '" + code + "'");
                }
                if (code != null && code.contains(",")) {
                    // Ingest joins the list with commas; an embedded comma would split one code in two.
                    violations.add(doc.id() + ": permission code '" + code + "' contains a comma");
                }
            }
        }

        assertThat(violations).as("docs with malformed permission codes").isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: every source-path resolves to a real, non-empty classpath resource")
    void everySourcePathResolvesToRealResource() {
        List<String> violations = new ArrayList<>();
        Map<String, String> byPath = new HashMap<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            String sourcePath = doc.sourcePath();
            if (sourcePath == null || sourcePath.isBlank()) {
                violations.add(doc.id() + ": missing source-path");
                continue;
            }
            if (!sourcePath.startsWith(CLASSPATH_PREFIX)) {
                violations.add(doc.id() + ": source-path '" + sourcePath + "' must be classpath-qualified");
                continue;
            }
            String relative = sourcePath.substring(CLASSPATH_PREFIX.length());

            // Mirrors StaticRagPreloadServiceImpl.preloadDocument: ClassPathResource(resourcePath(..)).
            ClassPathResource resource = new ClassPathResource(relative);
            if (!resource.exists()) {
                violations.add(doc.id() + ": classpath resource not found -> " + sourcePath);
                continue;
            }
            if (content(doc.id(), relative).isBlank()) {
                violations.add(doc.id() + ": classpath resource is empty -> " + sourcePath);
            }
            // ...and on disk, so a stale target/classes copy cannot mask a deleted source file.
            if (!MAIN_RESOURCES.resolve(relative).toFile().isFile()) {
                violations.add(doc.id() + ": source file missing under src/main/resources -> " + relative);
            }

            String previous = byPath.put(relative, doc.id());
            if (previous != null) {
                violations.add(doc.id() + ": source-path '" + relative + "' already used by " + previous);
            }
        }

        assertThat(violations).as("docs whose source-path does not resolve").isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: every doc yields a distinct, reproducible content hash")
    void everyDocHasAReproducibleUniqueContentHash() {
        List<String> violations = new ArrayList<>();
        Map<String, String> byHash = new HashMap<>();

        for (StaticDocEntry doc : docs(BASE_YAML)) {
            String relative = doc.sourcePath().substring(CLASSPATH_PREFIX.length());
            byte[] bytes = bytes(doc.id(), relative);
            String hash = sha256(bytes);

            if (!hash.equals(sha256(bytes))) {
                violations.add(doc.id() + ": content hash is not reproducible");
            }
            String previous = byHash.put(hash, doc.id());
            if (previous != null) {
                // Two ids over identical bytes means duplicated corpus content competing for the
                // same top-K slots.
                violations.add(doc.id() + ": byte-identical content to " + previous);
            }
        }

        assertThat(violations)
                .as("docs with non-reproducible or duplicated content hashes")
                .isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: chunking is documented globally and every doc is heading-chunkable")
    void chunkingIsDocumentedAndAppliesToEveryDoc() {
        StandardEnvironment environment = environment(BASE_YAML);

        // Chunking is corpus-wide policy, not per-doc: DocumentEmbeddingIngestor reads these three
        // keys via @Value and applies one documented split to every static doc.
        String enabled = environment.getProperty("mcp.rag.chunking.enabled");
        Integer maxSegmentSize = environment.getProperty("mcp.rag.chunking.max-segment-size", Integer.class);
        Integer maxOverlapSize = environment.getProperty("mcp.rag.chunking.max-overlap-size", Integer.class);

        assertThat(enabled)
                .as("mcp.rag.chunking.enabled must be declared and on, otherwise whole documents are "
                        + "embedded as one vector")
                .isEqualTo("true");
        assertThat(maxSegmentSize)
                .as("mcp.rag.chunking.max-segment-size must be declared")
                .isNotNull()
                .isPositive();
        assertThat(maxOverlapSize)
                .as("mcp.rag.chunking.max-overlap-size must be declared and smaller than the segment "
                        + "size (DocumentEmbeddingIngestor steps by segment - overlap)")
                .isNotNull()
                .isNotNegative();
        assertThat(maxOverlapSize)
                .as("overlap must be smaller than the segment size")
                .isLessThan(maxSegmentSize);

        // Per-doc half: DocumentEmbeddingIngestor.split prefers markdown "## " section boundaries and
        // only falls back to the blind sliding window when a document has <= 1 top-level section
        // (see its javadoc, issue #1124/#1125). A doc with no sections therefore gets undocumented,
        // fact-splitting chunks.
        List<String> violations = new ArrayList<>();
        for (StaticDocEntry doc : docs(BASE_YAML)) {
            String relative = doc.sourcePath().substring(CLASSPATH_PREFIX.length());
            long sections = content(doc.id(), relative)
                    .lines()
                    .filter(line -> line.startsWith("## "))
                    .count();
            if (sections < MIN_MARKDOWN_SECTIONS) {
                violations.add("%s: %d '## ' sections, needs >= %d for heading-aware chunking"
                        .formatted(doc.id(), sections, MIN_MARKDOWN_SECTIONS));
            }
        }

        assertThat(violations)
                .as("docs that fall back to blind sliding-window chunking")
                .isEmpty();
    }

    @Test
    @DisplayName("retrieval lock: the alpha overlay declares exactly the same corpus as the base config")
    void alphaOverlayCorpusMatchesBaseCorpus() {
        // Only the alpha profile runs StaticRagPreloadServiceImpl, and a profile-specific list
        // replaces the base list wholesale rather than merging — so drift between these two files
        // silently changes what is embedded while the base config keeps looking correct.
        assertThat(describe(docs(BASE_YAML, ALPHA_YAML)))
                .as("application-alpha.yml mcp.rag.preload.docs has drifted from application.yml")
                .containsExactlyElementsOf(describe(docs(BASE_YAML)));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static List<String> describe(List<StaticDocEntry> docs) {
        return docs.stream()
                .map(doc ->
                        "%s|%s|%s|%s".formatted(doc.id(), doc.sourcePath(), doc.ragScope(), doc.requiredPermissions()))
                .toList();
    }

    /** Binds {@code mcp.rag.preload} exactly the way the Spring Boot runtime binds it. */
    private static List<StaticDocEntry> docs(String... yamlResources) {
        StaticRagPreloadProperties properties = Binder.get(environment(yamlResources))
                .bind(PRELOAD_PREFIX, StaticRagPreloadProperties.class)
                .orElseThrow(() ->
                        new AssertionError(PRELOAD_PREFIX + " did not bind from " + String.join(", ", yamlResources)));
        return properties.docs();
    }

    /** Later resources win, mirroring profile-overlay precedence. */
    private static StandardEnvironment environment(String... yamlResources) {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (int i = 0; i < yamlResources.length; i++) {
                ClassPathResource resource = new ClassPathResource(yamlResources[i]);
                assertThat(resource.exists())
                        .as("config resource %s must exist on the classpath", yamlResources[i])
                        .isTrue();
                List<PropertySource<?>> sources = loader.load("retrieval-lock-" + i, resource);
                for (PropertySource<?> source : sources) {
                    environment.getPropertySources().addFirst(source);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return environment;
    }

    private static String content(String documentId, String relativePath) {
        try {
            return new ClassPathResource(relativePath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("unreadable RAG document " + documentId + " -> " + relativePath, exception);
        }
    }

    /** Raw bytes, exactly as StaticRagPreloadServiceImpl reads them before hashing. */
    private static byte[] bytes(String documentId, String relativePath) {
        try {
            return new ClassPathResource(relativePath).getContentAsByteArray();
        } catch (IOException exception) {
            throw new AssertionError("unreadable RAG document " + documentId + " -> " + relativePath, exception);
        }
    }

    /** Same digest StaticRagPreloadServiceImpl.computeHash persists into mcp_rag_preload_record. */
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
