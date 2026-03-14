package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.service.AuthenticationService;
import com.positivity.securityservice.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    @Override
    @NonNull
    public TokenPairResponse login(@NonNull LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        // BadCredentialsException propagates to GlobalExceptionHandler if credentials are invalid.

        String username = authentication.getName();

        // Extract userId from principal. CustomUserDetailsService always returns SecurityUserPrincipal.
        // Any other principal type is a programming error — fail fast rather than silently issuing
        // a token bound to a random, untrackable identity.
        if (!(authentication.getPrincipal() instanceof CustomUserDetailsService.SecurityUserPrincipal p)) {
            throw new IllegalStateException(
                    "Unexpected principal type: " + authentication.getPrincipal().getClass().getName());
        }
        UUID userId = p.userId();

        Set<String> roleNames = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toSet());

        JwtService.TokenPair pair = jwtService.generateTokenPair(username, userId, roleNames);
        return TokenPairResponse.of(pair.accessToken(), pair.refreshToken());
    }
}
