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
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedResource;
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

    /** The classpath root of the test resources, standing in for the service's own code source. */
    private static final String TEST_ROOT = RegistrationNumberShapes.codeSourceRoot(RegistrationNumberShapesTest.class);

    /** Loads a classpath YAML file the way Boot's config-data loader does: wrapped in an OriginTrackedResource. */
    private static StandardEnvironment environmentWith(String resource) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load(resource, OriginTrackedResource.of(new ClassPathResource(resource), null))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @Test
    @DisplayName("a shape set by an environment variable fails startup (never at runtime)")
    void shapeFromTheEnvironmentFailsStartup() {
        TaxProperties bound = TaxProfileFixtures.bind(stubsWith(p -> {}));
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "test-systemEnvironment", Map.of("POS_TAX_REGISTRATION_FORMATS_0_SHAPE", "A#########")));

        assertThatThrownBy(() -> new RegistrationNumberShapes(bound, new TaxCountryProfiles(bound), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Invalid tax configuration pos.tax.registration.formats[0].shape:")
                .hasMessageContaining("test-systemEnvironment")
                .hasMessageNotContaining("A#########");
    }

    @Test
    @DisplayName("a shape from a shipped profile file in the service's code source is accepted")
    void shapeFromAShippedProfileFileIsAccepted() throws Exception {
        StandardEnvironment environment = environmentWith("application-s32bguard.yml");

        assertThatCode(() -> RegistrationNumberShapes.requireShippedSource(environment, TEST_ROOT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a shape from any document of a shipped multi-document file is accepted")
    void shapeFromAMultiDocumentFileIsAccepted() throws Exception {
        StandardEnvironment environment = environmentWith("application-s32bmultidoc.yml");

        assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
                .anyMatch(name -> name.contains("(document #1)"));
        assertThatCode(() -> RegistrationNumberShapes.requireShippedSource(environment, TEST_ROOT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an application*.yml from another code source is refused")
    void shapeFromAnotherCodeSourceIsRefused() throws Exception {
        StandardEnvironment environment = environmentWith("application-s32bguard.yml");
        String serviceRoot = RegistrationNumberShapes.codeSourceRoot(RegistrationNumberShapes.class);

        assertThat(serviceRoot).isNotEqualTo(TEST_ROOT);
        assertThatThrownBy(() -> RegistrationNumberShapes.requireShippedSource(environment, serviceRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.tax.registration.formats[0]");
    }

    @Test
    @DisplayName("a classpath file not named application*.yml is refused")
    void shapeFromAnotherFileNameIsRefused() throws Exception {
        StandardEnvironment environment = environmentWith("s32b/shapes.yml");

        assertThatThrownBy(() -> RegistrationNumberShapes.requireShippedSource(environment, TEST_ROOT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.tax.registration.formats[0]");
    }

    @Test
    @DisplayName("a source that cannot list its names is refused, except random and stubs")
    void unlistableSourceIsRefused() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertySource<Object>("opaque-remote", new Object()) {
            @Override
            public Object getProperty(String name) {
                return null;
            }
        });

        assertThatThrownBy(() -> RegistrationNumberShapes.requireShippedSource(environment, TEST_ROOT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opaque-remote");

        StandardEnvironment allowed = new StandardEnvironment();
        allowed.getPropertySources().addLast(new RandomValuePropertySource());
        allowed.getPropertySources().addLast(new PropertySource.StubPropertySource("servletConfigInitParams"));
        assertThatCode(() -> RegistrationNumberShapes.requireShippedSource(allowed, TEST_ROOT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a number longer than 128 characters is never well formed and is not normalised")
    void overlongNumber() {
        RegistrationNumberShapes shapes = shapes(stubsWith(p -> {}));
        String overlong = "000000000RT0001" + " ".repeat(RegistrationNumberShapes.MAX_NUMBER_LENGTH);

        assertThat(shapes.wellFormed("GST_HST", overlong)).isFalse();
        assertThatThrownBy(() -> RegistrationNumberShapes.normalize(overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("000000000RT0001");
        // At the limit, a padded number still normalises and matches.
        String atLimit = "000000000RT0001" + " ".repeat(RegistrationNumberShapes.MAX_NUMBER_LENGTH - 15);
        assertThat(shapes.wellFormed("GST_HST", atLimit)).isTrue();
    }

    @Test
    @DisplayName("normalisation: String.trim, then only U+0020 and U+002D removed, then only a-z upper-cased")
    void normalisationRule() {
        assertThat(RegistrationNumberShapes.normalize("\t ab-c d\u00e9\u00a0 ")).isEqualTo("ABCD\u00e9\u00a0");
        assertThat(RegistrationNumberShapes.normalize("a\u2010b")).isEqualTo("A\u2010B");
    }

    /**
     * CAP:550 S32d AC 25: the normalisation matrix. The SAME rows, inputs and outputs, run in pos-tax's
     * {@code RegistrationNumberShapesTest} and pos-order's {@code SupplierRegistrationNumbersTest}, so the two copies of
     * the rule cannot drift (Order acknowledgement 2026-10-08). Changing one table without the other is a review
     * failure.
     */
    static Stream<Arguments> normalisationMatrix() {
        return Stream.of(
                Arguments.of(" 000 000 000-rt-0001 ", "000000000RT0001"),
                Arguments.of("000 000 000 rt 0001", "000000000RT0001"),
                Arguments.of("000000000rt0001", "000000000RT0001"),
                Arguments.of("\t000000000RT0001\n", "000000000RT0001"),
                Arguments.of("000000000\tRT0001", "000000000\tRT0001"),
                Arguments.of("000000000\u00A0RT0001", "000000000\u00A0RT0001"),
                Arguments.of("\u2003000000000RT0001", "\u2003000000000RT0001"),
                Arguments.of("000000000\u2013RT0001", "000000000\u2013RT0001"),
                Arguments.of("\u00e9-z", "\u00e9Z"),
                Arguments.of("- -", ""));
    }

    @ParameterizedTest(name = "[{index}] normalize")
    @MethodSource("normalisationMatrix")
    @DisplayName("CAP:550 S32d AC 25: the normalisation matrix shared with pos-order")
    void normalisationMatrixSharedWithPosOrder(String input, String expected) {
        assertThat(RegistrationNumberShapes.normalize(input)).isEqualTo(expected);
    }
}
