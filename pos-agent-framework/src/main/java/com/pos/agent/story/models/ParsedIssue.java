package com.pos.agent.story.models;

import java.util.List;
import java.util.Objects;

/**
 * Represents a parsed GitHub issue with structured metadata and body content.
 * This is the output of the parsing stage.
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
public class ParsedIssue {
    private final IssueMetadata metadata;
    private final String body;
    private final List<Section> sections;

    public ParsedIssue(IssueMetadata metadata, String body, List<Section> sections) {
        this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
        this.body = Objects.requireNonNull(body, "Body cannot be null");
        this.sections = Objects.requireNonNull(sections, "Sections cannot be null");
    }

    public IssueMetadata getMetadata() {
        return metadata;
    }

    public String getBody() {
        return body;
    }

    public List<Section> getSections() {
        return sections;
    }

    /**
     * Metadata extracted from a GitHub issue.
     */
    public static class IssueMetadata {
        private final String title;
        private final List<String> labels;
        private final String repository;

        public IssueMetadata(String title, List<String> labels, String repository) {
            this.title = Objects.requireNonNull(title, "Title cannot be null");
            this.labels = Objects.requireNonNull(labels, "Labels cannot be null");
            this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        }

        public String getTitle() {
            return title;
        }

        public List<String> getLabels() {
            return labels;
        }

        public String getRepository() {
            return repository;
        }
    }

    /**
     * A section within the issue body.
     */
    public static class Section {
        private final String heading;
        private final String content;

        public Section(String heading, String content) {
            this.heading = Objects.requireNonNull(heading, "Heading cannot be null");
            this.content = Objects.requireNonNull(content, "Content cannot be null");
        }

        public String getHeading() {
            return heading;
        }

        public String getContent() {
            return content;
        }
    }

    @Override
    public String toString() {
        return String.format("ParsedIssue{repo='%s', title='%s', sections=%d}", 
                           metadata.getRepository(), metadata.getTitle(), sections.size());
    }
}
