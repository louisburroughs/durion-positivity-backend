package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.supplier.internal.adapter.ediwheelc12.EdiwheelC12MktCatCodec;
import com.positivity.supplier.internal.adapter.ediwheelc12.MktCatDecodeException;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.MktCatImportException;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import com.positivity.supplier.internal.spi.ExchangeOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sweep around {@code stageAndPublish} (CAP-324 #1230): reading the variant list, fetching each
 * variant's detail, images and language data, and surviving the ones that cannot be read.
 *
 * <p>The load-bearing behaviour here is partial failure. One unreadable variant must cost that
 * variant and nothing else — a sweep that aborted would lose the whole catalogue over a single bad
 * row, and one that reported an empty list would look to a differ like the vendor withdrawing
 * everything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MktCatImporter — the sweep")
class MktCatImporterSweepTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");
    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final SupplierRef SUPPLIER = new SupplierRef("ediwheel-net");

    @Mock
    private SupplierProfileResolver profileResolver;

    @Mock
    private AdapterRegistry adapterRegistry;

    @Mock
    private SupplierBaseClient baseClient;

    @Mock
    private SupplierMktCatVariantRepository variantRepository;

    @Mock
    private MktCatImageFetcher imageFetcher;

    @Mock
    private SupplierOutboxEventWriter outboxEventWriter;

    @Mock
    private EdiwheelC12MktCatCodec codec;

    private MktCatImporter importer;

    @BeforeEach
    void setUp() {
        importer = new MktCatImporter(
                profileResolver,
                adapterRegistry,
                baseClient,
                imageFetcher,
                new MktCatVariantStager(
                        variantRepository,
                        outboxEventWriter,
                        JsonMapper.builder().build(),
                        Clock.fixed(NOW, ZoneOffset.UTC)));

        when(profileResolver.resolveBinding(any(), any())).thenReturn(binding());
        when(adapterRegistry.resolve(any(), any(), any())).thenReturn(new AdapterResolution.Resolved(codec));
        SupplierRequestSpec spec =
                new SupplierRequestSpec("GET", "/variants", Map.of(), null, null, null, true, Map.of());
        when(codec.buildVariantListRequest()).thenReturn(spec);
        when(codec.buildVariantDetailRequest(any())).thenReturn(spec);
        when(codec.buildVariantImagesRequest(any())).thenReturn(spec);
        when(codec.buildVariantLanguageDataRequest(any())).thenReturn(spec);
        when(baseClient.exchange(any())).thenReturn(ok("{}"));
        when(imageFetcher.fetchAndStore(any())).thenReturn(List.of());
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.empty());
        when(variantRepository.save(any())).thenAnswer(invocation -> {
            SupplierMktCatVariantEntity row = invocation.getArgument(0);
            if (row.getSupplierMktCatVariantId() == null) {
                row.setSupplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"));
            }
            return row;
        });
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setVendorProfileId(PROFILE_ID);
        profile.setSupplierRef(SUPPLIER.value());
        return new ResolvedBinding(
                profile,
                new SupplierEndpointBindingEntity(),
                new SupplierAuthConfigEntity(),
                SupplierCapability.MARKETING_CATALOG,
                ProtocolFamily.EDIWHEEL_JSON,
                new ProtocolVersion("C1.2"));
    }

    private static SupplierHttpResponse ok(String body) {
        return new SupplierHttpResponse(
                ExchangeOutcome.OK, 200, body, "corr-1", 1, Duration.ofMillis(5), null, Map.of());
    }

    private static SupplierHttpResponse failed(String detail) {
        return new SupplierHttpResponse(
                ExchangeOutcome.PRE_SEND_FAILURE, null, null, "corr-1", 1, Duration.ofMillis(5), detail, Map.of());
    }

    private static MarketingVariant variant(String id) {
        return new MarketingVariant(
                id, "Michelin", "Primacy 4", null, "Primacy 4", "PC", "SUMMER", List.of(), List.of());
    }

    @Nested
    @DisplayName("importAll")
    class ImportAll {

        @Test
        @DisplayName("imports every variant the list names")
        void importsTheWholeList() {
            when(codec.decodeVariantIds(any())).thenReturn(List.of("V1", "V2"));
            when(codec.decodeVariant(any(), any(), any(), any())).thenAnswer(i -> variant(i.getArgument(0)));

            MktCatImporter.ImportOutcome outcome = importer.importAll(SUPPLIER);

            assertThat(outcome.seen()).isEqualTo(2);
            assertThat(outcome.published()).isEqualTo(2);
            assertThat(outcome.skipped()).isZero();
        }

        @Test
        @DisplayName("throws rather than reporting an empty catalogue when the list cannot be read")
        void unreadableListThrows() {
            // Reported as empty, a differ would read this as the vendor withdrawing everything.
            when(baseClient.exchange(any())).thenReturn(failed("connection refused"));

            assertThatThrownBy(() -> importer.importAll(SUPPLIER))
                    .isInstanceOf(MktCatImportException.class)
                    .hasMessageContaining("variant list could not be read")
                    .hasMessageContaining("connection refused");
            verify(outboxEventWriter, never()).publish(any(), any());
        }

        @Test
        @DisplayName("omits the detail suffix when the failure carried none")
        void unreadableListWithoutDetail() {
            when(baseClient.exchange(any()))
                    .thenReturn(new SupplierHttpResponse(
                            ExchangeOutcome.PRE_SEND_FAILURE,
                            null,
                            null,
                            "corr-1",
                            1,
                            Duration.ofMillis(5),
                            null,
                            Map.of()));

            assertThatThrownBy(() -> importer.importAll(SUPPLIER))
                    .isInstanceOf(MktCatImportException.class)
                    .hasMessageNotContaining("--");
        }

        @Test
        @DisplayName("an empty catalogue is a valid answer and publishes nothing")
        void emptyCatalogue() {
            when(codec.decodeVariantIds(any())).thenReturn(List.of());

            MktCatImporter.ImportOutcome outcome = importer.importAll(SUPPLIER);

            assertThat(outcome.seen()).isZero();
            verify(outboxEventWriter, never()).publish(any(), any());
        }
    }

    @Nested
    @DisplayName("importVariants")
    class ImportVariants {

        @Test
        @DisplayName("one unreadable variant is skipped and the rest of the sweep continues")
        void oneBadVariantDoesNotEndTheImport() {
            when(codec.decodeVariant(eq("V1"), any(), any(), any())).thenThrow(new MktCatDecodeException("bad xml"));
            when(codec.decodeVariant(eq("V2"), any(), any(), any())).thenReturn(variant("V2"));

            MktCatImporter.ImportOutcome outcome = importer.importVariants(SUPPLIER, List.of("V1", "V2"));

            assertThat(outcome.seen()).isEqualTo(2);
            assertThat(outcome.published()).isEqualTo(1);
            assertThat(outcome.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("a variant whose detail cannot be read is skipped, not fatal")
        void unreadableDetailIsSkipped() {
            when(baseClient.exchange(any())).thenReturn(failed("timeout"));

            MktCatImporter.ImportOutcome outcome = importer.importVariants(SUPPLIER, List.of("V1"));

            assertThat(outcome.skipped()).isEqualTo(1);
            assertThat(outcome.published()).isZero();
        }

        @Test
        @DisplayName("an unchanged variant is seen but not published")
        void unchangedVariantIsNotRepublished() {
            when(codec.decodeVariant(any(), any(), any(), any())).thenReturn(variant("V1"));
            MktCatImporter.ImportOutcome first = importer.importVariants(SUPPLIER, List.of("V1"));
            String hash = firstSavedHash();

            when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                    .thenReturn(Optional.of(SupplierMktCatVariantEntity.builder()
                            .supplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                            .vendorProfileId(PROFILE_ID)
                            .vendorVariantId("V1")
                            .contentHash(hash)
                            .build()));

            MktCatImporter.ImportOutcome second = importer.importVariants(SUPPLIER, List.of("V1"));

            assertThat(first.published()).isEqualTo(1);
            assertThat(second.published()).isZero();
            assertThat(second.seen()).isEqualTo(1);
        }

        private String firstSavedHash() {
            org.mockito.ArgumentCaptor<SupplierMktCatVariantEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(SupplierMktCatVariantEntity.class);
            verify(variantRepository).save(captor.capture());
            return captor.getValue().getContentHash();
        }
    }

    @Nested
    @DisplayName("optional data and hashing")
    class OptionalDataAndHashing {

        @Test
        @DisplayName("missing images and language data do not stop the variant being imported")
        void optionalDataMayBeAbsent() {
            // A variant with neither is still a variant; refusing it would lose the design entirely
            // over its lack of a picture.
            when(baseClient.exchange(any()))
                    .thenReturn(ok("<detail/>"))
                    .thenReturn(failed("no images"))
                    .thenReturn(failed("no languages"));
            when(codec.decodeVariant(any(), any(), any(), any())).thenReturn(variant("V1"));

            assertThat(importer.importVariants(SUPPLIER, List.of("V1")).published())
                    .isEqualTo(1);
            verify(codec).decodeVariant(eq("V1"), eq("<detail/>"), eq(null), eq(null));
        }

        @Test
        @DisplayName("unresolved artwork changes the hash, so the variant republishes once it arrives")
        void unresolvedArtworkIsPartOfTheHash() {
            when(codec.decodeVariant(any(), any(), any(), any())).thenReturn(variant("V1"));
            when(imageFetcher.fetchAndStore(any()))
                    .thenReturn(List.of(new SupplierCatalogEnrichmentImage("MAIN", null, null, null, true)));
            importer.importVariants(SUPPLIER, List.of("V1"));
            String unresolvedHash = savedHash();

            org.mockito.Mockito.reset(variantRepository);
            when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(variantRepository.save(any())).thenAnswer(i -> {
                SupplierMktCatVariantEntity row = i.getArgument(0);
                row.setSupplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"));
                return row;
            });
            when(imageFetcher.fetchAndStore(any()))
                    .thenReturn(List.of(new SupplierCatalogEnrichmentImage(
                            "MAIN", 1L, "hash-a", "https://cdn.example.com/1.jpg", false)));
            importer.importVariants(SUPPLIER, List.of("V1"));

            assertThat(savedHash()).isNotEqualTo(unresolvedHash);
        }

        private String savedHash() {
            org.mockito.ArgumentCaptor<SupplierMktCatVariantEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(SupplierMktCatVariantEntity.class);
            verify(variantRepository).save(captor.capture());
            return captor.getValue().getContentHash();
        }
    }

    @Nested
    @DisplayName("codec resolution")
    class CodecResolution {

        @Test
        @DisplayName("refuses a binding with no marketing catalogue codec registered")
        void noCodecRegistered() {
            when(adapterRegistry.resolve(any(), any(), any()))
                    .thenReturn(new AdapterResolution.NotConfigured("nothing bound"));

            // Asserting on the capability and protocol rather than the sentence: the message is
            // SupplierCodecs' to word, and pinning its prose makes this test fail on a rewording.
            assertThatThrownBy(() -> importer.importAll(SUPPLIER))
                    .isInstanceOf(SupplierConfigurationException.class)
                    .hasMessageContainingAll("MARKETING_CATALOG", "EDIWHEEL_JSON", "C1.2");
        }
    }
}
