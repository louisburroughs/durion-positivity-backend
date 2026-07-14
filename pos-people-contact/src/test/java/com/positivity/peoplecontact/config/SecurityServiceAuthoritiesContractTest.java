package com.positivity.peoplecontact.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.peoplecontact.internal.config.RestClientConfig;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for the static service-to-service grant sent to pos-security-service (#880).
 *
 * <p>The client performs six operations; pos-security-service enforces one authority per
 * endpoint (see the {@code SECURITY_SERVICE_AUTHORITIES} javadoc for the mapping). This test
 * pins the header set so a header edit that drops a required authority — or an operation added
 * without extending the grant — fails here instead of 403ing at runtime. The mirror test in
 * pos-security-service ({@code PeopleContactClientAuthoritiesTest}) pins the other side: the
 * endpoints this module calls must not start requiring authorities outside this grant.
 */
class SecurityServiceAuthoritiesContractTest {

    /** One authority per operation class the client performs — keep in sync with the javadoc. */
    private static final Set<String> REQUIRED_AUTHORITIES = Set.of(
            // GET /v1/users
            "security:user:view",
            // GET /v1/roles, GET /v1/roles/by-name/{name}, GET /v1/roles/assignments/user/{userId}
            "security:role:view",
            // POST /v1/roles/assignments and DELETE /v1/roles/assignments/{assignmentId} (revoke)
            "security:role:assign");

    @Test
    @DisplayName("The static X-Authorities grant covers every operation the client performs, revoke included")
    void grantCoversAllClientOperations() {
        Set<String> granted = Set.of(RestClientConfig.SECURITY_SERVICE_AUTHORITIES.split(","));

        assertThat(granted).containsExactlyInAnyOrderElementsOf(REQUIRED_AUTHORITIES);
    }
}
