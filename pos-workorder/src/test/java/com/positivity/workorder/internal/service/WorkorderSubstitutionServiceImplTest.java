package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import com.positivity.workorder.internal.entity.WorkOrderPartSubstitution;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.PriceLockStatus;
import com.positivity.workorder.internal.enums.SubstitutionStatus;
import com.positivity.workorder.internal.repository.WorkOrderPartSubstitutionRepository;
import com.positivity.workorder.internal.repository.WorkorderPartAdjustmentEventRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * Part substitution (#49): the original line is price-locked and flagged, a mirrored substitute
 * line is created, and an immutable substitution record captures the pricing snapshot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderSubstitutionServiceImpl")
class WorkorderSubstitutionServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb11");
    private static final UUID ORIGINAL_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb12");
    private static final UUID SUBSTITUTE_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb13");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb14");
    private static final UUID NEW_PART_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb15");
    private static final UUID EVENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbb16");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String IDEMPOTENCY_KEY = "substitution-key-1";

    @Mock
    private WorkorderPartRepository workorderPartRepository;

    @Mock
    private WorkOrderPartSubstitutionRepository substitutionRepository;

    @Mock
    private WorkorderPartAdjustmentEventRepository adjustmentEventRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private WorkorderFactPublisher workorderFactPublisher;

    @Mock
    private WorkorderRepository workorderRepository;

    private WorkorderSubstitutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderSubstitutionServiceImpl(
                workorderPartRepository,
                substitutionRepository,
                adjustmentEventRepository,
                idempotencyService,
                new ObjectMapper(),
                workorderFactPublisher,
                workorderRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("jane.smith", "n/a", List.of());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);

        when(workorderPartRepository.save(any())).thenAnswer(invocation -> {
            WorkorderPart part = invocation.getArgument(0);
            if (part.getId() == null) {
                part.setId(NEW_PART_ID);
            }
            return part;
        });
        when(adjustmentEventRepository.save(any())).thenAnswer(invocation -> {
            WorkorderPartAdjustmentEvent event = invocation.getArgument(0);
            if (event.getId() == null) {
                event.setId(EVENT_ID);
            }
            return event;
        });
        when(idempotencyService.getExistingPartAdjustmentEventId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static WorkorderPart originalPart(String consumed) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        return WorkorderPart.builder()
                .id(ORIGINAL_PART_ID)
                .workorder(workorder)
                .productEntityId(PRODUCT_ID)
                .description("Brake pad set")
                .quantity(new BigDecimal("1"))
                .unitPrice(new BigDecimal("49.99"))
                .lineTotal(new BigDecimal("49.99"))
                .quantityConsumed(new BigDecimal(consumed))
                .build();
    }

    private WorkorderPartAdjustmentEventResponse substitute() {
        return service.substitutePartLine(
                WORKORDER_ID, ORIGINAL_PART_ID, SUBSTITUTE_PART_ID, "OUT_OF_STOCK", null, "supplier backorder");
    }

    @Test
    void locksTheOriginalLineAndMirrorsItAsTheSubstitute() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("0")));

        WorkorderPartAdjustmentEventResponse response = substitute();

        ArgumentCaptor<WorkorderPart> saved = ArgumentCaptor.forClass(WorkorderPart.class);
        verify(workorderPartRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        WorkorderPart original = saved.getAllValues().get(0);
        assertThat(original.getIsSubstituted()).isTrue();
        assertThat(original.getPriceLockStatus()).isEqualTo(PriceLockStatus.LOCKED);
        assertThat(original.getOriginalProductId()).isEqualTo(PRODUCT_ID);

        WorkorderPart substitute = saved.getAllValues().get(1);
        assertThat(substitute.getProductEntityId()).isEqualTo(SUBSTITUTE_PART_ID);
        assertThat(substitute.getDescription()).isEqualTo("Brake pad set (Substitute)");
        assertThat(substitute.getUnitPrice()).isEqualByComparingTo("49.99");
        assertThat(substitute.getQuantityConsumed()).isEqualByComparingTo("0");
        assertThat(substitute.getPriceLockStatus()).isEqualTo(PriceLockStatus.LOCKED);

        assertThat(response.getAdjustmentType()).isEqualTo("SUBSTITUTION");
        assertThat(response.getPerformedBy()).isEqualTo("jane.smith");
        assertThat(response.getPerformedAt()).isEqualTo(NOW);
        assertThat(response.getNotes()).isEqualTo("supplier backorder");
        verify(workorderFactPublisher, org.mockito.Mockito.times(2)).markChanged(WORKORDER_ID);
    }

    @Test
    void recordsAnImmutableSubstitutionWithItsPricingSnapshot() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("0")));

        substitute();

        ArgumentCaptor<WorkOrderPartSubstitution> record = ArgumentCaptor.forClass(WorkOrderPartSubstitution.class);
        verify(substitutionRepository).save(record.capture());
        assertThat(record.getValue().getSelectedBy()).isEqualTo("jane.smith");
        assertThat(record.getValue().getSelectedAt()).isEqualTo(NOW);
        assertThat(record.getValue().getReasonCode()).isEqualTo("OUT_OF_STOCK");
        assertThat(record.getValue().getStatus()).isEqualTo(SubstitutionStatus.APPLIED);
        assertThat(record.getValue().getOriginalPartNumberSnapshot()).isEqualTo("Brake pad set");
        assertThat(record.getValue().getPricingSnapshot()).contains("49.99");
    }

    @Test
    void resolvesTheOriginalProductFromANonInventoryLineWhenThereIsNoProductId() {
        WorkorderPart part = originalPart("0");
        part.setProductEntityId(null);
        part.setNonInventoryProductEntityId(SUBSTITUTE_PART_ID);
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(part));

        service.substitutePartLine(WORKORDER_ID, ORIGINAL_PART_ID, PRODUCT_ID, "OUT_OF_STOCK", null, null);

        assertThat(part.getOriginalProductId()).isEqualTo(SUBSTITUTE_PART_ID);
    }

    @Test
    void fallsBackToThePartIdWhenNeitherProductReferenceIsSet() {
        WorkorderPart part = originalPart("0");
        part.setProductEntityId(null);
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(part));

        substitute();

        assertThat(part.getOriginalProductId()).isEqualTo(ORIGINAL_PART_ID);
    }

    @Test
    void keepsAnAlreadyRecordedOriginalProductReference() {
        WorkorderPart part = originalPart("0");
        part.setOriginalProductId(SUBSTITUTE_PART_ID);
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(part));

        substitute();

        assertThat(part.getOriginalProductId()).isEqualTo(SUBSTITUTE_PART_ID);
    }

    @Test
    void resolvesTheWorkorderThroughTheServiceLineWhenThePartHasNoDirectLink() {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        WorkorderPart part = originalPart("0");
        part.setWorkorder(null);
        part.setWorkOrderService(
                WorkorderServiceLine.builder().workOrder(workorder).build());
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(part));

        assertThat(substitute()).isNotNull();
    }

    @Test
    void refusesAPartThatBelongsToAnotherWorkorder() {
        WorkorderPart part = originalPart("0");
        part.getWorkorder().setId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fbbff"));
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(part));

        assertThatThrownBy(this::substitute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong to workorder");
    }

    @Test
    void refusesToSubstituteAPartThatHasAlreadyBeenConsumed() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("1")));

        assertThatThrownBy(this::substitute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been consumed");
    }

    @Test
    void refusesASubstituteThatIsTheSamePart() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("0")));

        assertThatThrownBy(() -> service.substitutePartLine(
                        WORKORDER_ID, ORIGINAL_PART_ID, ORIGINAL_PART_ID, "OUT_OF_STOCK", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void failsForAPartThatDoesNotExist() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::substitute).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void requiresAnAuthenticatedActor() {
        SecurityContextHolder.clearContext();

        // SecurityContextHelper is fail-fast: an unauthenticated call never reaches the
        // substitution logic at all.
        assertThatThrownBy(this::substitute).isInstanceOf(IllegalStateException.class);
        verify(workorderPartRepository, never()).save(any());
    }

    @Test
    void registersTheIdempotencyKeyAgainstTheAdjustmentEvent() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("0")));

        service.substitutePartLine(
                WORKORDER_ID, ORIGINAL_PART_ID, SUBSTITUTE_PART_ID, "OUT_OF_STOCK", IDEMPOTENCY_KEY, null);

        verify(idempotencyService)
                .markKeyProcessedForPartAdjustment(
                        anyString(), org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY), any());
    }

    @Test
    void replaysTheOriginalAdjustmentEventForAKnownIdempotencyKey() {
        when(idempotencyService.getExistingPartAdjustmentEventId(anyString(), anyString()))
                .thenReturn(Optional.of(EVENT_ID));
        when(adjustmentEventRepository.findById(EVENT_ID))
                .thenReturn(Optional.of(WorkorderPartAdjustmentEvent.builder()
                        .id(EVENT_ID)
                        .originalPart(originalPart("0"))
                        .workorderId(WORKORDER_ID)
                        .adjustmentType("SUBSTITUTION")
                        .substitutedWithPartId(NEW_PART_ID)
                        .quantityAdjustment(BigDecimal.ZERO)
                        .reason("OUT_OF_STOCK")
                        .performedBy("jane.smith")
                        .performedAt(NOW)
                        .build()));
        when(workorderPartRepository.findById(NEW_PART_ID))
                .thenReturn(Optional.of(WorkorderPart.builder()
                        .id(NEW_PART_ID)
                        .description("Brake pad set (Substitute)")
                        .build()));

        WorkorderPartAdjustmentEventResponse response = service.substitutePartLine(
                WORKORDER_ID, ORIGINAL_PART_ID, SUBSTITUTE_PART_ID, "OUT_OF_STOCK", IDEMPOTENCY_KEY, null);

        assertThat(response.getId()).isEqualTo(EVENT_ID);
        assertThat(response.getSubstitutedWithPartDescription()).isEqualTo("Brake pad set (Substitute)");
        verify(substitutionRepository, never()).save(any());
    }

    @Test
    void failsWhenTheReplayedAdjustmentEventIsGone() {
        when(idempotencyService.getExistingPartAdjustmentEventId(anyString(), anyString()))
                .thenReturn(Optional.of(EVENT_ID));
        when(adjustmentEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.substitutePartLine(
                        WORKORDER_ID, ORIGINAL_PART_ID, SUBSTITUTE_PART_ID, "OUT_OF_STOCK", IDEMPOTENCY_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Adjustment event not found");
    }

    @Test
    void ignoresABlankIdempotencyKey() {
        when(workorderPartRepository.findById(ORIGINAL_PART_ID)).thenReturn(Optional.of(originalPart("0")));

        service.substitutePartLine(WORKORDER_ID, ORIGINAL_PART_ID, SUBSTITUTE_PART_ID, "OUT_OF_STOCK", "  ", null);

        verify(idempotencyService, never()).getExistingPartAdjustmentEventId(anyString(), anyString());
        verify(idempotencyService, never()).markKeyProcessedForPartAdjustment(anyString(), anyString(), any());
    }
}
