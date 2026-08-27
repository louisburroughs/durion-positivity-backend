package com.positivity.tax.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxJurisdiction;
import com.positivity.tax.common.dto.TaxRateComponent;
import com.positivity.tax.common.dto.TaxRateLookupResponse;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.common.enums.TaxReferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the pos-tax-common wire contract, which was at 34% line coverage.
 *
 * <p>
 * This library is shared between pos-tax and every service that asks it to
 * calculate tax, so its enums and request DTO <em>are</em> the contract between
 * them. Two properties are worth holding down:
 *
 * <ul>
 * <li><b>Enum constant names are serialized values.</b> Jackson writes these
 * across a service boundary, so renaming a constant is a breaking wire change
 * that looks like a local refactor. The round trip is asserted for every
 * constant rather than a sample, so adding one is free and renaming one is
 * loud.</li>
 * <li><b>{@code TaxJurisdictionType} carries an explicit code</b> and resolves
 * case-insensitively, because jurisdiction codes arrive from third-party tax
 * providers whose casing is not ours to control. Unknown values are rejected
 * rather than defaulted — a jurisdiction quietly mapped to the wrong type is a
 * tax rate applied at the wrong level of government.</li>
 * </ul>
 */
@DisplayName("pos-tax-common — the shared wire contract")
class TaxCommonContractTest {

    @Nested
    @DisplayName("enum wire values")
    class EnumWireValues {

        @ParameterizedTest
        @EnumSource(TaxReferenceType.class)
        @DisplayName("every TaxReferenceType resolves from its own name")
        void referenceTypeRoundTrips(TaxReferenceType value) {
            // No @JsonValue on this enum, so Jackson serializes it by name(): the constant
            // spelling is the wire value, and valueOf is the exact inverse Jackson performs.
            assertThat(TaxReferenceType.valueOf(value.name())).isEqualTo(value);
        }

        @ParameterizedTest
        @EnumSource(ExemptionReasonCode.class)
        @DisplayName("every ExemptionReasonCode resolves from its own name")
        void exemptionReasonRoundTrips(ExemptionReasonCode value) {
            // These end up on stored exemption certificates, so a rename would orphan every
            // certificate already written with the old spelling.
            assertThat(ExemptionReasonCode.valueOf(value.name())).isEqualTo(value);
        }

        @ParameterizedTest
        @EnumSource(TaxProviderTransactionStatus.class)
        @DisplayName("every TaxProviderTransactionStatus resolves from its own name")
        void providerStatusRoundTrips(TaxProviderTransactionStatus value) {
            assertThat(TaxProviderTransactionStatus.valueOf(value.name())).isEqualTo(value);
        }

        @Test
        @DisplayName("the provider status set still distinguishes not-yet-committed from failed")
        void providerStatusesRemainDistinct() {
            // PENDING_COMMIT and FAILED are the two that decide whether a reconciliation job
            // retries a transaction or reports it. Collapsing them would either strand real
            // failures or replay committed tax.
            assertThat(TaxProviderTransactionStatus.values())
                    .containsExactly(
                            TaxProviderTransactionStatus.PENDING_COMMIT,
                            TaxProviderTransactionStatus.COMMITTED,
                            TaxProviderTransactionStatus.VOIDED,
                            TaxProviderTransactionStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("jurisdiction type resolution")
    class JurisdictionTypes {

        @ParameterizedTest
        @EnumSource(TaxJurisdictionType.class)
        @DisplayName("every jurisdiction type resolves back from the code it serializes as")
        void jurisdictionRoundTrips(TaxJurisdictionType value) {
            assertThat(TaxJurisdictionType.fromValue(value.code())).isEqualTo(value);
        }

        @Test
        @DisplayName("the code is the serialized form and fromValue is the deserializer")
        void serializationHooksArePresent() throws Exception {
            // Asserted by reflection rather than through an ObjectMapper: this module depends on
            // jackson-annotations only, deliberately, so that a shared DTO library does not pin
            // a databind version on every consumer. That makes the annotations themselves the
            // contract — without @JsonValue the enum would serialize by name() instead of by
            // code(), which is the same string today for every constant and would therefore
            // break silently the first time the two diverge.
            assertThat(TaxJurisdictionType.class.getMethod("code").isAnnotationPresent(JsonValue.class))
                    .isTrue();
            assertThat(TaxJurisdictionType.class
                            .getMethod("fromValue", String.class)
                            .isAnnotationPresent(JsonCreator.class))
                    .isTrue();
        }

        @ParameterizedTest
        @EnumSource(TaxJurisdictionType.class)
        @DisplayName("every jurisdiction type has a distinct, non-blank i18n key")
        void i18nKeysArePresentAndDistinct(TaxJurisdictionType value) {
            assertThat(value.i18nKey()).isNotBlank().startsWith("tax.jurisdiction.type.");
        }

        @Test
        @DisplayName("no two jurisdiction types share a code or an i18n key")
        void codesAndKeysAreUnique() {
            // A duplicated code would make fromValue return whichever constant happened to be
            // declared first, silently mapping one level of government onto another.
            assertThat(TaxJurisdictionType.values())
                    .extracting(TaxJurisdictionType::code)
                    .doesNotHaveDuplicates();
            assertThat(TaxJurisdictionType.values())
                    .extracting(TaxJurisdictionType::i18nKey)
                    .doesNotHaveDuplicates();
        }

        @ParameterizedTest(name = "[{0}] resolves to STATE")
        @ValueSource(strings = {"STATE", "state", "State", "sTaTe"})
        @DisplayName("resolution is case-insensitive, because provider casing is not ours to control")
        void resolutionIsCaseInsensitive(String input) {
            assertThat(TaxJurisdictionType.fromValue(input)).isEqualTo(TaxJurisdictionType.STATE);
        }

        @ParameterizedTest(name = "[{0}] resolves to null")
        @CsvSource(
                value = {"NULL", "''", "'   '"},
                nullValues = "NULL")
        @DisplayName("an absent value resolves to null rather than throwing")
        void absentValueIsNull(String input) {
            // Absent and unknown are treated differently on purpose: a missing jurisdiction is
            // an optional field, while a populated one we cannot understand is a data error.
            assertThat(TaxJurisdictionType.fromValue(input)).isNull();
        }

        @ParameterizedTest(name = "[{0}] is rejected")
        @ValueSource(strings = {"PARISH", "state ", "STATES", "ST"})
        @DisplayName("an unrecognised jurisdiction is rejected rather than defaulted")
        void unknownValueThrows(String input) {
            // "state " with a trailing space and "STATES" are the interesting ones: the match is
            // equalsIgnoreCase, not trim-or-prefix, so near-misses fail loudly instead of
            // resolving to a neighbouring jurisdiction level.
            assertThatThrownBy(() -> TaxJurisdictionType.fromValue(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown tax jurisdiction type");
        }
    }

    @Nested
    @DisplayName("TaxCalculationRequest")
    class CalculationRequest {

        @Test
        @DisplayName("a committable request without a referenceId fails its own assertion")
        void committableRequiresReferenceId() {
            TaxCalculationRequest request =
                    TaxCalculationRequest.builder().committable(true).build();

            // Committing writes a transaction at the tax provider that later has to be found
            // again to void or adjust it. Without a referenceId there is nothing to find it by.
            assertThat(request.isReferenceIdPresentWhenCommittable()).isFalse();
        }

        @Test
        @DisplayName("a committable request with a referenceId satisfies the assertion")
        void committableWithReferenceIdIsValid() {
            TaxCalculationRequest request = TaxCalculationRequest.builder()
                    .committable(true)
                    .referenceId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
                    .build();

            assertThat(request.isReferenceIdPresentWhenCommittable()).isTrue();
        }

        @Test
        @DisplayName("a non-committable request needs no referenceId")
        void quoteNeedsNoReferenceId() {
            // A quote is thrown away, so requiring an id would force callers to invent one for
            // every price preview.
            assertThat(TaxCalculationRequest.builder()
                            .committable(false)
                            .build()
                            .isReferenceIdPresentWhenCommittable())
                    .isTrue();
        }

        @Test
        @DisplayName("address accessors return null rather than throwing when no address is set")
        void addressAccessorsAreNullSafe() {
            TaxCalculationRequest request = TaxCalculationRequest.builder().build();

            // These four are convenience readers over an optional nested object. Without the
            // null guards, every quote built before an address is known would throw.
            assertThat(request.getPostalCode()).isNull();
            assertThat(request.getCountryCode()).isNull();
            assertThat(request.getStateCode()).isNull();
            assertThat(request.getCity()).isNull();
        }

        @Test
        @DisplayName("address accessors read through to the destination address when one is set")
        void addressAccessorsReadThrough() {
            TaxCalculationRequest request = TaxCalculationRequest.builder()
                    .destinationAddress(TaxCalculationRequest.TaxAddress.builder()
                            .countryCode("US")
                            .regionCode("CA")
                            .postalCode("94105")
                            .city("San Francisco")
                            .build())
                    .build();

            assertThat(request.getCountryCode()).isEqualTo("US");
            // getStateCode reads regionCode: the accessor and the field deliberately differ in
            // name, which is exactly the sort of mapping that gets crossed with postalCode.
            assertThat(request.getStateCode()).isEqualTo("CA");
            assertThat(request.getPostalCode()).isEqualTo("94105");
            assertThat(request.getCity()).isEqualTo("San Francisco");
        }

        @Test
        @DisplayName("currency and calculation type carry defaults so a minimal request is still complete")
        void defaultsAreApplied() {
            TaxCalculationRequest request = TaxCalculationRequest.builder().build();

            // Builder defaults, not field initialisers — @Builder.Default is easy to omit, and
            // omitting it leaves these null for every request built through the builder.
            assertThat(request.getCurrencyCode()).isEqualTo("USD");
            assertThat(request.getCalculationType()).isNotNull();
        }
    }

    @Nested
    @DisplayName("jurisdiction rate lookup (#1522)")
    class RateLookup {

        @Test
        @DisplayName("the record components are the JSON field names the lookup responds with")
        void recordComponentsAreTheWireContract() {
            // Neither record carries @JsonProperty, so Jackson names each field after its record
            // component. Renaming one is a source-local edit that silently changes the response
            // body -- and the Angular SDK is generated from that body, so the break surfaces in
            // the browser rather than here. Order is asserted too: it is the order the generated
            // OpenAPI schema lists the properties in.
            assertThat(componentNames(TaxRateLookupResponse.class))
                    .containsExactly(
                            "countryCode",
                            "regionCode",
                            "city",
                            "postalCode",
                            "asOf",
                            "components",
                            "combinedRate",
                            "source");
            assertThat(componentNames(TaxRateComponent.class)).containsExactly("jurisdictionType", "rate");
        }

        @Test
        @DisplayName("lookup rates use the decimal-fraction convention, not percentage points")
        void ratesAreDecimalFractions() {
            // This module ships both conventions: TaxJurisdiction.taxRate is percentage points
            // (7.25) while JurisdictionTax.rate is a fraction (0.0725). TaxRateComponent
            // deliberately follows the latter, and the @Schema example is where a caller reading
            // the generated spec learns which. An example "corrected" to 7.25 would document a
            // hundredfold error, so pin it against both neighbours rather than on its own.
            BigDecimal componentRate = schemaExample(TaxRateComponent.class, "rate");
            assertThat(componentRate)
                    .isEqualByComparingTo(schemaExample(TaxCalculationResponse.JurisdictionTax.class, "rate"))
                    .isLessThan(BigDecimal.ONE);
            assertThat(schemaExample(TaxJurisdiction.class, "taxRate")).isGreaterThan(BigDecimal.ONE);

            // combinedRate is documented as the sum of components[].rate, so it has to be on the
            // same scale as the components it sums.
            assertThat(schemaExample(TaxRateLookupResponse.class, "combinedRate"))
                    .isLessThan(BigDecimal.ONE);
        }

        @Test
        @DisplayName("a lookup by country and postal code alone is representable")
        void regionAndCityMayBeAbsent() {
            // regionCode and city are NOT_REQUIRED query parameters echoed back, and a US ZIP is
            // enough to resolve rates without either. A compact constructor rejecting nulls here
            // would make the endpoint's own documented minimal request unrepresentable.
            TaxRateLookupResponse response = new TaxRateLookupResponse(
                    "US",
                    null,
                    null,
                    "94103",
                    LocalDate.of(2026, 8, 27),
                    List.of(new TaxRateComponent(TaxJurisdictionType.STATE, new BigDecimal("0.0725"))),
                    new BigDecimal("0.0725"),
                    "TEST_MODE");

            assertThat(response.regionCode()).isNull();
            assertThat(response.city()).isNull();
            assertThat(response.countryCode()).isEqualTo("US");
            assertThat(response.postalCode()).isEqualTo("94103");
        }

        @Test
        @DisplayName("a jurisdiction that taxes at zero keeps its row in the breakdown")
        void zeroRateJurisdictionIsRepresentable() {
            // A 0% level of government is real data -- several US states levy no sales tax while
            // their counties do. Dropping or rejecting the row would leave the caller unable to
            // tell "this jurisdiction charges nothing" from "this jurisdiction was not consulted".
            TaxRateComponent component = new TaxRateComponent(TaxJurisdictionType.STATE, BigDecimal.ZERO);

            assertThat(component.rate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(component.jurisdictionType()).isEqualTo(TaxJurisdictionType.STATE);
        }

        private List<String> componentNames(Class<?> record) {
            return Arrays.stream(record.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }

        /**
         * The {@code example} of the {@code @Schema} on a declared field, as a BigDecimal.
         *
         * <p>
         * Read off the field rather than the record component: {@code @Schema} predates records
         * and does not target {@code RECORD_COMPONENT}, so javac propagates it to the generated
         * field, not to the component.
         */
        private BigDecimal schemaExample(Class<?> owner, String fieldName) {
            try {
                Field field = owner.getDeclaredField(fieldName);
                Schema schema = field.getAnnotation(Schema.class);
                assertThat(schema)
                        .describedAs("@Schema on %s.%s", owner.getSimpleName(), fieldName)
                        .isNotNull();
                return new BigDecimal(schema.example());
            } catch (NoSuchFieldException e) {
                throw new AssertionError("no field " + owner.getSimpleName() + "." + fieldName, e);
            }
        }
    }
}
