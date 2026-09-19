package com.positivity.mcp.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON contract tests for {@link ChatBlock} (#2072 wave 1): {@code kind} must serialize exactly
 * once per variant (backed by {@link com.fasterxml.jackson.annotation.JsonTypeInfo.As#EXISTING_PROPERTY},
 * not an injected discriminator), null optionals must be omitted, and a {@code table} payload must
 * round-trip through the sealed interface.
 *
 * <p>Jackson 3 native ({@code tools.jackson.databind}), matching this module's other JSON tests
 * ({@code McpOpenApiContractTest}, {@code EvalFixtureSatisfiabilityTest}) — the {@code jackson-annotation}
 * artifact ({@code com.fasterxml.jackson.annotation.*}) that {@link ChatBlock} is annotated with is
 * unchanged between Jackson 2 and Jackson 3, only {@code databind}/{@code core} moved packages.
 */
@DisplayName("ChatBlock JSON contract")
class ChatBlockTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("MarkdownBlock: kind serializes exactly once, no null optionals")
    void markdownBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.MarkdownBlock("Hello **world**");

        JsonNode node = mapper.valueToTree(block);

        assertThat(countOccurrences(mapper.writeValueAsString(block), "\"kind\""))
                .isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("markdown");
        assertThat(node.get("markdown").asText()).isEqualTo("Hello **world**");
    }

    @Test
    @DisplayName("TableBlock: null title is omitted, kind serializes exactly once")
    void tableBlock_nullTitleOmitted() throws Exception {
        ChatBlock block = new ChatBlock.TableBlock(
                null,
                List.of(new ChatBlock.Column("Name", "start"), new ChatBlock.Column("Count", "end")),
                List.of(List.of("Alpha", "26")));

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("table");
        assertThat(node.has("title")).isFalse();
        assertThat(node.get("columns")).hasSize(2);
        assertThat(node.get("rows").get(0).get(0).asText()).isEqualTo("Alpha");
    }

    @Test
    @DisplayName("CodeBlock: null language is omitted, kind serializes exactly once")
    void codeBlock_nullLanguageOmitted() throws Exception {
        ChatBlock block = new ChatBlock.CodeBlock(null, "SELECT 1;");

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("code");
        assertThat(node.has("language")).isFalse();
        assertThat(node.get("code").asText()).isEqualTo("SELECT 1;");
    }

    @Test
    @DisplayName("ChartBlock: kind serializes exactly once, null title omitted")
    void chartBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.ChartBlock(null, List.of(new ChatBlock.Series("Bay 1", 12.0)));

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("chart");
        assertThat(node.has("title")).isFalse();
    }

    @Test
    @DisplayName("TextBlock: kind serializes exactly once")
    void textBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.TextBlock("plain text");

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("text");
    }

    @Test
    @DisplayName("ImageBlock: kind serializes exactly once, null caption omitted")
    void imageBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.ImageBlock("/mcp-server/v1/mcp/blobs/1", "tyre wear", null);

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("image");
        assertThat(node.has("caption")).isFalse();
    }

    @Test
    @DisplayName("FileBlock: kind serializes exactly once, null optionals omitted")
    void fileBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.FileBlock("bulletin.pdf", null, null, "/mcp-server/v1/mcp/blobs/2");

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("file");
        assertThat(node.has("sizeBytes")).isFalse();
        assertThat(node.has("mimeType")).isFalse();
    }

    @Test
    @DisplayName("ErrorBlock: kind serializes exactly once, null optionals omitted")
    void errorBlock_kindOnce() throws Exception {
        ChatBlock block = new ChatBlock.ErrorBlock("chat.error.toolFailed", null, null, null, false);

        String json = mapper.writeValueAsString(block);
        JsonNode node = mapper.readTree(json);

        assertThat(countOccurrences(json, "\"kind\"")).isEqualTo(1);
        assertThat(node.get("kind").asText()).isEqualTo("error");
        assertThat(node.has("detailKey")).isFalse();
        assertThat(node.has("detailParams")).isFalse();
        assertThat(node.has("correlationId")).isFalse();
    }

    @Test
    @DisplayName("a {\"kind\":\"table\",...} payload round-trips to a TableBlock through the sealed interface")
    void tablePayload_roundTripsToTableBlock() throws Exception {
        String payload = """
                {"kind":"table","columns":[{"label":"Name","align":"start"},{"label":"Count","align":"end"}],
                 "rows":[["Alpha","26"],["Beta","1200"]]}
                """;

        ChatBlock block = mapper.readValue(payload, ChatBlock.class);

        assertThat(block).isInstanceOf(ChatBlock.TableBlock.class);
        ChatBlock.TableBlock table = (ChatBlock.TableBlock) block;
        assertThat(table.kind()).isEqualTo("table");
        assertThat(table.columns()).extracting(ChatBlock.Column::label).containsExactly("Name", "Count");
        assertThat(table.rows()).containsExactly(List.of("Alpha", "26"), List.of("Beta", "1200"));

        // Round trip: re-serializing must still carry kind exactly once.
        String reserialized = mapper.writeValueAsString(table);
        assertThat(countOccurrences(reserialized, "\"kind\"")).isEqualTo(1);
    }

    @Test
    @DisplayName("ErrorBlock detailParams carries interpolation values through a round trip")
    void errorBlock_detailParamsRoundTrip() throws Exception {
        ChatBlock block = new ChatBlock.ErrorBlock(
                "chat.error.toolFailed", "chat.error.detail", Map.of("count", 3), "corr-1", true);

        String json = mapper.writeValueAsString(block);
        ChatBlock roundTripped = mapper.readValue(json, ChatBlock.class);

        assertThat(roundTripped).isInstanceOf(ChatBlock.ErrorBlock.class);
        ChatBlock.ErrorBlock error = (ChatBlock.ErrorBlock) roundTripped;
        assertThat(error.detailParams()).containsEntry("count", 3);
        assertThat(error.correlationId()).isEqualTo("corr-1");
        assertThat(error.retryable()).isTrue();
    }

    private static long countOccurrences(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1L;
    }
}
