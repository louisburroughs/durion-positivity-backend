package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.dto.MarketingConsentDecision;
import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.SegmentResolutionResponse;
import com.positivity.customer.internal.dto.SegmentResponse;
import com.positivity.customer.internal.dto.UpsertSegmentRequest;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.entity.SegmentMember;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.SegmentRepository;
import com.positivity.customer.service.MarketingConsentService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link SegmentServiceImpl}.
 *
 * <p>
 * A segment is either STATIC (an explicit pinned member list) or DYNAMIC (a
 * stored predicate evaluated on demand). Most of the behaviour worth pinning
 * down here defends that split:
 *
 * <ul>
 * <li>A STATIC segment must not carry a predicate and a DYNAMIC one must, or the
 * stored definition stops describing the membership it produces.</li>
 * <li>Neither {@code type} nor {@code audienceType} may change after creation —
 * switching would strand the other kind's state.</li>
 * <li>{@code memberCount} is only meaningful for STATIC segments and is
 * deliberately {@code null} for DYNAMIC ones rather than a stale stored
 * number.</li>
 * </ul>
 *
 * <p>
 * The resolve path also masks the sample labels it returns. That is a privacy
 * control, not formatting: the endpoint exists so an operator can recognize a
 * mis-scoped audience, and it must not double as a way to export a contact list.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SegmentServiceImpl — segment lifecycle, membership, and resolution")
class SegmentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID SEGMENT_ID = UUID.randomUUID();

    @Mock
    private SegmentRepository segmentRepository;

    @Mock
    private SegmentMemberRepository segmentMemberRepository;

    @Mock
    private SegmentResolutionService resolutionService;

    @Mock
    private MarketingConsentService marketingConsentService;

    @Mock
    private CustomerFactPublisher factPublisher;

    @Captor
    private ArgumentCaptor<Segment> savedSegment;

    private SegmentServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new SegmentServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                segmentRepository,
                segmentMemberRepository,
                resolutionService,
                marketingConsentService,
                factPublisher);
    }

    private static SegmentPredicate predicate() {
        return new SegmentPredicate.Comparison("party.accountTier", SegmentOperator.IN, List.of("GOLD", "PLATINUM"));
    }

    private static UpsertSegmentRequest dynamicRequest(String name) {
        return new UpsertSegmentRequest(
                name, "a description", AudienceType.COMMERCIAL, SegmentType.DYNAMIC, predicate(), true);
    }

    private static UpsertSegmentRequest staticRequest(String name) {
        return new UpsertSegmentRequest(name, "a description", AudienceType.COMMERCIAL, SegmentType.STATIC, null, true);
    }

    private static Segment segment(SegmentType type, String predicateJson) {
        return Segment.builder()
                .segmentId(SEGMENT_ID)
                .name("Fleet accounts")
                .description("a description")
                .audienceType(AudienceType.COMMERCIAL)
                .type(type)
                .predicate(predicateJson)
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("persists a DYNAMIC segment with its predicate serialized")
        void create_dynamic_persistsPredicate() {
            when(segmentRepository.existsByNameIgnoreCase("Fleet accounts")).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            SegmentResponse response = sut.create(dynamicRequest("Fleet accounts"));

            verify(segmentRepository).save(savedSegment.capture());
            assertThat(savedSegment.getValue().getPredicate()).contains("party.accountTier");
            assertThat(response.type()).isEqualTo("DYNAMIC");
        }

        @Test
        @DisplayName("persists a STATIC segment with a null predicate")
        void create_static_persistsWithoutPredicate() {
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.create(staticRequest("Pinned accounts"));

            verify(segmentRepository).save(savedSegment.capture());
            assertThat(savedSegment.getValue().getPredicate()).isNull();
        }

        @Test
        @DisplayName("trims the supplied name before the uniqueness check and before storing it")
        void create_trimsName() {
            when(segmentRepository.existsByNameIgnoreCase("Fleet accounts")).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.create(new UpsertSegmentRequest(
                    "  Fleet accounts  ", "a description", AudienceType.COMMERCIAL, SegmentType.STATIC, null, true));

            verify(segmentRepository).save(savedSegment.capture());
            assertThat(savedSegment.getValue().getName()).isEqualTo("Fleet accounts");
        }

        @Test
        @DisplayName("rejects a duplicate name case-insensitively")
        void create_whenNameTaken_throwsDuplicate() {
            when(segmentRepository.existsByNameIgnoreCase("Fleet accounts")).thenReturn(true);

            assertThatThrownBy(() -> sut.create(staticRequest("Fleet accounts")))
                    .isInstanceOf(CrmDuplicateResourceException.class);
            verify(segmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("defaults active to true when the request leaves it unset")
        void create_whenActiveOmitted_defaultsToActive() {
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.create(new UpsertSegmentRequest(
                    "Fleet accounts", null, AudienceType.COMMERCIAL, SegmentType.STATIC, null, null));

            verify(segmentRepository).save(savedSegment.capture());
            assertThat(savedSegment.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("rejects a STATIC segment that carries a predicate")
        void create_staticWithPredicate_throws() {
            UpsertSegmentRequest invalid = new UpsertSegmentRequest(
                    "Fleet accounts", null, AudienceType.COMMERCIAL, SegmentType.STATIC, predicate(), true);
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);

            assertThatThrownBy(() -> sut.create(invalid))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("explicit member list");
        }

        @Test
        @DisplayName("rejects a DYNAMIC segment with no predicate")
        void create_dynamicWithoutPredicate_throws() {
            UpsertSegmentRequest invalid = new UpsertSegmentRequest(
                    "Fleet accounts", null, AudienceType.COMMERCIAL, SegmentType.DYNAMIC, null, true);
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);

            assertThatThrownBy(() -> sut.create(invalid))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("requires a predicate");
        }

        @Test
        @DisplayName("publishes a segment-changed fact on create")
        void create_publishesFact() {
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.create(staticRequest("Fleet accounts"));

            verify(factPublisher)
                    .segmentChanged(any(), eq("Fleet accounts"), eq("COMMERCIAL"), eq("STATIC"), eq(true), eq(false));
        }

        @Test
        @DisplayName("reports a member count of zero for a freshly created segment")
        void create_reportsZeroMembers() {
            when(segmentRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.create(staticRequest("Fleet accounts")).memberCount())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("applies the new name, description, and active flag")
        void update_appliesChanges() {
            Segment existing = segment(SegmentType.STATIC, null);
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(existing));
            when(segmentRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.update(
                    SEGMENT_ID,
                    new UpsertSegmentRequest(
                            "Renamed", "new description", AudienceType.COMMERCIAL, SegmentType.STATIC, null, false));

            assertThat(existing.getName()).isEqualTo("Renamed");
            assertThat(existing.getDescription()).isEqualTo("new description");
            assertThat(existing.isActive()).isFalse();
        }

        @Test
        @DisplayName("leaves the active flag untouched when the request omits it")
        void update_whenActiveOmitted_leavesFlagUnchanged() {
            Segment existing = segment(SegmentType.STATIC, null);
            existing.setActive(false);
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(existing));
            when(segmentRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.update(
                    SEGMENT_ID,
                    new UpsertSegmentRequest(
                            "Fleet accounts", null, AudienceType.COMMERCIAL, SegmentType.STATIC, null, null));

            assertThat(existing.isActive()).isFalse();
        }

        @Test
        @DisplayName("throws 404 when the segment does not exist")
        void update_whenAbsent_throwsNotFound() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.update(SEGMENT_ID, staticRequest("Fleet accounts")))
                    .isInstanceOf(CrmResourceNotFoundException.class);
        }

        @Test
        @DisplayName("rejects a rename onto another segment's name")
        void update_whenNameTakenByOther_throwsDuplicate() {
            Segment existing = segment(SegmentType.STATIC, null);
            Segment other = segment(SegmentType.STATIC, null);
            other.setSegmentId(UUID.randomUUID());
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(existing));
            when(segmentRepository.findByNameIgnoreCase("Fleet accounts")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> sut.update(SEGMENT_ID, staticRequest("Fleet accounts")))
                    .isInstanceOf(CrmDuplicateResourceException.class);
        }

        @Test
        @DisplayName("allows a segment to keep its own name")
        void update_whenNameHeldBySelf_isAllowed() {
            Segment existing = segment(SegmentType.STATIC, null);
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(existing));
            when(segmentRepository.findByNameIgnoreCase("Fleet accounts")).thenReturn(Optional.of(existing));
            when(segmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(sut.update(SEGMENT_ID, staticRequest("Fleet accounts"))).isNotNull();
        }

        @Test
        @DisplayName("refuses to switch segment type after creation")
        void update_whenTypeChanges_throws() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.update(SEGMENT_ID, dynamicRequest("Fleet accounts")))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("type cannot change");
        }

        @Test
        @DisplayName("refuses to switch audience type after creation")
        void update_whenAudienceTypeChanges_throws() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());

            UpsertSegmentRequest switched = new UpsertSegmentRequest(
                    "Fleet accounts", null, AudienceType.INDIVIDUAL, SegmentType.STATIC, null, true);

            assertThatThrownBy(() -> sut.update(SEGMENT_ID, switched))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("audienceType cannot change");
        }
    }

    @Nested
    @DisplayName("delete, get, and list")
    class ReadAndDelete {

        @Test
        @DisplayName("delete removes the pinned members before the segment itself")
        void delete_removesMembersThenSegment() {
            Segment existing = segment(SegmentType.STATIC, null);
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(existing));

            sut.delete(SEGMENT_ID);

            verify(segmentMemberRepository).deleteBySegmentId(SEGMENT_ID);
            verify(segmentRepository).delete(existing);
            verify(factPublisher).segmentChanged(any(), any(), any(), any(), eq(true), eq(true));
        }

        @Test
        @DisplayName("delete throws 404 for an unknown segment")
        void delete_whenAbsent_throwsNotFound() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.delete(SEGMENT_ID)).isInstanceOf(CrmResourceNotFoundException.class);
            verify(segmentMemberRepository, never()).deleteBySegmentId(any());
        }

        @Test
        @DisplayName("get returns a live member count for a STATIC segment")
        void get_static_countsMembers() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentMemberRepository.countBySegmentId(SEGMENT_ID)).thenReturn(7L);

            assertThat(sut.get(SEGMENT_ID).memberCount()).isEqualTo(7L);
        }

        @Test
        @DisplayName("get reports a null member count for a DYNAMIC segment rather than a stale number")
        void get_dynamic_reportsNullMemberCount() {
            when(segmentRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segment(SegmentType.DYNAMIC, predicateJson())));

            assertThat(sut.get(SEGMENT_ID).memberCount()).isNull();
            verify(segmentMemberRepository, never()).countBySegmentId(any());
        }

        @Test
        @DisplayName("get deserializes the stored predicate back onto the response")
        void get_dynamic_returnsParsedPredicate() {
            when(segmentRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segment(SegmentType.DYNAMIC, predicateJson())));

            assertThat(sut.get(SEGMENT_ID).predicate()).isInstanceOf(SegmentPredicate.Comparison.class);
        }

        @Test
        @DisplayName("list without an audience type returns every segment")
        void list_withoutFilter_returnsAll() {
            when(segmentRepository.findAllByOrderByNameAsc()).thenReturn(List.of(segment(SegmentType.STATIC, null)));

            assertThat(sut.list(null)).hasSize(1);
            verify(segmentRepository, never()).findByAudienceTypeOrderByNameAsc(any());
        }

        @Test
        @DisplayName("list with an audience type uses the filtered query")
        void list_withFilter_usesFilteredQuery() {
            when(segmentRepository.findByAudienceTypeOrderByNameAsc(AudienceType.COMMERCIAL))
                    .thenReturn(List.of(segment(SegmentType.STATIC, null)));

            assertThat(sut.list(AudienceType.COMMERCIAL)).hasSize(1);
            verify(segmentRepository, never()).findAllByOrderByNameAsc();
        }

        @Test
        @DisplayName("attributeCatalog exposes the whole predicate attribute catalog")
        void attributeCatalog_isNotEmpty() {
            assertThat(sut.attributeCatalog()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("membership")
    class Membership {

        @Test
        @DisplayName("pins the supplied parties onto a STATIC segment")
        void addMembers_pinsParties() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentMemberRepository.findBySegmentIdAndPartyId(any(), any()))
                    .thenReturn(Optional.empty());
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            sut.addMembers(SEGMENT_ID, new SegmentMembersRequest(List.of(first, second)));

            ArgumentCaptor<List<SegmentMember>> added = ArgumentCaptor.captor();
            verify(segmentMemberRepository).saveAll(added.capture());
            assertThat(added.getValue()).extracting(SegmentMember::getPartyId).containsExactly(first, second);
            assertThat(added.getValue())
                    .allSatisfy(member -> assertThat(member.getAddedAt()).isEqualTo(NOW));
        }

        @Test
        @DisplayName("de-duplicates repeated party ids within one request")
        void addMembers_deduplicatesWithinRequest() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentMemberRepository.findBySegmentIdAndPartyId(any(), any()))
                    .thenReturn(Optional.empty());
            UUID partyId = UUID.randomUUID();

            sut.addMembers(SEGMENT_ID, new SegmentMembersRequest(List.of(partyId, partyId, partyId)));

            ArgumentCaptor<List<SegmentMember>> added = ArgumentCaptor.captor();
            verify(segmentMemberRepository).saveAll(added.capture());
            assertThat(added.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("skips parties already pinned to the segment")
        void addMembers_skipsExistingMembers() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.of(segment(SegmentType.STATIC, null)));
            when(segmentMemberRepository.findBySegmentIdAndPartyId(any(), any()))
                    .thenReturn(Optional.of(SegmentMember.builder().build()));

            sut.addMembers(SEGMENT_ID, new SegmentMembersRequest(List.of(UUID.randomUUID())));

            verify(segmentMemberRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("refuses to pin members onto a DYNAMIC segment")
        void addMembers_onDynamicSegment_throws() {
            when(segmentRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segment(SegmentType.DYNAMIC, predicateJson())));

            assertThatThrownBy(() -> sut.addMembers(SEGMENT_ID, new SegmentMembersRequest(List.of(UUID.randomUUID()))))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("STATIC");
        }

        @Test
        @DisplayName("removeMember deletes the pin when it exists")
        void removeMember_whenPresent_deletes() {
            SegmentMember member = SegmentMember.builder().build();
            UUID partyId = UUID.randomUUID();
            when(segmentMemberRepository.findBySegmentIdAndPartyId(SEGMENT_ID, partyId))
                    .thenReturn(Optional.of(member));

            sut.removeMember(SEGMENT_ID, partyId);

            verify(segmentMemberRepository).delete(member);
        }

        @Test
        @DisplayName("removeMember is a silent no-op when the party is not pinned")
        void removeMember_whenAbsent_isNoOp() {
            when(segmentMemberRepository.findBySegmentIdAndPartyId(any(), any()))
                    .thenReturn(Optional.empty());

            sut.removeMember(SEGMENT_ID, UUID.randomUUID());

            verify(segmentMemberRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        private final UUID partyA = UUID.randomUUID();
        private final UUID partyB = UUID.randomUUID();

        private void resolutionReturns(List<UUID> partyIds, boolean truncated) {
            when(segmentRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segment(SegmentType.DYNAMIC, predicateJson())));
            when(resolutionService.resolve(any(), any()))
                    .thenReturn(new SegmentResolutionService.Resolution(partyIds, truncated));
        }

        @Test
        @DisplayName("reports the matched count and carries the truncated flag through")
        void resolve_reportsCountAndTruncation() {
            resolutionReturns(List.of(partyA, partyB), true);
            lenient().when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of());

            SegmentResolutionResponse response = sut.resolve(SEGMENT_ID, null, 10);

            assertThat(response.totalMatched()).isEqualTo(2);
            assertThat(response.truncated()).isTrue();
        }

        @Test
        @DisplayName("leaves the eligible count null when no channel is supplied")
        void resolve_withoutChannel_leavesEligibleNull() {
            resolutionReturns(List.of(partyA, partyB), false);
            lenient().when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of());

            SegmentResolutionResponse response = sut.resolve(SEGMENT_ID, null, 0);

            assertThat(response.eligibleCount()).isNull();
            assertThat(response.channel()).isNull();
            verify(marketingConsentService, never()).resolveEligibility(any(), any());
        }

        @Test
        @DisplayName("counts only consent-eligible parties when a channel is supplied")
        void resolve_withChannel_countsEligibleOnly() {
            resolutionReturns(List.of(partyA, partyB), false);
            lenient().when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of());
            when(marketingConsentService.resolveEligibility(partyA, MarketingChannel.EMAIL))
                    .thenReturn(decision(partyA, true));
            when(marketingConsentService.resolveEligibility(partyB, MarketingChannel.EMAIL))
                    .thenReturn(decision(partyB, false));

            SegmentResolutionResponse response = sut.resolve(SEGMENT_ID, MarketingChannel.EMAIL, 0);

            assertThat(response.totalMatched()).isEqualTo(2);
            assertThat(response.eligibleCount()).isEqualTo(1L);
            assertThat(response.channel()).isEqualTo("EMAIL");
        }

        @ParameterizedTest(name = "a requested sample of {0} yields {1} entries")
        @CsvSource({"0, 0", "1, 1", "2, 2", "50, 2", "-5, 0"})
        @DisplayName("clamps the sample size to the 0..25 window and to what actually matched")
        void resolve_clampsSampleSize(int requested, int expected) {
            resolutionReturns(List.of(partyA, partyB), false);
            lenient().when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of());

            assertThat(sut.resolve(SEGMENT_ID, null, requested).sample()).hasSize(expected);
        }

        @Test
        @DisplayName("masks sample labels — the endpoint identifies an audience, it does not export one")
        void resolve_masksSampleLabels() {
            resolutionReturns(List.of(partyA), false);
            when(resolutionService.loadAttributes(eq(AudienceType.COMMERCIAL), anyList()))
                    .thenReturn(List.of(attributes(partyA, "Northwind Traders Incorporated")));

            SegmentResolutionResponse response = sut.resolve(SEGMENT_ID, null, 5);

            assertThat(response.sample()).singleElement().satisfies(entry -> {
                assertThat(entry.maskedLabel()).isEqualTo("North***");
                assertThat(entry.maskedLabel()).doesNotContain("Traders");
            });
        }

        @Test
        @DisplayName("leaves a short label unmasked — there is nothing to truncate")
        void resolve_leavesShortLabelIntact() {
            resolutionReturns(List.of(partyA), false);
            when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of(attributes(partyA, "ACME")));

            assertThat(sut.resolve(SEGMENT_ID, null, 5).sample())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.maskedLabel()).isEqualTo("ACME"));
        }

        @Test
        @DisplayName("emits a null label when the party has no display label")
        void resolve_whenLabelBlank_emitsNull() {
            resolutionReturns(List.of(partyA), false);
            when(resolutionService.loadAttributes(any(), anyList())).thenReturn(List.of(attributes(partyA, "  ")));

            assertThat(sut.resolve(SEGMENT_ID, null, 5).sample())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.maskedLabel()).isNull());
        }

        @Test
        @DisplayName("throws 404 for an unknown segment")
        void resolve_whenAbsent_throwsNotFound() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.resolve(SEGMENT_ID, null, 5)).isInstanceOf(CrmResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("resolveAndPublish")
    class ResolveAndPublish {

        @Test
        @DisplayName("publishes the resolved membership and reports its size")
        void resolveAndPublish_publishesResult() {
            UUID requestId = UUID.randomUUID();
            UUID partyId = UUID.randomUUID();
            when(segmentRepository.findById(SEGMENT_ID))
                    .thenReturn(Optional.of(segment(SegmentType.DYNAMIC, predicateJson())));
            when(resolutionService.resolve(any(), any()))
                    .thenReturn(new SegmentResolutionService.Resolution(List.of(partyId), false));

            assertThat(sut.resolveAndPublish(requestId, SEGMENT_ID)).contains(1);

            verify(factPublisher).segmentResolved(requestId, SEGMENT_ID, "COMMERCIAL", List.of(partyId), false);
        }

        @Test
        @DisplayName("returns empty and publishes nothing when the segment has since been deleted")
        void resolveAndPublish_whenSegmentDeleted_returnsEmpty() {
            when(segmentRepository.findById(SEGMENT_ID)).thenReturn(Optional.empty());

            // Deliberately not an exception: the request is not worth retrying, so the
            // requester is left to time out rather than the listener failing forever.
            assertThat(sut.resolveAndPublish(UUID.randomUUID(), SEGMENT_ID)).isEmpty();

            verify(factPublisher, never()).segmentResolved(any(), any(), any(), anyList(), anyBoolean());
        }
    }

    private static MarketingConsentDecision decision(UUID partyId, boolean allowed) {
        return new MarketingConsentDecision(partyId, "EMAIL", allowed, allowed ? "OPT_IN" : "OPT_OUT", null);
    }

    /**
     * PartyAttributes carries 27 components; only displayLabel matters to this
     * service, so the rest are filled with inert values.
     */
    private static PartyAttributes attributes(UUID partyId, String displayLabel) {
        return new PartyAttributes(
                partyId,
                "COMMERCIAL",
                null,
                null,
                false,
                Map.of(),
                Set.of(),
                null,
                null,
                null,
                "UNSET",
                "UNSET",
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                0L,
                displayLabel,
                null,
                false,
                null,
                false,
                null,
                null,
                null,
                null);
    }

    private static String predicateJson() {
        return new ObjectMapper().writeValueAsString(predicate());
    }
}
