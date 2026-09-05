package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #1735: conversation memory was keyed on {@code (username, role)} alone, so every request from one
 * actor shared a single history — the analytics gate's twelve questions ran as one twelve-turn
 * conversation rather than twelve independent single-turn tests.
 *
 * <p>These assert the key itself rather than replayed memory. An earlier draft tried to prove
 * isolation by capturing the {@code Prompt} and checking whether a prior message appeared in it;
 * that test passed, but so did the same assertion with the fix reverted — the mocked chat model
 * never exercises the memory advisor, so the prompt contains no history either way. A test that
 * cannot fail proves nothing, so the assertion moved to the one thing this change actually decides.
 */
@DisplayName("SessionAgentManager memory key — partitioning a conversation beneath the actor")
class SessionMemoryKeyTest {

    @Test
    @DisplayName("no conversation id keeps the pre-#1735 key, so existing callers are unaffected")
    void nullIdIsBackwardCompatible() {
        assertThat(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", null)).isEqualTo("user-1::ROLE_ADMIN");
    }

    @Test
    @DisplayName("a blank id is treated as absent rather than as a distinct conversation")
    void blankIdIsTreatedAsAbsent() {
        assertThat(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", "   ")).isEqualTo("user-1::ROLE_ADMIN");
    }

    @Test
    @DisplayName("an id partitions the memory beneath the same actor and role")
    void idPartitionsBeneathActor() {
        assertThat(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", "gate-q01"))
                .isEqualTo("user-1::ROLE_ADMIN::gate-q01");
    }

    @Test
    @DisplayName("two ids give two keys — what makes the gate's questions independent")
    void distinctIdsDoNotCollide() {
        assertThat(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", "gate-q01"))
                .isNotEqualTo(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", "gate-q02"));
    }

    @Test
    @DisplayName("the same id under different roles stays separate, as it did before")
    void roleStillPartitions() {
        assertThat(SessionAgentManager.memoryKey("user-1", "ROLE_ADMIN", "c"))
                .isNotEqualTo(SessionAgentManager.memoryKey("user-1", "ROLE_CASHIER", "c"));
    }
}
