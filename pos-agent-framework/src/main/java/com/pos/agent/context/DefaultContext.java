package com.pos.agent.context;

public class DefaultContext extends AgentContext {

    protected DefaultContext(Builder builder) {
        super(builder);
        // Default basic fields
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        public Builder() {
            domain("default");
            contextType("default-context");
        }

        @Override
        protected Builder self() {
            return this;
        }

        public DefaultContext build() {
            return new DefaultContext(this);
        }
    }

}
