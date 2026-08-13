package com.positivity.openapivalidation.internal.validator;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Checks the ADR-0042 §1 description depth and §3 request body rules for a single operation.
 *
 * <p>The seven §1 elements are detected by the canonical lead-in phrases fixed in
 * {@code docs/OPENAPI_DESCRIPTION_STANDARD.md}. Detecting lead-ins rather than meaning is what makes the
 * rule enforceable; it is also what makes the fleet read in one voice, which is the point of the
 * standard — these descriptions are compared against each other by an agent choosing a tool.
 */
public class OpenApiAnnotationDepthValidator {

    static final int MIN_SENTENCES = 4;
    static final int MAX_SENTENCES = 8;
    private static final int MIN_PRIMARY_ACTION_LENGTH = 30;

    /** Splits on sentence punctuation only when the next sentence starts, so "e.g." does not count. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z(\"])");

    private static final Pattern WHEN_TO_USE = Pattern.compile("(?i)\\buse this (tool|operation|endpoint)\\b");
    private static final Pattern PRECONDITIONS = Pattern.compile("(?i)\\bpreconditions?\\b\\s*:");
    private static final Pattern INPUT_EXPECTATIONS = Pattern.compile("(?i)\\b(required )?inputs?\\b\\s*:");
    private static final Pattern SIDE_EFFECTS =
            Pattern.compile("(?i)\\bemits\\b|\\bno events are emitted\\b|\\bside[ -]effects?\\b\\s*:");
    private static final Pattern ERROR_CONDITIONS = Pattern.compile("(?i)\\breturns\\s+\\d{3}\\b");
    private static final Pattern NEGATIVE_GUIDANCE =
            Pattern.compile("(?i)\\bdo not\\b|\\bdon't\\b|\\binstead\\b|\\brather than\\b");

    public @NonNull List<String> check(@NonNull Operation operation) {
        List<String> findings = new ArrayList<>();
        checkDescription(operation.getDescription(), findings);
        checkRequestBody(operation.getRequestBody(), findings);
        return List.copyOf(findings);
    }

    private void checkDescription(String description, List<String> findings) {
        if (description == null || description.isBlank()) {
            // Absence is already reported by OpenApiModuleValidator; depth adds nothing here.
            return;
        }
        // Text blocks wrap mid-phrase, so "do not" can arrive split across a newline. Match on
        // single-spaced text or every multi-word lead-in becomes line-break sensitive.
        String normalized = description.replaceAll("\\s+", " ").strip();
        List<String> sentences = splitSentences(normalized);
        if (sentences.size() < MIN_SENTENCES || sentences.size() > MAX_SENTENCES) {
            findings.add("description has " + sentences.size() + " sentence"
                    + (sentences.size() == 1 ? "" : "s") + " (ADR-0042 §1 requires " + MIN_SENTENCES + "-"
                    + MAX_SENTENCES + ")");
        }
        String first = sentences.isEmpty() ? "" : sentences.get(0);
        if (first.length() < MIN_PRIMARY_ACTION_LENGTH
                || WHEN_TO_USE.matcher(first).find()) {
            findings.add("description does not open with a primary-action sentence");
        }
        require(WHEN_TO_USE, normalized, findings, "when-to-use guidance (\"Use this tool ...\")");
        require(PRECONDITIONS, normalized, findings, "preconditions (\"Preconditions: ...\")");
        require(INPUT_EXPECTATIONS, normalized, findings, "input expectations (\"Required inputs: ...\")");
        require(SIDE_EFFECTS, normalized, findings, "side effects (\"Emits ...\" or \"No events are emitted\")");
        require(ERROR_CONDITIONS, normalized, findings, "error conditions (\"Returns <code> when ...\")");
        require(NEGATIVE_GUIDANCE, normalized, findings, "negative guidance (\"do not use ...\" / \"... instead\")");
    }

    private void checkRequestBody(RequestBody requestBody, List<String> findings) {
        if (requestBody == null) {
            return;
        }
        if (requestBody.getDescription() == null || requestBody.getDescription().isBlank()) {
            findings.add("request body missing description (ADR-0042 §3)");
        }
        if (requestBody.getRequired() == null) {
            findings.add("request body missing explicit required flag (ADR-0042 §3)");
        }
        if (!hasExample(requestBody.getContent())) {
            findings.add("request body missing example (ADR-0042 §3)");
        }
    }

    private static boolean hasExample(Content content) {
        if (content == null) {
            return false;
        }
        for (MediaType mediaType : content.values()) {
            if (mediaType == null) {
                continue;
            }
            if (mediaType.getExample() != null
                    || (mediaType.getExamples() != null
                            && !mediaType.getExamples().isEmpty())) {
                return true;
            }
            if (mediaType.getSchema() != null && mediaType.getSchema().getExample() != null) {
                return true;
            }
        }
        return false;
    }

    private static void require(Pattern pattern, String description, List<String> findings, String element) {
        if (!pattern.matcher(description).find()) {
            findings.add("description missing " + element);
        }
    }

    static @NonNull List<String> splitSentences(@NonNull String description) {
        List<String> sentences = new ArrayList<>();
        for (String candidate : SENTENCE_BOUNDARY.split(description.replaceAll("\\s+", " "))) {
            String trimmed = candidate.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return List.copyOf(sentences);
    }
}
