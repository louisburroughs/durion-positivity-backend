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

    @Test
    @DisplayName("the runbook names the permitted terminal arrangements for single-record inspection (CHK-006)")
    void permittedTerminals() throws Exception {
        String runbook = Files.readString(Path.of("../docs/OPERATIONS_RUNBOOK.md"));
        assertThat(runbook)
                .contains("Permitted terminal arrangements for single-record inspection")
                .contains("Session Manager");
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
