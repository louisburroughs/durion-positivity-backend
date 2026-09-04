package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Facade over {@link BusinessGlossary} (#1688): a no-HTTP lookup that turns an analytical business
 * phrase into its agreed metric and default window.
 *
 * <p>It exists so the assistant can tell a phrase it may answer from one it must ask about.
 * Before this, both readings were indistinguishable at runtime: the model either guessed a metric
 * for "best customers" and answered confidently on it, or asked a clarifying question about a
 * range the {@code DATE_WINDOW} contract already defaults — and the analytics gate scored every
 * clarifying question as a failure, which taught the wrong lesson in the other direction.
 *
 * <p>Like {@link DateWindowFacadeTool} this makes no downstream call and enforces no permission of
 * its own — the glossary is a set of definitions, not data, so any authenticated caller who can
 * ask the question may resolve the term in it. The resolved term and glossary version are logged
 * at INFO for the same reason the date window's shape is: it is the value a per-stage grader
 * (#1682) has to assert on, and a definition applied silently cannot be checked.
 *
 * <p><strong>The log line never carries the caller's text.</strong> The {@code term} argument is
 * whatever the user wrote — the tool accepts whole questions, so it routinely contains customer and
 * vendor names — and the grader needs none of it: whether a definition was found, which canonical
 * term it was, and the glossary version are the whole trace signal. Logging the query would put
 * caller content in application logs for no gain, which is the same reason {@code
 * ToolInvocationRecorder} declines to log tool arguments at all. The query is still echoed in the
 * JSON returned to the model, which already has it.
 */
@Component
public class GlossaryFacadeTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlossaryFacadeTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Tool(
            description = "Look up the agreed business definition of an analytical phrase (\"best customers\", "
                    + "\"payment problems\", \"running low\", \"backed up in the shop\", \"most productive "
                    + "technicians\"). Call this BEFORE answering any question whose metric is a business term "
                    + "rather than a plain field. Returns defined=true with metric and defaultWindow — apply "
                    + "them and answer, quoting the definition so the user can see which reading you used — or "
                    + "defined=false, which means the metric genuinely has no agreed definition and you should "
                    + "ask the user which measure they want instead of choosing one. A missing date range is "
                    + "NOT a reason to ask: resolve that through resolveDateWindow. Only a missing METRIC is.")
    public String lookupBusinessTerm(
            @ToolParam(
                            description = "The business phrase as the user wrote it, e.g. \"best customers\" or "
                                    + "\"who isn't paying on time\"")
                    @NonNull
                    String term) {
        Optional<BusinessGlossary.Definition> found = BusinessGlossary.lookup(term);
        // Deliberately omits the caller's text: see the class javadoc.
        LOGGER.info(
                "MCP glossary lookup defined={} resolvedTerm={} glossaryVersion={}",
                found.isPresent(),
                found.map(BusinessGlossary.Definition::term).orElse(null),
                BusinessGlossary.VERSION);
        return write(term, found);
    }

    private static String write(@NonNull String term, @NonNull Optional<BusinessGlossary.Definition> found) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("query", term);
        root.put("glossaryVersion", BusinessGlossary.VERSION);
        if (found.isEmpty()) {
            root.put("defined", false);
            root.put(
                    "guidance",
                    "This metric has no agreed definition. Ask the user which measure they mean rather than "
                            + "choosing one — a silently chosen metric produces an answer that looks confident "
                            + "and cannot be checked. Do not ask about the date range; resolve that with "
                            + "resolveDateWindow.");
            return serialize(root);
        }
        BusinessGlossary.Definition definition = found.get();
        root.put("defined", true);
        root.put("term", definition.term());
        root.put("metric", definition.definition());
        root.put("defaultWindow", definition.defaultWindow());
        root.put(
                "guidance",
                "Apply this metric and default window, then answer. State the definition you used in the "
                        + "answer. Do not ask the user to define this term.");
        return serialize(root);
    }

    private static String serialize(@NonNull ObjectNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            // ObjectNode of string and boolean fields only; writeValueAsString cannot fail here.
            throw new IllegalStateException(impossible);
        }
    }
}
