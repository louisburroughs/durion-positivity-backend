package com.positivity.marketing.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the grammar a campaign's catalog reference must follow (Story #1148).
 *
 * <p>The reference is free text pointing into another domain, so the grammar is the only thing
 * standing between "the message points at the alignment service" and a string nobody can ever
 * resolve. The cases below are the mistakes actually made: a bare name, a stray separator, and
 * a kind this platform has never heard of.
 */
@DisplayName("CatalogFocusRef — kind:value grammar")
class CatalogFocusRefTest {

    @Test
    @DisplayName("parses the canonical service reference")
    void parsesServiceReference() {
        Optional<CatalogFocusRef> parsed = CatalogFocusRef.parse("service:alignment");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().kind()).isEqualTo(CatalogFocusRef.Kind.SERVICE);
        assertThat(parsed.get().value()).isEqualTo("alignment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"product:018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03", "sku:BRK-2201", "category:Brakes"})
    @DisplayName("parses every recognized kind")
    void parsesEveryKind(String raw) {
        assertThat(CatalogFocusRef.parse(raw)).isPresent();
    }

    @Test
    @DisplayName("matches the kind case-insensitively and normalizes it")
    void kindIsCaseInsensitive() {
        assertThat(CatalogFocusRef.parse("SERVICE:alignment"))
                .get()
                .extracting(CatalogFocusRef::kind)
                .isEqualTo(CatalogFocusRef.Kind.SERVICE);
    }

    @Test
    @DisplayName("trims surrounding whitespace on both halves")
    void trimsWhitespace() {
        assertThat(CatalogFocusRef.parse("  service : alignment  "))
                .get()
                .extracting(CatalogFocusRef::value)
                .isEqualTo("alignment");
    }

    @Test
    @DisplayName("splits on the first separator only, so a name may contain colons")
    void splitsOnFirstSeparatorOnly() {
        assertThat(CatalogFocusRef.parse("service:front:alignment"))
                .get()
                .extracting(CatalogFocusRef::value)
                .isEqualTo("front:alignment");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "alignment", // the mistake actually made: a bare name with no kind
                "wheel-alignment:front", // a kind this platform cannot interpret
                ":alignment", // no kind
                "service:", // no value
                "service:   ", // whitespace is not a value
                ":"
            })
    @DisplayName("rejects anything that cannot be followed back to the catalog")
    void rejectsUnusableReferences(String raw) {
        assertThat(CatalogFocusRef.parse(raw)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("treats absence as absence, not as a malformed reference")
    void absentIsEmpty(String raw) {
        assertThat(CatalogFocusRef.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("round-trips back to its wire form so an error message can quote it")
    void roundTripsToWireForm() {
        assertThat(CatalogFocusRef.parse("SERVICE:alignment")).get().hasToString("service:alignment");
    }

    @Test
    @DisplayName("lists every supported kind, so a rejection can say what to write instead")
    void listsSupportedKinds() {
        assertThat(CatalogFocusRef.supportedKinds()).isEqualTo("product, sku, service, category");
    }
}
