package com.positivity.supplier.internal.mktcat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentImage;
import com.positivity.domainevents.supplier.SupplierCatalogEnrichmentText;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.supplier.internal.entity.SupplierMktCatVariantEntity;
import com.positivity.supplier.internal.repository.SupplierMktCatVariantRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MKCAT re-publication on request (ADR-0044 §4, #2356)")
class MktCatRepublisherTest {

    private static final UUID PROFILE_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000b1");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-17T06:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Mock
    private SupplierMktCatVariantRepository variantRepository;

    @Mock
    private SupplierOutboxEventWriter outboxWriter;

    private MktCatRepublisher republisher;

    @BeforeEach
    void setUp() {
        republisher = new MktCatRepublisher(variantRepository, outboxWriter, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(republisher, "pageSize", 2);
    }

    private static SupplierCatalogRepublishRequestedV1 request() {
        return new SupplierCatalogRepublishRequestedV1(PROFILE_ID, "operator", "#2356 recovery");
    }

    private static List<SupplierCatalogEnrichmentText> texts() {
        return List.of(new SupplierCatalogEnrichmentText("de", "Primacy 4", "Sommerreifen", null));
    }

    private static List<SupplierCatalogEnrichmentImage> images() {
        return List.of(
                new SupplierCatalogEnrichmentImage("HERO", 42L, "img-hash", "https://cdn.example.com/a.jpg", false),
                SupplierCatalogEnrichmentImage.unresolved("TREAD", "https://cdn.example.com/b.jpg"));
    }

    private static SupplierMktCatVariantEntity staged(int number) {
        return SupplierMktCatVariantEntity.builder()
                .supplierMktCatVariantId(UUID.fromString("019200aa-0000-7000-8000-00000000000" + number))
                .vendorProfileId(PROFILE_ID)
                .supplierRef("ediwheel-net")
                .vendorVariantId("V" + number)
                .brand("Michelin")
                .treadDesign("Primacy " + number)
                .productName("Michelin Primacy " + number)
                .vehicleType("passenger")
                .seasonality("summer")
                .contentHash("hash-" + number)
                // Written the way the stager writes them, so the read below is a real round trip.
                .textsJson(MAPPER.writeValueAsString(texts()))
                .imagesJson(MAPPER.writeValueAsString(images()))
                .hasUnresolvedImages(true)
                .firstSeenAt(PUBLISHED_AT)
                .lastSeenAt(NOW.minusSeconds(3600))
                .lastPublishedAt(PUBLISHED_AT)
                .build();
    }

    private void stage(List<SupplierMktCatVariantEntity> firstPage, List<SupplierMktCatVariantEntity> secondPage) {
        when(variantRepository.findByVendorProfileIdOrderBySupplierMktCatVariantIdAsc(PROFILE_ID, PageRequest.of(0, 2)))
                .thenReturn(firstPage);
        when(variantRepository.findByVendorProfileIdOrderBySupplierMktCatVariantIdAsc(PROFILE_ID, PageRequest.of(1, 2)))
                .thenReturn(secondPage);
    }

    private List<DomainEventEnvelope<?>> capturedEvents() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(outboxWriter, atLeastOnce()).publish(eq("supplier.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("every staged variant is re-emitted as it was published, whatever its hash")
    void reEmitsEveryStagedVariantWithItsStoredContent() {
        stage(List.of(staged(1), staged(2)), List.of(staged(3)));

        int emitted = republisher.republish(request());

        assertThat(emitted).isEqualTo(3);
        List<DomainEventEnvelope<?>> events = capturedEvents();
        // Three variants across two pages, then the completion that closes the run.
        assertThat(events).hasSize(4);
        assertThat(events.subList(0, 3))
                .allSatisfy(event -> assertThat(event.eventType()).isEqualTo(SupplierCatalogUpdatedV1.EVENT_TYPE));

        DomainEventEnvelope<?> first = events.get(0);
        // Keyed on the staged row, exactly as the original publication was: a re-emit that keyed
        // differently could overtake a newer publication of the same variant on another partition.
        assertThat(first.aggregateId()).isEqualTo(staged(1).getSupplierMktCatVariantId());
        SupplierCatalogUpdatedV1 payload = (SupplierCatalogUpdatedV1) first.payload();
        assertThat(payload.vendorProfileId()).isEqualTo(PROFILE_ID);
        assertThat(payload.supplierRef()).isEqualTo("ediwheel-net");
        assertThat(payload.vendorVariantId()).isEqualTo("V1");
        assertThat(payload.brand()).isEqualTo("Michelin");
        assertThat(payload.treadDesign()).isEqualTo("Primacy 1");
        assertThat(payload.productName()).isEqualTo("Michelin Primacy 1");
        assertThat(payload.vehicleType()).isEqualTo("passenger");
        assertThat(payload.seasonality()).isEqualTo("summer");
        // The stored hash, untouched: it is what lets a consumer that already holds this design
        // recognise the re-emit as a repeat.
        assertThat(payload.contentHash()).isEqualTo("hash-1");
        assertThat(payload.texts()).isEqualTo(texts());
        assertThat(payload.images()).isEqualTo(images());
        // When the enrichment was fetched, not when it was re-delivered.
        assertThat(payload.occurredAt()).isEqualTo(PUBLISHED_AT);
        assertThat(first.occurredAtUtc()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("each re-emit carries a new event id, or the consumer's guard would swallow it")
    void mintsANewEventIdPerVariantAndPerRun() {
        stage(List.of(staged(1), staged(2)), List.of());

        republisher.republish(request());
        republisher.republish(request());

        // Two runs of two variants and a completion each. The first run's ids are the stand-in for
        // the ids the consumer already recorded as ignored (#2177): a re-emit that reused them would
        // be skipped exactly as a replay of the original events is.
        assertThat(capturedEvents().stream().map(DomainEventEnvelope::eventId))
                .hasSize(6)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the run ends with a completion event carrying the count the consumer compares against")
    void endsWithACompletionCarryingTheVariantCount() {
        stage(List.of(staged(1), staged(2)), List.of(staged(3)));

        republisher.republish(request());

        DomainEventEnvelope<?> last = capturedEvents().getLast();
        assertThat(last.eventType()).isEqualTo(SupplierCatalogRepublishCompletedV1.EVENT_TYPE);
        assertThat(last.aggregateId()).isEqualTo(PROFILE_ID);
        SupplierCatalogRepublishCompletedV1 completion = (SupplierCatalogRepublishCompletedV1) last.payload();
        assertThat(completion.vendorProfileId()).isEqualTo(PROFILE_ID);
        assertThat(completion.supplierRef()).isEqualTo("ediwheel-net");
        assertThat(completion.variantCount()).isEqualTo(3);
        assertThat(completion.requestedBy()).isEqualTo("operator");
        assertThat(completion.completedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("nothing is written back, so the next ordinary import still sees the hash it stored")
    void leavesTheStagedRowsUntouched() {
        SupplierMktCatVariantEntity row = staged(1);
        stage(List.of(row), List.of());

        republisher.republish(request());

        verify(variantRepository, never()).save(any());
        assertThat(row.getContentHash()).isEqualTo("hash-1");
        assertThat(row.getLastPublishedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    @DisplayName("a profile with nothing staged emits nothing — not even a count of zero")
    void emitsNothingForAProfileWithNothingStaged() {
        stage(List.of(), List.of());

        int emitted = republisher.republish(request());

        assertThat(emitted).isZero();
        // A completion saying "zero variants" for a profile that was never imported would read
        // downstream as a catalogue that had been emptied.
        verify(outboxWriter, never()).publish(any(), any());
    }

    @Test
    @DisplayName("a staged row this module cannot read back fails the run rather than being skipped")
    void failsLoudlyOnAStagedRowItCannotReadBack() {
        SupplierMktCatVariantEntity broken = staged(2);
        broken.setTextsJson("{not json");
        stage(List.of(staged(1), broken), List.of());

        // Skipping it would re-emit a catalogue that looks whole while a design stayed missing. An
        // IllegalStateException is what the command listener rethrows as this module's own
        // inconsistent state instead of recording the command as malformed.
        assertThatThrownBy(() -> republisher.republish(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("texts_json");
    }
}
