package com.pos.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Security context for agent requests.
 * Contains authentication and authorization information.
 */
public class SecurityContext {
    private final String jwtToken;
    

    private SecurityContext(Builder builder) {
        this.jwtToken = builder.jwtToken;
        
    }

    public String getJwtToken() {
        return jwtToken;
    }

   
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String jwtToken;
        private String userId;
        private List<Roles> roles;
        private List<Permissions> permissions;
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

        public Builder roles(List<Roles> roles) {
            this.roles = roles;
            return this;
        }

        public Builder permissions(List<Permissions> permissions) {
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
            JwtTokenUtil.SecurityPayload decodedPayload = null;

            if (jwtToken != null && !jwtToken.isBlank()) {
                decodedPayload = JwtTokenUtil.decode(jwtToken);
            }

            if (userId == null && decodedPayload != null) {
                this.userId = decodedPayload.userId();
            }
            if (roles == null && decodedPayload != null) {
                this.roles = toRoles(decodedPayload.roles());
            }
            if (permissions == null && decodedPayload != null) {
                this.permissions = toPermissions(decodedPayload.permissions());
            }
            if (serviceId == null && decodedPayload != null) {
                this.serviceId = decodedPayload.serviceId();
            }
            if (serviceType == null && decodedPayload != null) {
                this.serviceType = decodedPayload.serviceType();
            }

            JwtTokenUtil.SecurityPayload payload = new JwtTokenUtil.SecurityPayload(
                    this.userId,
                    toStringList(this.roles),
                    toStringList(this.permissions),
                    this.serviceId,
                    this.serviceType);

            this.jwtToken = JwtTokenUtil.encode(payload);
            return new SecurityContext(this);
        }

        private List<String> toStringList(List<? extends Enum<?>> values) {
            if (values == null) {
                return new ArrayList<>();
            }
            return values.stream().filter(Objects::nonNull).map(Enum::name).collect(Collectors.toList());
        }

        private List<Roles> toRoles(List<String> names) {
            if (names == null) {
                return null;
            }
            List<Roles> resolved = new ArrayList<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    resolved.add(Roles.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // Skip unknown role
                }
            }
            return resolved;
        }

        private List<Permissions> toPermissions(List<String> names) {
            if (names == null) {
                return null;
            }
            List<Permissions> resolved = new ArrayList<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    resolved.add(Permissions.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // Skip unknown permission
                }
            }
            return resolved;
        }
    }
}
