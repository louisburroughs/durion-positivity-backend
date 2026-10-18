package com.positivity.extractionworker;

import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * The extraction worker (CAP:550 S26): a stateless utility that opens untrusted bill files in a child
 * JVM per call. It has no datasource, no Kafka and no tenancy runtime (S26 ruling 13); nothing in
 * its classpath brings them, so no auto-configuration exclusion is needed.
 */
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class PosExtractionWorkerApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosExtractionWorkerApplication.class, args);
    }
}
