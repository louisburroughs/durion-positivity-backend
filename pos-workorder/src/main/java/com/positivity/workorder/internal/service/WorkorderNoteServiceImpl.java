package com.positivity.workorder.internal.service;

import com.positivity.domainevents.workorder.WorkorderNoteAddedV1;
import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.dto.AddWorkorderNoteRequest;
import com.positivity.workorder.internal.dto.WorkorderNoteResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderNote;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.WorkorderNoteRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records workorder notes and publishes {@link WorkorderNoteAddedV1} to the transactional outbox
 * (issue #1584), so the note and its fact commit together.
 *
 * <p>When Kafka publishing is disabled the outbox writer bean is absent and the note is simply
 * saved, mirroring {@link WorkorderFactPublisher} — the local record never depends on the feed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderNoteServiceImpl implements WorkorderNoteService {

    private final Clock clock;
    private final WorkorderNoteRepository workorderNoteRepository;
    private final WorkorderRepository workorderRepository;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Override
    @NonNull
    @Transactional
    public WorkorderNoteResponse addNote(
            @NonNull UUID workorderId, @NonNull AddWorkorderNoteRequest request, @Nullable String authoredBy) {
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new WorkorderNotFoundException(workorderId));

        WorkorderNote saved = workorderNoteRepository.save(WorkorderNote.builder()
                .workorderId(workorderId)
                .noteType(normalize(request.getNoteType()))
                .noteText(request.getNoteText().trim())
                .authoredBy(normalize(authoredBy))
                .build());

        publish(workorder, saved);

        log.info("Recorded workorder note noteId={} workorderId={}", saved.getNoteId(), workorderId);
        return WorkorderNoteResponse.from(saved);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<WorkorderNoteResponse> listNotes(@NonNull UUID workorderId) {
        if (!workorderRepository.existsById(workorderId)) {
            throw new WorkorderNotFoundException(workorderId);
        }
        return workorderNoteRepository.findByWorkorderIdOrderByCreatedAtDesc(workorderId).stream()
                .map(WorkorderNoteResponse::from)
                .toList();
    }

    private void publish(Workorder workorder, WorkorderNote note) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // createdAt is stamped by the auditing listener at persist; fall back only if a caller
        // supplied its own entity manager behaviour that skipped it.
        Instant addedAt = note.getCreatedAt() != null ? note.getCreatedAt() : Instant.now(clock);
        WorkorderNoteAddedV1 payload = new WorkorderNoteAddedV1(
                workorder.getId(),
                workorder.getWorkorderNumber(),
                note.getNoteId(),
                workorder.getCustomerId(),
                workorder.getVehicleId(),
                workorder.getShopId(),
                note.getNoteType(),
                note.getNoteText(),
                note.getAuthoredBy(),
                addedAt);
        // Keyed by the workorder id so a workorder's facts stay ordered on one partition.
        writer.publish(
                WorkorderNoteAddedV1.EVENT_TYPE, WorkorderNoteAddedV1.SCHEMA_VERSION, workorder.getId(), payload);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
