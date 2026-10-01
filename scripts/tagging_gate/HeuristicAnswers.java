import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.orchestration.HeuristicQuestionTagger;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prints the heuristic tagger's answers for each line of stdin (ADR-0068 tagging gate,
 * hard_negative_for derivation). One input line is one utterance text; one output line is a JSON
 * object {"text": ..., "answers": {"<wire name>": "<value>", ...}}.
 *
 * <p>Needs pos-mcp-server's classes from a build that has HeuristicQuestionTagger (ADR-0068 wave 1,
 * #2367) and that module's runtime classpath. Run through scripts/derive_tagging_hard_negatives.py,
 * which explains how to build both; launched in Java source-file mode, so nothing here is compiled
 * into the repository's build.
 */
public final class HeuristicAnswers {

    private HeuristicAnswers() {}

    public static void main(String[] args) throws Exception {
        HeuristicQuestionTagger tagger = HeuristicQuestionTagger.withDefaultCatalog();
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            QuestionTags tags = tagger.tag(line);
            StringBuilder json = new StringBuilder("{\"text\":").append(quote(line)).append(",\"answers\":{");
            boolean first = true;
            for (Map.Entry<String, TagAnswer> entry : tags.heuristic().entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue().value()));
            }
            out.println(json.append("}}"));
        }
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
