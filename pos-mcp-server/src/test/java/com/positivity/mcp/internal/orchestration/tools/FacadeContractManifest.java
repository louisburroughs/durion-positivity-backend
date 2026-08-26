package com.positivity.mcp.internal.orchestration.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.yaml.snakeyaml.Yaml;

/**
 * Test-scope loader for {@code src/test/resources/facade-contract.yaml} (#1519 WS-0.3), the single
 * source of truth for what each facade {@code @Tool} method calls. Facade tests derive their
 * {@code MockRestServiceServer} expectations (HTTP verb + expanded URI template) from these
 * entries instead of re-stating the configured template as a string literal — so a test fails
 * whenever the manifest and the tool's actual request disagree.
 * {@link FacadeContractManifestTest} in turn locks every manifest template to the
 * {@code application.yml} property default, closing the manifest-vs-config loop.
 */
final class FacadeContractManifest {

    private static final String RESOURCE = "/facade-contract.yaml";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9]+)}");
    private static final Map<String, Entry> ENTRIES = load();

    private FacadeContractManifest() {}

    record Entry(
            @NonNull String key,
            @NonNull String verb,
            String baseUrlProperty,
            String templateProperty,
            String template,
            String route,
            String downstreamPath,
            String status) {

        @NonNull
        HttpMethod httpMethod() {
            return HttpMethod.valueOf(verb);
        }

        /**
         * Expand the configured template with the given URI-parameter values. Every
         * {@code {placeholder}} must be supplied; unused values are rejected — both directions
         * catch a manifest/tool drift in the parameter list.
         */
        @NonNull
        String expand(@NonNull Map<String, String> params) {
            StringBuilder expanded = new StringBuilder();
            Matcher matcher = PLACEHOLDER.matcher(template);
            int consumed = 0;
            int last = 0;
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = params.get(name);
                if (value == null) {
                    throw new IllegalArgumentException(
                            "Manifest entry " + key + " requires URI param '" + name + "', got " + params.keySet());
                }
                expanded.append(template, last, matcher.start()).append(value);
                last = matcher.end();
                consumed++;
            }
            expanded.append(template.substring(last));
            if (consumed != params.size()) {
                throw new IllegalArgumentException("Manifest entry " + key + " takes " + consumed
                        + " URI param(s) but the test supplied " + params.size() + ": " + params.keySet());
            }
            return expanded.toString();
        }
    }

    static @NonNull Entry entry(@NonNull String key) {
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("No facade-contract.yaml entry for " + key);
        }
        return entry;
    }

    static @NonNull Map<String, Entry> all() {
        return ENTRIES;
    }

    private static Map<String, Entry> load() {
        try (InputStream stream = FacadeContractManifest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + RESOURCE);
            }
            Map<String, Map<String, Object>> raw = new Yaml().load(stream);
            Map<String, Entry> entries = new LinkedHashMap<>();
            raw.forEach((key, fields) -> entries.put(
                    key,
                    new Entry(
                            key,
                            required(key, fields, "verb"),
                            (String) fields.get("baseUrlProperty"),
                            (String) fields.get("templateProperty"),
                            (String) fields.get("template"),
                            required(key, fields, "route"),
                            (String) fields.get("downstreamPath"),
                            (String) fields.get("status"))));
            return Map.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + RESOURCE, exception);
        }
    }

    private static String required(String key, Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null) {
            throw new IllegalStateException("facade-contract.yaml entry " + key + " is missing '" + field + "'");
        }
        return value.toString();
    }
}
