package com.positivity.inboundmail.internal.service;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import java.security.SecureRandom;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Mints intake addresses (S27 ruling 8): {@code bills-} plus 26 lower-case RFC 4648 base32 characters
 * ({@code a-z2-7}), each from five bits of {@link SecureRandom}, so 130 bits in all, then
 * {@code @} and the configured intake domain. The result is a capability: unguessable, never derived
 * from the tenant, never logged.
 */
@Component
public class AddressGenerator {

    static final String PREFIX = "bills-";
    static final int RANDOM_CHARACTERS = 26;
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private final SecureRandom random;
    private final String domain;

    public AddressGenerator(InboundMailProperties properties) {
        this(new SecureRandom(), properties.domain());
    }

    AddressGenerator(SecureRandom random, String domain) {
        this.random = random;
        this.domain = domain;
    }

    /** A fresh address; two calls collide with probability 2^-130, and the unique index would refuse one. */
    public @NonNull String generate() {
        StringBuilder address = new StringBuilder(PREFIX.length() + RANDOM_CHARACTERS + 1 + domain.length());
        address.append(PREFIX);
        for (int i = 0; i < RANDOM_CHARACTERS; i++) {
            address.append(BASE32[random.nextInt(BASE32.length)]);
        }
        return address.append('@').append(domain).toString();
    }
}
