package com.pos.agent.story.parsing;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ParsedIssue;
import com.pos.agent.story.models.ParsedIssue.IssueMetadata;
import com.pos.agent.story.models.ParsedIssue.Section;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses GitHub issues to extract structured metadata and body content.
 * Uses CommonMark for markdown parsing and section identification.
 * 
 * This component follows the reference pattern from workspace-agents/audit/StoryMetadataParser
 * and implements the parsing stage of the Story Strengthening Agent pipeline.
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
public class IssueParser {
    private static final Logger logger = LoggerFactory.getLogger(IssueParser.class);
    
    private final Parser markdownParser;

    /**
     * Creates a new IssueParser with default markdown parser configuration.
     */
    public IssueParser() {
        this.markdownParser = Parser.builder().build();
        logger.info("IssueParser initialized with CommonMark parser");
    }

    /**
     * Creates a new IssueParser with a custom markdown parser (for testing).
     * 
     * @param markdownParser Custom markdown parser instance
     */
    public IssueParser(Parser markdownParser) {
        this.markdownParser = Objects.requireNonNull(markdownParser, "Markdown parser cannot be null");
        logger.info("IssueParser initialized with custom parser");
    }

    /**
     * Parses a GitHub issue to extract metadata and structured body content.
     * 
     * This method:
     * 1. Extracts metadata (title, labels, repository)
     * 2. Parses the markdown body structure
     * 3. Identifies sections based on headings
     * 
     * @param issue The GitHub issue to parse
     * @return ParsedIssue with structured metadata and sections
     * @throws IssueParserException if parsing fails
     * 
     * Requirements: 2.1, 2.2, 2.3, 2.4
     */
    public ParsedIssue parseIssue(GitHubIssue issue) throws IssueParserException {
        Objects.requireNonNull(issue, "GitHub issue cannot be null");
        
        logger.debug("Parsing issue: {}", issue);
        
        try {
            // Extract metadata (Requirements: 2.1, 2.2, 2.3, 2.4)
            IssueMetadata metadata = extractMetadata(issue);
            logger.debug("Extracted metadata: title='{}', repository='{}', labels={}", 
                        metadata.getTitle(), metadata.getRepository(), metadata.getLabels());
            
            // Parse markdown body and identify sections (Requirements: 2.2)
            List<Section> sections = parseSections(issue.getBody());
            logger.debug("Identified {} sections in issue body", sections.size());
            
            ParsedIssue parsedIssue = new ParsedIssue(metadata, issue.getBody(), sections);
            logger.info("Successfully parsed issue: {}", parsedIssue);
            
            return parsedIssue;
            
        } catch (Exception e) {
            String errorMsg = String.format("Failed to parse issue %s#%d: %s", 
                                          issue.getRepository(), issue.getNumber(), e.getMessage());
            logger.error(errorMsg, e);
            throw new IssueParserException(errorMsg, e);
        }
    }

    /**
     * Extracts metadata from a GitHub issue.
     * 
     * @param issue The GitHub issue
     * @return IssueMetadata containing title, labels, and repository
     * 
     * Requirements: 2.1, 2.3, 2.4
     */
    private IssueMetadata extractMetadata(GitHubIssue issue) {
        return new IssueMetadata(
            issue.getTitle(),
            new ArrayList<>(issue.getLabels()), // Defensive copy
            issue.getRepository()
        );
    }

    /**
     * Parses the issue body to identify sections based on markdown headings.
     * 
     * This method walks the markdown AST and extracts sections delimited by headings.
     * Each section includes the heading text and all content until the next heading.
     * 
     * @param body The markdown body content
     * @return List of sections with headings and content
     * 
     * Requirements: 2.2
     */
    private List<Section> parseSections(String body) {
        List<Section> sections = new ArrayList<>();
        
        if (body == null || body.trim().isEmpty()) {
            logger.debug("Empty body, returning empty sections list");
            return sections;
        }
        
        // Parse markdown into AST
        Node document = markdownParser.parse(body);
        
        // Walk the AST to identify sections
        SectionExtractor extractor = new SectionExtractor();
        document.accept(extractor);
        
        sections.addAll(extractor.getSections());
        
        return sections;
    }

    /**
     * Visitor that extracts sections from a markdown AST.
     * Sections are delimited by headings (any level).
     */
    private static class SectionExtractor extends AbstractVisitor {
        private final List<Section> sections = new ArrayList<>();
        private String currentHeading = null;
        private final StringBuilder currentContent = new StringBuilder();
        
        @Override
        public void visit(Heading heading) {
            // Save previous section if it exists
            if (currentHeading != null) {
                sections.add(new Section(currentHeading, currentContent.toString().trim()));
                currentContent.setLength(0);
            }
            
            // Extract heading text
            currentHeading = extractText(heading);
            
            // Continue visiting children
            visitChildren(heading);
        }
        
        @Override
        public void visit(Paragraph paragraph) {
            if (currentHeading != null) {
                // Add paragraph content to current section
                String text = extractText(paragraph);
                if (!text.isEmpty()) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n\n");
                    }
                    currentContent.append(text);
                }
            }
            visitChildren(paragraph);
        }
        
        @Override
        public void visit(BulletList bulletList) {
            if (currentHeading != null) {
                String text = extractText(bulletList);
                if (!text.isEmpty()) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n\n");
                    }
                    currentContent.append(text);
                }
            }
            visitChildren(bulletList);
        }
        
        @Override
        public void visit(OrderedList orderedList) {
            if (currentHeading != null) {
                String text = extractText(orderedList);
                if (!text.isEmpty()) {
                    if (currentContent.length() > 0) {
                        currentContent.append("\n\n");
                    }
                    currentContent.append(text);
                }
            }
            visitChildren(orderedList);
        }
        
        @Override
        public void visit(Code code) {
            if (currentHeading != null) {
                currentContent.append("`").append(code.getLiteral()).append("`");
            }
        }
        
        @Override
        public void visit(FencedCodeBlock codeBlock) {
            if (currentHeading != null) {
                if (currentContent.length() > 0) {
                    currentContent.append("\n\n");
                }
                currentContent.append("```\n")
                             .append(codeBlock.getLiteral())
                             .append("```");
            }
        }
        
        /**
         * Extracts plain text from a node and its children.
         */
        private String extractText(Node node) {
            StringBuilder text = new StringBuilder();
            extractTextRecursive(node, text);
            return text.toString().trim();
        }
        
        private void extractTextRecursive(Node node, StringBuilder text) {
            if (node instanceof Text) {
                text.append(((Text) node).getLiteral());
            } else if (node instanceof Code) {
                text.append("`").append(((Code) node).getLiteral()).append("`");
            }
            
            Node child = node.getFirstChild();
            while (child != null) {
                extractTextRecursive(child, text);
                child = child.getNext();
            }
        }
        
        public List<Section> getSections() {
            // Add final section if it exists
            if (currentHeading != null) {
                sections.add(new Section(currentHeading, currentContent.toString().trim()));
            }
            return sections;
        }
    }

    /**
     * Exception thrown when issue parsing fails.
     */
    public static class IssueParserException extends Exception {
        public IssueParserException(String message) {
            super(message);
        }

        public IssueParserException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
