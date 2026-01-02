package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context model for implementation and build execution.
 * Tracks components, tasks, dependencies, and tooling used during delivery.
 */
public class ImplementationContext extends AgentContext {

    private final Set<String> components;
    private final Map<String, String> componentStatuses;
    private final Set<String> repositories;
    private final Map<String, String> branches;
    private final Set<String> languages;
    private final Set<String> frameworks;
    private final Set<String> tasks;
    private final Map<String, String> taskStatuses;
    private final Map<String, String> dependencies;
    private final Map<String, String> buildTools;

    private ImplementationContext(Builder builder) {
        super(builder);
        this.components = builder.components;
        this.componentStatuses = builder.componentStatuses;
        this.repositories = builder.repositories;
        this.branches = builder.branches;
        this.languages = builder.languages;
        this.frameworks = builder.frameworks;
        this.tasks = builder.tasks;
        this.taskStatuses = builder.taskStatuses;
        this.dependencies = builder.dependencies;
        this.buildTools = builder.buildTools;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getComponents() {
        return new LinkedHashSet<>(components);
    }

    public Map<String, String> getComponentStatuses() {
        return new HashMap<>(componentStatuses);
    }

    public Set<String> getRepositories() {
        return new LinkedHashSet<>(repositories);
    }

    public Map<String, String> getBranches() {
        return new HashMap<>(branches);
    }

    public Set<String> getLanguages() {
        return new LinkedHashSet<>(languages);
    }

    public Set<String> getFrameworks() {
        return new LinkedHashSet<>(frameworks);
    }

    public Set<String> getTasks() {
        return new LinkedHashSet<>(tasks);
    }

    public Map<String, String> getTaskStatuses() {
        return new HashMap<>(taskStatuses);
    }

    public Map<String, String> getDependencies() {
        return new HashMap<>(dependencies);
    }

    public Map<String, String> getBuildTools() {
        return new HashMap<>(buildTools);
    }

    // Mutators
    public void addComponent(String component, String status) {
        if (component != null) {
            this.components.add(component);
            if (status != null) {
                this.componentStatuses.put(component, status);
            }
            updateTimestamp();
        }
    }

    public void addRepository(String repository, String branch) {
        if (repository != null) {
            this.repositories.add(repository);
            if (branch != null) {
                this.branches.put(repository, branch);
            }
            updateTimestamp();
        }
    }

    public void addLanguage(String language) {
        if (language != null && this.languages.add(language)) {
            updateTimestamp();
        }
    }

    public void addFramework(String framework) {
        if (framework != null && this.frameworks.add(framework)) {
            updateTimestamp();
        }
    }

    public void addTask(String task, String status) {
        if (task != null) {
            this.tasks.add(task);
            if (status != null) {
                this.taskStatuses.put(task, status);
            }
            updateTimestamp();
        }
    }

    public void addDependency(String dependency, String version) {
        if (dependency != null && version != null) {
            this.dependencies.put(dependency, version);
            updateTimestamp();
        }
    }

    public void addBuildTool(String tool, String version) {
        if (tool != null) {
            this.buildTools.put(tool, version);
            updateTimestamp();
        }
    }

    public boolean isValid() {
        return !components.isEmpty() || !tasks.isEmpty();
    }

    public boolean isReady() {
        return !tasks.isEmpty() && tasks.stream().allMatch(task -> {
            String status = taskStatuses.get(task);
            return "DONE".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
        });
    }

    @Override
    public String toString() {
        return "ImplementationContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", components=" + components.size() +
                ", tasks=" + tasks.size() +
                ", repositories=" + repositories.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> components = new LinkedHashSet<>();
        private Map<String, String> componentStatuses = new HashMap<>();
        private Set<String> repositories = new LinkedHashSet<>();
        private Map<String, String> branches = new HashMap<>();
        private Set<String> languages = new LinkedHashSet<>();
        private Set<String> frameworks = new LinkedHashSet<>();
        private Set<String> tasks = new LinkedHashSet<>();
        private Map<String, String> taskStatuses = new HashMap<>();
        private Map<String, String> dependencies = new HashMap<>();
        private Map<String, String> buildTools = new HashMap<>();

        public Builder() {
            agentDomain("implementation");
            contextType("implementation-context");
        }

        public Builder addComponent(String component, String status) {
            if (component != null) {
                components.add(component);
                if (status != null) {
                    componentStatuses.put(component, status);
                }
            }
            return this;
        }

        public Builder components(Set<String> components) {
            if (components != null) {
                this.components.addAll(components);
            }
            return this;
        }

        public Builder componentStatuses(Map<String, String> statuses) {
            if (statuses != null) {
                this.componentStatuses.putAll(statuses);
            }
            return this;
        }

        public Builder addRepository(String repository, String branch) {
            if (repository != null) {
                repositories.add(repository);
                if (branch != null) {
                    branches.put(repository, branch);
                }
            }
            return this;
        }

        public Builder repositories(Set<String> repositories) {
            if (repositories != null) {
                this.repositories.addAll(repositories);
            }
            return this;
        }

        public Builder branches(Map<String, String> branches) {
            if (branches != null) {
                this.branches.putAll(branches);
            }
            return this;
        }

        public Builder addLanguage(String language) {
            if (language != null) {
                languages.add(language);
            }
            return this;
        }

        public Builder languages(Set<String> languages) {
            if (languages != null) {
                this.languages.addAll(languages);
            }
            return this;
        }

        public Builder addFramework(String framework) {
            if (framework != null) {
                frameworks.add(framework);
            }
            return this;
        }

        public Builder frameworks(Set<String> frameworks) {
            if (frameworks != null) {
                this.frameworks.addAll(frameworks);
            }
            return this;
        }

        public Builder addTask(String task, String status) {
            if (task != null) {
                tasks.add(task);
                if (status != null) {
                    taskStatuses.put(task, status);
                }
            }
            return this;
        }

        public Builder tasks(Set<String> tasks) {
            if (tasks != null) {
                this.tasks.addAll(tasks);
            }
            return this;
        }

        public Builder taskStatuses(Map<String, String> statuses) {
            if (statuses != null) {
                this.taskStatuses.putAll(statuses);
            }
            return this;
        }

        public Builder addDependency(String dependency, String version) {
            if (dependency != null && version != null) {
                dependencies.put(dependency, version);
            }
            return this;
        }

        public Builder dependencies(Map<String, String> dependencies) {
            if (dependencies != null) {
                this.dependencies.putAll(dependencies);
            }
            return this;
        }

        public Builder addBuildTool(String tool, String version) {
            if (tool != null) {
                buildTools.put(tool, version);
            }
            return this;
        }

        public Builder buildTools(Map<String, String> tools) {
            if (tools != null) {
                this.buildTools.putAll(tools);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ImplementationContext build() {
            return new ImplementationContext(this);
        }
    }
}
