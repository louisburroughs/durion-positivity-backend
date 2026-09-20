package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.positivity.mcp.internal.dto.ChatBlock;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ChatBlockSegmenter} (#2072 wave 1): splits a chat answer's final markdown into
 * typed {@link ChatBlock}s so the frontend can render a table as a table and a fenced code block
 * as code instead of re-parsing markdown client-side.
 */
@DisplayName("ChatBlockSegmenter")
class ChatBlockSegmenterTest {

    @Test
    @DisplayName("null markdown segments to an empty list")
    void nullMarkdown_returnsEmpty() {
        assertThat(ChatBlockSegmenter.segment(null)).isEmpty();
    }

    @Test
    @DisplayName("blank markdown segments to an empty list")
    void blankMarkdown_returnsEmpty() {
        assertThat(ChatBlockSegmenter.segment("   \n  \t ")).isEmpty();
        assertThat(ChatBlockSegmenter.segment("")).isEmpty();
    }

    @Test
    @DisplayName("prose-only answer segments to a single markdown block equal to the trimmed input")
    void proseOnly_singleMarkdownBlock() {
        String prose = "Hello there, this is a plain prose answer with no structure.";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(prose);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(prose.strip());
    }

    @Test
    @DisplayName("prose + GFM table + prose segments to [markdown, table, markdown] with no pipes left in markdown")
    void proseTableProse_segmentsInOrder() {
        String markdown = """
                Here is a report:

                | Name | Status |
                | --- | --- |
                | Alpha | **ACTIVE** |
                | Beta | inactive |

                Thanks!""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo("Here is a report:");
        assertThat(blocks.get(1)).isInstanceOf(ChatBlock.TableBlock.class);
        assertThat(blocks.get(2)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(2)).markdown()).isEqualTo("Thanks!");

        for (ChatBlock block : blocks) {
            if (block instanceof ChatBlock.MarkdownBlock markdownBlock) {
                assertThat(markdownBlock.markdown()).doesNotContain("|");
            }
        }
    }

    @Test
    @DisplayName("explicit right-aligned column (---:) aligns \"end\" even for a non-numeric body")
    void explicitRightAlign_endsEvenWhenNotNumeric() {
        String markdown = """
                | Metric | Trend |
                | --- | ---: |
                | Uptime | up |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns()).extracting(ChatBlock.Column::label).containsExactly("Metric", "Trend");
        assertThat(table.columns().get(0).align()).isEqualTo("start");
        assertThat(table.columns().get(1).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("numeric-body column with no explicit alignment aligns \"end\"")
    void numericBodyColumn_alignsEnd() {
        String markdown = """
                | Item | Price | Share |
                | --- | --- | --- |
                | Widget | $4.50 | 12% |
                | Gadget | 1,200 | 26 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(0).align()).isEqualTo("start");
        assertThat(table.columns().get(1).align()).isEqualTo("end");
        assertThat(table.columns().get(2).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("text column with no explicit alignment aligns \"start\"")
    void textColumn_alignsStart() {
        String markdown = """
                | Name | Status |
                | --- | --- |
                | Alpha | ACTIVE |
                | Beta | inactive |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(0).align()).isEqualTo("start");
        assertThat(table.columns().get(1).align()).isEqualTo("start");
    }

    @Test
    @DisplayName("ragged rows are padded/trimmed to header width")
    void raggedRows_paddedOrTrimmedToHeaderWidth() {
        String markdown = """
                | A | B | C |
                | --- | --- | --- |
                | 1 | 2 |
                | 3 | 4 | 5 | 6 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0)).hasSize(3).containsExactly("1", "2", "");
        assertThat(table.rows().get(1)).hasSize(3).containsExactly("3", "4", "5");
    }

    @Test
    @DisplayName("inline markup in a cell is stripped to plain text")
    void inlineMarkupInCell_strippedToPlainText() {
        String markdown = """
                | Name | Status |
                | --- | --- |
                | Alpha | **ACTIVE** |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.rows().get(0)).containsExactly("Alpha", "ACTIVE");
    }

    @Test
    @DisplayName("fenced code with a language: lower-cased, trailing newline stripped")
    void fencedCodeWithLanguage_lowerCasedNoTrailingNewline() {
        String markdown = """
                ```SQL
                SELECT 1;
                ```""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.language()).isEqualTo("sql");
        assertThat(code.code()).isEqualTo("SELECT 1;");
    }

    @Test
    @DisplayName("fenced code without a language has a null language")
    void fencedCodeWithoutLanguage_nullLanguage() {
        String markdown = """
                ```
                plain fenced text
                ```""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.language()).isNull();
        assertThat(code.code()).isEqualTo("plain fenced text");
    }

    @Test
    @DisplayName("indented code (not fenced) stays embedded inside its surrounding markdown block")
    void indentedCode_staysInMarkdown() {
        String markdown = """
                Explanation:

                    code line one
                    code line two

                Done.""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        String text = ((ChatBlock.MarkdownBlock) blocks.get(0)).markdown();
        assertThat(text)
                .contains("    code line one")
                .contains("    code line two")
                .startsWith("Explanation:")
                .endsWith("Done.");
    }

    @Test
    @DisplayName("a heading + list + paragraph run merges into ONE markdown block preserving exact source")
    void headingListParagraphRun_mergesIntoOneMarkdownBlockExactSource() {
        String markdown = """
                ## Title

                - item one
                - item two

                Some paragraph text.""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(markdown);
    }

    @Test
    @DisplayName("an unterminated fence never throws")
    void unterminatedFence_neverThrows() {
        String markdown = "```\ncode without a closing fence";

        assertThatCode(() -> ChatBlockSegmenter.segment(markdown)).doesNotThrowAnyException();
        assertThat(ChatBlockSegmenter.segment(markdown)).isNotEmpty();
    }

    @Test
    @DisplayName("a lone pipe character never throws")
    void lonePipe_neverThrows() {
        String markdown = "|";

        assertThatCode(() -> ChatBlockSegmenter.segment(markdown)).doesNotThrowAnyException();
        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);
        assertThat(blocks).isNotEmpty();
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
    }

    @Test
    @DisplayName("CRLF input never throws and still segments the table")
    void crlfInput_neverThrows() {
        String markdown = "Report:\r\n\r\n| A | B |\r\n| --- | --- |\r\n| 1 | 2 |\r\n";

        assertThatCode(() -> ChatBlockSegmenter.segment(markdown)).doesNotThrowAnyException();
        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);
        assertThat(blocks).isNotEmpty();
        assertThat(blocks).anyMatch(ChatBlock.TableBlock.class::isInstance);
    }

    @Test
    @DisplayName("fenced code with info string containing only spaces becomes null language")
    void fencedCodeWithSpaceInfoString_nullLanguage() {
        String markdown = "```  \ncode\n```";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.language()).isNull();
    }

    @Test
    @DisplayName("fenced code with language followed by options preserves language only")
    void fencedCodeWithLanguageAndOptions_languageExtracted() {
        String markdown = "```python3 linenos\ncode\n```";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.language()).isEqualTo("python3");
    }

    @Test
    @DisplayName("table with only header row (no body) segments successfully")
    void tableWithHeaderOnly_segmentsSuccessfully() {
        String markdown = """
                | Name | Count |
                | --- | --- |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.rows()).isEmpty();
        assertThat(table.columns()).hasSize(2);
    }

    @Test
    @DisplayName("prose followed by blank line and table segments correctly")
    void proseBlankLineBeforeTable_segmentsBoundary() {
        String markdown = """
                Here's a report:

                | A | B |
                | --- | --- |
                | 1 | 2 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(ChatBlock.TableBlock.class);
    }

    @Test
    @DisplayName("column with all null/blank values aligns start")
    void allBlankColumnValues_alignsStart() {
        String markdown = """
                | Name | Empty |
                | --- | --- |
                | Alpha |  |
                | Beta | |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(1).align()).isEqualTo("start");
    }

    @Test
    @DisplayName("column with mixed numeric and text values aligns start")
    void mixedNumericAndTextColumn_alignsStart() {
        String markdown = """
                | Item | Value |
                | --- | --- |
                | First | 100 |
                | Second | pending |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(1).align()).isEqualTo("start");
    }

    @Test
    @DisplayName("a numeric value plus a dash placeholder aligns \"end\" (placeholder counts as blank)")
    void numericAndPlaceholderColumn_alignsEnd() {
        String markdown = """
                | Item | Value |
                | --- | --- |
                | First | 100 |
                | Second | - |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(1).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("table cell with inline code markup stripped to plain text")
    void tableCell_inlineCodeStripped() {
        String markdown = """
                | Command | Output |
                | --- | --- |
                | `SELECT 1` | ok |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.rows().get(0).get(0)).isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("code with trailing newline removes it")
    void codeWithTrailingNewline_stripped() {
        String markdown = "```\nline1\nline2\n```";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.code()).isEqualTo("line1\nline2");
    }

    @Test
    @DisplayName("code without trailing newline unchanged")
    void codeWithoutTrailingNewline_unchanged() {
        String markdown = "```\nsingle line\n```";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.CodeBlock code = (ChatBlock.CodeBlock) blocks.get(0);
        assertThat(code.code()).isEqualTo("single line");
    }

    @Test
    @DisplayName("numeric column with currency symbols and commas")
    void numericColumnWithCurrencyAndCommas_alignsEnd() {
        String markdown = """
                | Item | Price |
                | --- | --- |
                | Alpha | $1,234.56 |
                | Beta | -$99.99 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(1).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("numeric column with leading plus sign")
    void numericColumnWithPlusSign_alignsEnd() {
        String markdown = """
                | Change |
                | --- |
                | +5.2 |
                | -3.1 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(0).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("table rows with varying column counts padded/trimmed to header width")
    void tableWithVaryingRowWidths_allNormalized() {
        String markdown = """
                | A | B | C | D |
                | --- | --- | --- | --- |
                | 1 |
                | 2 | 3 | 4 | 5 | 6 |
                | 7 | 8 | 9 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.rows()).allMatch(row -> row.size() == 4);
        assertThat(table.rows().get(0)).containsExactly("1", "", "", "");
        assertThat(table.rows().get(1)).containsExactly("2", "3", "4", "5");
        assertThat(table.rows().get(2)).containsExactly("7", "8", "9", "");
    }

    @Test
    @DisplayName("text with only blank lines segments to empty list")
    void onlyBlankLines_emptySegments() {
        String markdown = "\n\n   \n\t\t\n\n";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("code then prose then code segments in order")
    void codeProseCode_segmentedInOrder() {
        String markdown = """
                ```sql
                SELECT 1;
                ```

                Some explanation here.

                ```python
                print("hello")
                ```""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.CodeBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(blocks.get(2)).isInstanceOf(ChatBlock.CodeBlock.class);
    }

    @Test
    @DisplayName("percentage values in numeric column preserve alignment")
    void percentageNumericColumn_alignsEnd() {
        String markdown = """
                | Metric | Percent |
                | --- | --- |
                | A | 25% |
                | B | 75% |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(1).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("O1: a table nested inside a list item degrades to an empty list, never raw pipes")
    void tableNestedInListItem_returnsEmptyList() {
        String markdown = """
                - Item one
                  | A | B |
                  | --- | --- |
                  | 1 | 2 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("O1: a fenced code block nested inside a list item degrades to an empty list")
    void fencedCodeNestedInListItem_returnsEmptyList() {
        String markdown = """
                - Item one
                  ```
                  code here
                  ```""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("O1: a table nested inside a block quote degrades to an empty list")
    void tableNestedInBlockQuote_returnsEmptyList() {
        String markdown = """
                > | A | B |
                > | --- | --- |
                > | 1 | 2 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("cycle 4, pinned: a list immediately followed by a table with no blank line degrades to an empty list")
    void listImmediatelyFollowedByTable_degradesToEmptyList() {
        String markdown = """
                - item one
                - item two
                | A | B |
                | --- | --- |
                | 1 | 2 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        // Pinned to the actual, current outcome now that the safety net (containsUnsegmentedTable-
        // OrFence) scans every emitted MarkdownBlock for a delimiter row: the table never becomes a
        // TableBlock node here (nested in the list item's lazy-continuation paragraph, invisible to
        // hasNestedTableOrCode), so the run merges the list and the pipe lines into one MarkdownBlock
        // whose "| --- | --- |" line trips the safety net, and the whole answer degrades to []. A
        // commonmark/GFM-table-extension upgrade that starts recognizing this as a real nested or
        // top-level table would change this outcome — that's the point of pinning it exactly rather
        // than only asserting the weaker "no raw pipes" invariant.
        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName(
            "cycle 4, pinned: a paragraph immediately followed by a table with no blank line degrades to an empty list")
    void paragraphImmediatelyFollowedByTable_degradesToEmptyList() {
        String markdown = """
                Some text here
                | A | B |
                | --- | --- |
                | 1 | 2 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        // Same mechanism as the list case above: the table never interrupts the open paragraph in
        // this commonmark 0.24 configuration, so all four lines merge into one MarkdownBlock whose
        // "| --- | --- |" line is caught by the safety net, degrading the whole answer to [].
        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("cycle 4: a single-column table directly after a paragraph, pinned actual behavior")
    void singleColumnTableAfterParagraph_pinnedActualBehavior() {
        String markdown = "Total\n| Count |\n| --- |\n| 26 |";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        // Pinned: the single-cell delimiter row "| --- |" is exactly what the widened safety-net
        // regex was built to catch (a single-cell row, not just the usual multi-column form), and
        // the table never interrupts the preceding "Total" paragraph in this commonmark
        // configuration, so the whole answer degrades to []. Never a MarkdownBlock carrying the raw
        // "| Count |" / "| --- |" / "| 26 |" lines — that's the invariant this guards regardless of
        // which of the two spec-legal outcomes (empty, or a proper single-column TableBlock) a future
        // commonmark/extension upgrade produces.
        assertThat(blocks).isEmpty();
        assertNoUnsegmentedTableOrFenceInMarkdownBlocks(blocks);
    }

    @Test
    @DisplayName("cycle 4: a mismatched delimiter row (column count doesn't match the header) degrades to []")
    void mismatchedDelimiterRowColumnCount_returnsEmptyList() {
        String markdown = "| A | B |\n| --- |\n| 1 | 2 |";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        // A delimiter row whose cell count doesn't match the header is not a valid GFM table at
        // all (commonmark-java never emits a TableBlock node for it), so this stays plain paragraph
        // text carrying a single-cell delimiter row line ("| --- |") that the safety net catches.
        assertThat(blocks).isEmpty();
    }

    @Test
    @DisplayName("cycle 4 false positive: a thematic break between two paragraphs stays ONE markdown block")
    void thematicBreakBetweenParagraphs_isNotFlaggedAsDelimiterRow() {
        String markdown = "Intro\n\n---\n\nMore";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(markdown);
    }

    @Test
    @DisplayName("cycle 4 false positive: a setext heading underline stays ONE markdown block")
    void setextHeadingUnderline_isNotFlaggedAsDelimiterRow() {
        String markdown = "Title\n---";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(markdown);
    }

    @Test
    @DisplayName("cycle 4 false positive: a lone pipe inside a sentence stays ONE markdown block")
    void pipeInsideSentence_isNotFlaggedAsDelimiterRow() {
        String markdown = "Pick yes | no";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(markdown);
    }

    @Test
    @DisplayName("cycle 4 false positive: a leading pipe with no delimiter row stays ONE markdown block")
    void leadingPipeWithNoDelimiterRow_isNotFlaggedAsDelimiterRow() {
        String markdown = "| not a table\nmore text";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        assertThat(((ChatBlock.MarkdownBlock) blocks.get(0)).markdown()).isEqualTo(markdown);
    }

    @Test
    @DisplayName("O2: an empty GFM header cell stays Column(\"\", ...) rather than being dropped or shifted")
    void emptyHeaderCell_staysEmptyLabelColumn() {
        String markdown = """
                |  | Status |
                | --- | --- |
                | Alpha | ACTIVE |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).hasSize(1);
        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns()).hasSize(2);
        assertThat(table.columns().get(0).label()).isEqualTo("");
        assertThat(table.columns().get(1).label()).isEqualTo("Status");
    }

    @Test
    @DisplayName("a work-order id and an ISO date both align \"start\" (neither matches the numeric-cell pattern)")
    void workOrderIdAndIsoDateColumns_alignStart() {
        String markdown = """
                | Id | Created |
                | --- | --- |
                | WO-10432 | 2026-09-18 |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(0).align()).isEqualTo("start");
        assertThat(table.columns().get(1).align()).isEqualTo("start");
    }

    @Test
    @DisplayName("a pure-digit id column aligns \"end\" (intended, pinned)")
    void pureDigitIdColumn_alignsEnd() {
        String markdown = """
                | Id | Name |
                | --- | --- |
                | 1001 | Alpha |
                | 1002 | Beta |""";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        ChatBlock.TableBlock table = (ChatBlock.TableBlock) blocks.get(0);
        assertThat(table.columns().get(0).align()).isEqualTo("end");
    }

    @Test
    @DisplayName("CRLF input's markdown block text is LF-joined, never carrying a raw \\r")
    void crlfInput_markdownBlockIsLfJoined() {
        String markdown = "Line one\r\nLine two\r\n\r\n| A | B |\r\n| --- | --- |\r\n| 1 | 2 |\r\n";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        assertThat(blocks).isNotEmpty();
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.MarkdownBlock.class);
        String markdownText = ((ChatBlock.MarkdownBlock) blocks.get(0)).markdown();
        assertThat(markdownText).doesNotContain("\r").isEqualTo("Line one\nLine two");
    }

    @Test
    @DisplayName("an empty fence (pinned actual behavior)")
    void emptyFence_pinnedActualBehavior() {
        String markdown = "```\n```";

        List<ChatBlock> blocks = ChatBlockSegmenter.segment(markdown);

        // Pinned: an empty fence still segments to a single CodeBlock with an empty literal,
        // rather than being dropped, since buildCodeBlock never throws on an empty/blank literal.
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ChatBlock.CodeBlock.class);
        assertThat(((ChatBlock.CodeBlock) blocks.get(0)).code()).isEqualTo("");
    }

    @Test
    @DisplayName(
            "invariant: every non-empty segmentation result never leaves a markdown block with a raw table/fence line")
    void everyNonEmptyResult_neverLeavesRawTableOrFenceLinesInMarkdown() {
        List<String> representativeAnswers = List.of(
                """
                Here is a report:

                | Name | Status |
                | --- | --- |
                | Alpha | **ACTIVE** |
                | Beta | inactive |

                Thanks!""",
                """
                ```sql
                SELECT 1;
                ```

                Some explanation here.

                ```python
                print("hello")
                ```""",
                """
                ## Title

                - item one
                - item two

                Some paragraph text.""",
                "Line one\r\nLine two\r\n\r\n| A | B |\r\n| --- | --- |\r\n| 1 | 2 |\r\n",
                "Some text here\n| A | B |\n| --- | --- |\n| 1 | 2 |");

        for (String answer : representativeAnswers) {
            List<ChatBlock> blocks = ChatBlockSegmenter.segment(answer);
            assertNoUnsegmentedTableOrFenceInMarkdownBlocks(blocks);
        }
    }

    // -- java:S5998 / java:S8786 remediation: isDashOnlyDelimiterRow / isFenceOpener /
    // stripLeadingBlockquoteIndent replaced regexes with a repeated group with plain linear scans.
    // These exercise those package-private helpers directly (fastest, most precise) in addition to
    // the segment()-level tests above/below that already cover the safety net end to end.

    @ParameterizedTest(name = "delimiter row: \"{0}\"")
    @MethodSource("delimiterRowCases")
    @DisplayName("isDashOnlyDelimiterRow matches exactly the intended set of lines")
    void isDashOnlyDelimiterRow_matchesIntendedSet(String line, boolean expected) {
        assertThat(ChatBlockSegmenter.isDashOnlyDelimiterRow(line)).isEqualTo(expected);
    }

    private static Stream<Arguments> delimiterRowCases() {
        return Stream.of(
                Arguments.of("| --- | --- |", true),
                Arguments.of("--- | ---", true),
                Arguments.of("| --- | --- ", true),
                Arguments.of(" --- | --- |", true),
                Arguments.of("|---|---|", true),
                Arguments.of("| :--- | ---: | :---: |", true),
                Arguments.of("|  ---  |  ---  |", true),
                Arguments.of("| --- |", true),
                Arguments.of("---", false),
                Arguments.of("***", false),
                Arguments.of("- - -", false),
                Arguments.of("| | |", false),
                Arguments.of("| not a table", false),
                Arguments.of("Pick yes | no", false),
                Arguments.of("", false));
    }

    @Test
    @DisplayName("isDashOnlyDelimiterRow handles a pathological 5000-char input without recursing or hanging")
    void isDashOnlyDelimiterRow_pathologicalInput_noStackOverflowAndFast() {
        String longRow = "|-".repeat(2500);

        long start = System.nanoTime();
        boolean result = ChatBlockSegmenter.isDashOnlyDelimiterRow(longRow);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isTrue();
        assertThat(elapsedMs).isLessThan(1000);
    }

    @Test
    @DisplayName("stripLeadingBlockquoteIndent strips nested blockquote markers and surrounding whitespace")
    void stripLeadingBlockquoteIndent_stripsNestedMarkers() {
        assertThat(ChatBlockSegmenter.stripLeadingBlockquoteIndent("> > > deep"))
                .isEqualTo("deep");
        assertThat(ChatBlockSegmenter.stripLeadingBlockquoteIndent(">>>text")).isEqualTo("text");
        assertThat(ChatBlockSegmenter.stripLeadingBlockquoteIndent("  plain text"))
                .isEqualTo("plain text");
        assertThat(ChatBlockSegmenter.stripLeadingBlockquoteIndent("> | --- |")).isEqualTo("| --- |");
        assertThat(ChatBlockSegmenter.stripLeadingBlockquoteIndent("")).isEmpty();
    }

    @Test
    @DisplayName(
            "stripLeadingBlockquoteIndent on a pathological 5000-char nested-blockquote input never stack overflows")
    void stripLeadingBlockquoteIndent_pathologicalInput_noStackOverflowAndFast() {
        String deeplyNested = "> ".repeat(2500) + "text";

        long start = System.nanoTime();
        String result = ChatBlockSegmenter.stripLeadingBlockquoteIndent(deeplyNested);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEqualTo("text");
        assertThat(elapsedMs).isLessThan(1000);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "```",
                "~~~",
                "```python",
                "> ```",
                "> > ```",
                "  ```",
            })
    @DisplayName("isFenceOpener recognizes fence openers, with or without blockquote prefixes")
    void isFenceOpener_recognizesFenceOpeners(String line) {
        assertThat(ChatBlockSegmenter.isFenceOpener(line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a fence", "``", "`single`", "", "> not a fence"})
    @DisplayName("isFenceOpener rejects non-fence lines")
    void isFenceOpener_rejectsNonFenceLines(String line) {
        assertThat(ChatBlockSegmenter.isFenceOpener(line)).isFalse();
    }

    @Test
    @DisplayName("isFenceOpener on a pathological 5000-char nested-blockquote input never stack overflows")
    void isFenceOpener_pathologicalInput_noStackOverflowAndFast() {
        String deeplyNested = "> ".repeat(2500) + "```";

        long start = System.nanoTime();
        boolean result = ChatBlockSegmenter.isFenceOpener(deeplyNested);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isTrue();
        assertThat(elapsedMs).isLessThan(1000);
    }

    // Mirrors production's own safety net (ChatBlockSegmenter#containsUnsegmentedTableOrFence):
    // a delimiter row is 1+ dash-only cells (optionally colon-bounded) separated by pipes, with at
    // least one pipe AND at least one dash present anywhere on the line; a fence opener is a
    // (optionally blockquoted) ``` or ~~~ line. Deliberately NOT "any line starting with |" — that
    // stricter check flags legitimate prose like "| not a table" (leading pipe, no delimiter row
    // beneath it) that production correctly leaves alone (cycle 4).
    private static final Pattern DELIMITER_ROW_LIKE = Pattern.compile("^(?=.*\\|)(?=.*-)[\\s:|-]+$");
    private static final Pattern FENCE_OPENER_LIKE = Pattern.compile("^\\s*(>\\s*)*(```|~~~)");

    /**
     * Asserts no {@link ChatBlock.MarkdownBlock} in {@code blocks} contains a line that looks like
     * an unsegmented GFM table delimiter row or a fence opener — the one outcome the frontend (no
     * table/fence support of its own) can never render safely. Aligned with production's own
     * detection, not a blanket "starts with |" check, so it doesn't false-positive on prose that
     * merely contains a pipe character.
     */
    private static void assertNoUnsegmentedTableOrFenceInMarkdownBlocks(List<ChatBlock> blocks) {
        for (ChatBlock block : blocks) {
            if (block instanceof ChatBlock.MarkdownBlock markdownBlock) {
                for (String line : markdownBlock.markdown().split("\n", -1)) {
                    boolean looksLikeFenceOpener =
                            FENCE_OPENER_LIKE.matcher(line).find();
                    boolean looksLikeDelimiterRow =
                            DELIMITER_ROW_LIKE.matcher(line.strip()).matches();
                    assertThat(looksLikeFenceOpener || looksLikeDelimiterRow)
                            .describedAs(
                                    "markdown block leaked an unsegmented table delimiter row or fence opener: %s",
                                    line)
                            .isFalse();
                }
            }
        }
    }
}
