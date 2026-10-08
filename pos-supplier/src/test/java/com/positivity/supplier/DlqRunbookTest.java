package com.positivity.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2621 AC 18 (Security ruling on #2617, ruling 7): the runbook inspects DLQs by metadata only. A DLQ
 * record holds the whole original value, which may be CONFIDENTIAL or RESTRICTED, so no command reads
 * from the beginning with values or headers printed (dead-letter headers carry exception text, ADR-0072
 * Decision 5), and a value or headers are printed for one record only.
 */
@DisplayName("OPERATIONS_RUNBOOK DLQ inspection is metadata-only (#2621 AC 18)")
class DlqRunbookTest {

    private static final Pattern FENCED = Pattern.compile("```[a-z]*\\n(.*?)```", Pattern.DOTALL);

    private static List<String> consumerCommands() throws Exception {
        String runbook = Files.readString(Path.of("../docs/OPERATIONS_RUNBOOK.md"));
        List<String> commands = new ArrayList<>();
        Matcher block = FENCED.matcher(runbook);
        while (block.find()) {
            if (block.group(1).contains("kafka-console-consumer")) {
                commands.add(block.group(1));
            }
        }
        return commands;
    }

    /** CHK-006, as ruled on PR #2624 (issuecomment-6066876483): structure first, the full value only when unlogged. */
    @Test
    @DisplayName("CHK-006: one-record inspection is structure-only first, full value only in an unlogged session")
    void permittedTerminals() throws Exception {
        String runbook = Files.readString(Path.of("../docs/OPERATIONS_RUNBOOK.md"));
        assertThat(runbook)
                .contains("**Step 1: structure only (any SSM session, logged or not).**")
                .contains("jq missing: STOP here, do not print the raw value")
                .contains("Never fall back\nto printing the raw value")
                .contains("paths(scalars)")
                .contains("**Step 2: the full value or headers (only when step 1 is not enough).**")
                .contains("aws ssm get-document --name SSM-SessionManagerRunShell")
                .contains("`s3BucketName` and `cloudWatchLogGroupName` are both empty")
                .contains("never with a `--document-name` override")
                .contains("If logging is on,\n  full values are never printed on that host.")
                .contains("session-restore or saved-scrollback feature is off")
                .contains("**Record the inspection, never the value:**")
                .contains("an AI assistant's or agent's shell");
        // The step-1 filter prints the record's shape, never its value or headers.
        assertThat(consumerCommands())
                .filteredOn(command -> command.contains("paths(scalars)"))
                .singleElement()
                .satisfies(command -> assertThat(command)
                        .contains("--property print.headers=false")
                        .contains("--max-messages 1")
                        .doesNotContain("--from-beginning"));
    }

    @Test
    @DisplayName("ADR-0072 Decision 7: the DLQ cutoff is taken after consumers and retries drain, then inventoried")
    void dlqCutoffFollowsConsumerProgress() throws Exception {
        String runbook = Files.readString(Path.of("../docs/OPERATIONS_RUNBOOK.md"));
        int progress = runbook.indexOf("**Consumer progress and drained retries:**");
        int dlqCutoff = runbook.indexOf("**DLQ cutoff, only now:**");
        int inventory = runbook.indexOf("**DLQ inventory, separately:**");
        int delete = runbook.indexOf("**Delete up to the cutoffs**");
        assertThat(progress).isPositive();
        assertThat(dlqCutoff).isGreaterThan(progress);
        assertThat(inventory).isGreaterThan(dlqCutoff);
        assertThat(delete).isGreaterThan(inventory);
        assertThat(runbook)
                .contains("docker cp /tmp/supplier-cutoffs.json kafka-positivity:/tmp/supplier-cutoffs.json");
    }

    @Test
    @DisplayName("no --from-beginning command prints values or headers; a single-record command exists")
    void metadataOnly() throws Exception {
        List<String> commands = consumerCommands();

        assertThat(commands).isNotEmpty();
        assertThat(commands)
                .filteredOn(command -> command.contains("--from-beginning"))
                .isNotEmpty()
                .allSatisfy(command -> assertThat(command)
                        .contains("--property print.value=false")
                        .contains("--property print.headers=false")
                        .contains("--property print.key=true")
                        .doesNotContain("print.headers=true")
                        .contains("--property print.partition=true")
                        .contains("--property print.offset=true")
                        .contains("--property print.timestamp=true"));
        assertThat(commands)
                .anySatisfy(command -> assertThat(command)
                        .contains("--partition N")
                        .contains("--offset M")
                        .contains("--max-messages 1")
                        .doesNotContain("--from-beginning"));
    }
}
