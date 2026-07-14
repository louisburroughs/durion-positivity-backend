package com.positivity.securityservice.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.controller.RoleController;
import com.positivity.securityservice.internal.controller.UserController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Contract test for pos-people-contact's static service-to-service grant (#880).
 *
 * <p>pos-people-contact's {@code SecurityServiceClient} sends a fixed
 * {@code X-Authorities: security:user:view,security:role:view,security:role:assign} header and
 * calls the endpoints pinned below — including role revocation, which this service guards with
 * {@code security:role:assign} (there is no separate revoke authority). If one of these
 * endpoints starts requiring an authority outside that grant, this test fails at build time
 * instead of the client 403ing at runtime. The mirror test lives in pos-people-contact
 * ({@code SecurityServiceAuthoritiesContractTest}).
 */
class PeopleContactClientAuthoritiesTest {

    /** The exact grant pos-people-contact sends; keep in sync with its RestClientConfig. */
    private static final Set<String> PEOPLE_CONTACT_GRANT =
            Set.of("security:user:view", "security:role:view", "security:role:assign");

    @Test
    @DisplayName("Every endpoint pos-people-contact calls requires an authority inside its static grant")
    void endpointsUsedByPeopleContactStayInsideTheGrant() {
        assertAuthorityInGrant(UserController.class, "getAllUsers");
        assertAuthorityInGrant(RoleController.class, "getRoleByName");
        assertAuthorityInGrant(RoleController.class, "getAllRoles");
        assertAuthorityInGrant(RoleController.class, "getUserRoleAssignments");
        assertAuthorityInGrant(RoleController.class, "createRoleAssignment");
        // Revocation path: DELETE /v1/roles/assignments/{assignmentId} — guarded by
        // security:role:assign, not a distinct revoke authority (#880 finding).
        assertAuthorityInGrant(RoleController.class, "revokeRoleAssignment");
    }

    private static void assertAuthorityInGrant(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(controller.getSimpleName() + "#" + methodName
                        + " no longer exists — update the" + " pos-people-contact client contract (#880)"));
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize)
                .as("%s#%s must declare @PreAuthorize", controller.getSimpleName(), methodName)
                .isNotNull();
        String expression = preAuthorize.value();
        // Every authority referenced by the expression must be inside the grant: an expression
        // like hasAuthority('a') and hasAuthority('b') with b outside the grant would 403 the
        // client at runtime even though a is granted.
        Set<String> required = new HashSet<>();
        Matcher matcher = Pattern.compile("'([^']+)'").matcher(expression);
        while (matcher.find()) {
            required.add(matcher.group(1));
        }
        assertThat(required)
                .as(
                        "%s#%s declares no authorities in its @PreAuthorize expression: %s",
                        controller.getSimpleName(), methodName, expression)
                .isNotEmpty();
        assertThat(required)
                .as(
                        "%s#%s requires %s, which is outside pos-people-contact's static grant %s —"
                                + " update the client's X-Authorities header (see"
                                + " pos-people-contact RestClientConfig, #880)",
                        controller.getSimpleName(), methodName, expression, PEOPLE_CONTACT_GRANT)
                .isSubsetOf(PEOPLE_CONTACT_GRANT);
    }
}
