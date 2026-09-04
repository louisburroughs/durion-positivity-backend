package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.PartyTagAssignmentResponse;
import com.positivity.customer.internal.dto.PartyTagResponse;
import com.positivity.customer.internal.dto.UpsertPartyTagRequest;
import com.positivity.customer.internal.entity.PartyTag;
import com.positivity.customer.internal.entity.PartyTagAssignment;
import com.positivity.customer.internal.enums.TagAssignmentSource;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PartyTagRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link PartyTagServiceImpl}.
 *
 * <p>
 * Tagging drives downstream audience replicas, so the behaviour that matters is
 * mostly about keeping those replicas honest:
 *
 * <ul>
 * <li>Deleting a tag emits a removal fact <em>per assigned party</em> before the
 * assignments disappear. Skipping that would leave every replica holding a tag
 * that no longer exists, with nothing left to reconcile against.</li>
 * <li>Assignment is idempotent and converges under a concurrent write: a losing
 * race on the unique constraint returns the row that landed rather than
 * surfacing a conflict to the caller.</li>
 * <li>An inactive tag cannot be newly assigned, but an assignment that already
 * exists is still returned — deactivating a tag is not retroactive.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PartyTagServiceImpl — tag lifecycle and assignment")
class PartyTagServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID TAG_ID = UUID.randomUUID();
    private static final UUID PARTY_ID = UUID.randomUUID();

    @Mock
    private PartyTagRepository tagRepository;

    @Mock
    private PartyTagAssignmentRepository assignmentRepository;

    @Mock
    private CustomerFactPublisher factPublisher;

    @Captor
    private ArgumentCaptor<PartyTag> savedTag;

    @Captor
    private ArgumentCaptor<PartyTagAssignment> savedAssignment;

    private PartyTagServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new PartyTagServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC), tagRepository, assignmentRepository, factPublisher);
    }

    private static PartyTag tag(boolean active) {
        return PartyTag.builder()
                .tagId(TAG_ID)
                .name("VIP")
                .category("service")
                .color("#ff0000")
                .active(active)
                .build();
    }

    private static PartyTagAssignment assignment() {
        return PartyTagAssignment.builder()
                .assignmentId(UUID.randomUUID())
                .partyId(PARTY_ID)
                .tagId(TAG_ID)
                .assignedBy("system")
                .assignedAt(NOW)
                .source(TagAssignmentSource.MANUAL)
                .build();
    }

    private static UpsertPartyTagRequest upsert(String name, Boolean active) {
        return new UpsertPartyTagRequest(name, "service", "#ff0000", active);
    }

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("persists a new tag and reports zero assignments")
        void create_persistsTag() {
            when(tagRepository.existsByNameIgnoreCase("VIP")).thenReturn(false);
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            PartyTagResponse response = sut.createTag(upsert("VIP", true));

            assertThat(response.name()).isEqualTo("VIP");
            assertThat(response.assignmentCount()).isZero();
        }

        @Test
        @DisplayName("trims the name before the uniqueness check and before storing it")
        void create_trimsName() {
            when(tagRepository.existsByNameIgnoreCase("VIP")).thenReturn(false);
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.createTag(upsert("  VIP  ", true));

            verify(tagRepository).save(savedTag.capture());
            assertThat(savedTag.getValue().getName()).isEqualTo("VIP");
        }

        @Test
        @DisplayName("normalizes a blank category and color to null rather than storing whitespace")
        void create_blankOptionalFieldsBecomeNull() {
            when(tagRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.createTag(new UpsertPartyTagRequest("VIP", "   ", "", true));

            verify(tagRepository).save(savedTag.capture());
            assertThat(savedTag.getValue().getCategory()).isNull();
            assertThat(savedTag.getValue().getColor()).isNull();
        }

        @Test
        @DisplayName("defaults active to true when the request leaves it unset")
        void create_whenActiveOmitted_defaultsToActive() {
            when(tagRepository.existsByNameIgnoreCase(any())).thenReturn(false);
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.createTag(upsert("VIP", null));

            verify(tagRepository).save(savedTag.capture());
            assertThat(savedTag.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("rejects a duplicate name case-insensitively")
        void create_whenNameTaken_throwsDuplicate() {
            when(tagRepository.existsByNameIgnoreCase("VIP")).thenReturn(true);

            assertThatThrownBy(() -> sut.createTag(upsert("VIP", true)))
                    .isInstanceOf(CrmDuplicateResourceException.class);
            verify(tagRepository, never()).save(any());
        }

        @Test
        @DisplayName("translates a losing unique-constraint race into a duplicate error, not a 500")
        void create_whenConstraintRaceLost_throwsDuplicate() {
            when(tagRepository.existsByNameIgnoreCase("VIP")).thenReturn(false);
            when(tagRepository.save(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

            assertThatThrownBy(() -> sut.createTag(upsert("VIP", true)))
                    .isInstanceOf(CrmDuplicateResourceException.class);
        }
    }

    @Nested
    @DisplayName("updateTag, deleteTag, and reads")
    class UpdateDeleteRead {

        @Test
        @DisplayName("applies the new name, category, color, and active flag")
        void update_appliesChanges() {
            PartyTag existing = tag(true);
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(existing));
            when(tagRepository.findByNameIgnoreCase("Premier")).thenReturn(Optional.empty());
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(assignmentRepository.countByTagId(TAG_ID)).thenReturn(4L);

            PartyTagResponse response =
                    sut.updateTag(TAG_ID, new UpsertPartyTagRequest("Premier", "sales", "#00ff00", false));

            assertThat(existing.getName()).isEqualTo("Premier");
            assertThat(existing.getCategory()).isEqualTo("sales");
            assertThat(existing.getColor()).isEqualTo("#00ff00");
            assertThat(existing.isActive()).isFalse();
            assertThat(response.assignmentCount()).isEqualTo(4L);
        }

        @Test
        @DisplayName("leaves the active flag untouched when the request omits it")
        void update_whenActiveOmitted_leavesFlagUnchanged() {
            PartyTag existing = tag(false);
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(existing));
            when(tagRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.updateTag(TAG_ID, upsert("VIP", null));

            assertThat(existing.isActive()).isFalse();
        }

        @Test
        @DisplayName("allows a tag to keep its own name, so a pure case change is permitted")
        void update_whenNameHeldBySelf_isAllowed() {
            PartyTag existing = tag(true);
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(existing));
            when(tagRepository.findByNameIgnoreCase("vip")).thenReturn(Optional.of(existing));
            when(tagRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            sut.updateTag(TAG_ID, upsert("vip", true));

            assertThat(existing.getName()).isEqualTo("vip");
        }

        @Test
        @DisplayName("rejects a rename onto another tag's name")
        void update_whenNameTakenByOther_throwsDuplicate() {
            PartyTag other = tag(true);
            other.setTagId(UUID.randomUUID());
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(tagRepository.findByNameIgnoreCase("VIP")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> sut.updateTag(TAG_ID, upsert("VIP", true)))
                    .isInstanceOf(CrmDuplicateResourceException.class);
        }

        @Test
        @DisplayName("throws 404 when updating an unknown tag")
        void update_whenAbsent_throwsNotFound() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.updateTag(TAG_ID, upsert("VIP", true)))
                    .isInstanceOf(CrmResourceNotFoundException.class);
        }

        @Test
        @DisplayName("delete emits a removal fact for every assigned party")
        void delete_emitsRemovalFactPerParty() {
            UUID otherParty = UUID.randomUUID();
            PartyTagAssignment first = assignment();
            PartyTagAssignment second = assignment();
            second.setPartyId(otherParty);
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByTagId(TAG_ID)).thenReturn(List.of(first, second));

            sut.deleteTag(TAG_ID);

            // Without a per-party fact, downstream replicas would keep a tag that
            // no longer exists and have nothing left to reconcile against.
            verify(factPublisher).partyTagChanged(PARTY_ID, TAG_ID, "VIP", false, null);
            verify(factPublisher).partyTagChanged(otherParty, TAG_ID, "VIP", false, null);
            verify(assignmentRepository).deleteByTagId(TAG_ID);
        }

        @Test
        @DisplayName("delete throws 404 for an unknown tag and removes nothing")
        void delete_whenAbsent_throwsNotFound() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.deleteTag(TAG_ID)).isInstanceOf(CrmResourceNotFoundException.class);
            verify(assignmentRepository, never()).deleteByTagId(any());
        }

        @Test
        @DisplayName("listTags excludes inactive tags by default")
        void listTags_excludesInactiveByDefault() {
            when(tagRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(tag(true)));
            when(assignmentRepository.countByTagId(TAG_ID)).thenReturn(2L);

            assertThat(sut.listTags(false))
                    .singleElement()
                    .satisfies(
                            response -> assertThat(response.assignmentCount()).isEqualTo(2L));
            verify(tagRepository, never()).findAllByOrderByNameAsc();
        }

        @Test
        @DisplayName("listTags includes inactive tags when asked")
        void listTags_includesInactiveWhenRequested() {
            when(tagRepository.findAllByOrderByNameAsc()).thenReturn(List.of(tag(false)));
            when(assignmentRepository.countByTagId(TAG_ID)).thenReturn(0L);

            assertThat(sut.listTags(true)).hasSize(1);
            verify(tagRepository, never()).findAllByActiveTrueOrderByNameAsc();
        }

        @Test
        @DisplayName("getTag returns the tag with its live assignment count")
        void getTag_returnsCount() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.countByTagId(TAG_ID)).thenReturn(9L);

            assertThat(sut.getTag(TAG_ID).assignmentCount()).isEqualTo(9L);
        }
    }

    @Nested
    @DisplayName("assignTag")
    class AssignTag {

        @Test
        @DisplayName("creates the assignment and publishes an assigned fact")
        void assign_createsAssignment() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.empty());
            when(assignmentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            PartyTagAssignmentResponse response =
                    sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.CAMPAIGN));

            assertThat(response.name()).isEqualTo("VIP");
            verify(assignmentRepository).saveAndFlush(savedAssignment.capture());
            assertThat(savedAssignment.getValue().getAssignedAt()).isEqualTo(NOW);
            assertThat(savedAssignment.getValue().getSource()).isEqualTo(TagAssignmentSource.CAMPAIGN);
            verify(factPublisher).partyTagChanged(PARTY_ID, TAG_ID, "VIP", true, "CAMPAIGN");
        }

        @Test
        @DisplayName("defaults the assignment source to MANUAL when the request omits it")
        void assign_whenSourceOmitted_defaultsToManual() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByPartyIdAndTagId(any(), any())).thenReturn(Optional.empty());
            when(assignmentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

            sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, null));

            verify(assignmentRepository).saveAndFlush(savedAssignment.capture());
            assertThat(savedAssignment.getValue().getSource()).isEqualTo(TagAssignmentSource.MANUAL);
        }

        @Test
        @DisplayName("is idempotent — an existing assignment is returned without a second write or fact")
        void assign_whenAlreadyAssigned_isIdempotent() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.of(assignment()));

            sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL));

            verify(assignmentRepository, never()).saveAndFlush(any());
            verify(factPublisher, never()).partyTagChanged(any(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("refuses to newly assign an inactive tag")
        void assign_whenTagInactive_throws() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(false)));
            when(assignmentRepository.findByPartyIdAndTagId(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL)))
                    .isInstanceOf(CrmUnprocessableEntityException.class)
                    .hasMessageContaining("inactive");
        }

        @Test
        @DisplayName("still returns a pre-existing assignment on an inactive tag — deactivation is not retroactive")
        void assign_whenTagInactiveButAlreadyAssigned_returnsExisting() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(false)));
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.of(assignment()));

            assertThat(sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL)))
                    .isNotNull();
        }

        @Test
        @DisplayName("converges on the winning row when a concurrent assign loses the unique-constraint race")
        void assign_whenConstraintRaceLost_convergesOnWinningRow() {
            PartyTagAssignment winner = assignment();
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(assignmentRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));

            PartyTagAssignmentResponse response =
                    sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL));

            assertThat(response.assignmentId()).isEqualTo(winner.getAssignmentId());
            verify(factPublisher, never()).partyTagChanged(any(), any(), any(), eq(true), any());
        }

        @Test
        @DisplayName("rethrows when the constraint violation was not a losing race after all")
        void assign_whenConstraintViolationUnexplained_rethrows() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.empty());
            when(assignmentRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("some other violation"));

            assertThatThrownBy(() ->
                            sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("throws 404 when the tag does not exist")
        void assign_whenTagAbsent_throwsNotFound() {
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            sut.assignTag(PARTY_ID, new AssignPartyTagRequest(TAG_ID, TagAssignmentSource.MANUAL)))
                    .isInstanceOf(CrmResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("removeTag and queries")
    class RemoveAndQuery {

        @Test
        @DisplayName("removes the assignment and publishes a removal fact")
        void remove_deletesAndPublishes() {
            PartyTagAssignment existing = assignment();
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.of(existing));
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.of(tag(true)));

            sut.removeTag(PARTY_ID, TAG_ID);

            verify(assignmentRepository).delete(existing);
            verify(factPublisher).partyTagChanged(PARTY_ID, TAG_ID, "VIP", false, null);
        }

        @Test
        @DisplayName("is a silent no-op when the party does not carry the tag")
        void remove_whenNotAssigned_isNoOp() {
            when(assignmentRepository.findByPartyIdAndTagId(any(), any())).thenReturn(Optional.empty());

            sut.removeTag(PARTY_ID, TAG_ID);

            verify(assignmentRepository, never()).delete(any());
            verify(factPublisher, never()).partyTagChanged(any(), any(), any(), any(Boolean.class), any());
        }

        @Test
        @DisplayName("still deletes the assignment when the tag row has already gone")
        void remove_whenTagAlreadyDeleted_stillDeletesAssignment() {
            PartyTagAssignment existing = assignment();
            when(assignmentRepository.findByPartyIdAndTagId(PARTY_ID, TAG_ID)).thenReturn(Optional.of(existing));
            when(tagRepository.findById(TAG_ID)).thenReturn(Optional.empty());

            sut.removeTag(PARTY_ID, TAG_ID);

            verify(assignmentRepository).delete(existing);
            verify(factPublisher, never()).partyTagChanged(any(), any(), any(), any(Boolean.class), any());
        }

        @Test
        @DisplayName("listPartyTags returns an empty list without querying tags when the party has none")
        void listPartyTags_whenNone_returnsEmpty() {
            when(assignmentRepository.findByPartyId(PARTY_ID)).thenReturn(List.of());

            assertThat(sut.listPartyTags(PARTY_ID)).isEmpty();
            verify(tagRepository, never()).findAllByTagIdIn(anyList());
        }

        @Test
        @DisplayName("listPartyTags joins each assignment to its tag")
        void listPartyTags_joinsTagDetail() {
            when(assignmentRepository.findByPartyId(PARTY_ID)).thenReturn(List.of(assignment()));
            when(tagRepository.findAllByTagIdIn(List.of(TAG_ID))).thenReturn(List.of(tag(true)));

            assertThat(sut.listPartyTags(PARTY_ID)).singleElement().satisfies(response -> {
                assertThat(response.name()).isEqualTo("VIP");
                assertThat(response.category()).isEqualTo("service");
            });
        }

        @Test
        @DisplayName("listPartyTags drops an assignment whose tag row has vanished rather than failing")
        void listPartyTags_dropsOrphanedAssignment() {
            when(assignmentRepository.findByPartyId(PARTY_ID)).thenReturn(List.of(assignment()));
            when(tagRepository.findAllByTagIdIn(anyList())).thenReturn(List.of());

            assertThat(sut.listPartyTags(PARTY_ID)).isEmpty();
        }

        @Test
        @DisplayName("findPartyIdsByTags returns empty without querying when no tags are supplied")
        void findPartyIds_whenNoTags_returnsEmpty() {
            assertThat(sut.findPartyIdsByTags(List.of(), true)).isEmpty();
            verify(assignmentRepository, never()).findPartyIdsHavingAllTags(anyList(), anyLong());
            verify(assignmentRepository, never()).findPartyIdsHavingAnyTag(anyList());
        }

        @Test
        @DisplayName("findPartyIdsByTags uses the all-tags query and de-duplicates the required count")
        void findPartyIds_matchAll_usesAllTagsQuery() {
            when(assignmentRepository.findPartyIdsHavingAllTags(List.of(TAG_ID), 1L))
                    .thenReturn(List.of(PARTY_ID));

            // The duplicate must not inflate the required-match count to 2, which
            // would make the query match nothing.
            assertThat(sut.findPartyIdsByTags(List.of(TAG_ID, TAG_ID), true)).containsExactly(PARTY_ID);
        }

        @Test
        @DisplayName("findPartyIdsByTags uses the any-tag query when matchAll is false")
        void findPartyIds_matchAny_usesAnyTagQuery() {
            when(assignmentRepository.findPartyIdsHavingAnyTag(List.of(TAG_ID))).thenReturn(List.of(PARTY_ID));

            assertThat(sut.findPartyIdsByTags(List.of(TAG_ID), false)).containsExactly(PARTY_ID);
            verify(assignmentRepository, never()).findPartyIdsHavingAllTags(anyList(), anyLong());
        }
    }
}
