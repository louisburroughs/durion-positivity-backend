package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.PlatformTenant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the platform-template fix to {@code R__seed_location_2_bay_specialty.sql}: provisioning
 * (DECISION-LOCATION-025) reads the specialty map under {@code PlatformTenant.ID}, so the seed must
 * actually write rows there — not only under the alpha default tenant (a real regression this
 * guards: the seed originally bound only to alpha, leaving the platform tenant empty and every new
 * tenant's copied map silent-empty). It also pins the two tenants' rows to different id formulas, so
 * they can never collide under {@code bay_specialty_operation}'s {@code id}-alone primary key (V4).
 */
class BaySpecialtyTemplateSeedTest {

    private static final String ALPHA_TENANT_ID = "01900000-0000-7000-8000-000000000001";

    private static final Pattern INSERT_STATEMENT =
            Pattern.compile("INSERT INTO bay_specialty_operation", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("The seed binds both the alpha default tenant and the platform tenant")
    void seedBindsBothTenants() throws IOException {
        String seed = read("db/migration/R__seed_location_2_bay_specialty.sql");

        assertThat(seed).contains(ALPHA_TENANT_ID);
        assertThat(seed).contains(PlatformTenant.ID.toString());
    }

    @Test
    @DisplayName("There are two INSERT INTO bay_specialty_operation statements: one per tenant")
    void seedWritesTwoInsertStatements() throws IOException {
        String seed = read("db/migration/R__seed_location_2_bay_specialty.sql");

        Matcher matcher = INSERT_STATEMENT.matcher(seed);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertThat(count)
                .as("INSERT INTO bay_specialty_operation statement count")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("The platform tenant's ids are tenant-qualified, distinct from the alpha rows' formula")
    void platformIdsAreTenantQualified() throws IOException {
        String seed = read("db/migration/R__seed_location_2_bay_specialty.sql");

        // The alpha (unchanged) formula: no tenant in the hash.
        assertThat(seed).contains("md5('bso:' || t.bay_type || ':' || t.operation_code)");
        // The platform formula: tenant-qualified, so it can never produce the same id as the row
        // above for the same (bay_type, operation_code) pair.
        assertThat(seed).contains("md5('bso:' || platform::text || ':' || t.bay_type || ':' || t.operation_code)");
    }

    @Test
    @DisplayName("The seed is idempotent: every INSERT ends in an ON CONFLICT clause")
    void everyInsertIsIdempotent() throws IOException {
        String seed = read("db/migration/R__seed_location_2_bay_specialty.sql");

        long onConflictCount = seed.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .filter(line -> line.contains("ON CONFLICT"))
                .count();
        assertThat(onConflictCount).isEqualTo(2);
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
