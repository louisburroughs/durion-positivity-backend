package com.positivity.securityservice.internal.dto;

import java.util.Set;
import lombok.Data;

@Data
public class UserUpdateRequest {
    private String username;
    private String password;
    private Set<String> roles;
}
