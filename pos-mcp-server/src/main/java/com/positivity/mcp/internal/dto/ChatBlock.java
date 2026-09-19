package com.positivity.mcp.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Typed rendering unit of an assistant chat answer (#2072).
 *
 * <p>{@code POST /mcp/chat} answers with the full markdown in {@code response} for backward
 * compatibility, and (this wave) a parallel {@code blocks} list segmented from that same markdown
 * by {@code com.positivity.mcp.internal.service.ChatBlockSegmenter} so the frontend can render a
 * table as a table and a fenced code block as code instead of re-parsing markdown client-side.
 *
 * <p><strong>Wave 1 scope:</strong> only {@link MarkdownBlock}, {@link TableBlock} and
 * {@link CodeBlock} are ever produced by this module. {@link ChartBlock}, {@link TextBlock},
 * {@link ImageBlock}, {@link FileBlock} and {@link ErrorBlock} are modeled here for
 * OpenAPI/client forward compatibility (matching the issue's example payload and the frontend's
 * already-shipped {@code ChatBlock} union) but nothing in {@code pos-mcp-server} emits them yet
 * — no tool returns chart series, an image reference or a file reference, and the answer
 * ladder's deflection path is prose, not a typed error.
 *
 * <p>The discriminator property {@code kind} is carried by each record's own {@code kind()}
 * accessor (an ordinary bean property, not an injected one) so it serializes exactly once and
 * round-trips through {@link JsonTypeInfo.As#EXISTING_PROPERTY} rather than being written twice.
 * Optional fields are omitted from the JSON, never emitted as {@code null}
 * ({@link JsonInclude.Include#NON_NULL}), matching the frontend's {@code | null} modeling.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ChatBlock.MarkdownBlock.class, name = "markdown"),
    @JsonSubTypes.Type(value = ChatBlock.TableBlock.class, name = "table"),
    @JsonSubTypes.Type(value = ChatBlock.ChartBlock.class, name = "chart"),
    @JsonSubTypes.Type(value = ChatBlock.CodeBlock.class, name = "code"),
    @JsonSubTypes.Type(value = ChatBlock.TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ChatBlock.ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = ChatBlock.FileBlock.class, name = "file"),
    @JsonSubTypes.Type(value = ChatBlock.ErrorBlock.class, name = "error"),
})
@Schema(
        name = "ChatBlock",
        description = "Typed rendering unit of an assistant chat answer. `kind` selects the "
                + "variant; a `kind` this schema does not list should degrade to prose on the "
                + "client rather than fail, since new kinds may be added without a version bump.",
        oneOf = {
            ChatBlock.MarkdownBlock.class,
            ChatBlock.TableBlock.class,
            ChatBlock.ChartBlock.class,
            ChatBlock.CodeBlock.class,
            ChatBlock.TextBlock.class,
            ChatBlock.ImageBlock.class,
            ChatBlock.FileBlock.class,
            ChatBlock.ErrorBlock.class,
        },
        discriminatorProperty = "kind",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "markdown", schema = ChatBlock.MarkdownBlock.class),
            @DiscriminatorMapping(value = "table", schema = ChatBlock.TableBlock.class),
            @DiscriminatorMapping(value = "chart", schema = ChatBlock.ChartBlock.class),
            @DiscriminatorMapping(value = "code", schema = ChatBlock.CodeBlock.class),
            @DiscriminatorMapping(value = "text", schema = ChatBlock.TextBlock.class),
            @DiscriminatorMapping(value = "image", schema = ChatBlock.ImageBlock.class),
            @DiscriminatorMapping(value = "file", schema = ChatBlock.FileBlock.class),
            @DiscriminatorMapping(value = "error", schema = ChatBlock.ErrorBlock.class),
        })
public sealed interface ChatBlock
        permits ChatBlock.MarkdownBlock,
                ChatBlock.TableBlock,
                ChatBlock.ChartBlock,
                ChatBlock.CodeBlock,
                ChatBlock.TextBlock,
                ChatBlock.ImageBlock,
                ChatBlock.FileBlock,
                ChatBlock.ErrorBlock {

    /**
     * Discriminator value for this block ({@code "markdown"}, {@code "table"}, ...). Backs the
     * {@code kind} JSON property and is what {@link JsonTypeInfo.As#EXISTING_PROPERTY} reads /
     * writes, so it must equal the matching {@link JsonSubTypes.Type#name()} exactly.
     */
    @NonNull
    String kind();

    /**
     * Prose block: a source slice of {@code response} with line endings normalized to {@code \n}
     * (not re-rendered). A {@code markdown} block
     * never contains a top-level table or fenced code block — those are segmented into
     * {@link TableBlock} / {@link CodeBlock} instead.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatMarkdownBlock",
            description = "Prose markdown segment of the answer.",
            requiredProperties = {"kind", "markdown"})
    record MarkdownBlock(
            @Schema(
                    description = "Markdown source for this segment.",
                    example = "You have **26 mechanics**, all ACTIVE.")
            @NonNull
            String markdown)
            implements ChatBlock {

        @Schema(allowableValues = "markdown")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "markdown";
        }
    }

    /**
     * Tabular block segmented from a GFM pipe table in the answer. {@code columns} and each
     * {@code rows} entry are the same length; a row is padded/trimmed to the header width if the
     * source table was ragged. {@code align} is {@code "end"} for a column whose GFM alignment is
     * explicitly right, or (absent explicit alignment) when every non-blank body cell in that
     * column parses as numeric; otherwise {@code "start"}. A column's {@link Column#label()} may
     * be the empty string — GFM permits a blank header cell (e.g. a pivot table's corner column),
     * and the client renders it as an empty header rather than a placeholder.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatTableBlock",
            description = "Tabular segment of the answer, rendered as a table rather than markdown pipes.",
            requiredProperties = {"kind", "columns", "rows"})
    record TableBlock(
            @Schema(description = "Optional table caption.", example = "Mechanics by status", nullable = true) @Nullable
            String title,

            @Schema(description = "Column headers in source order.") @NonNull
            List<Column> columns,

            @Schema(description = "Row cell values, in column order, padded/trimmed to the header width.") @NonNull
            List<List<String>> rows)
            implements ChatBlock {

        @Schema(allowableValues = "table")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "table";
        }
    }

    /**
     * One table column header. {@code align} is {@code "end"} for a numeric column, {@code
     * "start"} otherwise; the client left-pads/right-pads rows to this width.
     */
    @Schema(
            name = "ChatTableColumn",
            requiredProperties = {"label", "align"})
    record Column(
            @Schema(description = "Column header text.", example = "Status") @NonNull
            String label,

            @Schema(
                    description = "Column alignment; \"end\" marks a numeric column.",
                    example = "end",
                    allowableValues = {"start", "end"})
            @NonNull
            String align) {}

    /**
     * Single-series bar chart. Schema-only in wave 1 (#2072) — no current tool returns chart
     * series, so nothing in this module emits {@code kind: "chart"} yet.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatChartBlock",
            description = "Single-series bar chart. Not yet produced by any tool in this module (schema-only).",
            requiredProperties = {"kind", "series"})
    record ChartBlock(
            @Schema(description = "Optional chart caption.", example = "Open workorders by bay", nullable = true)
            @Nullable
            String title,

            @Schema(description = "Chart series; a datum with no label or a non-finite value is dropped by the client.")
            @NonNull
            List<Series> series)
            implements ChatBlock {

        @Schema(allowableValues = "chart")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "chart";
        }
    }

    /** One chart datum. {@code value} must be finite; the client drops any datum that is not. */
    @Schema(
            name = "ChatChartSeries",
            requiredProperties = {"label", "value"})
    record Series(
            @Schema(description = "Datum label.", example = "Bay 1") @NonNull
            String label,

            @Schema(description = "Datum value; must be finite.", example = "12.0")
            double value) {}

    /**
     * Fenced code block segmented from the answer. {@code language} is the fence's info string,
     * lower-cased, or {@code null} when the fence carried none — never guessed server-side (the
     * client also lower-cases it, so pass through rather than duplicate that rule).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatCodeBlock",
            description = "Fenced code segment of the answer.",
            requiredProperties = {"kind", "code"})
    record CodeBlock(
            @Schema(
                    description = "Fence info string, lower-cased, or omitted when the fence carried none.",
                    example = "sql",
                    nullable = true)
            @Nullable
            String language,

            @Schema(description = "Code source for this fence.") @NonNull
            String code)
            implements ChatBlock {

        @Schema(allowableValues = "code")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "code";
        }
    }

    /**
     * Plain prose with no markdown syntax to interpret. Schema-only in wave 1 (#2072) —
     * {@code ChatBlockSegmenter} always emits {@link MarkdownBlock} for prose spans today, but
     * the frontend already renders {@code kind: "text"} (durion-positivity-frontend
     * {@code ChatTextBlock}), so it is modeled here for forward compatibility.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatTextBlock",
            description = "Plain prose segment with no markdown syntax. Not yet produced by this module (schema-only).",
            requiredProperties = {"kind", "text"})
    record TextBlock(
            @Schema(description = "Plain text content.") @NonNull
            String text) implements ChatBlock {

        @Schema(allowableValues = "text")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "text";
        }
    }

    /**
     * Inline image reference. Schema-only in wave 1 (#2072) — nothing in this module emits {@code
     * kind: "image"} yet. {@code alt} is required: the client drops an image block that has none
     * rather than render it inaccessibly, so the server must never emit one without it either.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatImageBlock",
            description = "Inline image reference. Not yet produced by any tool in this module (schema-only).",
            requiredProperties = {"kind", "url", "alt"})
    record ImageBlock(
            @Schema(description = "Image location.", example = "/mcp-server/v1/mcp/blobs/<id>") @NonNull
            String url,

            @Schema(
                    description =
                            "Text alternative. Required — an image without one is dropped by the client rather than rendered inaccessibly.",
                    example = "tyre wear")
            @NonNull
            String alt,

            @Schema(description = "Optional caption.", example = "Attached to WO-10432", nullable = true) @Nullable
            String caption)
            implements ChatBlock {

        @Schema(allowableValues = "image")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "image";
        }
    }

    /**
     * File attachment reference. Schema-only in wave 1 (#2072) — nothing in this module emits
     * {@code kind: "file"} yet.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatFileBlock",
            description = "File attachment reference. Not yet produced by any tool in this module (schema-only).",
            requiredProperties = {"kind", "name", "url"})
    record FileBlock(
            @Schema(description = "Display file name.", example = "bulletin.pdf") @NonNull
            String name,

            @Schema(description = "File size in bytes, when known.", example = "840000", nullable = true) @Nullable
            Long sizeBytes,

            @Schema(description = "MIME type, when known.", example = "application/pdf", nullable = true) @Nullable
            String mimeType,

            @Schema(description = "File location.", example = "/mcp-server/v1/mcp/blobs/<id>") @NonNull
            String url)
            implements ChatBlock {

        @Schema(allowableValues = "file")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "file";
        }
    }

    /**
     * Typed error surfaced as a block rather than raw prose. Schema-only in wave 1 (#2072) —
     * the answer-resolution ladder's deflection path is prose today, not a typed error, so
     * nothing in this module emits {@code kind: "error"} yet.
     *
     * <p>Shaped to match the frontend's shipped {@code ChatErrorBlock}
     * (durion-positivity-frontend {@code src/app/features/shell/models/chat.model.ts}, branch
     * {@code claude/adoring-mccarthy-mriyc5}): {@code messageKey} and {@code detailKey} are
     * client-side i18n keys, not server-rendered prose, so the message follows the viewer's
     * locale rather than the locale the request was made in. {@code chat-response.mapper.ts}
     * drops an error block that has no string {@code messageKey}, so a server-produced error
     * block must always carry one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(value = "kind", allowGetters = true)
    @Schema(
            name = "ChatErrorBlock",
            description = "Typed error block. Not yet produced by any tool in this module (schema-only).",
            requiredProperties = {"kind", "messageKey"})
    record ErrorBlock(
            @Schema(description = "Client i18n key for the error message.", example = "chat.error.toolFailed") @NonNull
            String messageKey,

            @Schema(description = "Optional client i18n key for additional detail.", nullable = true) @Nullable
            String detailKey,

            @Schema(
                    description = "Optional interpolation parameters for detailKey; values are string or number.",
                    nullable = true)
            @Nullable
            Map<String, Object> detailParams,

            @Schema(description = "Optional correlation id for support/troubleshooting.", nullable = true) @Nullable
            String correlationId,

            @Schema(description = "Whether the client should offer a retry action. Defaults to false.")
            boolean retryable)
            implements ChatBlock {

        @Schema(allowableValues = "error")
        @JsonProperty("kind")
        @Override
        public @NonNull String kind() {
            return "error";
        }
    }
}
