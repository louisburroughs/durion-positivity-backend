package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.internal.entity.LlmApiConfig;
import com.positivity.mcp.internal.repository.LlmApiConfigRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Tests for {@link LlmApiConfigServiceImpl}.
 *
 * Issue: NLTI-001
 */
@ExtendWith(MockitoExtension.class)
class LlmApiConfigServiceImplTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID EXISTING_ID = UUID.fromString("00000000-0000-7000-8000-000000000100");
    private static final UUID MISSING_ID = UUID.fromString("00000000-0000-7000-8000-000000000101");

    @Mock
    private LlmApiConfigRepository repository;

    @InjectMocks
    private LlmApiConfigServiceImpl service;

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static LlmApiConfig buildConfig(UUID id) {
        var entity = new LlmApiConfig();
        entity.setId(id);
        entity.setApiId("openai-gpt4");
        entity.setModel("gpt-4");
        entity.setBaseUrl("https://api.openai.com");
        entity.setApiKey("sk-test");
        entity.setHeaders(Map.of());
        entity.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        return entity;
    }

    private static LlmApiConfigRequest buildRequest() {
        return new LlmApiConfigRequest("openai-gpt4", "gpt-4",
                "https://api.openai.com", "sk-test", Map.of());
    }

    // ─── list() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list() returns mapped response for each entity returned by repository")
    void list_withOneEntity_returnsMappedList() {
        LlmApiConfig entity = buildConfig(EXISTING_ID);
        when(repository.findAll()).thenReturn(List.of(entity));

        List<LlmApiConfigResponse> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(EXISTING_ID);
        assertThat(result.get(0).apiId()).isEqualTo("openai-gpt4");
        assertThat(result.get(0).model()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("list() returns empty list when repository returns empty")
    void list_withNoEntities_returnsEmptyList() {
        when(repository.findAll()).thenReturn(List.of());

        List<LlmApiConfigResponse> result = service.list();

        assertThat(result).isEmpty();
    }

    // ─── get(id) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get(id) returns response when entity found")
    void get_whenFound_returnsMappedResponse() {
        LlmApiConfig entity = buildConfig(EXISTING_ID);
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(entity));

        LlmApiConfigResponse result = service.get(EXISTING_ID);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        assertThat(result.apiId()).isEqualTo("openai-gpt4");
        assertThat(result.baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    @DisplayName("get(id) throws NoSuchElementException when not found")
    void get_whenNotFound_throwsNoSuchElementException() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MISSING_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(MISSING_ID.toString());
    }

    // ─── create(request) ────────────────────────────────────────────────────

    @Test
    @DisplayName("create(request) saves and returns response when apiId is unique")
    void create_whenApiIdIsUnique_savesAndReturnsMappedResponse() {
        LlmApiConfigRequest request = buildRequest();
        LlmApiConfig saved = buildConfig(EXISTING_ID);
        when(repository.existsByApiId("openai-gpt4")).thenReturn(false);
        when(repository.save(any(LlmApiConfig.class))).thenReturn(saved);

        LlmApiConfigResponse result = service.create(request);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        assertThat(result.apiId()).isEqualTo("openai-gpt4");
        verify(repository).save(any(LlmApiConfig.class));
    }

    @Test
    @DisplayName("create(request) throws IllegalArgumentException when apiId already exists")
    void create_whenApiIdAlreadyExists_throwsIllegalArgumentException() {
        LlmApiConfigRequest request = buildRequest();
        when(repository.existsByApiId("openai-gpt4")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai-gpt4");
    }

    // ─── update(id, request) ────────────────────────────────────────────────

    @Test
    @DisplayName("update() updates and saves when id found and apiId unchanged")
    void update_whenFoundAndApiIdUnchanged_updatesAndSaves() {
        LlmApiConfig entity = buildConfig(EXISTING_ID);
        LlmApiConfigRequest request = buildRequest(); // same apiId "openai-gpt4"
        LlmApiConfig saved = buildConfig(EXISTING_ID);
        saved.setModel("gpt-4-turbo");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(saved);

        LlmApiConfigResponse result = service.update(EXISTING_ID, request);

        assertThat(result.id()).isEqualTo(EXISTING_ID);
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("update() updates and saves when found and new apiId is not conflicting")
    void update_whenFoundAndNewApiIdNotConflicting_updatesAndSaves() {
        LlmApiConfig entity = buildConfig(EXISTING_ID);
        // entity has apiId "openai-gpt4"; request changes to "openai-gpt4-turbo"
        LlmApiConfigRequest request = new LlmApiConfigRequest("openai-gpt4-turbo", "gpt-4-turbo",
                "https://api.openai.com", "sk-test", Map.of());
        LlmApiConfig saved = buildConfig(EXISTING_ID);
        saved.setApiId("openai-gpt4-turbo");
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(entity));
        when(repository.existsByApiId("openai-gpt4-turbo")).thenReturn(false);
        when(repository.save(entity)).thenReturn(saved);

        LlmApiConfigResponse result = service.update(EXISTING_ID, request);

        assertThat(result.apiId()).isEqualTo("openai-gpt4-turbo");
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("update() throws NoSuchElementException when id not found")
    void update_whenIdNotFound_throwsNoSuchElementException() {
        when(repository.findById(MISSING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MISSING_ID, buildRequest()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(MISSING_ID.toString());
    }

    @Test
    @DisplayName("update() throws IllegalArgumentException when new apiId conflicts with existing record")
    void update_whenNewApiIdConflictsWithExisting_throwsIllegalArgumentException() {
        LlmApiConfig entity = buildConfig(EXISTING_ID); // apiId = "openai-gpt4"
        // request attempts to change apiId to "azure-gpt4" which already exists
        LlmApiConfigRequest request = new LlmApiConfigRequest("azure-gpt4", "gpt-4",
                "https://api.azure.com", "sk-azure", Map.of());
        when(repository.findById(EXISTING_ID)).thenReturn(Optional.of(entity));
        when(repository.existsByApiId("azure-gpt4")).thenReturn(true);

        assertThatThrownBy(() -> service.update(EXISTING_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azure-gpt4");
    }

    // ─── delete(id) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete(id) delegates to repository.deleteById")
    void delete_delegatesToRepositoryDeleteById() {
        service.delete(EXISTING_ID);

        verify(repository).deleteById(EXISTING_ID);
    }
}
