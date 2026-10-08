package com.positivity.supplier.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2621: Flyway V4 is a Java migration ({@code VendorTaxRegistrationEncryptionMigration}); it needs the vendor
 * tax-id key, which a SQL script cannot hold. A {@code V4__*.sql} beside it would collide with it (or, worse,
 * replace it in a context without the bean), so none may exist.
 */
@DisplayName("db/migration has no V4 SQL script: V4 is the Java key migration (#2621)")
class VendorTaxRegistrationMigrationHygieneTest {

    @Test
    @DisplayName("no V4__*.sql in db/migration, and the Java migration is version 4")
    void v4IsJavaOnly() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            List<String> v4Scripts = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("(?i)V0*4__.*"))
                    .toList();
            assertThat(v4Scripts)
                    .as("V4 is VendorTaxRegistrationEncryptionMigration")
                    .isEmpty();
        }
        assertThat(new com.positivity.supplier.internal.migration.VendorTaxRegistrationEncryptionMigration(
                                new com.positivity.supplier.internal.entity.VendorTaxIdCipher(
                                        testEnvironment(), "", "k1", ""),
                                new com.positivity.supplier.internal.config.SupplierEncryptionKeySeparation(
                                        "", "", "", ""))
                        .getVersion()
                        .getVersion())
                .isEqualTo("4");
    }

    private static org.springframework.mock.env.MockEnvironment testEnvironment() {
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        environment.setActiveProfiles("test");
        return environment;
    }
}
