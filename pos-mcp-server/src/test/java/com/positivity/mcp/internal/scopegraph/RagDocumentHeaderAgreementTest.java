package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.config.StaticRagPreloadProperties.StaticDocEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * ADR-0069 section 3: "a RAG document header, where present, must agree with its {@code
 * mcp.rag.preload.docs} entry". The header is what a document author reads, the yml entry is what the
 * runtime enforces; a disagreement is a permission or scope mistake waiting to be shipped.
 *
 * <p>Two header shapes exist. YAML front matter ({@code rag_id}, {@code rag_scope}, {@code
 * required_permissions} list) wins when a document has it; otherwise the inline lines {@code RAG id:},
 * {@code RAG scope:} and {@code Required permissions:} (backticked, comma separated codes) are read.
 * A document with neither has no header to check. {@code entities:} is deliberately not in a header:
 * the yml entry is its only home.
 */
class RagDocumentHeaderAgreementTest {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern INLINE_LINE =
            Pattern.compile("^(RAG id|RAG scope|Required permissions):\\s*(.+?)\\s*$");

    /** The header of one document: any field is empty when the document does not state it. */
    private record Header(Optional<String> id, Optional<String> scope, Optional<Set<String>> permissions) {

        boolean present() {
            return id.isPresent() || scope.isPresent() || permissions.isPresent();
        }
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"default", "alpha"})
    @DisplayName("each document header that exists agrees with its preload entry on id, scope and permissions")
    void headersAgreeWithPreloadEntries(String profile) {
        int withHeader = 0;
        for (StaticDocEntry entry : ScopeGraphRealConfigValidationTest.ragDocs(profile)) {
            Header header = parse(read(entry.sourcePath()));
            if (!header.present()) {
                continue;
            }
            withHeader++;
            header.id()
                    .ifPresent(id -> assertThat(id)
                            .as("%s: header RAG id vs preload id (%s)", entry.sourcePath(), profile)
                            .isEqualTo(entry.id()));
            header.scope()
                    .ifPresent(scope -> assertThat(scope)
                            .as("%s: header RAG scope vs preload rag-scope (%s)", entry.sourcePath(), profile)
                            .isEqualTo(entry.ragScope()));
            header.permissions()
                    .ifPresent(permissions -> assertThat(permissions)
                            .as(
                                    "%s: header required permissions vs preload required-permissions (%s)",
                                    entry.sourcePath(), profile)
                            .isEqualTo(new TreeSet<>(entry.requiredPermissions())));
        }
        assertThat(withHeader)
                .as("documents with a readable header; the parser found none, so it is probably broken")
                .isGreaterThan(20);
    }

    @Test
    @DisplayName("the parser reads front matter, inline lines, and tolerates a document without a header")
    void parserHandlesEveryHeaderShape() {
        Header frontMatter = parse(
                "---\nrag_id: a.b\nrag_scope: order\nrequired_permissions:\n  - x:y:view\n  - z:w:read\n---\n\nbody");
        assertThat(frontMatter.id()).contains("a.b");
        assertThat(frontMatter.scope()).contains("order");
        assertThat(frontMatter.permissions()).contains(Set.of("x:y:view", "z:w:read"));

        Header inline = parse(
                "# Title\n\nRAG id: `c.d`  \nRAG scope: `tax`  \nRequired permissions: `p:q:view`, `r:s:call` (note).\n");
        assertThat(inline.id()).contains("c.d");
        assertThat(inline.scope()).contains("tax");
        assertThat(inline.permissions()).contains(Set.of("p:q:view", "r:s:call"));

        Header bare = parse("RAG id: e.f\nRAG scope: master\nRequired permissions: AUTHENTICATED\n");
        assertThat(bare.permissions()).contains(Set.of("AUTHENTICATED"));

        assertThat(parse("# Just a title\n\nNo header here.\n").present()).isFalse();
    }

    private static Header parse(String text) {
        List<String> lines = text.lines().toList();
        if (!lines.isEmpty() && lines.getFirst().strip().equals("---")) {
            int end = -1;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).strip().equals("---")) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                return frontMatter(lines.subList(1, end));
            }
        }
        return inline(lines);
    }

    private static Header frontMatter(List<String> lines) {
        Optional<String> id = Optional.empty();
        Optional<String> scope = Optional.empty();
        Optional<Set<String>> permissions = Optional.empty();
        boolean inPermissions = false;
        Set<String> codes = new TreeSet<>();
        for (String line : lines) {
            if (inPermissions && line.strip().startsWith("- ")) {
                codes.add(unquote(line.strip().substring(2)));
                continue;
            }
            inPermissions = false;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "rag_id" -> id = Optional.of(unquote(value));
                case "rag_scope" -> scope = Optional.of(unquote(value));
                case "required_permissions" -> {
                    permissions = Optional.of(codes);
                    inPermissions = true;
                    if (!value.isEmpty()) {
                        codes.addAll(inlineCodes(value));
                        inPermissions = false;
                    }
                }
                default -> {
                    // Other front matter keys are not part of the contract.
                }
            }
        }
        return new Header(id, scope, permissions);
    }

    private static Header inline(List<String> lines) {
        Optional<String> id = Optional.empty();
        Optional<String> scope = Optional.empty();
        Optional<Set<String>> permissions = Optional.empty();
        for (String line : lines) {
            Matcher matcher = INLINE_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String value = matcher.group(2);
            switch (matcher.group(1)) {
                case "RAG id" -> id = id.or(() -> Optional.of(firstToken(value)));
                case "RAG scope" -> scope = scope.or(() -> Optional.of(firstToken(value)));
                default -> permissions = permissions.or(() -> Optional.of(new TreeSet<>(inlineCodes(value))));
            }
        }
        return new Header(id, scope, permissions);
    }

    /** {@code `a`, `b` (note)} gives a and b; an unquoted {@code a, b} is split on commas. */
    private static List<String> inlineCodes(String value) {
        List<String> codes = new ArrayList<>();
        Matcher matcher = BACKTICKED.matcher(value);
        while (matcher.find()) {
            codes.add(matcher.group(1).strip());
        }
        if (codes.isEmpty()) {
            for (String part : value.split(",")) {
                String code = unquote(part.strip());
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    private static String firstToken(String value) {
        Matcher matcher = BACKTICKED.matcher(value);
        return matcher.find() ? matcher.group(1).strip() : unquote(value);
    }

    private static String unquote(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2
                && (stripped.startsWith("\"") && stripped.endsWith("\"")
                        || stripped.startsWith("'") && stripped.endsWith("'"))) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String read(String sourcePath) {
        assertThat(sourcePath).as("preload source-path").startsWith(CLASSPATH_PREFIX);
        try {
            return new String(
                    new ClassPathResource(sourcePath.substring(CLASSPATH_PREFIX.length()))
                            .getInputStream()
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
