package com.positivity.supplier.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.migration.VendorTaxRegistrationEncryptionMigration;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * #2621: the application's own Flyway bean ({@code FlywayConfig}, which replaces Boot's auto-configuration) is
 * handed the {@code V4} Java migration. Without it, production would apply V5 and V6 and skip V4, leaving every
 * stored number in clear.
 */
@DisplayName("The application's Flyway runs the V4 Java migration (#2621)")
class VendorTaxRegistrationMigrationWiringTest extends PostgresTenancyTestBase {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("V4 is configured on the application's Flyway and recorded as applied")
    void v4IsWired() {
        assertThat(Arrays.stream(flyway.getConfiguration().getJavaMigrations())
                        .map(Object::getClass)
                        .toList())
                .contains(VendorTaxRegistrationEncryptionMigration.class);
        assertThat(Arrays.stream(flyway.info().applied())
                        .map(info -> info.getVersion().getVersion())
                        .toList())
                .contains("4", "5", "6");
    }
}
