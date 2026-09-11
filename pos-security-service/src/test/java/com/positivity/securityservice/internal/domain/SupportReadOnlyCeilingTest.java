package com.positivity.securityservice.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The read-only ceiling of an impersonation token (ADR-0062 §7, WS2b-4). */
class SupportReadOnlyCeilingTest {

    @Test
    @DisplayName("admits view/read actions and location:read; refuses writes, platform:* and the exclusions")
    void rule() {
        assertThat(SupportReadOnlyCeiling.admits("crm:party:view")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("catalog:item_cost:read")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("accounting:credit-memo:read")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("location:read")).isTrue();

        assertThat(SupportReadOnlyCeiling.admits("security:user:delete")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("order:order:create")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("workorder:workorder:start")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("platform:tenant:read")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("platform:tenant:impersonate")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("mcp:chat:execute")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("nlti:request:submit")).isFalse();
        assertThat(SupportReadOnlyCeiling.admits(null)).isFalse();
        assertThat(SupportReadOnlyCeiling.admits("")).isFalse();
        for (String excluded : SupportReadOnlyCeiling.EXCLUDED) {
            assertThat(SupportReadOnlyCeiling.admits(excluded))
                    .as("%s is a read the ceiling refuses on purpose", excluded)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("no permission the ceiling admits has a write action, anywhere in the catalog")
    void nothingAdmittedIsAWrite() {
        Set<String> admitted = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::code)
                .filter(SupportReadOnlyCeiling::admits)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(admitted).isNotEmpty();
        assertThat(admitted)
                .allMatch(code -> code.endsWith(":view") || code.endsWith(":read"))
                .noneMatch(code -> code.startsWith("platform:"))
                .doesNotContainAnyElementsOf(SupportReadOnlyCeiling.EXCLUDED);
    }

    @Test
    @DisplayName("camelCase segments are admitted: the catalog really contains them (regression)")
    void camelCaseReadsAreAdmitted() {
        // An all-lowercase segment pattern silently refused these, so every impersonation token
        // was minted without them even though the SUPPORT role granted them.
        assertThat(SupportReadOnlyCeiling.admits("people:timeAdjustment:view")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("people:timeEntry:view")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("people:timeException:view")).isTrue();
        assertThat(SupportReadOnlyCeiling.admits("people-contact:userLink:view"))
                .isTrue();
        assertThat(SupportReadOnlyCeiling.admits("bulkImport:status:read")).isTrue();
        // Mixed case does not weaken the action half: a camelCase write is still a write.
        assertThat(SupportReadOnlyCeiling.admits("people:timeAdjustment:approve"))
                .isFalse();
        assertThat(SupportReadOnlyCeiling.admits("people-contact:userLink:write"))
                .isFalse();
    }

    @Test
    @DisplayName("every three-segment view/read in the catalog is admitted unless deliberately refused")
    void theCatalogsReadsAreAdmitted() {
        Set<String> refused = Arrays.stream(PermissionCode.values())
                .map(PermissionCode::code)
                .filter(code -> code.endsWith(":view") || code.endsWith(":read"))
                .filter(code -> code.chars().filter(c -> c == ':').count() == 2)
                .filter(code -> !code.startsWith("platform:"))
                .filter(code -> !SupportReadOnlyCeiling.EXCLUDED.contains(code))
                .filter(code -> !SupportReadOnlyCeiling.admits(code))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(refused)
                .as("a read the catalog defines but the ceiling's shape refuses is a token that reads too little")
                .isEmpty();
    }

    @Test
    @DisplayName("apply keeps the reads and reports the rest by code, sorted")
    void applyPartitions() {
        SupportReadOnlyCeiling.Result result = SupportReadOnlyCeiling.apply(Set.of(
                PermissionCode.CRM__PARTY__VIEW,
                PermissionCode.ORDER__ORDER__VIEW,
                PermissionCode.SECURITY__USER__DELETE,
                PermissionCode.PEOPLE__EMPLOYEE_PII__VIEW,
                PermissionCode.PLATFORM__TENANT__IMPERSONATE));

        assertThat(result.admitted())
                .containsExactlyInAnyOrder(PermissionCode.CRM__PARTY__VIEW, PermissionCode.ORDER__ORDER__VIEW);
        assertThat(result.dropped())
                .containsExactlyInAnyOrder(
                        "people:employee_pii:view", "platform:tenant:impersonate", "security:user:delete");
        assertThat(SupportReadOnlyCeiling.apply(Set.of()).dropped()).isEmpty();
    }
}
