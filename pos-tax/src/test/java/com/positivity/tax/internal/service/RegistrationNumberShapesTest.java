package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.tax.internal.config.TaxProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * CAP:550 S32b ACs 1 and 2: the registration-number shape stub. Every invalid shape fails startup naming
 * the property; nothing passes by default; matching normalises the number and never logs it.
 */
@DisplayName("RegistrationNumberShapes (CAP:550 S32b)")
class RegistrationNumberShapesTest {

    private static final String FORMATS = "pos.tax.registration.formats";

    private static Map<String, String> stubsWith(Consumer<Map<String, String>> change) {
        Map<String, String> properties = new LinkedHashMap<>(TaxProfileFixtures.FIRST_COUNTRY);
        properties.putAll(TaxProfileFixtures.FIRST_COUNTRY_STUBS);
        change.accept(properties);
        return properties;
    }

    private static RegistrationNumberShapes shapes(Map<String, String> properties) {
        TaxProperties bound = TaxProfileFixtures.bind(properties);
        return new RegistrationNumberShapes(bound, new TaxCountryProfiles(bound));
    }

    static TaxProperties shipped() throws Exception {
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(yaml), new PropertySourcesPlaceholdersResolver(yaml))
                .bind("pos.tax", TaxProperties.class)
                .get();
    }

    static Stream<Arguments> invalidShapes() {
        return Stream.of(
                Arguments.of(
                        "the GST_HST shape is missing",
                        stubsWith(p -> {
                            p.remove(FORMATS + "[0].regime");
                            p.remove(FORMATS + "[0].shape");
                            p.put(FORMATS + "[0].regime", "QST");
                            p.put(FORMATS + "[0].shape", "##########TQ####");
                            p.remove(FORMATS + "[1].regime");
                            p.remove(FORMATS + "[1].shape");
                        }),
                        FORMATS + ":"),
                Arguments.of(
                        "nine bare digits: no letter",
                        stubsWith(p -> p.put(FORMATS + "[0].shape", "#########")),
                        FORMATS + "[0].shape:"),
                Arguments.of(
                        "a character other than # or A-Z",
                        stubsWith(p -> p.put(FORMATS + "[0].shape", "#########RT###*")),
                        FORMATS + "[0].shape:"),
                Arguments.of(
                        "a lower-case letter",
                        stubsWith(p -> p.put(FORMATS + "[0].shape", "#########rt####")),
                        FORMATS + "[0].shape:"),
                Arguments.of(
                        "a blank shape", stubsWith(p -> p.put(FORMATS + "[0].shape", " ")), FORMATS + "[0].shape:"),
                Arguments.of(
                        "no shape on the entry",
                        stubsWith(p -> p.remove(FORMATS + "[0].shape")),
                        FORMATS + "[0].shape:"),
                Arguments.of(
                        "a shape longer than 32",
                        stubsWith(p -> p.put(FORMATS + "[0].shape", "A" + "#".repeat(32))),
                        FORMATS + "[0].shape:"),
                Arguments.of(
                        "a malformed regime code",
                        stubsWith(p -> p.put(FORMATS + "[1].regime", "q-st")),
                        FORMATS + "[1].regime:"),
                Arguments.of(
                        "a regime with two shapes",
                        stubsWith(p -> p.put(FORMATS + "[1].regime", "GST_HST")),
                        FORMATS + "[1].regime:"),
                Arguments.of(
                        "a supplier regime the country does not declare",
                        stubsWith(p -> p.put("pos.tax.countries.CA.supplier-registration-regime", "NO_SUCH")),
                        "pos.tax.countries.CA.supplier-registration-regime:"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidShapes")
    @DisplayName("AC 1: an invalid shape fails startup, naming the property")
    void invalidShapeFailsStartup(String label, Map<String, String> properties, String property) {
        assertThatThrownBy(() -> shapes(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid tax configuration " + property);
    }

    @Test
    @DisplayName("AC 1: a missing shape names the regime and where it is declared")
    void missingShapeNamesTheRegime() {
        Map<String, String> properties = stubsWith(p -> {
            p.remove(FORMATS + "[1].regime");
            p.remove(FORMATS + "[1].shape");
        });

        assertThatThrownBy(() -> shapes(properties))
                .hasMessage("Invalid tax configuration " + FORMATS + ": no shape is configured for regime QST,"
                        + " declared under pos.tax.countries.CA.regimes[1]");
    }

    @Test
    @DisplayName("AC 1 [M]: a shape of nine bare digits fails startup because it contains no letter")
    void nineBareDigitsFailStartup() {
        Map<String, String> properties = stubsWith(p -> p.put(FORMATS + "[0].shape", "#########"));

        assertThatThrownBy(() -> shapes(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FORMATS + "[0].shape")
                .hasMessageContaining("contains no letter");
    }

    @Test
    @DisplayName("AC 1: the shipped GST_HST and QST shapes start")
    void shippedShapesStart() throws Exception {
        TaxProperties shipped = shipped();

        RegistrationNumberShapes shapes = new RegistrationNumberShapes(shipped, new TaxCountryProfiles(shipped));

        assertThat(shipped.getRegistration().getFormats())
                .extracting(TaxProperties.RegistrationFormat::getRegime)
                .containsExactly("GST_HST", "QST");
        assertThat(shapes.wellFormed("GST_HST", "000000000RT0001")).isTrue();
        assertThat(shapes.wellFormed("QST", "0000000000TQ0001")).isTrue();
        assertThat(shapes.supplierRegime("CA")).contains("GST_HST");
    }

    @Test
    @DisplayName("AC 2: spaces, hyphens and case are normalised before matching")
    void normalisesBeforeMatching() throws Exception {
        TaxProperties shipped = shipped();
        RegistrationNumberShapes shapes = new RegistrationNumberShapes(shipped, new TaxCountryProfiles(shipped));

        assertThat(shapes.wellFormed("GST_HST", "000 000 000 rt 0001")).isTrue();
        assertThat(RegistrationNumberShapes.normalize("000 000 000 rt 0001")).isEqualTo("000000000RT0001");
        assertThat(shapes.wellFormed("GST_HST", " 000-000-000-RT-0001 ")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"000000000", "00000000RT0001", "000000000RT00011", "000000000TQ0001", "", " ", "A00000000RT0001"
            })
    @DisplayName("AC 2: a value that does not match the shape is not well formed")
    void nonMatchingValuesAreRefused(String number) throws Exception {
        TaxProperties shipped = shipped();
        RegistrationNumberShapes shapes = new RegistrationNumberShapes(shipped, new TaxCountryProfiles(shipped));

        assertThat(shapes.wellFormed("GST_HST", number)).isFalse();
    }

    @Test
    @DisplayName("nothing passes by default: a regime without a shape and a null number answer false")
    void noDefaultPass() {
        RegistrationNumberShapes shapes = shapes(stubsWith(p -> {}));

        assertThat(shapes.wellFormed("NO_SHAPE", "ANYTHING")).isFalse();
        assertThat(shapes.wellFormed(null, "000000000RT0001")).isFalse();
        assertThat(shapes.wellFormed("GST_HST", null)).isFalse();
        // A non-ASCII digit is not a digit.
        assertThat(shapes.wellFormed("GST_HST", "\u0660".repeat(9) + "RT0001")).isFalse();
    }

    @Test
    @DisplayName("AC 2: a failing value never reaches a pos-tax log, at any level")
    void failingValueIsNeverLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.positivity.tax");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            RegistrationNumberShapes shapes = shapes(stubsWith(p -> {}));
            assertThat(shapes.wellFormed("GST_HST", "123456789")).isFalse();
            assertThat(shapes.wellFormed("GST_HST", "12345678RT0001")).isFalse();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("123456789") || message.contains("12345678RT0001"));
    }

    @Test
    @DisplayName("a made-up country's shape works from configuration alone")
    void madeUpCountryShape() {
        Map<String, String> properties = new LinkedHashMap<>(TaxProfileFixtures.MADE_UP_COUNTRY);
        properties.putAll(TaxProfileFixtures.MADE_UP_COUNTRY_STUBS);
        RegistrationNumberShapes shapes = shapes(properties);

        assertThat(shapes.supplierRegime("ZZ")).contains("R_1");
        assertThat(shapes.wellFormed("R_1", "zz 12345")).isTrue();
        assertThat(shapes.wellFormed("R_1", "1212345")).isFalse();
    }

    @Test
    @DisplayName("a shape set outside the shipped configuration fails startup (never at runtime)")
    void shapeFromTheEnvironmentFailsStartup() {
        TaxProperties bound = TaxProfileFixtures.bind(stubsWith(p -> {}));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "test-systemEnvironment", Map.of("POS_TAX_REGISTRATION_FORMATS_0_SHAPE", "A#########")));

        assertThatThrownBy(() -> new RegistrationNumberShapes(bound, new TaxCountryProfiles(bound), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid tax configuration pos.tax.registration:")
                .hasMessageContaining("test-systemEnvironment");
    }

    @Test
    @DisplayName("a shape from the shipped classpath configuration is accepted")
    void shapeFromTheShippedConfigurationIsAccepted() {
        TaxProperties bound = TaxProfileFixtures.bind(stubsWith(p -> {}));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'",
                        Map.of(FORMATS + "[0].shape", "#########RT####")));

        assertThatCode(() -> new RegistrationNumberShapes(bound, new TaxCountryProfiles(bound), environment))
                .doesNotThrowAnyException();
    }
}
