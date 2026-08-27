package com.positivity.inventory.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code pos.inventory.sku-category.resolve-from-replica} placeholder against the env var
 * name the README documents (#1535).
 *
 * <p>This is a text test on purpose, and it is the test that would have caught the original bug.
 * Before #1535 the key had no entry in {@code application.yml} at all, so Spring's relaxed binding
 * had no placeholder to bind an environment variable into — the documented
 * {@code POS_INVENTORY_SKU_CATEGORY_RESOLVE_FROM_REPLICA} bound to nothing, and only the
 * unreadable {@code POS_INVENTORY_SKUCATEGORY_RESOLVEFROMREPLICA} would have worked. The README
 * documented a variable that did nothing, and no compiler or context test could tell.
 *
 * <p>Adding the placeholder with value {@code false} is a wiring fix and not a flip:
 * {@code @ConditionalOnProperty(havingValue = "true", matchIfMissing = false)} treats absent and
 * {@code false} identically. {@code ReplicaSkuCategoryProviderTest} pins that half.
 */
@DisplayName("SKU_CATEGORY property wiring")
class SkuCategoryPropertyWiringTest {

    private static final String ENV_VAR = "POS_INVENTORY_SKU_CATEGORY_RESOLVE_FROM_REPLICA";
    private static final String PLACEHOLDER = "resolve-from-replica: ${" + ENV_VAR + ":false}";

    private static String read(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the documented env var name matches the application.yml placeholder character for character")
    void documentedEnvVarNameMatchesTheApplicationYmlPlaceholder() throws IOException {
        assertThat(read("src/main/resources/application.yml"))
                .as("application.yml must carry the placeholder, or the documented env var binds to nothing")
                .contains(PLACEHOLDER);

        assertThat(read("README.md"))
                .as("README.md must document exactly the env var name the placeholder reads")
                .contains(ENV_VAR);
    }
}
