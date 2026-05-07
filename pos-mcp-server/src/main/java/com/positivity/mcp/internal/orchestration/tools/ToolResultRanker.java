package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.structured.Description;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier 3: Tool output result re-ranking using LLM.
 *
 * <p>
 * Ranks and filters tool execution results by relevance to the user query
 * before presenting
 * to the LLM. Reduces noise from tool outputs by scoring each result's
 * relevance.
 *
 * <p>
 * Use case:
 * <ul>
 * <li>Tool returns 100 inventory items; filter to top-5 most relevant
 * <li>Tool returns list of orders; score by recency + relevance to query
 * <li>Tool returns documentation snippets; rank by relevance to user question
 * </ul>
 *
 * <p>
 * Implementation:
 * <ul>
 * <li>Prompts LLM to score each result (0-1 relevance score)
 * <li>Sorts by score descending
 * <li>Returns top-k results (configurable, default 5)
 * <li>Falls back to unranked if LLM fails
 * </ul>
 */
public class ToolResultRanker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ToolResultRanker.class);

  private final @NonNull ChatModel chatModel;
  private final int topK;

  /**
   * Creates a tool result ranker.
   *
   * @param chatModel LLM for scoring
   * @param topK      Number of results to keep after ranking (default 5)
   */
  public ToolResultRanker(@NonNull ChatModel chatModel, int topK) {
    this.chatModel = chatModel;
    this.topK = Math.max(1, topK);
  }

  /**
   * Ranks tool results by relevance to user query.
   *
   * <p>
   * Tier 3: LLM-based result re-ranking for quality filtering.
   *
   * @param toolName  Name of the tool (for logging)
   * @param userQuery Original user question
   * @param results   Tool execution results (list of strings)
   * @return Top-k results ranked by relevance
   */
  @NonNull
  public List<String> rankResults(
      @NonNull String toolName, @NonNull String userQuery, @NonNull List<String> results) {
    if (results.isEmpty()) {
      return results;
    }

    // For small result sets, return as-is (no re-ranking overhead)
    if (results.size() <= topK) {
      LOGGER.debug(
          "Skipping ranking for small result set toolName={} resultCount={} topK={}",
          toolName,
          results.size(),
          topK);
      return results;
    }

    try {
      List<RankedResult> ranked = new ArrayList<>();
      for (int i = 0; i < results.size(); i++) {
        String result = results.get(i);
        double relevanceScore = scoreResult(userQuery, result, toolName, i);
        ranked.add(new RankedResult(result, relevanceScore, i));
      }

      // Sort by score descending, then by original position
      ranked.sort(Comparator.comparingDouble(RankedResult::score)
          .reversed()
          .thenComparingInt(RankedResult::position));

      List<String> topResults = ranked.stream()
          .limit(topK)
          .map(RankedResult::result)
          .toList();

      LOGGER.debug(
          "Ranked tool results toolName={} input={} output={} topK={}",
          toolName,
          results.size(),
          topResults.size(),
          topK);
      return topResults;
    } catch (RuntimeException e) {
      LOGGER.warn(
          "Failed to rank tool results toolName={} error={}; returning unranked",
          toolName,
          e.getClass().getSimpleName(),
          e);
      return results.size() > topK ? results.subList(0, topK) : results;
    }
  }

  /**
   * Scores a single result for relevance to the query.
   *
   * <p>
   * Asks LLM: "Rate this result's relevance to the query on a scale 0-1".
   *
   * @param userQuery   User question
   * @param result      Tool output result
   * @param toolName    Tool name (for logging)
   * @param resultIndex Position in original results
   * @return Relevance score 0-1
   */
  private double scoreResult(
      @NonNull String userQuery, @NonNull String result, @NonNull String toolName, int resultIndex) {
    try {
      String prompt = String.format(
          Locale.ROOT,
          """
              User query: %s

              Tool result: %s

              Rate the relevance of this tool result to the user query on a scale 0-1, where:
              0 = not relevant at all
              1 = perfectly answers the query
              0.5 = partially relevant

              Respond with a single decimal number (e.g., 0.85):
              """,
          userQuery,
          truncate(result, 500));

      String scoreText = chatModel.chat(ChatRequest.builder()
          .messages(List.of(SystemMessage.from(prompt)))
          .build())
          .aiMessage()
          .text()
          .trim();
      double score = parseScore(scoreText);

      LOGGER.trace(
          "Scored result toolName={} position={} score={} preview=\"{}\"",
          toolName,
          resultIndex,
          String.format(Locale.ROOT, "%.3f", score),
          truncate(result, 40));

      return score;
    } catch (RuntimeException e) {
      LOGGER.debug(
          "Failed to score result toolName={} position={} error={}; score=0",
          toolName,
          resultIndex,
          e.getClass().getSimpleName());
      return 0.0;
    }
  }

  /**
   * Parses a score string to a double value between 0 and 1.
   *
   * @param scoreText Text from LLM (e.g., "0.85", "0.5")
   * @return Parsed score, or 0.0 if parsing fails
   */
  private static double parseScore(@NonNull String scoreText) {
    try {
      double score = Double.parseDouble(scoreText);
      return Math.clamp(score, 0.0, 1.0);
    } catch (NumberFormatException e) {
      LOGGER.debug("Could not parse score from text: {}", scoreText);
      return 0.0;
    }
  }

  /**
   * Truncates text for logging and prompt inclusion.
   *
   * @param text      Text to truncate
   * @param maxLength Maximum length
   * @return Truncated text
   */
  @NonNull
  private static String truncate(@NonNull String text, int maxLength) {
    if (text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength - 3) + "...";
  }

  /**
   * Internal record for ranked results.
   */
  @Description("Ranked result with score and position")
  private record RankedResult(
      @NonNull String result, double score, int position) {
  }
}
