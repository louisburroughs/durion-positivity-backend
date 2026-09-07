package com.positivity.securityservice.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins ADR-0061 §1 (#1875): a role assignment is effective-dated user → role and carries no
 * location scope of its own. The scope model that used to live here ({@code scopeType},
 * {@code scopeLocationIds}, {@code coversLocation}) was deleted rather than migrated, and this
 * test is what stops it from quietly coming back.
 */
@DisplayName("RoleAssignment carries no location scope (ADR-0061 §1)")
class RoleAssignmentTest {

    private static final List<String> RETIRED_FIELDS = List.of("scopeType", "scopeLocationIds");
    private static final List<String> RETIRED_METHODS =
            List.of("coversLocation", "getScopeType", "setScopeType", "getScopeLocationIds", "setScopeLocationIds");

    @Test
    @DisplayName("no scope field survives on the entity")
    void noScopeFields() {
        List<String> fields = Arrays.stream(RoleAssignment.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fields).doesNotContainAnyElementsOf(RETIRED_FIELDS);
        assertThat(fields)
                .as("the effective-dating columns are what role_assignments keeps")
                .contains("effectiveStartDate", "effectiveEndDate", "revokedAt");
    }

    @Test
    @DisplayName("no scope accessor or coversLocation survives on the entity")
    void noScopeMethods() {
        List<String> methods = Arrays.stream(RoleAssignment.class.getMethods())
                .map(Method::getName)
                .toList();

        assertThat(methods).doesNotContainAnyElementsOf(RETIRED_METHODS);
    }

    @Test
    @DisplayName("nothing maps role_assignment_scope_locations any more")
    void noElementCollectionMapping() {
        List<Field> elementCollections = Arrays.stream(RoleAssignment.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ElementCollection.class)
                        || field.isAnnotationPresent(CollectionTable.class))
                .toList();

        assertThat(elementCollections)
                .as("V38 dropped role_assignment_scope_locations; no mapping may reference it")
                .isEmpty();
    }
}
