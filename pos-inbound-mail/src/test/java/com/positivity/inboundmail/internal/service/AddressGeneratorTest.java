package com.positivity.inboundmail.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inboundmail.internal.config.InboundMailProperties;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddressGenerator: bills- plus 26 lower-case base32 characters (S27 ruling 8)")
class AddressGeneratorTest {

    private static final Pattern FORMAT = Pattern.compile("bills-[a-z2-7]{26}@mail\\.example\\.com");

    private final AddressGenerator generator = new AddressGenerator(new InboundMailProperties(
            "s", "none", "Mail.Example.COM", Duration.ofDays(7), "0 15 3 * * *", 26_214_400L));

    @Test
    void formatIsExactAndDomainIsLowerCased() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).matches(FORMAT);
        }
    }

    @Test
    void addressesDoNotRepeatAndUseTheWholeAlphabet() {
        Set<String> addresses = new HashSet<>();
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String address = generator.generate();
            addresses.add(address);
            address.substring("bills-".length(), "bills-".length() + 26).chars().forEach(c -> seen.add((char) c));
        }
        assertThat(addresses).as("130 bits: no collision in 2000 draws").hasSize(2_000);
        assertThat(seen)
                .as("all 32 base32 characters appear (a collapsed alphabet would show here)")
                .hasSize(32);
    }
}
