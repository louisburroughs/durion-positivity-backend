package com.positivity.customer.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link CrmPermissionRegistry}.
 *
 * <p>
 * Permission names are the strings controllers enforce with
 * {@code @PreAuthorize("hasAuthority(...)")} and that {@code pos-security-service}
 * stores as the authority catalog. A typo in a name is not a compile error on
 * either side — it just produces a permission nobody holds, which fails closed
 * and locks users out of an endpoint at runtime rather than at build time.
 *
 * <p>
 * These tests therefore treat the naming convention (`domain:resource:action`,
 * snake_case, always under the {@code crm} domain) as a hard contract, and check
 * that every declared constant actually appears in the registration payload —
 * otherwise a permission could be enforced by a controller but never registered,
 * with the same lock-out effect.
 */
@DisplayName("CrmPermissionRegistry — permission naming and registration contract")
class CrmPermissionRegistryTest {

    /**
     * {@code domain:resource:action}, all lower snake_case. Documented in
     * CLAUDE.md and mirrored by every other module's registry.
     */
    private static final Pattern PERMISSION_NAME = Pattern.compile("^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$");

    private static final Set<String> VALID_RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /** Every {@code public static final String} constant on the registry. */
    private static List<String> declaredConstants() {
        List<String> values = new ArrayList<>();
        for (Field field : CrmPermissionRegistry.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Could not read " + field.getName(), e);
                }
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> registeredPermissions() {
        return (List<Map<String, String>>)
                CrmPermissionRegistry.buildCrmPermissionRegistration().get("permissions");
    }

    @Test
    @DisplayName("declares permission constants")
    void registry_declaresConstants() {
        assertThat(declaredConstants()).isNotEmpty();
    }

    @Test
    @DisplayName("every declared constant follows domain:resource:action in snake_case")
    void constants_followNamingConvention() {
        assertThat(declaredConstants())
                .allSatisfy(name ->
                        assertThat(name).as("permission constant %s", name).matches(PERMISSION_NAME));
    }

    @Test
    @DisplayName("every declared constant sits under the crm domain")
    void constants_areScopedToCrmDomain() {
        assertThat(declaredConstants())
                .allSatisfy(name -> assertThat(name).as("permission %s", name).startsWith("crm:"));
    }

    @Test
    @DisplayName("no two constants share a permission name")
    void constants_areUnique() {
        assertThat(declaredConstants()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the registration payload names the crm domain and this service")
    void registration_identifiesDomainAndService() {
        Map<String, Object> registration = CrmPermissionRegistry.buildCrmPermissionRegistration();

        assertThat(registration).containsEntry("domain", "crm").containsEntry("serviceName", "pos-customer");
        assertThat(registration.get("version")).asString().isNotBlank();
    }

    @Test
    @DisplayName("every registered permission carries a name, a description, and a risk level")
    void registration_entriesAreComplete() {
        assertThat(registeredPermissions()).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.get("name")).as("name in %s", entry).isNotBlank();
            assertThat(entry.get("description"))
                    .as("description for %s", entry.get("name"))
                    .isNotBlank();
            assertThat(entry.get("riskLevel"))
                    .as("risk level for %s", entry.get("name"))
                    .isIn(VALID_RISK_LEVELS);
        });
    }

    @Test
    @DisplayName("registered permission names match the declared constants, so none is enforced but unregistered")
    void registration_coversEveryDeclaredConstant() {
        List<String> registered =
                registeredPermissions().stream().map(entry -> entry.get("name")).toList();

        // A constant a controller enforces but that is never registered fails
        // closed at runtime: nobody can hold it, so the endpoint is unreachable.
        assertThat(registered).containsAll(declaredConstants());
    }

    @Test
    @DisplayName("no permission is registered twice")
    void registration_hasNoDuplicateNames() {
        assertThat(registeredPermissions().stream()
                        .map(entry -> entry.get("name"))
                        .toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the grouped accessors only return permissions the registry declares")
    void groupedAccessors_returnDeclaredPermissions() {
        List<String> declared = declaredConstants();

        assertThat(CrmPermissionRegistry.partyPermissions())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(declared).contains(name));
        assertThat(CrmPermissionRegistry.contactPermissions())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(declared).contains(name));
        assertThat(CrmPermissionRegistry.vehiclePermissions())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(declared).contains(name));
        assertThat(CrmPermissionRegistry.integrationPermissions())
                .isNotEmpty()
                .allSatisfy(name -> assertThat(declared).contains(name));
    }

    @Test
    @DisplayName("each grouped accessor stays within the resource family its name implies")
    void groupedAccessors_areScopedToTheirFamily() {
        assertThat(CrmPermissionRegistry.partyPermissions())
                .allSatisfy(name -> assertThat(name).startsWith("crm:party"));
        assertThat(CrmPermissionRegistry.vehiclePermissions())
                .allSatisfy(name -> assertThat(name).startsWith("crm:vehicle"));
    }
}
