package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the seeded travel buffer policies to the buffer types the API accepts (#2249): the seed once
 * wrote {@code MINUTES}, which no client could select or PATCH back. The V6 CHECK constraint now
 * refuses such a row in the database too, so this also keeps the seed and the constraint from
 * drifting apart.
 */
class TravelBufferPolicySeedTest {

    private static final Pattern POLICY_INSERT = Pattern.compile(
            "INSERT INTO travel_buffer_policies \\([^)]*\\)\\s*VALUES \\('[^']*'::uuid, '([^']*)', '([^']*)'",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("#2249 - every seeded policy uses a buffer type the API accepts")
    void seededBufferTypesAreSupported() throws IOException {
        String seed = read("db/migration/R__seed_location_1_reference.sql");

        List<String> types = new ArrayList<>();
        Matcher matcher = POLICY_INSERT.matcher(seed);
        while (matcher.find()) {
            types.add(matcher.group(2));
        }

        assertThat(types).as("seeded travel buffer policy types").hasSize(3);
        assertThat(types)
                .allSatisfy(type -> assertThat(TravelBufferPolicyServiceImpl.SUPPORTED_BUFFER_TYPES)
                        .contains(type));
    }

    @Test
    @DisplayName("#2249 - the buffer_type CHECK allows exactly the types the API accepts")
    void checkConstraintMatchesSupportedTypes() throws IOException {
        // V6 first constrained buffer_type; #2266/DECISION-LOCATION-028 narrowed the accepted set
        // again in V11, which is the CHECK now live in the database. The migration text still
        // mentions the retired types in its header comment and in the UPDATE statements that
        // convert old rows away from them (the V6 CHECK is dropped ahead of those, so the constraint
        // name appears before them too), so only the ADD CONSTRAINT clause itself is asserted
        // against the supported set.
        String migration = read("db/migration/V11__distance_units_and_travel_buffer_policy_types.sql");
        int addConstraint = migration.indexOf("ADD CONSTRAINT travel_buffer_policies_buffer_type_check");
        assertThat(addConstraint)
                .as("V11 re-adds travel_buffer_policies_buffer_type_check")
                .isNotNegative();
        String checkClause = migration.substring(addConstraint);

        assertThat(TravelBufferPolicyServiceImpl.SUPPORTED_BUFFER_TYPES)
                .allSatisfy(type -> assertThat(checkClause).contains("'" + type + "'"));
        assertThat(checkClause)
                .doesNotContain("'FLAT_MINUTES'")
                .doesNotContain("'PERCENTAGE_OF_TRAVEL'")
                .doesNotContain("'DISTANCE_MULTIPLIER'")
                .doesNotContain("IN ('MINUTES'");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
