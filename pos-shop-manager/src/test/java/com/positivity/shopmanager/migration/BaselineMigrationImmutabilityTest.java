package com.positivity.shopmanager.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the content of {@code V1__baseline_shop_manager.sql} so it cannot be edited again.
 *
 * <p>The baseline is applied on alpha. Flyway records the checksum it computed there, and this
 * module's validation is deliberately strict for versioned migrations (see {@link
 * FlywayIgnoreMigrationPatternsTest}), so any edit to the file makes the checksums disagree and
 * aborts the application context before the {@code EntityManagerFactory} is built. This is not
 * hypothetical either: adding the schedule-capacity replica columns to the baseline rather than to
 * a new migration took the alpha deployment down on {@code 527ff8c18}.
 *
 * <p>A schema change goes in a new {@code V<n>__*.sql}. That is the only correct answer here, and
 * it is why this test pins the file rather than merely checking that the columns exist: reading the
 * baseline and a migration in order is the intended way to see the current schema, and editing the
 * baseline to shortcut that reading is what breaks the deployment.
 *
 * <p>If this test fails, do not update the expected digest — move the change into a new versioned
 * migration and restore the baseline. The digest may only change if the baseline is re-flattened,
 * which is a deliberate operation that resets every environment's history.
 */
class BaselineMigrationImmutabilityTest {

    private static final String BASELINE = "db/migration/V1__baseline_shop_manager.sql";

    /** SHA-256 of the baseline as alpha applied it. */
    private static final String EXPECTED_SHA256 = "edb349156530c9dee897c4eb28446b607be6e6f5b9c1d4453204299ec1bf2019";

    @Test
    @DisplayName("the applied baseline migration is unchanged")
    void baseline_isUnchanged() throws Exception {
        assertThat(sha256Of(BASELINE))
                .as(
                        "%s is already applied on alpha; put schema changes in a new versioned migration"
                                + " instead of editing it",
                        BASELINE)
                .isEqualTo(EXPECTED_SHA256);
    }

    private static String sha256Of(String classpathResource) throws Exception {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            byte[] content = in.readAllBytes();
            // Normalize line endings so a checkout on Windows does not fail this on whitespace alone.
            String text = new String(content, StandardCharsets.UTF_8).replace("\r\n", "\n");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
