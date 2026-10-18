package com.positivity.inboundmail.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InboundMailPropertiesTest {

    private static InboundMailProperties with(String source, String domain, Duration ttl, long max) {
        return new InboundMailProperties("s", source, domain, ttl, "0 15 3 * * *", max);
    }

    @Test
    void noneIsTheOnlySourceAndDomainIsNormalised() {
        InboundMailProperties properties = with(" None ", " Mail.Example.Com ", Duration.ofDays(7), 1L);

        assertThat(properties.source()).isEqualTo("none");
        assertThat(properties.domain()).isEqualTo("mail.example.com");
    }

    @Test
    void anyOtherSourceFailsStartUp() {
        assertThatThrownBy(() -> with("ses", "mail.example.com", Duration.ofDays(7), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pos.inbound-mail.source");
    }

    @Test
    void rejectsNonsenseLimits() {
        assertThatThrownBy(() -> with("none", " ", Duration.ofDays(7), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("none", "mail.example.com", Duration.ZERO, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("none", "mail.example.com", Duration.ofDays(7), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
