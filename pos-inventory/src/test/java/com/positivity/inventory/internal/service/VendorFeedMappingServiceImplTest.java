package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.vendorfeed.UnmappedFeedVendorResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingRequest;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingUpdateRequest;
import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.entity.VendorFeedMapping;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.enums.PurchaseSuggestionStatus;
import com.positivity.inventory.internal.enums.SuggestedVendorType;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.exception.VendorFeedMappingException;
import com.positivity.inventory.internal.repository.DistributorNormalizedInventoryRepository;
import com.positivity.inventory.internal.repository.FeedVendorSummary;
import com.positivity.inventory.internal.repository.NormalizedAvailabilityRepository;
import com.positivity.inventory.internal.repository.PurchaseSuggestionRepository;
import com.positivity.inventory.internal.repository.VendorFeedMappingRepository;
import com.positivity.inventory.internal.service.VendorFeedMappingService.ResolvedFeedVendor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Vendor feed mapping rules (CAP:550 S36, #2525): create, update, delete, resolve and the unmapped scan. */
@SuppressWarnings({"unchecked", "java:S100", "java:S1192"})
class VendorFeedMappingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000b1");
    private static final UUID OTHER_VENDOR_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");
    private static final UUID MAPPING_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID MANUFACTURER = UUID.fromString("018f0000-0000-7000-8000-0000000000c1");

    private final VendorFeedMappingRepository mappings = mock(VendorFeedMappingRepository.class);
    private final PurchaseSuggestionRepository suggestions = mock(PurchaseSuggestionRepository.class);
    private final NormalizedAvailabilityRepository manufacturerFeed = mock(NormalizedAvailabilityRepository.class);
    private final DistributorNormalizedInventoryRepository distributorFeed =
            mock(DistributorNormalizedInventoryRepository.class);
    private final VendorCopyReader vendorCopy = mock(VendorCopyReader.class);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private VendorFeedMappingServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(meterRegistry);
        service = new VendorFeedMappingServiceImpl(
                mappings,
                suggestions,
                manufacturerFeed,
                distributorFeed,
                vendorCopy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters);
        when(mappings.saveAndFlush(any(VendorFeedMapping.class))).thenAnswer(inv -> {
            VendorFeedMapping saved = inv.getArgument(0);
            if (saved.getMappingId() == null) {
                saved.setMappingId(MAPPING_ID);
                saved.setVersion(0L);
            }
            return saved;
        });
        when(mappings.findByFeedTypeAndFeedVendorRef(any(), any())).thenReturn(Optional.empty());
    }

    private static ExtSupplierVendorReplica vendor(UUID id, VendorStatus status) {
        return ExtSupplierVendorReplica.builder()
                .vendorId(id)
                .vendorNumber("V-" + id.toString().substring(34))
                .displayName("Vendor " + id.toString().substring(34))
                .status(status)
                .aggregateVersion(1)
                .build();
    }

    private static VendorFeedMapping mapping(FeedVendorType type, String ref, UUID vendorId) {
        return VendorFeedMapping.builder()
                .mappingId(MAPPING_ID)
                .feedType(type)
                .feedVendorRef(ref)
                .vendorId(vendorId)
                .version(3L)
                .build();
    }

    private static VendorFeedMappingRequest request(FeedVendorType type, String ref, UUID vendorId) {
        return new VendorFeedMappingRequest(type, ref, vendorId, null);
    }

    private static FeedVendorSummary summary(Object ref, long products, Instant last) {
        FeedVendorSummary summary = mock(FeedVendorSummary.class);
        when(summary.getFeedVendorRef()).thenReturn(ref);
        when(summary.getProductCount()).thenReturn(products);
        when(summary.getLastReportedAt()).thenReturn(last);
        return summary;
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("create stores a trimmed, lower-case manufacturer ref and points open suggestions at the vendor")
    void create_storesNormalizedRefAndFollowsSuggestions() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));
        when(suggestions.updateVendorIdForFeedVendor(any(), any(), anyCollection(), any(), any()))
                .thenReturn(2);

        VendorFeedMappingResponse response = service.create(request(
                FeedVendorType.MANUFACTURER, "  " + MANUFACTURER.toString().toUpperCase() + " ", VENDOR_ID));

        ArgumentCaptor<VendorFeedMapping> saved = ArgumentCaptor.forClass(VendorFeedMapping.class);
        verify(mappings).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFeedVendorRef()).isEqualTo(MANUFACTURER.toString());
        assertThat(saved.getValue().getFeedType()).isEqualTo(FeedVendorType.MANUFACTURER);
        assertThat(response.getVendorStatus()).isEqualTo("ACTIVE");
        assertThat(response.getVendorNumber()).startsWith("V-");
        ArgumentCaptor<Collection<PurchaseSuggestionStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(suggestions)
                .updateVendorIdForFeedVendor(
                        eq(SuggestedVendorType.MANUFACTURER),
                        eq(MANUFACTURER.toString()),
                        statuses.capture(),
                        eq(VENDOR_ID),
                        eq(NOW));
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(PurchaseSuggestionStatus.SUGGESTED, PurchaseSuggestionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a distributor ref is trimmed and folded to the canonical lower-case key; it need not be a UUID")
    void create_distributorRefIsCanonical() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));

        service.create(request(FeedVendorType.DISTRIBUTOR, " Acme-Dist-01 ", VENDOR_ID));

        ArgumentCaptor<VendorFeedMapping> saved = ArgumentCaptor.forClass(VendorFeedMapping.class);
        verify(mappings).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFeedVendorRef()).isEqualTo("acme-dist-01");
    }

    @Test
    @DisplayName("a manufacturer ref that is not a UUID is refused with 400 VALIDATION_ERROR")
    void create_manufacturerRefNotUuid_isRefused() {
        assertThatThrownBy(() -> service.create(request(FeedVendorType.MANUFACTURER, "not-a-uuid", VENDOR_ID)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(e.getStatus().value()).isEqualTo(400);
                });
        verify(mappings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a blank ref is refused")
    void create_blankRef_isRefused() {
        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "   ", VENDOR_ID)))
                .isInstanceOf(VendorFeedMappingException.class);
    }

    @Test
    @DisplayName("a vendor missing from the copy is 422 VENDOR_NOT_FOUND")
    void create_vendorNotInCopy_is422() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "D1", VENDOR_ID)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VENDOR_NOT_FOUND");
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
        verify(mappings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an INACTIVE vendor is 422 VENDOR_INACTIVE")
    void create_inactiveVendor_is422() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.INACTIVE)));

        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "D1", VENDOR_ID)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VENDOR_INACTIVE");
                    assertThat(e.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    @DisplayName("a feed vendor that already has a mapping is 409 VENDOR_FEED_MAPPING_EXISTS")
    void create_existingMapping_is409() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));
        when(mappings.findByFeedTypeAndFeedVendorRef(FeedVendorType.DISTRIBUTOR, "d1"))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", OTHER_VENDOR_ID)));

        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "D1", VENDOR_ID)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_EXISTS");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("losing a race on the unique constraint is also 409 VENDOR_FEED_MAPPING_EXISTS")
    void create_uniqueViolation_is409() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));
        when(mappings.saveAndFlush(any(VendorFeedMapping.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "ERROR: duplicate key value violates unique constraint \"uq_vendor_feed_mapping_feed\""));

        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "D1", VENDOR_ID)))
                .isInstanceOfSatisfying(
                        VendorFeedMappingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_EXISTS"));
        verify(suggestions, never()).updateVendorIdForFeedVendor(any(), any(), anyCollection(), any(), any());
    }

    @Test
    @DisplayName("any other integrity failure is not turned into a 409")
    void create_otherIntegrityViolation_propagates() {
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));
        when(mappings.saveAndFlush(any(VendorFeedMapping.class)))
                .thenThrow(new DataIntegrityViolationException("null value in column \"vendor_id\""));

        assertThatThrownBy(() -> service.create(request(FeedVendorType.DISTRIBUTOR, "D1", VENDOR_ID)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("update re-points the mapping and its open suggestions")
    void update_repointsMappingAndSuggestions() {
        when(mappings.findById(MAPPING_ID))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)));
        when(vendorCopy.find(OTHER_VENDOR_ID)).thenReturn(Optional.of(vendor(OTHER_VENDOR_ID, VendorStatus.ACTIVE)));

        VendorFeedMappingResponse response =
                service.update(MAPPING_ID, new VendorFeedMappingUpdateRequest(OTHER_VENDOR_ID, " moved ", 3L));

        assertThat(response.getVendorId()).isEqualTo(OTHER_VENDOR_ID);
        assertThat(response.getNote()).isEqualTo("moved");
        verify(suggestions)
                .updateVendorIdForFeedVendor(
                        eq(SuggestedVendorType.DISTRIBUTOR), eq("d1"), anyCollection(), eq(OTHER_VENDOR_ID), eq(NOW));
    }

    @Test
    @DisplayName("update with a stale version is 409 CONFLICT and changes nothing")
    void update_staleVersion_is409() {
        when(mappings.findById(MAPPING_ID))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)));
        when(vendorCopy.find(OTHER_VENDOR_ID)).thenReturn(Optional.of(vendor(OTHER_VENDOR_ID, VendorStatus.ACTIVE)));

        assertThatThrownBy(
                        () -> service.update(MAPPING_ID, new VendorFeedMappingUpdateRequest(OTHER_VENDOR_ID, null, 2L)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_STALE_VERSION");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        verify(mappings, never()).saveAndFlush(any());
        verify(suggestions, never()).updateVendorIdForFeedVendor(any(), any(), anyCollection(), any(), any());
    }

    @Test
    @DisplayName("an optimistic-lock failure on save is also 409 CONFLICT")
    void update_optimisticLockFailure_is409() {
        when(mappings.findById(MAPPING_ID))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)));
        when(vendorCopy.find(OTHER_VENDOR_ID)).thenReturn(Optional.of(vendor(OTHER_VENDOR_ID, VendorStatus.ACTIVE)));
        when(mappings.saveAndFlush(any(VendorFeedMapping.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(VendorFeedMapping.class, MAPPING_ID));

        assertThatThrownBy(
                        () -> service.update(MAPPING_ID, new VendorFeedMappingUpdateRequest(OTHER_VENDOR_ID, null, 3L)))
                .isInstanceOfSatisfying(
                        VendorFeedMappingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_STALE_VERSION"));
    }

    @Test
    @DisplayName("update of a missing mapping is 404 VENDOR_FEED_MAPPING_NOT_FOUND")
    void update_missing_is404() {
        when(mappings.findById(MAPPING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MAPPING_ID, new VendorFeedMappingUpdateRequest(VENDOR_ID, null, 0L)))
                .isInstanceOfSatisfying(VendorFeedMappingException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_NOT_FOUND");
                    assertThat(e.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("update to an inactive or unknown vendor is refused")
    void update_inactiveVendor_is422() {
        when(mappings.findById(MAPPING_ID))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)));
        when(vendorCopy.find(OTHER_VENDOR_ID)).thenReturn(Optional.of(vendor(OTHER_VENDOR_ID, VendorStatus.INACTIVE)));

        assertThatThrownBy(
                        () -> service.update(MAPPING_ID, new VendorFeedMappingUpdateRequest(OTHER_VENDOR_ID, null, 3L)))
                .isInstanceOfSatisfying(
                        VendorFeedMappingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VENDOR_INACTIVE"));
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete removes the mapping and clears the vendor on its open suggestions")
    void delete_clearsSuggestionVendor() {
        when(mappings.findById(MAPPING_ID))
                .thenReturn(Optional.of(mapping(FeedVendorType.MANUFACTURER, MANUFACTURER.toString(), VENDOR_ID)));

        service.delete(MAPPING_ID);

        verify(mappings).delete(any(VendorFeedMapping.class));
        verify(suggestions)
                .updateVendorIdForFeedVendor(
                        eq(SuggestedVendorType.MANUFACTURER),
                        eq(MANUFACTURER.toString()),
                        anyCollection(),
                        isNull(),
                        eq(NOW));
    }

    @Test
    @DisplayName("delete of a missing mapping is 404")
    void delete_missing_is404() {
        when(mappings.findById(MAPPING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(MAPPING_ID))
                .isInstanceOfSatisfying(
                        VendorFeedMappingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("VENDOR_FEED_MAPPING_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ resolve

    @Test
    @DisplayName("resolve returns the vendor from the copy, in any status")
    void resolve_returnsVendorAnyStatus() {
        when(mappings.findByFeedTypeAndFeedVendorRef(FeedVendorType.DISTRIBUTOR, "d1"))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)));
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.INACTIVE)));

        Optional<ResolvedFeedVendor> resolved = service.resolve(FeedVendorType.DISTRIBUTOR, " D1 ");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().vendorId()).isEqualTo(VENDOR_ID);
        assertThat(resolved.get().status()).isEqualTo(VendorStatus.INACTIVE);
        assertThat(resolved.get().isActive()).isFalse();
    }

    @Test
    @DisplayName("resolve is empty when the feed vendor is unmapped or the vendor is not in the copy")
    void resolve_emptyWhenUnmappedOrVendorMissing() {
        assertThat(service.resolve(FeedVendorType.DISTRIBUTOR, "D1")).isEmpty();

        when(mappings.findByFeedTypeAndFeedVendorRef(FeedVendorType.DISTRIBUTOR, "d2"))
                .thenReturn(Optional.of(mapping(FeedVendorType.DISTRIBUTOR, "d2", VENDOR_ID)));
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.empty());
        assertThat(service.resolve(FeedVendorType.DISTRIBUTOR, "D2")).isEmpty();
    }

    @Test
    @DisplayName("resolve lower-cases a manufacturer UUID before the lookup")
    void resolve_manufacturerLookupIsLowerCase() {
        when(mappings.findByFeedTypeAndFeedVendorRef(FeedVendorType.MANUFACTURER, MANUFACTURER.toString()))
                .thenReturn(Optional.of(mapping(FeedVendorType.MANUFACTURER, MANUFACTURER.toString(), VENDOR_ID)));
        when(vendorCopy.find(VENDOR_ID)).thenReturn(Optional.of(vendor(VENDOR_ID, VendorStatus.ACTIVE)));

        assertThat(service.resolve(
                        FeedVendorType.MANUFACTURER, MANUFACTURER.toString().toUpperCase()))
                .isPresent();
    }

    @Test
    @DisplayName("resolveAll keys resolved vendors by ref and omits unmapped refs and vendors missing from the copy")
    void resolveAll_batch() {
        VendorFeedMapping mapped = mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID);
        VendorFeedMapping orphan = mapping(FeedVendorType.DISTRIBUTOR, "d2", OTHER_VENDOR_ID);
        when(mappings.findAllByFeedTypeAndFeedVendorRefIn(eq(FeedVendorType.DISTRIBUTOR), anyCollection()))
                .thenReturn(List.of(mapped, orphan));
        when(vendorCopy.byIds(anyCollection())).thenReturn(Map.of(VENDOR_ID, vendor(VENDOR_ID, VendorStatus.ACTIVE)));

        Map<String, ResolvedFeedVendor> resolved =
                service.resolveAll(FeedVendorType.DISTRIBUTOR, List.of("D1", " D2", "D3"));

        assertThat(resolved).containsOnlyKeys("d1");
        assertThat(resolved.get("d1").vendorId()).isEqualTo(VENDOR_ID);
    }

    @Test
    @DisplayName("resolveAll of nothing asks for nothing")
    void resolveAll_empty() {
        assertThat(service.resolveAll(FeedVendorType.DISTRIBUTOR, List.of())).isEmpty();
        verify(mappings, never()).findAllByFeedTypeAndFeedVendorRefIn(any(), anyCollection());
    }

    // ------------------------------------------------------------------ list / unmapped

    @Test
    @DisplayName("list decorates each mapping with its vendor from the copy and applies the filters")
    void list_decoratesWithVendor() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<VendorFeedMapping> page =
                new PageImpl<>(List.of(mapping(FeedVendorType.DISTRIBUTOR, "d1", VENDOR_ID)), pageable, 1);
        when(mappings.findAllByFeedTypeAndVendorId(FeedVendorType.DISTRIBUTOR, VENDOR_ID, pageable))
                .thenReturn(page);
        when(vendorCopy.byIds(anyCollection())).thenReturn(Map.of(VENDOR_ID, vendor(VENDOR_ID, VendorStatus.ACTIVE)));

        Page<VendorFeedMappingResponse> result = service.list(FeedVendorType.DISTRIBUTOR, VENDOR_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getVendorDisplayName()).startsWith("Vendor ");
    }

    @Test
    @DisplayName("the unmapped scan names unmapped, missing-from-copy and inactive feed vendors, and counts them")
    void unmapped_scan() {
        Instant last = Instant.parse("2026-10-10T00:00:00Z");
        UUID activeVendor = UUID.fromString("018f0000-0000-7000-8000-0000000000b3");
        List<FeedVendorSummary> feedVendors = List.of(
                summary("D-UNMAPPED", 4, last),
                summary("D-MISSING", 2, last),
                summary("D-INACTIVE", 1, last),
                summary("D-OK", 9, last));
        when(distributorFeed.summarizeDistributors()).thenReturn(feedVendors);
        when(mappings.findAllByFeedType(FeedVendorType.DISTRIBUTOR))
                .thenReturn(List.of(
                        mapping(FeedVendorType.DISTRIBUTOR, "d-missing", OTHER_VENDOR_ID),
                        mapping(FeedVendorType.DISTRIBUTOR, "d-inactive", VENDOR_ID),
                        mapping(FeedVendorType.DISTRIBUTOR, "d-ok", activeVendor)));
        when(vendorCopy.byIds(anyCollection()))
                .thenReturn(Map.of(
                        VENDOR_ID, vendor(VENDOR_ID, VendorStatus.INACTIVE),
                        activeVendor, vendor(activeVendor, VendorStatus.ACTIVE)));

        List<UnmappedFeedVendorResponse> unmapped = service.listUnmapped(FeedVendorType.DISTRIBUTOR);

        assertThat(unmapped)
                .extracting(UnmappedFeedVendorResponse::getFeedVendorRef, UnmappedFeedVendorResponse::getReason)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("d-inactive", "VENDOR_INACTIVE"),
                        org.assertj.core.api.Assertions.tuple("d-missing", "VENDOR_NOT_IN_COPY"),
                        org.assertj.core.api.Assertions.tuple("d-unmapped", "UNMAPPED"));
        assertThat(unmapped.stream()
                        .filter(u -> u.getFeedVendorRef().equals("d-unmapped"))
                        .findFirst()
                        .orElseThrow()
                        .getProductCount())
                .isEqualTo(4);
        assertThat(meterRegistry
                        .get("inventory.vendor_feed.unmapped")
                        .tag("feedType", "DISTRIBUTOR")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        verify(manufacturerFeed, never()).summarizeManufacturers();
    }

    @Test
    @DisplayName("without a feedType filter both feeds are scanned; a manufacturer UUID matches its mapping")
    void unmapped_scanBothFeeds() {
        List<FeedVendorSummary> feedVendors = List.of(summary(MANUFACTURER, 5, null));
        when(manufacturerFeed.summarizeManufacturers()).thenReturn(feedVendors);
        when(distributorFeed.summarizeDistributors()).thenReturn(List.of());
        when(mappings.findAllByFeedType(FeedVendorType.MANUFACTURER))
                .thenReturn(List.of(mapping(FeedVendorType.MANUFACTURER, MANUFACTURER.toString(), VENDOR_ID)));
        when(mappings.findAllByFeedType(FeedVendorType.DISTRIBUTOR)).thenReturn(List.of());
        when(vendorCopy.byIds(anyCollection())).thenReturn(Map.of(VENDOR_ID, vendor(VENDOR_ID, VendorStatus.ACTIVE)));

        assertThat(service.listUnmapped(null)).isEmpty();
        assertThat(meterRegistry
                        .get("inventory.vendor_feed.unmapped")
                        .tag("feedType", "MANUFACTURER")
                        .gauge()
                        .value())
                .isZero();
        assertThat(Set.of(FeedVendorType.values())).hasSize(2);
    }
}
