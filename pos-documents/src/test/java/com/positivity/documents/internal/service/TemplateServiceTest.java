package com.positivity.documents.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.documents.internal.exception.TemplateNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ClassUtils;

/**
 * Template resolution and the small mustache-like binder used for rendered documents:
 * {@code ${path}} substitution plus {@code {{#each}}} / {@code {{#if}}} blocks.
 */
@DisplayName("TemplateService")
class TemplateServiceTest {

    private static final String BASE_PATH = "classpath:/templates";

    /** Serves one canned template body and records every path the service asked for. */
    private static final class StubResourceLoader implements ResourceLoader {

        private final Map<String, String> bodies = new LinkedHashMap<>();
        private final java.util.List<String> requestedPaths = new java.util.ArrayList<>();

        private StubResourceLoader with(String path, String body) {
            bodies.put(path, body);
            return this;
        }

        @Override
        public Resource getResource(String location) {
            requestedPaths.add(location);
            String body = bodies.get(location);
            if (body == null) {
                return new DescriptiveResource("missing " + location);
            }
            return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public ClassLoader getClassLoader() {
            return ClassUtils.getDefaultClassLoader();
        }
    }

    private static TemplateService service(StubResourceLoader loader, String basePath) {
        return new TemplateService(loader, new PdfConfiguration(100_000, basePath, 1_000));
    }

    @Nested
    @DisplayName("resolveTemplate")
    class ResolveTemplate {

        @Test
        void loadsTheNamedTemplateFromTheConfiguredBasePath() {
            StubResourceLoader loader = new StubResourceLoader().with(BASE_PATH + "/INVOICE_A4.html", "<p>hello</p>");

            assertThat(service(loader, BASE_PATH).resolveTemplate("INVOICE_A4")).isEqualTo("<p>hello</p>");
        }

        @Test
        void fallsBackToTheDefaultTemplateForABlankOrMissingId() {
            StubResourceLoader loader = new StubResourceLoader()
                    .with(BASE_PATH + "/" + TemplateService.DEFAULT_TEMPLATE_ID + ".html", "<p>default</p>");
            TemplateService service = service(loader, BASE_PATH);

            assertThat(service.resolveTemplate(null)).isEqualTo("<p>default</p>");
            assertThat(service.resolveTemplate("   ")).isEqualTo("<p>default</p>");
        }

        @Test
        void trimsSurroundingWhitespaceFromTheTemplateId() {
            StubResourceLoader loader = new StubResourceLoader().with(BASE_PATH + "/INVOICE_A4.html", "<p>hello</p>");

            assertThat(service(loader, BASE_PATH).resolveTemplate("  INVOICE_A4  "))
                    .isEqualTo("<p>hello</p>");
        }

        @Test
        void toleratesATrailingSlashOnTheConfiguredBasePath() {
            StubResourceLoader loader = new StubResourceLoader().with(BASE_PATH + "/INVOICE_A4.html", "<p>hello</p>");

            assertThat(service(loader, BASE_PATH + "/").resolveTemplate("INVOICE_A4"))
                    .isEqualTo("<p>hello</p>");
        }

        @Test
        void readsEachTemplateOnlyOnce() {
            StubResourceLoader loader = new StubResourceLoader().with(BASE_PATH + "/INVOICE_A4.html", "<p>hello</p>");
            TemplateService service = service(loader, BASE_PATH);

            service.resolveTemplate("INVOICE_A4");
            service.resolveTemplate("INVOICE_A4");

            assertThat(loader.requestedPaths).hasSize(1);
        }

        @Test
        void rejectsATemplateIdThatCouldEscapeTheBasePath() {
            StubResourceLoader loader = new StubResourceLoader();
            TemplateService service = service(loader, BASE_PATH);

            assertThatThrownBy(() -> service.resolveTemplate("../../etc/passwd"))
                    .isInstanceOf(TemplateNotFoundException.class)
                    .hasMessageContaining("Template not found");
            // The traversal attempt never reaches the resource loader at all.
            assertThat(loader.requestedPaths).isEmpty();
        }

        @Test
        void rejectsALowercaseTemplateId() {
            TemplateService service = service(new StubResourceLoader(), BASE_PATH);

            assertThatThrownBy(() -> service.resolveTemplate("invoice")).isInstanceOf(TemplateNotFoundException.class);
        }

        @Test
        void failsWhenTheTemplateFileIsNotThere() {
            TemplateService service = service(new StubResourceLoader(), BASE_PATH);

            assertThatThrownBy(() -> service.resolveTemplate("INVOICE_A4"))
                    .isInstanceOf(TemplateNotFoundException.class)
                    .hasMessageContaining("INVOICE_A4");
        }

        @Test
        void failsWhenTheTemplateCannotBeRead() {
            ResourceLoader unreadable = new ResourceLoader() {
                @Override
                public Resource getResource(String location) {
                    return new AbstractResource() {
                        @Override
                        public boolean exists() {
                            return true;
                        }

                        @Override
                        public String getDescription() {
                            return "unreadable template";
                        }

                        @Override
                        public InputStream getInputStream() throws IOException {
                            throw new IOException("disk error");
                        }
                    };
                }

                @Override
                public ClassLoader getClassLoader() {
                    return ClassUtils.getDefaultClassLoader();
                }
            };
            TemplateService service = new TemplateService(unreadable, new PdfConfiguration(100_000, BASE_PATH, 1_000));

            assertThatThrownBy(() -> service.resolveTemplate("INVOICE_A4"))
                    .isInstanceOf(TemplateNotFoundException.class)
                    .hasMessageContaining("Failed to load template");
        }
    }

    @Nested
    @DisplayName("bindTemplate")
    class BindTemplate {

        private final TemplateService service = service(new StubResourceLoader(), BASE_PATH);

        @Test
        void substitutesTopLevelAndNestedVariables() {
            String bound = service.bindTemplate(
                    "Hello ${name}, order ${order.number}", Map.of("name", "Jane", "order", Map.of("number", "SO-1")));

            assertThat(bound).isEqualTo("Hello Jane, order SO-1");
        }

        @Test
        void rendersAnUnknownPathAsTheEmptyString() {
            String bound = service.bindTemplate("Hello ${missing} ${order.missing} ${name.deep}", Map.of("name", "x"));

            assertThat(bound).isEqualTo("Hello   ");
        }

        @Test
        void expandsAnEachBlockOverAListOfScalars() {
            String bound =
                    service.bindTemplate("{{#each items}}[${this}]{{/each}}", Map.of("items", List.of("a", "b")));

            assertThat(bound).isEqualTo("[a][b]");
        }

        @Test
        void expandsAnEachBlockOverAListOfMaps() {
            String bound = service.bindTemplate(
                    "{{#each lines}}${this.sku}=${this.qty};{{/each}}",
                    Map.of("lines", List.of(Map.of("sku", "SKU-1", "qty", 2), Map.of("sku", "SKU-2", "qty", 3))));

            assertThat(bound).isEqualTo("SKU-1=2;SKU-2=3;");
        }

        @Test
        void rendersAnEachBlockAsEmptyWhenThePathIsNotACollection() {
            assertThat(service.bindTemplate("{{#each items}}x{{/each}}", Map.of("items", "not-a-list")))
                    .isEmpty();
            assertThat(service.bindTemplate("{{#each items}}x{{/each}}", Map.of()))
                    .isEmpty();
        }

        @Test
        void keepsAnIfBlockWhoseValueIsTruthy() {
            assertThat(service.bindTemplate("{{#if show}}visible{{/if}}", Map.of("show", true)))
                    .isEqualTo("visible");
            assertThat(service.bindTemplate("{{#if note}}visible{{/if}}", Map.of("note", "anything")))
                    .isEqualTo("visible");
        }

        @Test
        void dropsAnIfBlockWhoseValueIsFalseyBlankOrAbsent() {
            assertThat(service.bindTemplate("{{#if show}}visible{{/if}}", Map.of("show", false)))
                    .isEmpty();
            assertThat(service.bindTemplate("{{#if note}}visible{{/if}}", Map.of("note", "  ")))
                    .isEmpty();
            assertThat(service.bindTemplate("{{#if note}}visible{{/if}}", Map.of()))
                    .isEmpty();
        }

        @Test
        void substitutesVariablesInsideASurvivingIfBlock() {
            String bound =
                    service.bindTemplate("{{#if show}}Hello ${name}{{/if}}", Map.of("show", true, "name", "Jane"));

            assertThat(bound).isEqualTo("Hello Jane");
        }

        @Test
        void escapesDollarSignsInSubstitutedValues() {
            // A value containing $ or \ must not be re-interpreted as a replacement reference.
            String bound = service.bindTemplate("total=${amount}", Map.of("amount", "$5\\0"));

            assertThat(bound).isEqualTo("total=$5\\0");
        }
    }
}
