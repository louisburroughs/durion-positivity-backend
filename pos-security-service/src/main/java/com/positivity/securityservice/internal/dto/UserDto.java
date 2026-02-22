package com.positivity.securityservice.internal.dto;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserDto {
    UUID id;
    String username;
    Set<String> roles;
}

