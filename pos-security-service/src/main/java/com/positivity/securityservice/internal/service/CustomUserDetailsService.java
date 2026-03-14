package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
        private final UserRepository userRepository;

        @Override
        public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
                User user = userRepository.findByUsername(username)
                                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
                Set<GrantedAuthority> authorities = user.getRoles().stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                                .collect(Collectors.toSet());
                var delegate = new org.springframework.security.core.userdetails.User(
                                user.getUsername(),
                                user.getPassword(),
                                user.isEnabled(),
                                user.isAccountNonExpired(),
                                user.isCredentialsNonExpired(),
                                user.isAccountNonLocked(),
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
