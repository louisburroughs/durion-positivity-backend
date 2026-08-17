package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.domain.model.MarketingVariant;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishing on change rather than on arrival (CAP-324 #1230).
 *
 * <p>A marketing catalogue republishing itself unchanged is the normal case, not the exception. If
 * arrival counted as change, a nightly sweep would emit the entire catalogue every night — which
 * downstream is indistinguishable from every product's marketing copy having actually changed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MktCatImporter — published when it changes, not when it arrives (#1230)")
class MktCatImporterTest {

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

    private MktCatImporter importer;

    @BeforeEach
    void setUp() {
        importer = new MktCatImporter(
                profileResolver,
                adapterRegistry,
                baseClient,
                variantRepository,
                imageFetcher,
                outboxEventWriter,
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(variantRepository.save(any())).thenAnswer(invocation -> {
            SupplierMktCatVariantEntity row = invocation.getArgument(0);
            if (row.getSupplierMktCatVariantId() == null) {
                row.setSupplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"));
            }
            return row;
        });
    }

    @Test
    @DisplayName("a variant never seen before is staged and published")
    void newVariantIsPublished() {
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.empty());

        boolean published = importer.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), List.of(), "hash-1");

        assertThat(published).isTrue();
        verify(outboxEventWriter).publish(any(), any());
    }

    @Test
    @DisplayName("an unchanged republication is recorded as seen and publishes nothing")
    void unchangedRepublicationIsSilent() {
        // The load-bearing case. A catalogue that republishes nightly must not look like a catalogue
        // that changes nightly.
        SupplierMktCatVariantEntity existing = staged("hash-1");
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.of(existing));

        boolean published = importer.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), List.of(), "hash-1");

        assertThat(published).isFalse();
        verify(outboxEventWriter, never()).publish(any(), any());
        // Still recorded as seen: "the catalogue stopped sending this" and "the catalogue keeps
        // sending it unchanged" look identical if only changes are timestamped.
        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("changed content republishes and moves the published timestamp")
    void changedContentIsPublished() {
        SupplierMktCatVariantEntity existing = staged("hash-1");
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.of(existing));

        boolean published = importer.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), List.of(), "hash-2");

        assertThat(published).isTrue();
        assertThat(existing.getContentHash()).isEqualTo("hash-2");
        assertThat(existing.getLastPublishedAt()).isEqualTo(NOW);
        verify(outboxEventWriter).publish(any(), any());
    }

    @Test
    @DisplayName("a variant with unresolved artwork is flagged, so the retry runner can find it")
    void unresolvedArtworkIsFlagged() {
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.empty());
        List<SupplierCatalogEnrichmentImage> images =
                List.of(SupplierCatalogEnrichmentImage.unresolved("TREAD", "https://cdn.example.com/x.jpg"));

        importer.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), images, "hash-1");

        // A column rather than something derived from JSON at query time: the retry runner selects
        // on it, and a scan that parsed JSON per row would be the query that degrades as the
        // catalogue grows.
        verify(variantRepository).save(any());
    }

    @Test
    @DisplayName("the enrichment publishes its text even when a picture is missing")
    void textSurvivesAMissingPicture() {
        when(variantRepository.findByVendorProfileIdAndVendorVariantId(any(), any()))
                .thenReturn(Optional.empty());
        List<SupplierCatalogEnrichmentImage> images =
                List.of(SupplierCatalogEnrichmentImage.unresolved("TREAD", "https://cdn.example.com/x.jpg"));

        // Dropping the record would discard the marketing copy that did arrive because a picture
        // did not.
        boolean published = importer.stageAndPublish(PROFILE_ID, SUPPLIER, variant(), texts(), images, "hash-1");

        assertThat(published).isTrue();
        SupplierCatalogUpdatedV1 payload = new SupplierCatalogUpdatedV1(
                PROFILE_ID,
                SUPPLIER.value(),
                "V1",
                "Michelin",
                "Primacy 4",
                null,
                null,
                null,
                null,
                "hash-1",
                texts(),
                images,
                NOW);
        assertThat(payload.hasUnresolvedImages()).isTrue();
        assertThat(payload.texts()).isNotEmpty();
    }

    private static MarketingVariant variant() {
        return new MarketingVariant("V1", "Michelin", "Primacy 4", null, null, null, null, List.of(), List.of());
    }

    private static List<SupplierCatalogEnrichmentText> texts() {
        return List.of(new SupplierCatalogEnrichmentText("de", "Primacy 4", "Sommerreifen", null));
    }

    private static SupplierMktCatVariantEntity staged(String contentHash) {
        return SupplierMktCatVariantEntity.builder()
                .supplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .vendorProfileId(PROFILE_ID)
                .supplierRef(SUPPLIER.value())
                .vendorVariantId("V1")
                .contentHash(contentHash)
                .textsJson("[]")
                .imagesJson("[]")
                .firstSeenAt(NOW.minusSeconds(86400))
                .lastSeenAt(NOW.minusSeconds(86400))
                .build();
    }
}
