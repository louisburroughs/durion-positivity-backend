package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link SystemPromptServiceImpl}.
 *
 * Issue: NLTI-001
 */
@ExtendWith(MockitoExtension.class)
class SystemPromptServiceImplTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID EXISTING_ID = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final UUID MISSING_ID = UUID.fromString("00000000-0000-7000-8000-000000000201");

    @Mock
    private SystemPromptRepository repository;

    @InjectMocks
    private SystemPromptServiceImpl service;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static SystemPrompt buildPrompt(UUID id, String name) {
        var p = new SystemPrompt();
        p.setId(id);
        p.setName(name);
        p.setContent("You are a helpful assistant.");
        p.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        p.setUpdatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return p;
    }

    private static SystemPromptRequest buildRequest(String name) {
        return new SystemPromptRequest(name, "You are a helpful assistant.");
    }

    // ─── create(request) ────────────────────────────────────────────────────

    @Test
    @DisplayName("create(request) saves and returns response when name is unique")
    void create_whenNameIsUnique_savesAndReturnsMappedResponse() {
        SystemPromptRequest request = buildRequest("default-prompt");
        SystemPrompt saved = buildPrompt(EXISTING_ID, "default-prompt");
        when(repository.existsByName("default-prompt")).thenReturn(false);
        when(repository.save(any(SystemPrompt.class))).thenReturn(saved);

        SystemPromptResponse result = service.create(request);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        assertThat(result.name()).isEqualTo("default-prompt");
        assertThat(result.content()).isEqualTo("You are a helpful assistant.");
        verify(repository).save(any(SystemPrompt.class));
    }

    @Test
    @DisplayName("create(request) throws IllegalArgumentException when name already exists")
    void create_whenNameAlreadyExists_throwsIllegalArgumentException() {
        SystemPromptRequest request = buildRequest("default-prompt");
        when(repository.existsByName("default-prompt")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-prompt");
    }

    // ─── findAll() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll() returns mapped list of all prompts")
    void findAll_withMultipleEntities_returnsMappedList() {
        SystemPrompt p1 = buildPrompt(EXISTING_ID, "default-prompt");
        SystemPrompt p2 = buildPrompt(MISSING_ID, "concise-prompt");
        when(repository.findAll()).thenReturn(List.of(p1, p2));

        List<SystemPromptResponse> result = service.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("default-prompt");
        assertThat(result.get(1).name()).isEqualTo("concise-prompt");
    }

    @Test
    @DisplayName("findAll() returns empty list when repository is empty")
    void findAll_withNoEntities_returnsEmptyList() {
        when(repository.findAll()).thenReturn(List.of());

        List<SystemPromptResponse> result = service.findAll();

        assertThat(result).isEmpty();
    }

    // ─── get(id) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get(id) returns response when entity found")
    void get_whenFound_returnsMappedResponse() {
        SystemPrompt prompt = buildPrompt(EXISTING_ID, "default-prompt");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(prompt));

        SystemPromptResponse result = service.get(EXISTING_ID);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        assertThat(result.name()).isEqualTo("default-prompt");
    }

    @Test
    @DisplayName("get(id) throws NoSuchElementException when not found")
    void get_whenNotFound_throwsNoSuchElementException() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MISSING_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(MISSING_ID.toString());
    }

    // ─── update(id, request) ────────────────────────────────────────────────

    @Test
    @DisplayName("update() updates and saves when id found and name unchanged")
    void update_whenFoundAndNameUnchanged_updatesAndSaves() {
        SystemPrompt prompt = buildPrompt(EXISTING_ID, "default-prompt");
        SystemPromptRequest request = buildRequest("default-prompt"); // same name
        SystemPrompt saved = buildPrompt(EXISTING_ID, "default-prompt");
        saved.setContent("Updated content.");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(prompt));
        when(repository.save(prompt)).thenReturn(saved);

        SystemPromptResponse result = service.update(EXISTING_ID, request);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        verify(repository).save(prompt);
    }

    @Test
    @DisplayName("update() updates and saves when name changed and not conflicting")
    void update_whenNameChangedAndNotConflicting_updatesAndSaves() {
        SystemPrompt prompt = buildPrompt(EXISTING_ID, "default-prompt");
        SystemPromptRequest request = new SystemPromptRequest("new-prompt-name", "Updated content.");
        SystemPrompt saved = buildPrompt(EXISTING_ID, "new-prompt-name");
        saved.setContent("Updated content.");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(prompt));
        when(repository.existsByName("new-prompt-name")).thenReturn(false);
        when(repository.save(prompt)).thenReturn(saved);

        SystemPromptResponse result = service.update(EXISTING_ID, request);

        assertThat(result.name()).isEqualTo("new-prompt-name");
        verify(repository).save(prompt);
    }

    @Test
    @DisplayName("update() throws NoSuchElementException when id not found")
    void update_whenIdNotFound_throwsNoSuchElementException() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MISSING_ID, buildRequest("any-name")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(MISSING_ID.toString());
    }

    @Test
    @DisplayName("update() throws IllegalArgumentException when new name already exists for different prompt")
    void update_whenNewNameConflictsWithExistingPrompt_throwsIllegalArgumentException() {
        SystemPrompt prompt = buildPrompt(EXISTING_ID, "default-prompt");
        // trying to rename to "concise-prompt" which already belongs to another record
        SystemPromptRequest request = new SystemPromptRequest("concise-prompt", "Some content.");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(prompt));
        when(repository.existsByName("concise-prompt")).thenReturn(true);

        assertThatThrownBy(() -> service.update(EXISTING_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concise-prompt");
    }

    // ─── delete(id) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete(id) delegates to repository.deleteById")
    void delete_delegatesToRepositoryDeleteById() {
        service.delete(EXISTING_ID);

        verify(repository).deleteById(EXISTING_ID);
    }
}
