package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.config.BillIntakeProperties;
import com.positivity.accounting.internal.billintake.dto.BillIntakeItemResponse;
import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeRejectionReason;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.billintake.repository.BillSourceFileRepository;
import com.positivity.accounting.internal.exception.VendorBillException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal entry of bill intake: what the upload endpoint calls (CAP:550 S25, #2518). It makes one draft per
 * file, {@code QUARANTINED} with its file stored apart, or {@code FILE_REJECTED} at once when the bytes are not a PDF,
 * JPEG, PNG, HEIC or TIFF (magic bytes; nothing is scanned or stored for such a file). Scanning, inspecting and
 * reading are the {@link BillIntakeProcessor}'s, later, so an outage of either never loses or rejects an upload.
 *
 * <p><b>Idempotency.</b> A request id covers a whole request of up to 10 files, but a draft's request id is unique per
 * tenant and channel, so file <i>i</i> of the request is stored under a request id derived from the request's and
 * <i>i</i>. A replay finds the first file's draft, checks that every file has the same sha-256 and that the request has
 * the same number of files, and answers the drafts it made; any difference is {@code 409 IDEMPOTENCY_CONFLICT}, as is
 * a request that lost a race to the same id. Nothing here logs a file name or bytes (ADR-0072).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillIntakeEntry {

    /** One file of a request, as received. {@code contentType} and {@code originalName} are never trusted. */
    public record IncomingFile(byte @NonNull [] bytes, @Nullable String contentType, @Nullable String originalName) {}

    /** The drafts a request made, and whether they were made by an earlier request with the same id. */
    public record Received(@NonNull List<BillIntakeItemResponse> items, boolean replayed) {}

    private final BillIntakeItemRepository items;
    private final BillSourceFileRepository sourceFiles;
    private final FileStore fileStore;
    private final BillIntakeReferences references;
    private final BillIntakeAuditRecorder audit;
    private final BillIntakeProperties properties;
    private final Clock clock;

    /**
     * Receives the files of one request.
     *
     * @throws VendorBillException 400 {@code VALIDATION_ERROR} for no file or too many; 413 {@code PAYLOAD_TOO_LARGE}
     *     for a file over the limit; 409 {@code IDEMPOTENCY_CONFLICT} for a request id used with other content
     */
    @Transactional
    public @NonNull Received receive(
            @NonNull List<IncomingFile> files,
            @NonNull BillIntakeChannel channel,
            @NonNull UUID requestId,
            @NonNull String actor) {
        validate(files);
        List<UUID> fileRequestIds = new ArrayList<>();
        for (int i = 0; i <= files.size(); i++) {
            fileRequestIds.add(fileRequestId(requestId, i));
        }
        Optional<BillIntakeItem> first = items.findByChannelAndRequestId(channel, fileRequestIds.get(0));
        if (first.isPresent()) {
            return new Received(replay(files, channel, fileRequestIds), true);
        }
        Instant now = Instant.now(clock);
        List<BillIntakeItemResponse> made = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            made.add(receiveOne(files.get(i), channel, fileRequestIds.get(i), actor, now));
        }
        return new Received(made, false);
    }

    private void validate(List<IncomingFile> files) {
        if (files.isEmpty()) {
            throw new VendorBillException(VendorBillException.Code.VALIDATION_ERROR, "Send at least one file");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "Send at most " + properties.getMaxFiles() + " files in one request");
        }
        for (IncomingFile file : files) {
            if (file.bytes().length > properties.getMaxFileBytes()) {
                throw new VendorBillException(
                        VendorBillException.Code.PAYLOAD_TOO_LARGE,
                        "A file is over the limit of " + (properties.getMaxFileBytes() / (1024 * 1024)) + " MB");
            }
        }
    }

    private BillIntakeItemResponse receiveOne(
            IncomingFile file, BillIntakeChannel channel, UUID fileRequestId, String actor, Instant now) {
        BillIntakeItem item = new BillIntakeItem();
        item.setChannel(channel);
        item.setRequestId(fileRequestId);
        item.setUploadedBy(actor);
        item.setUploadedAt(now);
        Optional<BillFileTypes.Type> type = BillFileTypes.detect(file.bytes());
        FileStore.StoredFile stored;
        if (type.isEmpty()) {
            stored = fileStore.recordRejected(
                    file.bytes(),
                    declaredOrDefault(file.contentType()),
                    file.originalName(),
                    actor,
                    now.plus(properties.getRejectedRetention()));
            item.setStatus(BillIntakeStatus.FILE_REJECTED);
            item.setRejectionReason(BillIntakeRejectionReason.TYPE_NOT_ALLOWED.name());
        } else {
            stored = fileStore.quarantine(file.bytes(), type.get().contentType(), file.originalName(), actor);
            item.setStatus(BillIntakeStatus.QUARANTINED);
        }
        item.setSourceFileId(stored.fileId());
        item.setIntakeReference(references.next());
        try {
            items.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            throw new VendorBillException(
                    VendorBillException.Code.IDEMPOTENCY_CONFLICT,
                    "The requestId was used by a concurrent request; send it again to read its result");
        }
        audit.record(
                item.getIntakeId(),
                BillIntakeAuditRecorder.UPLOAD,
                actor,
                null,
                null,
                "{\"sha256\":\"" + stored.sha256() + "\",\"sizeBytes\":" + stored.sizeBytes() + ",\"status\":\""
                        + item.getStatus() + "\"}");
        log.info(
                "Bill upload received | intakeReference={} | status={} | sha256={} | sizeBytes={} | contentType={}",
                item.getIntakeReference(),
                item.getStatus(),
                stored.sha256(),
                stored.sizeBytes(),
                stored.contentType());
        return response(item, stored.sha256());
    }

    private List<BillIntakeItemResponse> replay(List<IncomingFile> files, BillIntakeChannel channel, List<UUID> ids) {
        if (items.findByChannelAndRequestId(channel, ids.get(files.size())).isPresent()) {
            throw replayConflict();
        }
        List<BillIntakeItemResponse> same = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            BillIntakeItem item = items.findByChannelAndRequestId(channel, ids.get(i))
                    .orElseThrow(BillIntakeEntry::replayConflict);
            String sha = item.getSourceFileId() == null
                    ? null
                    : sourceFiles.findById(item.getSourceFileId()).map(f -> f.getSha256()).orElse(null);
            if (sha == null || !sha.equals(DbFileStore.sha256(files.get(i).bytes()))) {
                throw replayConflict();
            }
            same.add(response(item, sha));
        }
        return same;
    }

    private static VendorBillException replayConflict() {
        return new VendorBillException(
                VendorBillException.Code.IDEMPOTENCY_CONFLICT,
                "The requestId was used before with other files; use a new requestId for new files");
    }

    private static BillIntakeItemResponse response(BillIntakeItem item, @Nullable String sha256) {
        return new BillIntakeItemResponse(
                item.getIntakeId(),
                item.getIntakeReference(),
                item.getChannel(),
                item.getStatus(),
                item.getRejectionReason(),
                sha256,
                item.getUploadedAt());
    }

    private static String declaredOrDefault(@Nullable String declared) {
        return declared == null || declared.isBlank() ? "application/octet-stream" : declared;
    }

    /** The request id file {@code index} of a request is stored under: a name-based UUID of the two. */
    static @NonNull UUID fileRequestId(@NonNull UUID requestId, int index) {
        return UUID.nameUUIDFromBytes((requestId + "#" + index).getBytes(StandardCharsets.UTF_8));
    }
}
