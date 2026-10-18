package com.positivity.accounting.internal.billintake.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings of bill intake (CAP:550 S25, #2518), under {@code pos.accounting.bill-intake}.
 *
 * <p>The adapters are chosen by {@code scanner.adapter} ({@code unavailable}, the default, or {@code clamd}) and
 * {@code extraction.adapter} (only {@code unavailable} ships in Part B). With the {@code unavailable} adapters an
 * upload stays {@code QUARANTINED}: an outage never rejects a file (ruling 2).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "pos.accounting.bill-intake")
public class BillIntakeProperties {

    private final Scanner scanner = new Scanner();
    private final Extraction extraction = new Extraction();
    private final Processor processor = new Processor();

    /** How long an extraction outage is retried before a draft is left blank for a person to key (ruling 2). */
    private Duration extractionRetryWindow = Duration.ofMinutes(15);

    /** A file quarantined longer than this raises the outage alert (a WARN and the gauge); it is never rejected. */
    private Duration quarantineAlertAfter = Duration.ofHours(24);

    /** How long the bytes of a refused file are kept (malware, scan failure, inspection refusal). */
    private Duration rejectedRetention = Duration.ofDays(30);

    /** At most this many files in one upload request. */
    private int maxFiles = 10;

    /** At most this many bytes in one file. */
    private long maxFileBytes = 25L * 1024 * 1024;

    @Data
    public static class Scanner {
        /** {@code unavailable} (the dev and test default, answers "unavailable") or {@code clamd}. */
        private String adapter = "unavailable";

        private String host = "clamav";
        private int port = 3310;
        private Duration timeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Extraction {
        /** Only {@code unavailable} ships before the worker's adapter (S26 Part D). */
        private String adapter = "unavailable";

        /** The extraction proxy the worker is reached through; pos-accounting never joins the worker's network. */
        private String baseUrl = "http://extraction-proxy:8080";
    }

    @Data
    public static class Processor {
        private boolean enabled = true;
        private Duration interval = Duration.ofSeconds(30);
        private Duration initialDelay = Duration.ofMinutes(1);

        /** Items one tenant's pass handles per step before it moves on. */
        private int batch = 25;

        /** The longest wait before a step that found a dependency unavailable is tried again. */
        private Duration maxBackoff = Duration.ofMinutes(15);
    }
}
