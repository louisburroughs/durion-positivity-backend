package com.positivity.supplier.internal.adapter.ediwheelc12;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the C1.2 marketing catalogue (CAP-324 #1230).
 *
 * <p>The behaviours worth pinning are the ones where being wrong loses material silently: artwork
 * published in two places of which only one is read, copy whose language cannot be established, and
 * an unreadable response mistaken for an empty catalogue.
 */
@DisplayName("EdiwheelC12MktCatCodec — reading the marketing catalogue (#1230)")
class EdiwheelC12MktCatCodecTest {

    private final EdiwheelC12MktCatCodec codec =
            new EdiwheelC12MktCatCodec(JsonMapper.builder().build());

    @Nested
    @DisplayName("an unreadable catalogue is not an empty one")
    class Readability {

        @Test
        @DisplayName("an empty variant list body is refused rather than read as no variants")
        void emptyBodyIsRefused() {
            // The importer diffs. "The catalogue published nothing" and "we could not read it" would
            // otherwise reach the same conclusion: that every variant held has been withdrawn.
            assertThatThrownBy(() -> codec.decodeVariantIds(null)).isInstanceOf(MktCatDecodeException.class);
            assertThatThrownBy(() -> codec.decodeVariantIds("   ")).isInstanceOf(MktCatDecodeException.class);
        }

        @Test
        @DisplayName("a variant list that is not JSON is refused")
        void unreadableListIsRefused() {
            assertThatThrownBy(() -> codec.decodeVariantIds("<html>maintenance</html>"))
                    .isInstanceOf(MktCatDecodeException.class);
        }

        @Test
        @DisplayName("an empty JSON array is an empty catalogue, which is a real answer")
        void emptyArrayIsAnAnswer() {
            assertThat(codec.decodeVariantIds("[]")).isEmpty();
        }

        @Test
        @DisplayName("a variant with no detail body is refused rather than published blank")
        void missingDetailIsRefused() {
            assertThatThrownBy(() -> codec.decodeVariant("V1", null, null, null))
                    .isInstanceOf(MktCatDecodeException.class);
        }
    }

    @Nested
    @DisplayName("assembling a variant")
    class Assembly {

        @Test
        @DisplayName("the id is carried through, never taken from the catalogue's echo")
        void idIsOurs() {
            MarketingVariant variant = codec.decodeVariant(
                    "V1", "{\"TreadDesignVariant\":\"SOMETHING-ELSE\",\"Brand\":\"Michelin\"}", null, null);

            // A detail body echoing a different id is a catalogue defect; adopting it would attach
            // this variant's marketing copy to another design.
            assertThat(variant.vendorVariantId()).isEqualTo("V1");
            assertThat(variant.brand()).isEqualTo("Michelin");
        }

        @Test
        @DisplayName("a variant with neither artwork nor copy is still a variant")
        void bareVariantSurvives() {
            MarketingVariant variant = codec.decodeVariant("V1", "{\"TreadDesign1\":\"Primacy 4\"}", null, null);

            assertThat(variant.treadDesign()).isEqualTo("Primacy 4");
            assertThat(variant.texts()).isEmpty();
            assertThat(variant.imageUris()).isEmpty();
        }

        @Test
        @DisplayName("blank vendor fields become absent rather than empty strings")
        void blanksBecomeAbsent() {
            MarketingVariant variant =
                    codec.decodeVariant("V1", "{\"Brand\":\"   \",\"TreadDesign2\":\"\"}", null, null);

            assertThat(variant.brand()).isNull();
            assertThat(variant.treadDesign2()).isNull();
        }
    }

    @Nested
    @DisplayName("artwork, which the catalogue publishes in two places")
    class Artwork {

        private static final String IMAGES = """
                [{"ImageType":"TREAD","URI":"https://cdn.example.com/tread.jpg"}]
                """;

        private static final String LANGUAGES = """
                [{"LanguageID":"de","Description":"Sommerreifen",
                  "AdditionalGraphic":[{"Graphic":"LABEL","URI":"https://cdn.example.com/label-de.png"}]},
                 {"LanguageID":"fr","Description":"Pneu ete",
                  "AdditionalGraphic":[{"Graphic":"LABEL","URI":"https://cdn.example.com/label-fr.png"}]}]
                """;

        @Test
        @DisplayName("both the images resource and the per-language graphics are collected")
        void bothSourcesAreRead() {
            // Reading only the variant's own images resource would silently drop the per-market
            // artwork, which is the material a marketing catalogue exists to supply.
            MarketingVariant variant = codec.decodeVariant("V1", "{}", IMAGES, LANGUAGES);

            assertThat(variant.imageUris())
                    .extracting(MarketingVariant.MarketingImageRef::uri)
                    .containsExactly(
                            "https://cdn.example.com/tread.jpg",
                            "https://cdn.example.com/label-de.png",
                            "https://cdn.example.com/label-fr.png");
        }

        @Test
        @DisplayName("the same picture listed against several languages is fetched once")
        void duplicateUrisAreCollapsed() {
            String sharedAcrossLanguages = """
                    [{"LanguageID":"de","AdditionalGraphic":[{"URI":"https://cdn.example.com/shared.png"}]},
                     {"LanguageID":"fr","AdditionalGraphic":[{"URI":"https://cdn.example.com/shared.png"}]}]
                    """;

            MarketingVariant variant = codec.decodeVariant("V1", "{}", null, sharedAcrossLanguages);

            // Otherwise every ingest is multiplied by the number of markets, for one picture.
            assertThat(variant.imageUris()).hasSize(1);
        }

        @Test
        @DisplayName("an unreadable image list fails the variant, because artwork is not optional to lose quietly")
        void unreadableImageListIsRefused() {
            assertThatThrownBy(() -> codec.decodeVariant("V1", "{}", "not json", null))
                    .isInstanceOf(MktCatDecodeException.class);
        }
    }

    @Nested
    @DisplayName("marketing copy")
    class Copy {

        @Test
        @DisplayName("copy is kept per language rather than flattened")
        void copyIsPerLanguage() {
            MarketingVariant variant = codec.decodeVariant("V1", "{}", null, """
                    [{"LanguageID":"de","ProductName":"Primacy","Description":"Sommerreifen","FootNotes":"*"},
                     {"LanguageID":"en","ProductName":"Primacy","Description":"Summer tyre"}]
                    """);

            assertThat(variant.texts()).hasSize(2);
            assertThat(variant.texts().getFirst().languageCode()).isEqualTo("de");
            assertThat(variant.texts().getFirst().footNotes()).isEqualTo("*");
        }

        @Test
        @DisplayName("copy with no language is dropped, because it cannot be shown to anyone safely")
        void copyWithoutALanguageIsDropped() {
            // A shop would have no way to know it was reading German text to a French customer.
            MarketingVariant variant = codec.decodeVariant("V1", "{}", null, "[{\"Description\":\"orphaned text\"}]");

            assertThat(variant.texts()).isEmpty();
        }

        @Test
        @DisplayName("an unreadable language body costs the copy, not the variant")
        void unreadableCopyDoesNotFailTheVariant() {
            MarketingVariant variant = codec.decodeVariant("V1", "{\"Brand\":\"Michelin\"}", null, "not json");

            assertThat(variant.brand()).isEqualTo("Michelin");
            assertThat(variant.texts()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the requests it builds")
    class Requests {

        @Test
        @DisplayName("reads are idempotent and subscribe is not")
        void idempotencyMatchesTheEffect() {
            assertThat(codec.buildVariantListRequest().idempotent()).isTrue();
            assertThat(codec.buildVariantDetailRequest("V1").idempotent()).isTrue();
            // The catalogue records a subscription per call, so a blind re-send risks a second one
            // and therefore duplicate callbacks.
            assertThat(codec.buildSubscribeRequest("https://us/callback", "TDV_CHANGED")
                            .idempotent())
                    .isFalse();
        }

        @Test
        @DisplayName("a catalogue id is encoded, so it cannot alter the path it sits in")
        void idsCannotEscapeTheirSegment() {
            SupplierRequestSpec spec = codec.buildVariantDetailRequest("V/../../admin");
            assertThat(spec.pathSuffix()).contains("%2F").doesNotContain("/../");
        }

        @Test
        @DisplayName("subscribe carries the callback and the event the catalogue needs")
        void subscribeCarriesItsParameters() {
            SupplierRequestSpec spec = codec.buildSubscribeRequest("https://us/callback", "TDV_CHANGED");

            assertThat(spec.queryParams())
                    .containsEntry("callbackUrl", "https://us/callback")
                    .containsEntry("event", "TDV_CHANGED");
        }
    }
}
