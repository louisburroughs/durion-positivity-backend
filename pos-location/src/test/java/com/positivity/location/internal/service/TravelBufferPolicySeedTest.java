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
        String migration = read("db/migration/V6__travel_buffer_type_and_mobile_unit_status_and_name_keys.sql");

        assertThat(TravelBufferPolicyServiceImpl.SUPPORTED_BUFFER_TYPES)
                .allSatisfy(type -> assertThat(migration).contains("'" + type + "'"));
        assertThat(migration).doesNotContain("IN ('MINUTES'");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
