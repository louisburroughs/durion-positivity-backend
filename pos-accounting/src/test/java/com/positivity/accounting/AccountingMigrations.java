package com.positivity.accounting;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Flyway locations for migration tests that stop at an older version.
 *
 * <p>Flyway applies repeatable migrations whatever the target, and the repeatable template seed now names
 * schema V11 adds (#2511: the {@code CASH_ON_HAND} subtype, {@code petty_expense_category}). A test that
 * migrates to an older version therefore runs that release as it shipped: versioned migrations up to the
 * target and the template seed as it was before #2511 ({@code db/legacy/}). A later full migrate from
 * {@code classpath:db/migration} then applies the rest and the current seed, as an upgrade does.
 */
public final class AccountingMigrations {

    private AccountingMigrations() {}

    /** A filesystem location holding V1..V{@code target} and the pre-#2511 template seed. */
    public static String releasedUpTo(int target) {
        try {
            Path directory = Files.createTempDirectory("accounting-migrations-v" + target);
            for (Resource migration :
                    new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql")) {
                String name = migration.getFilename();
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                if (version <= target) {
                    try (InputStream in = migration.getInputStream()) {
                        Files.copy(in, directory.resolve(name));
                    }
                }
            }
            try (InputStream in =
                    new ClassPathResource("db/legacy/R__seed_reference_accounting_before_2511.sql").getInputStream()) {
                Files.copy(in, directory.resolve("R__seed_reference_accounting.sql"));
            }
            return "filesystem:" + directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
