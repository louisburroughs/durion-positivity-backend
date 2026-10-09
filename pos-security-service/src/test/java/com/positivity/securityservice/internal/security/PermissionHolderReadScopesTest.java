package com.positivity.securityservice.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2669 decision D2: the read-scope map holds exactly one entry — the AP approval policy governing
 * the five AP decision codes. Adding an entry is a security-domain decision; this test is the tripwire
 * that makes one visible in review.
 */
@DisplayName("PermissionHolderReadScopes — the D2 scope map (#2669)")
class PermissionHolderReadScopesTest {

    private static final Set<String> AP_CODES = Set.of(
            "accounting:ap:approve",
            "accounting:ap:approve_over_limit",
            "accounting:ap:reject",
            "accounting:ap:pay",
            "accounting:ap_approval_policy:manage");

    @Test
    @DisplayName("the map holds exactly the five AP codes under accounting:ap_approval_policy:manage")
    void theMapHoldsExactlyTheFiveApCodesUnderThePolicyPermission() {
        assertThat(PermissionHolderReadScopes.scopes())
                .containsOnlyKeys("accounting:ap_approval_policy:manage")
                .containsEntry("accounting:ap_approval_policy:manage", AP_CODES);
    }

    @Test
    @DisplayName("a holder of the policy permission may read the five AP codes and nothing else")
    void aPolicyManagerReadsTheFiveApCodes() {
        assertThat(PermissionHolderReadScopes.readableCodes(
                        List.of("accounting:ap_approval_policy:manage", "accounting:ap:view")))
                .isEqualTo(AP_CODES);
    }

    @Test
    @DisplayName("a caller holding no governing permission may read nothing (deny by default)")
    void aCallerWithoutAGoverningPermissionReadsNothing() {
        assertThat(PermissionHolderReadScopes.readableCodes(List.of("accounting:ap:approve", "accounting:ap:pay")))
                .isEmpty();
        assertThat(PermissionHolderReadScopes.readableCodes(List.of())).isEmpty();
    }
}
