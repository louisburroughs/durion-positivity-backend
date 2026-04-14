package com.positivity.securityservice.internal.dto;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserAuthContext {
    UUID id;
    String username;
    String passwordHash;
    Set<String> roles;
}
