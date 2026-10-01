package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.client.JevAnswer;
import com.positivity.mcp.internal.client.JevClient;
import com.positivity.mcp.internal.client.JevClient.JevResponse;
import com.positivity.mcp.internal.client.JevProviderException;
import com.positivity.mcp.internal.config.TaggingEnabledCondition;
import com.positivity.mcp.internal.config.TaggingProperties;
import com.positivity.mcp.internal.domain.QuestionTagger;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.TagAnswer;
import com.positivity.mcp.internal.domain.TagSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * ADR-0068 §2, §5: the decision-model tagger. Sends the current question set ({@link
 * TaggingQuestions}) about the message to the System One endpoint through {@link JevClient} and maps
 * each answer to a {@link TagAnswer} (ADR-0068 §1: Noul value {@code p >= 0.5}, confidence {@code
 * max(p, 1 - p)}; Choice and Score carry Jev's own confidence, a Score's value is the argmax level).
 *
 * <p>A provider failure propagates as {@link JevProviderException}; {@link TaggingService} turns it
 * into the heuristic record for the whole turn.
 */
@Component
@Conditional(TaggingEnabledCondition.class)
public class JevQuestionTagger implements QuestionTagger {

    private final JevClient client;
    private final TaggingQuestions questions;
    private final TaggingProperties properties;

    public JevQuestionTagger(
            @NonNull JevClient client, @NonNull TaggingQuestions questions, @NonNull TaggingProperties properties) {
        this.client = client;
        this.questions = questions;
        this.properties = properties;
    }

    /**
     * @throws JevProviderException on any provider failure (timeout, status, error body, malformed
     *     answers)
     */
    @Override
    public @NonNull QuestionTags tag(@NonNull String message) {
        TaggingQuestions.QuestionSet asked = questions.questionSet();
        JevResponse response = client.ask(message, asked.questions());
        Map<String, TagAnswer> answers = new LinkedHashMap<>();
        response.answers().forEach((name, answer) -> answers.put(name, toTagAnswer(answer)));
        return new QuestionTags(
                properties.mode(),
                Map.of(),
                answers,
                answers,
                null,
                // ADR-0068 §3.6: the configured model, never the provider's own model string.
                properties.provider().model(),
                response.latencyMs(),
                response.stateTruncated(),
                asked.size(),
                response.requestBodyBytes(),
                asked.optionListHash());
    }

    static @NonNull TagAnswer toTagAnswer(@NonNull JevAnswer answer) {
        return switch (answer) {
            case JevAnswer.Noul noul -> TagAnswer.noul(noul.probability());
            case JevAnswer.Choice choice -> new TagAnswer(choice.label(), choice.confidence(), TagSource.JEV, null);
            case JevAnswer.Score score ->
                new TagAnswer(score.level(), score.confidence(), TagSource.JEV, score.score());
        };
    }
}
