package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.RolePersona;
import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Issue #1613, D4: the persona refresh mechanism, and specifically that every tier is fail-soft.
 *
 * <p>The failure this issue exists to fix was silent — a role added upstream and not to Java broke
 * nothing and alerted nobody — so the cases that matter most here are the ones where the fetch fails
 * and the service has to keep serving what it already had.
 */
@DisplayName("RolePersonaRefresher (#1613)")
class RolePersonaRefresherTest {

    private StubSource source;
    private RolePersonaSnapshotHolder holder;
    private SystemPromptRepository repository;
    private RolePersonaRefresher refresher;

    @BeforeEach
    void setUp() {
        source = new StubSource();
        holder = TestSnapshots.emptyHolder();
        repository = Mockito.mock(SystemPromptRepository.class);
        Mockito.when(repository.findByName(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repository.save(Mockito.any(SystemPrompt.class))).thenAnswer(i -> i.getArgument(0));
        refresher = new RolePersonaRefresher(source, holder, repository);
    }

    @Test
    @DisplayName("a startup pull populates the snapshot and persists one row per eligible role")
    void startupPullPopulatesSnapshotAndRows() {
        source.all = Optional.of(new RolePersonaSource.RolePersonaSnapshotData(
                Instant.EPOCH,
                List.of(
                        TestSnapshots.eligible("ADMIN", 20),
                        TestSnapshots.eligible("TECHNICIAN", 80),
                        new RolePersona("CUSTOMER", null, null, null, null, null, false))));

        assertThat(refresher.refreshAll()).isTrue();

        assertThat(holder.get().rankedAuthorities()).containsExactly("ROLE_ADMIN", "ROLE_TECHNICIAN");
        // The ineligible role gets no row: it can never assemble a ROLE layer, so a persona for it
        // would be dead weight that an administrator could later mistake for a live one.
        assertThat(savedNames()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TECHNICIAN");
    }

    @Test
    @DisplayName("a failed fetch keeps the previous snapshot rather than blanking it")
    void failedFetchKeepsPreviousSnapshot() {
        source.all = Optional.of(new RolePersonaSource.RolePersonaSnapshotData(
                Instant.EPOCH, List.of(TestSnapshots.eligible("ADMIN", 20))));
        refresher.refreshAll();

        source.all = Optional.empty();

        assertThat(refresher.refreshAll()).isFalse();
        // Blanking here would silently demote every admin to the generic fallback persona for as
        // long as security-service was unreachable.
        assertThat(holder.get().rankedAuthorities()).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a failed fetch at boot leaves an empty snapshot and does not throw")
    void failedFetchAtBootIsSurvivable() {
        source.all = Optional.empty();

        assertThat(refresher.refreshAll()).isFalse();
        assertThat(holder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an on-miss fetch merges one role into the held snapshot, in rank order")
    void onMissFetchMergesOneRole() {
        // Tier 2 is what makes a role created after boot work without a restart.
        source.all = Optional.of(new RolePersonaSource.RolePersonaSnapshotData(
                Instant.EPOCH, List.of(TestSnapshots.eligible("TECHNICIAN", 80))));
        refresher.refreshAll();
        source.one = Optional.of(TestSnapshots.eligible("ADMIN", 20));

        assertThat(refresher.refreshRole("ROLE_ADMIN")).isTrue();

        assertThat(holder.get().rankedAuthorities()).containsExactly("ROLE_ADMIN", "ROLE_TECHNICIAN");
        assertThat(savedNames()).contains("ROLE_ADMIN");
        assertThat(source.requestedNames).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("an on-miss fetch that finds nothing reports failure and changes nothing")
    void onMissFetchFailureChangesNothing() {
        source.one = Optional.empty();

        assertThat(refresher.refreshRole("ROLE_NEVER_HEARD_OF_IT")).isFalse();
        assertThat(holder.get().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an on-miss fetch of an ineligible role records it without writing a persona row")
    void onMissFetchOfIneligibleRoleWritesNoRow() {
        source.one = Optional.of(new RolePersona("CUSTOMER", null, null, null, null, null, false));

        assertThat(refresher.refreshRole("ROLE_CUSTOMER")).isTrue();

        // Knowing about it is the point: it stops the next request counting this as a sync gap.
        assertThat(holder.get().isIneligible("ROLE_CUSTOMER")).isTrue();
        assertThat(savedNames()).isEmpty();
    }

    @Test
    @DisplayName("a persona whose text is unchanged is not rewritten")
    void unchangedPersonaIsNotRewritten() {
        source.all = Optional.of(new RolePersonaSource.RolePersonaSnapshotData(
                Instant.EPOCH, List.of(TestSnapshots.eligible("ADMIN", 20))));
        refresher.refreshAll();

        SystemPrompt existing = new SystemPrompt();
        existing.setName("ROLE_ADMIN");
        existing.setContent(holder.get().personaText("ROLE_ADMIN").orElseThrow());
        Mockito.reset(repository);
        Mockito.when(repository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(existing));

        refresher.refreshAll();

        Mockito.verify(repository, Mockito.never()).save(Mockito.any(SystemPrompt.class));
    }

    @Test
    @DisplayName("a row that fails to persist does not abandon the rest of the sync")
    void oneBadRowDoesNotAbandonTheSync() {
        Mockito.reset(repository);
        Mockito.when(repository.findByName("ROLE_ADMIN")).thenThrow(new IllegalStateException("boom"));
        Mockito.when(repository.findByName("ROLE_TECHNICIAN")).thenReturn(Optional.empty());
        Mockito.when(repository.save(Mockito.any(SystemPrompt.class))).thenAnswer(i -> i.getArgument(0));
        source.all = Optional.of(new RolePersonaSource.RolePersonaSnapshotData(
                Instant.EPOCH, List.of(TestSnapshots.eligible("ADMIN", 20), TestSnapshots.eligible("TECHNICIAN", 80))));

        assertThat(refresher.refreshAll()).isTrue();

        assertThat(savedNames()).containsExactly("ROLE_TECHNICIAN");
    }

    private List<String> savedNames() {
        var captor = org.mockito.ArgumentCaptor.forClass(SystemPrompt.class);
        Mockito.verify(repository, Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(SystemPrompt::getName).toList();
    }

    /** Fail-soft by contract, so the stub returns Optionals rather than throwing. */
    private static final class StubSource implements RolePersonaSource {
        private Optional<RolePersonaSnapshotData> all = Optional.empty();
        private Optional<RolePersona> one = Optional.empty();
        private final List<String> requestedNames = new ArrayList<>();

        @Override
        public Optional<RolePersonaSnapshotData> fetchAll() {
            return all;
        }

        @Override
        public Optional<RolePersona> fetchOne(String roleName) {
            requestedNames.add(roleName);
            return one;
        }
    }
}
