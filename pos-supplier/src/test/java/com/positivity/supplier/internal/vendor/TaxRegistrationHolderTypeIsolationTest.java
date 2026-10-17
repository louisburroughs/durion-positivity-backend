package com.positivity.supplier.internal.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #2623 AC 9 (Security ruling on #2617, ruling 1): protection never depends on a scheme's holder type. No production
 * class of pos-supplier outside the pos-tax client and its answer record reads {@code holderType}; the vendor entry,
 * the cipher, the masking and the fact treat an SSN exactly like an EIN.
 */
@DisplayName("holderType is read nowhere in pos-supplier but the pos-tax client (#2623 AC 9)")
class TaxRegistrationHolderTypeIsolationTest {

    private static final Path MAIN = Path.of("src/main/java/com/positivity/supplier");
    private static final String CLIENT_PACKAGE = "/internal/client/";

    @Test
    void onlyTheClientReadsHolderType() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/').contains(CLIENT_PACKAGE))
                    .filter(TaxRegistrationHolderTypeIsolationTest::mentionsHolderType)
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders)
                .as("production classes outside internal.client that mention holderType")
                .isEmpty();
    }

    private static boolean mentionsHolderType(Path path) {
        try {
            return Files.readString(path).contains("holderType");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
