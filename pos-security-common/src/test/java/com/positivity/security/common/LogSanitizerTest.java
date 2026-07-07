package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void forLog_null_returnsLiteralNull() {
        assertThat(LogSanitizer.forLog(null)).isEqualTo("null");
    }

    @Test
    void forLog_plainValue_isUnchanged() {
        assertThat(LogSanitizer.forLog("keyName")).isEqualTo("keyName");
    }

    @Test
    void forLog_stripsCarriageReturnAndLineFeed() {
        // A classic log-forging payload injecting a fake second log line.
        String forged = "keyName\r\n2026-01-01 INFO admin logged in";
        assertThat(LogSanitizer.forLog(forged)).isEqualTo("keyName__2026-01-01 INFO admin logged in");
    }

    @Test
    void forLog_stripsTabsButKeepsOrdinarySpaces() {
        // Tab (0x09) is a control char and is replaced; the regular space (0x20) is kept.
        assertThat(LogSanitizer.forLog("a\tb c")).isEqualTo("a_b c");
    }

    @Test
    void forLog_nonStringValue_usesToString() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(LogSanitizer.forLog(id)).isEqualTo("00000000-0000-0000-0000-000000000001");
    }
}
