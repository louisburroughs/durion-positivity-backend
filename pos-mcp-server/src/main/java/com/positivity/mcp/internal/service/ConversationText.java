package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ChatBlock;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Pure text rules for persisted conversations (#2073): the derived title, the history-rail preview,
 * and the raw content of a client-appended turn that carried only blocks.
 *
 * <p>Title and preview mirror the frontend's {@code deriveTitle}/{@code derivePreview}
 * ({@code chat-state.service.ts}) so a conversation reads the same whether the rail was built from
 * localStorage or from the server.
 */
final class ConversationText {

    /** Frontend {@code TITLE_MAX_LENGTH}. */
    static final int DERIVED_TITLE_MAX_LENGTH = 48;

    /** Frontend {@code PREVIEW_MAX_LENGTH}. */
    static final int PREVIEW_MAX_LENGTH = 90;

    /** Title of a conversation that has no user message yet and was not given one. */
    static final String DEFAULT_TITLE = "New conversation";

    private static final String ELLIPSIS = "…";

    private ConversationText() {}

    /**
     * First line of the opening question, clipped to 48 characters on a word boundary (when the last
     * space falls past the midpoint) and marked with an ellipsis; {@link #DEFAULT_TITLE} when the text
     * has no non-blank first line.
     */
    static @NonNull String deriveTitle(@Nullable String text) {
        String firstLine = firstLine(text);
        if (firstLine.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (firstLine.length() <= DERIVED_TITLE_MAX_LENGTH) {
            return firstLine;
        }
        String clipped = firstLine.substring(0, DERIVED_TITLE_MAX_LENGTH);
        int lastSpace = clipped.lastIndexOf(' ');
        String kept = lastSpace > DERIVED_TITLE_MAX_LENGTH / 2 ? clipped.substring(0, lastSpace) : clipped;
        return kept.stripTrailing() + ELLIPSIS;
    }

    /**
     * First line of an assistant turn, clipped to 90 characters plus an ellipsis; {@code null} when
     * the turn has no non-blank first line (the conversation then keeps its previous preview).
     */
    static @Nullable String derivePreview(@Nullable String assistantContent) {
        String firstLine = firstLine(assistantContent);
        if (firstLine.isEmpty()) {
            return null;
        }
        return firstLine.length() <= PREVIEW_MAX_LENGTH
                ? firstLine
                : firstLine.substring(0, PREVIEW_MAX_LENGTH) + ELLIPSIS;
    }

    /**
     * Raw text of a turn that arrived with blocks only: markdown and text verbatim, code re-fenced,
     * tables as GFM, and the textual parts of the remaining kinds, joined by blank lines. Blank when
     * no block carries any text.
     */
    static @NonNull String contentFromBlocks(@NonNull List<ChatBlock> blocks) {
        StringBuilder content = new StringBuilder();
        for (ChatBlock block : blocks) {
            String part = blockText(block);
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(part.strip());
        }
        return content.toString();
    }

    private static @Nullable String blockText(ChatBlock block) {
        return switch (block) {
            case ChatBlock.MarkdownBlock markdown -> markdown.markdown();
            case ChatBlock.TextBlock text -> text.text();
            case ChatBlock.CodeBlock code ->
                "```" + (code.language() == null ? "" : code.language()) + "\n" + code.code() + "\n```";
            case ChatBlock.TableBlock table -> tableText(table);
            case ChatBlock.ChartBlock chart -> chart.title();
            case ChatBlock.ImageBlock image -> image.caption() != null ? image.caption() : image.alt();
            case ChatBlock.FileBlock file -> file.name();
            case ChatBlock.ErrorBlock error -> error.messageKey();
        };
    }

    private static @NonNull String tableText(ChatBlock.TableBlock table) {
        StringBuilder text = new StringBuilder();
        if (table.title() != null && !table.title().isBlank()) {
            text.append(table.title().strip()).append("\n\n");
        }
        List<ChatBlock.Column> columns = table.columns();
        if (columns == null || columns.isEmpty()) {
            return text.toString();
        }
        text.append('|');
        for (ChatBlock.Column column : columns) {
            text.append(' ').append(cell(column.label())).append(" |");
        }
        text.append("\n|");
        for (int index = 0; index < columns.size(); index++) {
            text.append(" --- |");
        }
        if (table.rows() != null) {
            for (List<String> row : table.rows()) {
                text.append("\n|");
                for (String value : row) {
                    text.append(' ').append(cell(value)).append(" |");
                }
            }
        }
        return text.toString();
    }

    private static @NonNull String cell(@Nullable String value) {
        return value == null ? "" : value.replace("|", "\\|").replace('\n', ' ');
    }

    private static @NonNull String firstLine(@Nullable String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.strip().split("\\R", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
}
