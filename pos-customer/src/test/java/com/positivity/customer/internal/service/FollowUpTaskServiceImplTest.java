package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.CloseFollowUpTaskRequest;
import com.positivity.customer.internal.dto.CreateFollowUpTaskRequest;
import com.positivity.customer.internal.dto.FollowUpTaskResponse;
import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Story #1153: a follow-up queue that cannot be emptied without recording what happened. */
@ExtendWith(MockitoExtension.class)
class FollowUpTaskServiceImplTest {

    private static final UUID PARTY = UUID.fromString("01960003-0000-7000-8000-000000000020");
    private static final UUID TASK = UUID.fromString("01960003-0000-7000-8000-000000000021");
    private static final UUID BOOKED_WORKORDER = UUID.fromString("01960003-0000-7000-8000-000000000022");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private FollowUpTaskRepository taskRepository;

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    private FollowUpTaskServiceImpl service() {
        return new FollowUpTaskServiceImpl(clock, taskRepository, commercialPartyRepository, personPartyRepository);
    }

    private static FollowUpTask openTask() {
        return FollowUpTask.builder()
                .taskId(TASK)
                .partyId(PARTY)
                .type(FollowUpType.DECLINED_SERVICE_FOLLOWUP)
                .status(FollowUpStatus.OPEN)
                .build();
    }

    private static CloseFollowUpTaskRequest close() {
        return new CloseFollowUpTaskRequest("Customer booked for 12 Aug", BOOKED_WORKORDER, null);
    }

    @Test
    @DisplayName("raising a task against an unknown party is refused")
    void refusesUnknownParty() {
        when(commercialPartyRepository.existsById(PARTY)).thenReturn(false);
        when(personPartyRepository.existsById(PARTY)).thenReturn(false);
        CreateFollowUpTaskRequest request =
                new CreateFollowUpTaskRequest(FollowUpType.GENERAL, null, null, null, null, null, null);

        assertThatThrownBy(() -> service().create(PARTY, request)).isInstanceOf(CrmResourceNotFoundException.class);
    }

    @Test
    @DisplayName("completion records the outcome and links the booking the hand-off produced")
    void completionRecordsOutcomeAndBooking() {
        when(taskRepository.findById(TASK)).thenReturn(Optional.of(openTask()));
        when(taskRepository.save(any(FollowUpTask.class))).thenAnswer(inv -> inv.getArgument(0));

        FollowUpTaskResponse response = service().complete(TASK, close());

        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.outcome()).isEqualTo("Customer booked for 12 Aug");
        assertThat(response.bookedWorkorderId()).isEqualTo(BOOKED_WORKORDER);
        assertThat(response.closedAt()).isEqualTo(Instant.parse("2026-07-28T12:00:00Z"));
    }

    @Test
    @DisplayName("dismissal is recorded distinctly from completion so queue reporting stays meaningful")
    void dismissalIsDistinctFromCompletion() {
        when(taskRepository.findById(TASK)).thenReturn(Optional.of(openTask()));
        when(taskRepository.save(any(FollowUpTask.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().dismiss(TASK, close()).status()).isEqualTo("DISMISSED");
    }

    @Test
    @DisplayName("a closed task cannot be reworked — its recorded outcome must survive")
    void closedTaskCannotBeModified() {
        FollowUpTask done = openTask();
        done.setStatus(FollowUpStatus.DONE);
        when(taskRepository.findById(TASK)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service().complete(TASK, close()))
                .isInstanceOf(CrmUnprocessableEntityException.class)
                .hasMessageContaining("already DONE");
        assertThatThrownBy(() -> service().assign(TASK, "csr-2")).isInstanceOf(CrmUnprocessableEntityException.class);
    }

    @Test
    @DisplayName("an unknown task id is a 404, not a silent no-op")
    void unknownTaskIsNotFound() {
        lenient().when(taskRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(TASK)).isInstanceOf(CrmResourceNotFoundException.class);
    }
}
