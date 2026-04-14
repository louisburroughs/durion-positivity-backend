package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.dto.PostingRuleVersionResponse;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import com.positivity.accounting.internal.service.PostingRuleServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for PostingRuleServiceImpl.
 *
 * Tests rule set versioning, state management, and CRUD operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostingRuleService Unit Tests")
class PostingRuleServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private PostingRuleSetRepository ruleSetRepository;

    @Mock
    private PostingRuleVersionRepository versionRepository;

    @InjectMocks
    private PostingRuleServiceImpl service;

    private UUID ruleSetId;
    private UUID versionId;
    private PostingRuleSet testRuleSet;
    private PostingRuleVersion testVersion;

    @BeforeEach
    void setUp() {
        ruleSetId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        versionId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        testRuleSet = new PostingRuleSet();
        testRuleSet.setPostingRuleSetId(ruleSetId);
        testRuleSet.setName("Labor Rules");
        testRuleSet.setEventType("LABOR_CHARGE");
        testRuleSet.setDescription("Rules for labor");
        testRuleSet.setVersions(new ArrayList<>());

        testVersion = new PostingRuleVersion();
        testVersion.setVersionId(versionId);
        testVersion.setVersionNumber(1);
        testVersion.setState(PostingRuleSetState.DRAFT);
        testVersion.setRulesDefinition("{\"conditions\":[]}");
        testVersion.setPostingRuleSet(testRuleSet);
    }

    // ===== CREATE RULE SET WITH VERSION =====

    @Test
    @DisplayName("createPostingRuleSetWithVersion - creates rule set with initial draft version")
    void createPostingRuleSetWithVersion_success() {
        PostingRuleSetCreateRequest request = PostingRuleSetCreateRequest.builder()
                .name("Labor Rules")
                .eventType("LABOR_CHARGE")
                .description("desc")
                .rulesDefinition("{\"conditions\":[]}")
                .createdBy("admin")
                .build();

        when(ruleSetRepository.save(any(PostingRuleSet.class))).thenAnswer(inv -> {
            PostingRuleSet rs = inv.getArgument(0);
            rs.setPostingRuleSetId(ruleSetId);
            return rs;
        });

        PostingRuleSetResponse result = service.createPostingRuleSetWithVersion(request);

        assertThat(result).isNotNull();
        verify(ruleSetRepository).save(any(PostingRuleSet.class));
    }

    // ===== PUBLISH RULE SET =====

    @Nested
    @DisplayName("publishRuleSet")
    class PublishRuleSet {

        @Test
        @DisplayName("publishes draft version successfully")
        void success() {
            testRuleSet.getVersions().add(testVersion);

            when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                    .thenReturn(List.of(testVersion));
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));
            when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                            eq(ruleSetId), eq(PostingRuleSetState.PUBLISHED)))
                    .thenReturn(List.of());
            when(versionRepository.save(any(PostingRuleVersion.class))).thenReturn(testVersion);

            PostingRuleVersionResponse result = service.publishRuleSet(ruleSetId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws when no draft version exists")
        void noDraftVersion() {
            testVersion.setState(PostingRuleSetState.PUBLISHED);
            testRuleSet.getVersions().add(testVersion);

            when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                    .thenReturn(List.of(testVersion));

            assertThatThrownBy(() -> service.publishRuleSet(ruleSetId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No DRAFT version");
        }
    }

    // ===== ARCHIVE RULE SET =====

    @Nested
    @DisplayName("archiveRuleSet")
    class ArchiveRuleSet {

        @Test
        @DisplayName("archives published version successfully")
        void success() {
            testVersion.setState(PostingRuleSetState.PUBLISHED);
            testRuleSet.getVersions().add(testVersion);

            when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                    .thenReturn(List.of(testVersion));
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));
            when(versionRepository.save(any(PostingRuleVersion.class))).thenReturn(testVersion);

            PostingRuleVersionResponse result = service.archiveRuleSet(ruleSetId);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws when no published version exists")
        void noPublishedVersion() {
            testRuleSet.getVersions().add(testVersion); // testVersion is DRAFT

            when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                    .thenReturn(List.of(testVersion));

            assertThatThrownBy(() -> service.archiveRuleSet(ruleSetId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No PUBLISHED version");
        }
    }

    // ===== LIST / GET =====

    @Test
    @DisplayName("listRuleSetsAsResponse - returns paginated response")
    void listRuleSetsAsResponse_returnsPaginated() {
        Page<PostingRuleSet> page = new PageImpl<>(List.of(testRuleSet));
        when(ruleSetRepository.findAll(any(Pageable.class))).thenReturn(page);

        var result = service.listRuleSetsAsResponse(0, 10, "name");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getPostingRuleSetAsResponse - returns DTO for existing rule set")
    void getPostingRuleSetAsResponse_success() {
        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));

        PostingRuleSetResponse result = service.getPostingRuleSetAsResponse(ruleSetId);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Labor Rules");
    }

    @Test
    @DisplayName("listVersionsAsResponse - returns versions with pagination")
    void listVersionsAsResponse_returnsPaginated() {
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId)).thenReturn(List.of(testVersion));

        List<PostingRuleVersionResponse> result = service.listVersionsAsResponse(ruleSetId, 0, 10);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listVersionsAsResponse - returns empty when page is out of range")
    void listVersionsAsResponse_emptyWhenOutOfRange() {
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId)).thenReturn(List.of(testVersion));

        List<PostingRuleVersionResponse> result = service.listVersionsAsResponse(ruleSetId, 5, 10);

        assertThat(result).isEmpty();
    }

    // ===== UPDATE RULE SET =====

    @Test
    @DisplayName("updatePostingRuleSet - updates when no published version exists")
    void updatePostingRuleSet_success() {
        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                .thenReturn(List.of(testVersion)); // testVersion is DRAFT
        when(ruleSetRepository.save(any(PostingRuleSet.class))).thenReturn(testRuleSet);

        PostingRuleSet updates = new PostingRuleSet();
        updates.setName("Updated Rules");
        updates.setEventType("LABOR_CHARGE");
        updates.setDescription("Updated desc");

        PostingRuleSet result = service.updatePostingRuleSet(ruleSetId, updates);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("updatePostingRuleSet - throws when published version exists")
    void updatePostingRuleSet_publishedVersionExists() {
        testVersion.setState(PostingRuleSetState.PUBLISHED);
        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId)).thenReturn(List.of(testVersion));

        assertThatThrownBy(() -> service.updatePostingRuleSet(ruleSetId, testRuleSet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot modify published");
    }

    // ===== CREATE VERSION =====

    @Test
    @DisplayName("createVersion - creates version with incremented number")
    void createVersion_success() {
        PostingRuleVersion existing = new PostingRuleVersion();
        existing.setVersionNumber(2);
        existing.setState(PostingRuleSetState.ARCHIVED);

        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId)).thenReturn(List.of(existing));
        when(versionRepository.save(any(PostingRuleVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        PostingRuleVersion newVersion = new PostingRuleVersion();
        newVersion.setRulesDefinition("{\"conditions\":[]}");
        newVersion.setCreatedBy("admin");
        newVersion.setModifiedBy("admin");

        PostingRuleVersion result = service.createVersion(ruleSetId, newVersion);

        assertThat(result.getVersionNumber()).isEqualTo(3);
        assertThat(result.getState()).isEqualTo(PostingRuleSetState.DRAFT);
    }

    // ===== UPDATE VERSION =====

    @Nested
    @DisplayName("updateVersion")
    class UpdateVersion {

        @Test
        @DisplayName("updates draft version successfully")
        void success() {
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));
            when(versionRepository.save(any(PostingRuleVersion.class))).thenReturn(testVersion);

            PostingRuleVersion updates = new PostingRuleVersion();
            updates.setRulesDefinition("{\"conditions\":[{\"new\":true}]}");

            PostingRuleVersion result = service.updateVersion(versionId, updates);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("throws when version is not DRAFT")
        void nonDraftThrows() {
            testVersion.setState(PostingRuleSetState.PUBLISHED);
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));

            PostingRuleVersion updates = new PostingRuleVersion();
            updates.setRulesDefinition("{\"conditions\":[]}");

            assertThatThrownBy(() -> service.updateVersion(versionId, updates))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT");
        }
    }

    // ===== PUBLISH VERSION =====

    @Nested
    @DisplayName("publishVersion")
    class PublishVersion {

        @Test
        @DisplayName("publishes draft version, archives existing published version")
        void success_archivesExisting() {
            PostingRuleVersion existing = new PostingRuleVersion();
            UUID existingId = UUID.fromString("00000000-0000-0000-0000-000000000099");
            existing.setVersionId(existingId);
            existing.setState(PostingRuleSetState.PUBLISHED);
            existing.setPostingRuleSet(testRuleSet);

            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));
            when(versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                            eq(ruleSetId), eq(PostingRuleSetState.PUBLISHED)))
                    .thenReturn(List.of(existing));
            when(versionRepository.save(any(PostingRuleVersion.class))).thenAnswer(inv -> inv.getArgument(0));

            PostingRuleVersion result = service.publishVersion(versionId);

            assertThat(result.getState()).isEqualTo(PostingRuleSetState.PUBLISHED);
            assertThat(result.getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws when version is not DRAFT")
        void nonDraftThrows() {
            testVersion.setState(PostingRuleSetState.ARCHIVED);
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));

            assertThatThrownBy(() -> service.publishVersion(versionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Can only publish DRAFT");
        }

        @Test
        @DisplayName("throws when rules definition is empty")
        void emptyRulesThrows() {
            testVersion.setRulesDefinition("  ");
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));

            assertThatThrownBy(() -> service.publishVersion(versionId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rules definition is empty");
        }
    }

    // ===== ARCHIVE VERSION =====

    @Nested
    @DisplayName("archiveVersion")
    class ArchiveVersion {

        @Test
        @DisplayName("archives published version successfully")
        void success() {
            testVersion.setState(PostingRuleSetState.PUBLISHED);
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));
            when(versionRepository.save(any(PostingRuleVersion.class))).thenReturn(testVersion);

            PostingRuleVersion result = service.archiveVersion(versionId);

            assertThat(result.getState()).isEqualTo(PostingRuleSetState.ARCHIVED);
            assertThat(result.getArchivedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws when version is not PUBLISHED")
        void nonPublishedThrows() {
            testVersion.setState(PostingRuleSetState.DRAFT);
            when(versionRepository.findById(versionId)).thenReturn(Optional.of(testVersion));

            assertThatThrownBy(() -> service.archiveVersion(versionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Can only archive PUBLISHED");
        }
    }

    // ===== LIST =====

    @Test
    @DisplayName("listRuleSets - returns all rule sets")
    void listRuleSets_returnsAll() {
        when(ruleSetRepository.findAll()).thenReturn(List.of(testRuleSet));

        List<PostingRuleSet> result = service.listRuleSets();

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listVersions - returns versions for rule set")
    void listVersions_returnsVersions() {
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId)).thenReturn(List.of(testVersion));

        List<PostingRuleVersion> result = service.listVersions(ruleSetId);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("getPostingRuleSet - returns rule set entity")
    void getPostingRuleSet_success() {
        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));

        PostingRuleSet result = service.getPostingRuleSet(ruleSetId);

        assertThat(result).isEqualTo(testRuleSet);
    }

    @Test
    @DisplayName("getPostingRuleSet - throws when not found")
    void getPostingRuleSet_notFound() {
        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPostingRuleSet(ruleSetId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("updatePostingRuleSetFromRequest - delegates to updatePostingRuleSet")
    void updatePostingRuleSetFromRequest_success() {
        PostingRuleSetCreateRequest request = PostingRuleSetCreateRequest.builder()
                .name("Updated")
                .eventType("LABOR_CHARGE")
                .description("desc")
                .rulesDefinition("{}")
                .createdBy("admin")
                .build();

        when(ruleSetRepository.findByIdWithVersions(ruleSetId)).thenReturn(Optional.of(testRuleSet));
        when(versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId))
                .thenReturn(List.of(testVersion)); // DRAFT - no published version
        when(ruleSetRepository.save(any(PostingRuleSet.class))).thenReturn(testRuleSet);

        PostingRuleSet result = service.updatePostingRuleSetFromRequest(ruleSetId, request);

        assertThat(result).isNotNull();
    }
}
