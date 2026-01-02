package com.pos.agent.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Context for story processing scenarios
 */
public class StoryContext extends AgentContext {

    private String repositoryUrl;
    private Long issueId;
    private String issueTitle;
    private String issueBody;
    private String moduleName;
    private List<String> dependencies;

    private StoryContext(Builder builder) {
        super(builder);
        this.repositoryUrl = builder.repositoryUrl;
        this.issueId = builder.issueId;
        this.issueTitle = builder.issueTitle;
        this.issueBody = builder.issueBody;
        this.moduleName = builder.moduleName;
        this.dependencies = builder.dependencies;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public Long getIssueId() {
        return issueId;
    }

    public String getIssueTitle() {
        return issueTitle;
    }

    public String getIssueBody() {
        return issueBody;
    }

    public String getModuleName() {
        return moduleName;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private String repositoryUrl;
        private Long issueId;
        private String issueTitle;
        private String issueBody;
        private String moduleName;
        private List<String> dependencies = new ArrayList<>();

        public Builder() {
            // Set default domain for story context
            agentDomain("story");
        }

        public Builder repositoryUrl(String repositoryUrl) {
            this.repositoryUrl = repositoryUrl;
            return this;
        }

        public Builder issueId(Long issueId) {
            this.issueId = issueId;
            return this;
        }

        public Builder issueTitle(String issueTitle) {
            this.issueTitle = issueTitle;
            return this;
        }

        public Builder issueBody(String issueBody) {
            this.issueBody = issueBody;
            return this;
        }

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder dependencies(List<String> dependencies) {
            this.dependencies = dependencies != null ? dependencies : new ArrayList<>();
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public StoryContext build() {
            return new StoryContext(this);
        }
    }
}
