package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryResponse;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryChange;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryChangeType;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * AP reads #2670, amendment C.1 (S21 ruling Q2): the petty-expense category history serves a nullable {@code
 * actorName} beside {@code actor}, resolved when the response is built: one lookup across every category on the
 * list, and freshly on a replayed {@code requestId}, never from the kept copy. Null when not known, never the
 * username.
 */
@DisplayName("Petty-expense category history: actorName on the list and on a replay (#2670 C.1)")
class PettyExpenseCategoryActorNamesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    private final PettyExpenseCategoryRepository categories = mock(PettyExpenseCategoryRepository.class);
    private final PettyExpenseCategoryChangeRepository changes = mock(PettyExpenseCategoryChangeRepository.class);
    private final GLMappingRepository glMappings = mock(GLMappingRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final ActorDisplayNames actorNames = mock(ActorDisplayNames.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PettyExpenseCategoryServiceImpl service;

    @BeforeEach
    void wire() {
        service = new PettyExpenseCategoryServiceImpl(
                categories,
                changes,
                mock(PostingCategoryRepository.class),
                mock(MappingKeyRepository.class),
                glMappings,
                mock(GLAccountRepository.class),
                mock(PettyExpenseCategoryFacts.class),
                zoneResolver,
                CLOCK,
                mock(EntityManager.class),
                objectMapper,
                actorNames);
        when(zoneResolver.today()).thenReturn(LocalDate.of(2026, 10, 9));
        when(glMappings.findByMappingKey_MappingKeyId(any())).thenReturn(List.of());
        when(actorNames.namesOf(anyCollection())).thenReturn(Map.of("controller.cfo", "Dana Reyes"));
    }

    private static PettyExpenseCategory category(String code) {
        PettyExpenseCategory category = new PettyExpenseCategory();
        category.setPettyExpenseCategoryId(UUID.nameUUIDFromBytes(code.getBytes()));
        category.setMappingKeyId(UUID.nameUUIDFromBytes(("key-" + code).getBytes()));
        category.setCode(code);
        category.setLabel(code);
        category.setStatus(PettyExpenseCategoryStatus.ACTIVE);
        return category;
    }

    private static PettyExpenseCategoryChange change(PettyExpenseCategory category, String actor, int second) {
        PettyExpenseCategoryChange change = new PettyExpenseCategoryChange();
        change.setPettyExpenseCategoryId(category.getPettyExpenseCategoryId());
        change.setCode(category.getCode());
        change.setChangeType(PettyExpenseCategoryChangeType.RELABEL);
        change.setOldValue("Before");
        change.setNewValue("After");
        change.setActor(actor);
        change.setJustification("Cashiers asked for a clearer label");
        change.setChangedAt(CLOCK.instant().plusSeconds(second));
        return change;
    }

    @Test
    @DisplayName("the list resolves every category's rows in one lookup; a known actor is named, an unknown one null")
    void listInOneLookup() {
        PettyExpenseCategory meals = category("STAFF_MEALS");
        PettyExpenseCategory fuel = category("VEHICLE_FUEL");
        when(categories.findAllByOrderByCodeAsc()).thenReturn(List.of(meals, fuel));
        when(changes.findAllByOrderByChangedAtAscChangeIdAsc())
                .thenReturn(List.of(
                        change(meals, "controller.cfo", 1),
                        change(fuel, "clerk.ana", 2),
                        change(fuel, "controller.cfo", 3)));

        List<PettyExpenseCategoryResponse> listed = service.list().categories();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(actorNames, times(1)).namesOf(asked.capture());
        assertThat(asked.getValue()).containsExactlyInAnyOrder("controller.cfo", "clerk.ana", "controller.cfo");
        assertThat(listed.get(0).history()).singleElement().satisfies(row -> {
            assertThat(row.actor()).isEqualTo("controller.cfo");
            assertThat(row.actorName()).isEqualTo("Dana Reyes");
        });
        assertThat(listed.get(1).history())
                .extracting(
                        PettyExpenseCategoryResponse.HistoryItem::actor,
                        PettyExpenseCategoryResponse.HistoryItem::actorName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("clerk.ana", null),
                        org.assertj.core.groups.Tuple.tuple("controller.cfo", "Dana Reyes"));
        assertThat(listed.get(1).history().get(1).toString()).doesNotContain("Dana Reyes");
    }

    @Test
    @DisplayName("a replayed requestId resolves the names now, never from the kept copy, and never falls back to the"
            + " username")
    void replayResolvesNow() {
        UUID requestId = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
        String justification = "We stopped buying fuel in cash";
        PettyExpenseCategoryResponse kept = new PettyExpenseCategoryResponse(
                "VEHICLE_FUEL",
                "Vehicle fuel",
                null,
                PettyExpenseCategoryStatus.INACTIVE,
                3,
                null,
                null,
                List.of(
                        new PettyExpenseCategoryResponse.HistoryItem(
                                CLOCK.instant(),
                                "controller.cfo",
                                "Stale Name",
                                PettyExpenseCategoryChangeType.DEACTIVATE,
                                "ACTIVE",
                                "INACTIVE",
                                justification),
                        new PettyExpenseCategoryResponse.HistoryItem(
                                CLOCK.instant(),
                                "clerk.ana",
                                "Another Stale Name",
                                PettyExpenseCategoryChangeType.RELABEL,
                                "Fuel",
                                "Vehicle fuel",
                                "Cashiers asked for a clearer label")),
                false);
        PettyExpenseCategoryChange original = change(category("VEHICLE_FUEL"), "controller.cfo", 0);
        original.setRequestId(requestId);
        original.setRequestHash(new RegisterFloatServiceImpl.Hash()
                .field("DEACTIVATE")
                .field("VEHICLE_FUEL")
                .field(justification)
                .digest());
        original.setResponseJson(objectMapper.writeValueAsString(kept));
        when(changes.findByRequestId(requestId)).thenReturn(Optional.of(original));

        PettyExpenseCategoryResponse replayed =
                service.deactivate("VEHICLE_FUEL", new PettyExpenseCategoryDeactivateRequest(justification, requestId));

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.history())
                .extracting(
                        PettyExpenseCategoryResponse.HistoryItem::actor,
                        PettyExpenseCategoryResponse.HistoryItem::actorName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("controller.cfo", "Dana Reyes"),
                        org.assertj.core.groups.Tuple.tuple("clerk.ana", null));
        verify(actorNames, times(1)).namesOf(anyCollection());
    }
}
