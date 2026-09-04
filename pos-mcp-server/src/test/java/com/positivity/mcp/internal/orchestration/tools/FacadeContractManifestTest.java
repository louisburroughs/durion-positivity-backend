package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.yaml.snakeyaml.Yaml;

/**
 * Locks {@code facade-contract.yaml} (#1519 WS-0.3) to reality in both directions:
 *
 * <ul>
 *   <li>every configured {@code template} equals the {@code application.yml} property default it
 *       claims to describe (the manifest can never drift from the config the tools actually run
 *       with), and
 *   <li>every {@code @Tool} method of every facade tool has exactly one manifest entry, and no
 *       entry points at a method that no longer exists.
 * </ul>
 */
class FacadeContractManifestTest {

    private static final List<Class<?>> FACADE_TOOLS = List.of(
            AccountingFacadeTool.class,
            AdminFacadeTool.class,
            CatalogFacadeTool.class,
            CustomerFacadeTool.class,
            DateWindowFacadeTool.class,
            GlossaryFacadeTool.class,
            EventsFacadeTool.class,
            HrFacadeTool.class,
            InventoryFacadeTool.class,
            InvoiceFacadeTool.class,
            LocationFacadeTool.class,
            OrderFacadeTool.class,
            PricingFacadeTool.class,
            ReportingFacadeTool.class,
            ShopManagerFacadeTool.class,
            TaxFacadeTool.class,
            VehicleFacadeTool.class,
            WorkorderFacadeTool.class);

    @Test
    @DisplayName("every manifest template matches the application.yml property default")
    void manifestTemplatesMatchConfiguredDefaults() throws IOException {
        Map<String, Object> posBlock = applicationYamlPosBlock();

        FacadeContractManifest.all().forEach((key, entry) -> verifyAgainstConfig(posBlock, entry));
    }

    private static void verifyAgainstConfig(Map<String, Object> posBlock, FacadeContractManifest.Entry entry) {
        String key = entry.key();
        if (entry.templateProperty() != null) {
            String configured = extractDefault(resolveProperty(posBlock, entry.templateProperty(), key));
            assertThat(entry.template())
                    .as("%s: manifest template vs application.yml default of %s", key, entry.templateProperty())
                    .isEqualTo(configured);
        }
        if (entry.baseUrlProperty() != null) {
            assertThat(resolveProperty(posBlock, entry.baseUrlProperty(), key))
                    .as("%s: base-url property %s must exist in application.yml", key, entry.baseUrlProperty())
                    .isNotNull();
        }
        entry.legs().values().forEach(leg -> verifyAgainstConfig(posBlock, leg));
    }

    @Test
    @DisplayName("composition entries declare COMPOSITE and fully specified legs; simple entries declare none")
    void compositionEntriesDeclareFullySpecifiedLegs() {
        FacadeContractManifest.all().forEach((key, entry) -> {
            if (entry.isComposition()) {
                assertThat(entry.verb())
                        .as("%s: a legged entry must use verb COMPOSITE", key)
                        .isEqualTo("COMPOSITE");
                entry.legs().forEach((legName, leg) -> {
                    assertThat(leg.verb())
                            .as("%s#%s: leg verb must be a concrete HTTP method", key, legName)
                            .isNotEqualTo("COMPOSITE");
                    assertThat(leg.templateProperty())
                            .as("%s#%s: leg must name its template property", key, legName)
                            .isNotNull();
                    assertThat(leg.baseUrlProperty())
                            .as("%s#%s: leg must name its base-url property", key, legName)
                            .isNotNull();
                    assertThat(leg.downstreamPath())
                            .as("%s#%s: leg must state its downstream path", key, legName)
                            .isNotNull();
                });
            } else {
                assertThat(entry.verb())
                        .as("%s: COMPOSITE is only valid with legs", key)
                        .isNotEqualTo("COMPOSITE");
            }
        });
    }

    @Test
    @DisplayName("manifest entries and facade @Tool methods correspond one-to-one")
    void manifestCoversExactlyTheToolSurface() {
        Set<String> toolMethods = new LinkedHashSet<>();
        for (Class<?> facade : FACADE_TOOLS) {
            for (Method method : facade.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    toolMethods.add(facade.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertThat(FacadeContractManifest.all().keySet())
                .as("facade-contract.yaml keys vs the actual @Tool surface")
                .containsExactlyInAnyOrderElementsOf(toolMethods);
    }

    // ── application.yml access ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationYamlPosBlock() throws IOException {
        try (InputStream stream = FacadeContractManifestTest.class.getResourceAsStream("/application.yml")) {
            assertThat(stream).as("application.yml on the test classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(stream);
            return (Map<String, Object>) root.get("pos");
        }
    }

    @SuppressWarnings("unchecked")
    private static String resolveProperty(Map<String, Object> posBlock, String property, String key) {
        assertThat(property).as("%s: property must live under pos.*", key).startsWith("pos.");
        Object node = posBlock;
        for (String segment : property.substring("pos.".length()).split("\\.")) {
            assertThat(node)
                    .as("%s: %s resolves inside application.yml's pos block", key, property)
                    .isInstanceOf(Map.class);
            node = ((Map<String, Object>) node).get(segment);
            assertThat(node)
                    .as("%s: pos-block segment '%s' of %s exists", key, segment, property)
                    .isNotNull();
        }
        assertThat(node).as("%s: %s is a scalar", key, property).isInstanceOf(String.class);
        return (String) node;
    }

    /**
     * {@code ${VAR:default}} → {@code default}, brace-aware (the default may itself contain
     * {@code {placeholders}}); plain values pass through; nested defaults recurse. Mirrors
     * {@code extract_default} in {@code scripts/check-mcp-facade-paths.py}.
     */
    private static String extractDefault(String value) {
        if (value == null || !value.matches("(?s)\\$\\{[A-Za-z0-9_.]+:.*")) {
            return value;
        }
        int start = value.indexOf(':') + 1;
        int depth = 1;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            out.append(c);
        }
        return extractDefault(out.toString().trim());
    }
}
