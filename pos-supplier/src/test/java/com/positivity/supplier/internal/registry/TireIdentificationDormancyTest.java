package com.positivity.supplier.internal.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DOT and sidewall scanning stay dormant, and are verified to be (CAP-324 #1230, §12 decision 10).
 *
 * <h2>Why an absence needs a test</h2>
 *
 * {@code TireIdentificationPort} is a deliberate placeholder: DOT scanning is likely to be required
 * by most service providers, but it has not been confirmed, and the decision was to define the port
 * and build no adapter until it is. An unwritten adapter needs no test — but a decision to leave one
 * unwritten does, because nothing else would notice the day somebody adds one "while they were in
 * there". By then the capability is half-built, unowned and untested against a vendor nobody chose.
 *
 * <p>So this asserts the two things that make dormancy real: the registry answers
 * {@code CAPABILITY_NOT_CONFIGURED} for the capability, and no codec claims it.
 */
@DisplayName("Tire identification — verified dormant (#1230)")
class TireIdentificationDormancyTest {

    @Test
    @DisplayName("no codec claims the tire-identification capability")
    void noCodecClaimsIt() throws IOException {
        List<String> claimants;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java/com/positivity/supplier"))) {
            claimants = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(TireIdentificationDormancyTest::claimsTireIdentification)
                    .map(Path::toString)
                    .toList();
        }

        // The enum value may be referenced — by the port, and by the enum itself. What must not
        // exist is a codec returning it from capability(), because that is what would make the
        // registry resolve one.
        assertThat(claimants)
                .as("files declaring a codec for TIRE_IDENTIFICATION")
                .isEmpty();
    }

    @Test
    @DisplayName("the registry answers not-configured rather than resolving something")
    void registryReportsNotConfigured() {
        // An empty registry is the right shape for this assertion: it proves the not-configured
        // answer is what an unbound capability produces, rather than an exception a caller would
        // have to catch.
        AdapterRegistry registry = new AdapterRegistry(List.<SupplierAdapterCodec>of());

        AdapterResolution resolution = registry.resolve(
                SupplierCapability.TIRE_IDENTIFICATION, ProtocolFamily.TEST, new ProtocolVersion("V1"));

        assertThat(resolution).isInstanceOf(AdapterResolution.NotConfigured.class);
    }

    /** Whether a file declares a codec whose {@code capability()} returns tire identification. */
    private static boolean claimsTireIdentification(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return source.contains("implements SupplierAdapterCodec")
                    && source.contains("SupplierCapability.TIRE_IDENTIFICATION");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
