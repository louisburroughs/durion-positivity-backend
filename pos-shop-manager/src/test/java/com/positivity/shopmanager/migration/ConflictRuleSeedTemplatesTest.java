package com.positivity.shopmanager.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.SchedulingWorldFixture;
import com.positivity.shopmanager.internal.entity.ConflictRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The seeded {@code conflict_rule} message templates, which are the refusal text a caller reads.
 *
 * <p>Three things are pinned here, all of them defects this module has already shipped:
 *
 * <ul>
 *   <li>Every template that quotes a window names the zone it was rendered in (#2139). A booking
 *       sent as {@code 09:00Z} and refused for "04:00–05:00" reads as a platform arithmetic error;
 *       it was the correct facility-local conversion (DECISION-SHOPMGMT-015), and the missing zone
 *       is the whole reason that took a dead accelerated run to establish. The assertion is over
 *       every template rather than the one code, so a rule added later cannot reintroduce it.
 *   <li>{@code MECHANIC_UNAVAILABLE} states the question the rule asks — whether a technician
 *       staffing assignment covers that facility-local date — rather than asserting that nobody is
 *       in the building (#2140). Those differ in a way the caller must act on: a shop with seven
 *       technicians and no assignment effective on the date asked about is staffed.
 *   <li>{@link SchedulingWorldFixture} carries the same templates as the migration. The fixture is
 *       what the unit and contract tests render, so a template corrected in one place and not the
 *       other leaves every message assertion in the module testing a string production does not
 *       send.
 * </ul>
 */
@DisplayName("conflict_rule seed templates")
class ConflictRuleSeedTemplatesTest {

    private static final String MIGRATION = "db/migration/R__seed_shop_manager_1_conflict_rules.sql";

    /**
     * One {@code VALUES} tuple: code, severity, resource type, template, active flag. The template
     * group tolerates SQL's doubled single quote, which is how the migration writes an apostrophe.
     */
    private static final Pattern SEEDED_RULE =
            Pattern.compile("\\('([A-Z_]+)',\\s*'(HARD|SOFT)',\\s*'([A-Z]+)',\\s*'((?:[^']|'')*)',\\s*(true|false)\\)");

    private static Map<String, SeededRule> seeded;

    @BeforeAll
    static void parseMigration() throws IOException {
        seeded = new LinkedHashMap<>();
        Matcher matcher = SEEDED_RULE.matcher(migrationSql());
        while (matcher.find()) {
            seeded.put(
                    matcher.group(1),
                    new SeededRule(
                            matcher.group(1),
                            matcher.group(2),
                            matcher.group(3),
                            matcher.group(4).replace("''", "'"),
                            Boolean.parseBoolean(matcher.group(5))));
        }
        assertThat(seeded).as("the migration's eight-rule catalog was parsed").hasSize(8);
    }

    @Test
    @DisplayName("#2139: every template quoting a window names the zone the times are in")
    void everyWindowTemplateNamesTheZone() {
        assertThat(seeded.values())
                .filteredOn(rule ->
                        rule.template().contains("{start}") || rule.template().contains("{end}"))
                .isNotEmpty()
                .allSatisfy(rule -> assertThat(rule.template())
                        .as("%s quotes a window; it must name the zone", rule.code())
                        .contains("{zone}"));
    }

    @Test
    @DisplayName("#2140: the mechanic refusal names the staffing assignment, not presence")
    void mechanicUnavailableNamesTheAssignmentRatherThanPresence() {
        String template = seeded.get("MECHANIC_UNAVAILABLE").template();

        assertThat(template).contains("staffing assignment").contains("{reason}");
        assertThat(template).doesNotContain("is present");
    }

    @Test
    @DisplayName("the test fixture seeds the templates the migration does")
    void theFixtureMirrorsTheMigration() {
        Map<String, ConflictRule> fixture = new LinkedHashMap<>();
        SchedulingWorldFixture.conflictRules().forEach(rule -> fixture.put(rule.getCode(), rule));

        assertThat(fixture.keySet()).containsExactlyInAnyOrderElementsOf(seeded.keySet());
        seeded.forEach((code, rule) -> {
            ConflictRule mirrored = fixture.get(code);
            assertThat(mirrored.getMessageTemplate()).as("%s template", code).isEqualTo(rule.template());
            assertThat(mirrored.getSeverity().name()).as("%s severity", code).isEqualTo(rule.severity());
            assertThat(mirrored.getResourceType().name())
                    .as("%s resource type", code)
                    .isEqualTo(rule.resourceType());
            assertThat(mirrored.isActive()).as("%s active flag", code).isEqualTo(rule.active());
        });
    }

    private static String migrationSql() throws IOException {
        try (InputStream stream = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SeededRule(String code, String severity, String resourceType, String template, boolean active) {}
}
