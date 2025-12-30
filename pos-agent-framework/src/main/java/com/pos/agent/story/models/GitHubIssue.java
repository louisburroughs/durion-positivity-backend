package com.pos.agent.story.models;

import java.util.List;
import java.util.Objects;

/**
 * Represents a GitHub issue with metadata and content.
 * This is the input to the Story Strengthening Agent pipeline.
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
public class GitHubIssue {
    private final String title;
    private final String body;
    private final List<String> labels;
    private final String repository;
    private final int number;

    public GitHubIssue(String title, String body, List<String> labels, String repository, int number) {
        this.title = Objects.requireNonNull(title, "Title cannot be null");
        this.body = Objects.requireNonNull(body, "Body cannot be null");
        this.labels = Objects.requireNonNull(labels, "Labels cannot be null");
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        this.number = number;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public List<String> getLabels() {
        return labels;
    }

    public String getRepository() {
        return repository;
    }

    public int getNumber() {
        return number;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GitHubIssue that = (GitHubIssue) o;
        return number == that.number && 
               Objects.equals(repository, that.repository);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repository, number);
    }

    @Override
    public String toString() {
        return String.format("GitHubIssue{repo='%s', #%d, title='%s'}", 
                           repository, number, title);
    }
}
