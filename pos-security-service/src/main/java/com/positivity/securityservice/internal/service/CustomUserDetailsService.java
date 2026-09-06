package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;
    private final RoleAssignmentRepository roleAssignmentRepository;
    private final Clock clock;

    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        User user = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        Set<String> effectiveRoles =
                new HashSet<>(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()));
        var effectiveAssignments = roleAssignmentRepository.findEffectiveAssignmentsByUser(user);
        List<String> assignedRoles = effectiveAssignments == null
                ? List.of()
                : effectiveAssignments.stream()
                        .map(assignment -> assignment.getRole().getName())
                        .toList();
        effectiveRoles.addAll(assignedRoles);

        Set<GrantedAuthority> authorities = effectiveRoles.stream()
                .map(roleName -> new SimpleGrantedAuthority("ROLE_" + roleName))
                .collect(Collectors.toSet());
        // A timed lockout whose lockedUntil has passed is not a lock; an administrative lock has
        // lockedUntil == null and stays one. This mirrors LockoutServiceImpl#isLockedOut so the
        // bearer path (JwtAuthenticationFilter) and the login path share one definition of
        // "locked" (#1803) — otherwise a lapsed lockout kept refusing bearer tokens until someone
        // attempted a password login. The persisted flag itself is cleared by
        // LockoutServiceImpl#unlockIfCooldownExpired on the next login and is deliberately not
        // written here: a read path must not write.
        boolean accountNonLocked = user.isAccountNonLocked()
                || (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(Instant.now(clock)));
        var delegate = new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.isEnabled(),
                user.isAccountNonExpired(),
                user.isCredentialsNonExpired(),
                accountNonLocked,
                authorities);
        return new SecurityUserPrincipal(user.getId(), user.getPersonId(), delegate);
    }

    public record SecurityUserPrincipal(
            @NonNull UUID userId,
            @Nullable UUID personId,
            org.springframework.security.core.userdetails.@NonNull User delegate)
            implements UserDetails {

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public String getPassword() {
            return delegate.getPassword();
        }

        @Override
        public String getUsername() {
            return delegate.getUsername();
        }

        @Override
        public boolean isAccountNonExpired() {
            return delegate.isAccountNonExpired();
        }

        @Override
        public boolean isAccountNonLocked() {
            return delegate.isAccountNonLocked();
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return delegate.isCredentialsNonExpired();
        }

        @Override
        public boolean isEnabled() {
            return delegate.isEnabled();
        }
    }
}
