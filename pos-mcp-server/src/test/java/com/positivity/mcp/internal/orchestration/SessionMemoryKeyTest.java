package com.positivity.mcp.internal.orchestration;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1735: conversation memory was keyed on {@code (username, role)} alone, so every request from one
 * actor shared a single history — the analytics gate's twelve questions ran as one twelve-turn
 * conversation rather than twelve independent single-turn tests. ADR-0062 plan WS6 (R-B6) then put
 * the tenant in front: a username is unique within a tenant only, so without it the same login in
 * two tenants would share one history and one rate budget.
 *
 * <p>These assert the key itself rather than replayed memory. An earlier draft tried to prove
 * isolation by capturing the {@code Prompt} and checking whether a prior message appeared in it;
 * that test passed, but so did the same assertion with the fix reverted — the mocked chat model
 * never exercises the memory advisor, so the prompt contains no history either way. A test that
 * cannot fail proves nothing, so the assertion moved to the one thing this change actually decides.
 */
@DisplayName("SessionAgentManager memory key — partitioning a conversation beneath the tenant and the actor")
class SessionMemoryKeyTest {

    private static final String A = TENANT_A.toString();

    @Test
    @DisplayName("no conversation id keeps the pre-#1735 shape beneath the tenant, so existing callers are unaffected")
    void nullIdIsBackwardCompatible() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", null))
                .isEqualTo(A + "::user-1::ROLE_ADMIN");
    }

    @Test
    @DisplayName("a blank id is treated as absent rather than as a distinct conversation")
    void blankIdIsTreatedAsAbsent() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "   "))
                .isEqualTo(A + "::user-1::ROLE_ADMIN");
    }

    @Test
    @DisplayName("an id partitions the memory beneath the same actor and role")
    void idPartitionsBeneathActor() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "gate-q01"))
                .isEqualTo(A + "::user-1::ROLE_ADMIN::gate-q01");
    }

    @Test
    @DisplayName("two ids give two keys — what makes the gate's questions independent")
    void distinctIdsDoNotCollide() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "gate-q01"))
                .isNotEqualTo(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "gate-q02"));
    }

    @Test
    @DisplayName("the same id under different roles stays separate, as it did before")
    void roleStillPartitions() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_ADMIN", "c"))
                .isNotEqualTo(SessionAgentManager.memoryKey(TENANT_A, "user-1", "ROLE_CASHIER", "c"));
    }

    @Test
    @DisplayName("the same username, role and conversation in two tenants are two conversations (ADR-0062 WS6)")
    void tenantPartitionsTheSameActor() {
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "admin", "ROLE_ADMIN", "c"))
                .isNotEqualTo(SessionAgentManager.memoryKey(TENANT_B, "admin", "ROLE_ADMIN", "c"));
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "admin", "ROLE_ADMIN", null))
                .isNotEqualTo(SessionAgentManager.memoryKey(TENANT_B, "admin", "ROLE_ADMIN", null));
    }

    @Test
    @DisplayName("the actor key leads every memory key, so evicting an actor cannot reach another tenant's user")
    void actorKeyLeadsAndCarriesTheTenant() {
        String actor = SessionAgentManager.actorKey(TENANT_A, "admin");
        assertThat(actor).isEqualTo(A + "::admin");
        assertThat(SessionAgentManager.memoryKey(TENANT_A, "admin", "ROLE_ADMIN", "c"))
                .startsWith(actor + "::");
        assertThat(SessionAgentManager.memoryKey(TENANT_B, "admin", "ROLE_ADMIN", "c"))
                .doesNotStartWith(actor + "::");
    }

    @Test
    @DisplayName("the streaming manager keys the same way")
    void streamingManagerKeysBeneathTheTenant() {
        assertThat(StreamingSessionAgentManager.memoryKey(TENANT_A, "admin", "ROLE_ADMIN"))
                .isEqualTo(A + "::admin::ROLE_ADMIN")
                .isNotEqualTo(StreamingSessionAgentManager.memoryKey(TENANT_B, "admin", "ROLE_ADMIN"));
        assertThat(StreamingSessionAgentManager.actorKey(TENANT_A, "admin")).isEqualTo(A + "::admin");
    }
}
