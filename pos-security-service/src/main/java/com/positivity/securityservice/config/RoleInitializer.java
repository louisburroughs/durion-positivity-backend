package com.positivity.securityservice.config;

import com.positivity.securityservice.model.Role;
import com.positivity.securityservice.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RoleInitializer {
    private final RoleRepository roleRepository;

    @PostConstruct
    public void initRoles() {
        List<String> roles = List.of("ADMIN", "GENERAL_MANAGER", "MANAGER", "CUSTOMER");
        for (String roleName : roles) {
            if (!roleRepository.existsByName(roleName)) {
                Role role = new Role();
                role.setName(roleName);
                roleRepository.save(role);
            }
        }
    }
}

