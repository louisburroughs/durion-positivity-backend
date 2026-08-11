package com.positivity.documents;

import com.positivity.documents.internal.config.PdfConfiguration;
import com.positivity.shared.annotation.CoverageGenerated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties(PdfConfiguration.class)
public class PosDocumentsApplication {

    @CoverageGenerated
    public static void main(String[] args) {
        SpringApplication.run(PosDocumentsApplication.class, args);
    }
}
