package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context model for business domain alignment.
 * Captures goals, stakeholders, KPIs, products, and processes.
 */
public class BusinessContext extends AgentContext {

    private final Set<String> businessGoals;
    private final Map<String, String> stakeholders; // role -> owner
    private final Map<String, Double> kpis;
    private final Set<String> products;
    private final Map<String, String> processes;
    private final Set<String> regulations;

    private BusinessContext(Builder builder) {
        super(builder);
        this.businessGoals = builder.businessGoals;
        this.stakeholders = builder.stakeholders;
        this.kpis = builder.kpis;
        this.products = builder.products;
        this.processes = builder.processes;
        this.regulations = builder.regulations;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getBusinessGoals() {
        return new LinkedHashSet<>(businessGoals);
    }

    public Map<String, String> getStakeholders() {
        return new HashMap<>(stakeholders);
    }

    public Map<String, Double> getKpis() {
        return new HashMap<>(kpis);
    }

    public Set<String> getProducts() {
        return new LinkedHashSet<>(products);
    }

    public Map<String, String> getProcesses() {
        return new HashMap<>(processes);
    }

    public Set<String> getRegulations() {
        return new LinkedHashSet<>(regulations);
    }

    // Mutators
    public void addGoal(String goal) {
        if (goal != null && this.businessGoals.add(goal)) {
            updateTimestamp();
        }
    }

    public void addStakeholder(String role, String owner) {
        if (role != null && owner != null) {
            this.stakeholders.put(role, owner);
            updateTimestamp();
        }
    }

    public void addKpi(String name, Double value) {
        if (name != null && value != null) {
            this.kpis.put(name, value);
            updateTimestamp();
        }
    }

    public void addProduct(String product) {
        if (product != null && this.products.add(product)) {
            updateTimestamp();
        }
    }

    public void addProcess(String process, String owner) {
        if (process != null) {
            this.processes.put(process, owner);
            updateTimestamp();
        }
    }

    public void addRegulation(String regulation) {
        if (regulation != null && this.regulations.add(regulation)) {
            updateTimestamp();
        }
    }

    // Validation helpers
    public boolean isValid() {
        return !businessGoals.isEmpty() || !products.isEmpty();
    }

    public boolean isAlignedWithGoal(String goal) {
        return goal != null && businessGoals.stream().anyMatch(g -> g.equalsIgnoreCase(goal));
    }

    @Override
    public String toString() {
        return "BusinessContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", goals=" + businessGoals.size() +
                ", products=" + products.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> businessGoals = new LinkedHashSet<>();
        private Map<String, String> stakeholders = new HashMap<>();
        private Map<String, Double> kpis = new HashMap<>();
        private Set<String> products = new LinkedHashSet<>();
        private Map<String, String> processes = new HashMap<>();
        private Set<String> regulations = new LinkedHashSet<>();

        public Builder() {
            agentDomain("business");
            contextType("business-context");
        }

        public Builder addGoal(String goal) {
            if (goal != null) {
                businessGoals.add(goal);
            }
            return this;
        }

        public Builder businessGoals(Set<String> goals) {
            if (goals != null) {
                this.businessGoals.addAll(goals);
            }
            return this;
        }

        public Builder addStakeholder(String role, String owner) {
            if (role != null && owner != null) {
                stakeholders.put(role, owner);
            }
            return this;
        }

        public Builder stakeholders(Map<String, String> stakeholders) {
            if (stakeholders != null) {
                this.stakeholders.putAll(stakeholders);
            }
            return this;
        }

        public Builder addKpi(String name, Double value) {
            if (name != null && value != null) {
                kpis.put(name, value);
            }
            return this;
        }

        public Builder kpis(Map<String, Double> kpis) {
            if (kpis != null) {
                this.kpis.putAll(kpis);
            }
            return this;
        }

        public Builder addProduct(String product) {
            if (product != null) {
                products.add(product);
            }
            return this;
        }

        public Builder products(Set<String> products) {
            if (products != null) {
                this.products.addAll(products);
            }
            return this;
        }

        public Builder addProcess(String process, String owner) {
            if (process != null) {
                processes.put(process, owner);
            }
            return this;
        }

        public Builder processes(Map<String, String> processes) {
            if (processes != null) {
                this.processes.putAll(processes);
            }
            return this;
        }

        public Builder addRegulation(String regulation) {
            if (regulation != null) {
                regulations.add(regulation);
            }
            return this;
        }

        public Builder regulations(Set<String> regulations) {
            if (regulations != null) {
                this.regulations.addAll(regulations);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public BusinessContext build() {
            return new BusinessContext(this);
        }
    }
}
