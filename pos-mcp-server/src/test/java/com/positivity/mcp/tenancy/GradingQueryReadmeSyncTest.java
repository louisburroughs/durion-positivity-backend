package com.positivity.mcp.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sync guard (#2075): the grading join documented in {@code README.md} must be the exact text
 * {@link ConversationPersistenceIT#GRADING_QUERY} executes, so a hand-edit to either one cannot
 * drift from the other without failing a test. Reads the README relative to the module directory
 * ({@code user.dir} at Surefire/Failsafe run time), the same convention as {@code
 * FacadeToolPermissionSeedTest}'s migration-file reads.
 */
@DisplayName("README grading query stays in sync with ConversationPersistenceIT.GRADING_QUERY (#2075)")
class GradingQueryReadmeSyncTest {

    private static final Path README = Paths.get(System.getProperty("user.dir"), "README.md");
    private static final String FENCE_START = "```sql\nSELECT m.tenant_id";

    @Test
    @DisplayName("the README's grading-join SQL fence matches GRADING_QUERY verbatim")
    void readmeGradingQuery_matchesTheExecutedQueryVerbatim() throws IOException {
        assertThat(README).as("README.md must exist at the module root").exists();
        String readme = Files.readString(README);

        int fenceStart = readme.indexOf(FENCE_START);
        assertThat(fenceStart)
                .as("the README must contain the grading join's fenced ```sql block, starting with "
                        + "\"SELECT m.tenant_id\"")
                .isNotEqualTo(-1);
        int contentStart = fenceStart + "```sql\n".length();
        int fenceEnd = readme.indexOf("```", contentStart);
        assertThat(fenceEnd).as("the fenced SQL block must be closed").isNotEqualTo(-1);
        String readmeQuery = readme.substring(contentStart, fenceEnd);

        assertThat(readmeQuery.strip())
                .as("README.md's documented grading join and ConversationPersistenceIT.GRADING_QUERY "
                        + "must be the identical SQL text")
                .isEqualTo(ConversationPersistenceIT.GRADING_QUERY.strip());
    }
}
