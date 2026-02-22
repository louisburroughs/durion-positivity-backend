package com.positivity.securityservice.internal.dto;

import java.util.Set;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserAuthContext {
    String username;
    String passwordHash;
    Set<String> roles;
}

