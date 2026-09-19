package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ChatBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableCell.Alignment;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Splits a chat answer's final markdown into typed {@link ChatBlock}s (#2072).
 *
 * <p>Per the resolved plan decision, blocks come from segmenting the model's already-produced
 * markdown answer — not from raw tool-call result rows. The model may aggregate/filter tool data,
 * call exploratory tools, or return the prose {@code RENDER_INSTRUCTION} asks it to write as a pipe
 * table; segmenting the final text is the only point that reflects what the caller actually sees.
 *
 * <p>Top-level document nodes are walked in order: a GFM {@code TableBlock} becomes a {@link
 * ChatBlock.TableBlock}, a fenced code block becomes a {@link ChatBlock.CodeBlock}, and every other
 * maximal run of consecutive nodes (paragraphs, headings, lists, block quotes, indented code, …)
 * collapses into one {@link ChatBlock.MarkdownBlock} carrying the source slice for that run (via
 * source spans, not a re-render, with line endings normalized to {@code \n}) so nothing is subtly
 * reformatted.
 *
 * <p><strong>Empty-list fallback:</strong> the frontend renders {@code blocks} whenever it is
 * non-empty and never falls back to re-parsing {@code response} in that case, and its markdown
 * renderer has no table/fenced-code support of its own. A table or fenced code block that is
 * <em>not</em> a top-level node — nested inside a list item, block quote, or similar — would
 * therefore render as raw pipes/backticks if emitted as part of a {@code MarkdownBlock}, a
 * regression versus the client's own current markdown parser. Rather than risk that, this method
 * returns {@code List.of()} whenever a table or fenced code block appears anywhere below the
 * top level, on any parser/source-span surprise, and whenever the walk otherwise produces no
 * blocks for non-blank input — {@code response} then remains the client's only source of truth for
 * that answer.
 *
 * <p>commonmark 0.24 also fails to recognize a GFM table (or a fenced code block) as such when its
 * lines directly follow a paragraph line, or a list item's first line, with no intervening blank
 * line — the lines stay plain paragraph text, invisible to {@code hasNestedTableOrCode}, and raw
 * pipes/fence markers would otherwise ship inside a {@code MarkdownBlock}. A final safety net,
 * {@code containsUnsegmentedTableOrFence}, scans every emitted {@code MarkdownBlock}'s text
 * line-by-line for a fence-opener line or a table delimiter row and returns {@code List.of()} if it
 * finds one, since the caller-visible answer must be either a faithful segmentation of every
 * table/fence in it or empty. A delimiter row is one or more dash-only cells (optionally
 * colon-bounded) separated by pipes, with at least one pipe required anywhere on the line — this
 * covers a single-cell row ({@code "| --- |"}) as well as the usual multi-column form, while still
 * excluding a pipe-less thematic break ({@code "---"}, {@code "- - -"}) or setext heading underline.
 * As a second, independent guard the same line is also checked against a literal mirror of the
 * frontend's own fallback delimiter-row regex, so this method can never under-detect relative to
 * what the client itself already renders as a table. Net contract: the result is either a faithful
 * segmentation or empty, never a partial or best-effort one.
 */
public final class ChatBlockSegmenter {

    // Sign, thousands commas, decimal point, trailing percent, leading currency symbol.
    private static final Pattern NUMERIC_CELL = Pattern.compile("^[+-]?\\$?\\d[\\d,]*(\\.\\d+)?%?$");
    private static final Pattern LINE_SPLIT = Pattern.compile("\\r\\n|\\r|\\n");

    // Placeholder cells that neither confirm nor refute a column being numeric; treated like blank.
    private static final Set<String> NON_NUMERIC_PLACEHOLDER_CELLS = Set.of("-", "–", "—", "n/a");

    // Safety-net patterns for text commonmark 0.24 left as plain paragraph lines instead of
    // recognizing as a table/fence (no blank line before the table/fence in the source). A GFM
    // table delimiter row: 1+ dash-only cells (each optionally colon-bounded) separated by pipes,
    // optional leading/trailing pipe. The leading "(?=.*\|)" lookahead requires at least one pipe
    // somewhere on the line (checked before the cell grammar, which alone would also accept a
    // pipe-less "---"/"***" thematic break or a setext heading underline), so those and prose with
    // a single stray "|" cannot match.
    private static final Pattern LEADING_BLOCKQUOTE_INDENT = Pattern.compile("^(?:[ \\t]*>)*[ \\t]*");
    private static final Pattern TABLE_DELIMITER_ROW =
            Pattern.compile("^(?=.*\\|)\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    // Mirrors the frontend's own fallback-detection regex exactly (chat-message-renderer's
    // TABLE_DELIMITER_RE), so this can never under-detect relative to what the client itself treats
    // as a table delimiter row. Requires a literal "-" too (the bare regex alone would also match an
    // all-space/colon row like "| | |" that carries no delimiter dash at all).
    private static final Pattern CLIENT_TABLE_DELIMITER_ROW = Pattern.compile("^\\|[\\s:|-]+\\|\\s*$");
    private static final Pattern FENCE_OPENER = Pattern.compile("^\\s*(>\\s*)*(```|~~~)");

    private ChatBlockSegmenter() {}

    public static @NonNull List<ChatBlock> segment(@Nullable String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        try {
            return doSegment(markdown);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<ChatBlock> doSegment(String markdown) {
        String[] lines = LINE_SPLIT.split(markdown, -1);
        Parser parser = Parser.builder()
                .extensions(List.of(TablesExtension.create()))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build();
        Node document = parser.parse(markdown);

        // A table or fenced code block that is not itself a top-level node (nested inside a list
        // item, block quote, etc.) has no safe representation: emitting it as part of a
        // MarkdownBlock would render as raw pipes/backticks on the client, which has no table/fence
        // support of its own. Bail out to the empty-list fallback rather than risk that.
        if (hasNestedTableOrCode(document)) {
            return List.of();
        }

        List<ChatBlock> blocks = new ArrayList<>();
        List<Node> run = new ArrayList<>();

        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.ext.gfm.tables.TableBlock tableBlock) {
                flushRun(run, lines, blocks);
                blocks.add(buildTableBlock(tableBlock));
            } else if (child instanceof FencedCodeBlock fenced) {
                flushRun(run, lines, blocks);
                blocks.add(buildCodeBlock(fenced));
            } else {
                run.add(child);
            }
        }
        flushRun(run, lines, blocks);

        // Final safety net: commonmark 0.24 leaves a table/fence that directly follows a paragraph
        // or list-item line (no blank line before it) as plain paragraph text, so it never became a
        // TableBlock/FencedCodeBlock node and hasNestedTableOrCode above could not see it. Raw pipes
        // or fence markers must never ship inside a MarkdownBlock.
        for (ChatBlock block : blocks) {
            if (block instanceof ChatBlock.MarkdownBlock markdownBlock
                    && containsUnsegmentedTableOrFence(markdownBlock.markdown())) {
                return List.of();
            }
        }
        return blocks;
    }

    /** True when any line of {@code text} looks like a GFM table delimiter row or a fence opener. */
    private static boolean containsUnsegmentedTableOrFence(String text) {
        for (String line : LINE_SPLIT.split(text, -1)) {
            if (FENCE_OPENER.matcher(line).find()) {
                return true;
            }
            String stripped = LEADING_BLOCKQUOTE_INDENT.matcher(line).replaceFirst("");
            if (isTableDelimiterRow(stripped)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTableDelimiterRow(String stripped) {
        if (TABLE_DELIMITER_ROW.matcher(stripped).matches()) {
            return true;
        }
        // Belt-and-suspenders: also bail whenever the client's own, looser fallback regex would
        // treat this line as a delimiter row, so this method can never miss a case the client
        // itself renders as a table.
        return CLIENT_TABLE_DELIMITER_ROW.matcher(stripped).matches() && stripped.contains("-");
    }

    /**
     * True when a {@code TableBlock} or {@code FencedCodeBlock} appears anywhere in the document
     * below the top level (e.g. inside a list item or block quote), where it cannot be segmented
     * out into its own typed block.
     */
    private static boolean hasNestedTableOrCode(Node document) {
        Set<Node> topLevel = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node c = document.getFirstChild(); c != null; c = c.getNext()) {
            topLevel.add(c);
        }
        return containsNestedTableOrCode(document, topLevel);
    }

    private static boolean containsNestedTableOrCode(Node node, Set<Node> topLevel) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            boolean isRisky =
                    child instanceof org.commonmark.ext.gfm.tables.TableBlock || child instanceof FencedCodeBlock;
            if (isRisky && !topLevel.contains(child)) {
                return true;
            }
            if (containsNestedTableOrCode(child, topLevel)) {
                return true;
            }
        }
        return false;
    }

    private static void flushRun(List<Node> run, String[] lines, List<ChatBlock> blocks) {
        if (run.isEmpty()) {
            return;
        }
        String text = sliceSource(run, lines);
        run.clear();
        if (!text.isBlank()) {
            blocks.add(new ChatBlock.MarkdownBlock(text));
        }
    }

    /**
     * Source slice spanning the run, via source spans — not a re-render — with line endings
     * normalized to {@code \n}.
     */
    private static String sliceSource(List<Node> run, String[] lines) {
        Node first = run.get(0);
        Node last = run.get(run.size() - 1);
        List<SourceSpan> firstSpans = first.getSourceSpans();
        List<SourceSpan> lastSpans = last.getSourceSpans();
        if (firstSpans.isEmpty() || lastSpans.isEmpty()) {
            throw new IllegalStateException("markdown node missing source spans");
        }
        int firstLine = firstSpans.get(0).getLineIndex();
        int lastLine = lastSpans.get(lastSpans.size() - 1).getLineIndex();
        if (firstLine < 0 || lastLine >= lines.length || firstLine > lastLine) {
            throw new IllegalStateException("markdown node source span out of range");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = firstLine; i <= lastLine; i++) {
            if (i > firstLine) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return trimSurroundingBlankLines(sb.toString());
    }

    private static String trimSurroundingBlankLines(String text) {
        String[] parts = LINE_SPLIT.split(text, -1);
        int start = 0;
        int end = parts.length - 1;
        while (start <= end && parts[start].isBlank()) {
            start++;
        }
        while (end >= start && parts[end].isBlank()) {
            end--;
        }
        if (start > end) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static ChatBlock.CodeBlock buildCodeBlock(FencedCodeBlock fenced) {
        String info = fenced.getInfo();
        String language = null;
        if (info != null && !info.isBlank()) {
            String firstToken = info.trim().split("\\s+", 2)[0];
            language = firstToken.isBlank() ? null : firstToken.toLowerCase(Locale.ROOT);
        }
        String literal = fenced.getLiteral();
        if (literal == null) {
            literal = "";
        }
        if (literal.endsWith("\n")) {
            literal = literal.substring(0, literal.length() - 1);
        }
        return new ChatBlock.CodeBlock(language, literal);
    }

    private static ChatBlock.TableBlock buildTableBlock(org.commonmark.ext.gfm.tables.TableBlock table) {
        TableHead head = null;
        TableBody body = null;
        for (Node c = table.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof TableHead h) {
                head = h;
            } else if (c instanceof TableBody b) {
                body = b;
            }
        }
        if (head == null || !(head.getFirstChild() instanceof TableRow headerRow)) {
            throw new IllegalStateException("table without a header row");
        }

        List<TableCell> headerCells = new ArrayList<>();
        for (Node c = headerRow.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof TableCell cell) {
                headerCells.add(cell);
            }
        }
        int width = headerCells.size();

        List<List<String>> rows = new ArrayList<>();
        if (body != null) {
            for (Node r = body.getFirstChild(); r != null; r = r.getNext()) {
                if (!(r instanceof TableRow row)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell cell) {
                        values.add(renderPlainText(cell).trim());
                    }
                }
                rows.add(padOrTrim(values, width));
            }
        }

        List<ChatBlock.Column> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            TableCell headerCell = headerCells.get(i);
            String label = renderPlainText(headerCell).trim();
            String align = determineAlign(headerCell.getAlignment(), columnValues(rows, i));
            columns.add(new ChatBlock.Column(label, align));
        }

        return new ChatBlock.TableBlock(null, columns, rows);
    }

    private static List<String> padOrTrim(List<String> values, int width) {
        List<String> result = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            result.add(i < values.size() ? values.get(i) : "");
        }
        return result;
    }

    private static List<String> columnValues(List<List<String>> rows, int columnIndex) {
        List<String> values = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            values.add(row.get(columnIndex));
        }
        return values;
    }

    private static String determineAlign(@Nullable Alignment alignment, List<String> columnValues) {
        if (alignment == Alignment.RIGHT) {
            return "end";
        }
        boolean sawNonBlank = false;
        boolean allNumeric = true;
        for (String value : columnValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (NON_NUMERIC_PLACEHOLDER_CELLS.contains(trimmed.toLowerCase(Locale.ROOT))) {
                continue;
            }
            sawNonBlank = true;
            if (!NUMERIC_CELL.matcher(trimmed).matches()) {
                allNumeric = false;
                break;
            }
        }
        return (sawNonBlank && allNumeric) ? "end" : "start";
    }

    /** Renders a cell's inline content as plain text: keeps text, strips markup. */
    private static String renderPlainText(Node node) {
        StringBuilder sb = new StringBuilder();
        appendPlainText(node, sb);
        return sb.toString();
    }

    private static void appendPlainText(Node node, StringBuilder sb) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                sb.append(text.getLiteral());
            } else if (child instanceof Code code) {
                sb.append(code.getLiteral());
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                sb.append(' ');
            } else {
                appendPlainText(child, sb);
            }
        }
    }
}
