package com.pos.agent.core;

import java.util.List;

/**
 * Security context for agent requests.
 * Contains authentication and authorization information.
 */
public class SecurityContext {
    private final String jwtToken;
    private final String userId;
    private final List<String> roles;
    private final List<String> permissions;
    private final String serviceId;
    private final String serviceType;

    private SecurityContext(Builder builder) {
        this.jwtToken = builder.jwtToken;
        this.userId = builder.userId;
        this.roles = builder.roles;
        this.permissions = builder.permissions;
        this.serviceId = builder.serviceId;
        this.serviceType = builder.serviceType;
    }

    public String getJwtToken() {
        return jwtToken;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getServiceType() {
        return serviceType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String jwtToken;
        private String userId;
        private List<String> roles;
        private List<String> permissions;
        private String serviceId;
        private String serviceType;

        public Builder jwtToken(String jwtToken) {
            this.jwtToken = jwtToken;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder roles(List<String> roles) {
            this.roles = roles;
            return this;
        }

        public Builder permissions(List<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        public Builder serviceType(String serviceType) {
            this.serviceType = serviceType;
            return this;
        }

        public SecurityContext build() {
            return new SecurityContext(this);
        }
    }
}
