package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.entity.BillSourceFile;
import com.positivity.accounting.internal.billintake.entity.BillSourceFileContent;
import com.positivity.accounting.internal.billintake.enums.BillSourceFileState;
import com.positivity.accounting.internal.billintake.repository.BillSourceFileContentRepository;
import com.positivity.accounting.internal.billintake.repository.BillSourceFileRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link FileStore} over Postgres: {@code bill_source_file} for the metadata, {@code bill_source_file_content} for the bytes. */
@Service
@RequiredArgsConstructor
public class DbFileStore implements FileStore {

    private static final int NAME_LENGTH = 255;
    private static final int TYPE_LENGTH = 100;

    private final BillSourceFileRepository files;
    private final BillSourceFileContentRepository contents;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull StoredFile quarantine(
            byte @NonNull [] bytes,
            @NonNull String contentType,
            @Nullable String originalName,
            @NonNull String actor) {
        BillSourceFile file = newFile(bytes, contentType, originalName, actor, BillSourceFileState.QUARANTINED);
        files.saveAndFlush(file);
        contents.save(new BillSourceFileContent(file.getFileId(), bytes));
        return stored(file);
    }

    @Override
    @Transactional
    public @NonNull StoredFile recordRejected(
            byte @NonNull [] bytes,
            @NonNull String contentType,
            @Nullable String originalName,
            @NonNull String actor,
            @NonNull Instant retentionUntil) {
        BillSourceFile file = newFile(bytes, contentType, originalName, actor, BillSourceFileState.REJECTED);
        file.setRetentionUntil(retentionUntil);
        files.saveAndFlush(file);
        return stored(file);
    }

    @Override
    @Transactional
    public void promote(@NonNull UUID fileId, @Nullable Integer pageCount) {
        BillSourceFile file = require(fileId);
        file.setState(BillSourceFileState.STORED);
        file.setPageCount(pageCount);
        files.save(file);
    }

    @Override
    @Transactional
    public void reject(@NonNull UUID fileId, @NonNull Instant retentionUntil) {
        BillSourceFile file = require(fileId);
        file.setState(BillSourceFileState.REJECTED);
        file.setRetentionUntil(retentionUntil);
        files.save(file);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<StoredContent> open(@NonNull UUID fileId) {
        return files.findById(fileId)
                .filter(file -> file.getState() != BillSourceFileState.PURGED)
                .flatMap(file -> contents.findById(fileId)
                        .map(content -> new StoredContent(
                                fileId, file.getSha256(), file.getContentType(), file.getSizeBytes(), content.getContent())));
    }

    @Override
    @Transactional
    public @NonNull UUID storeDerived(
            @NonNull UUID derivedFrom,
            int pageNumber,
            @NonNull String contentType,
            byte @NonNull [] bytes,
            @NonNull String actor) {
        BillSourceFile file = newFile(bytes, contentType, null, actor, BillSourceFileState.STORED);
        file.setDerivedFrom(derivedFrom);
        file.setPageNumber(pageNumber);
        files.saveAndFlush(file);
        contents.save(new BillSourceFileContent(file.getFileId(), bytes));
        return file.getFileId();
    }

    @Override
    @Transactional
    public void setRetentionUntil(@NonNull UUID fileId, @Nullable Instant retentionUntil) {
        BillSourceFile file = require(fileId);
        file.setRetentionUntil(retentionUntil);
        files.save(file);
    }

    @Override
    @Transactional
    public void setLegalHold(@NonNull UUID fileId, boolean onHold, @Nullable String reason) {
        BillSourceFile file = require(fileId);
        file.setLegalHold(onHold);
        file.setLegalHoldReason(onHold ? reason : null);
        files.save(file);
    }

    @Override
    @Transactional
    public void purge(@NonNull UUID fileId) {
        BillSourceFile file = require(fileId);
        List<BillSourceFile> all = new ArrayList<>(files.findByDerivedFrom(fileId));
        all.add(file);
        Instant now = Instant.now(clock);
        contents.deleteByFileIdIn(all.stream().map(BillSourceFile::getFileId).toList());
        for (BillSourceFile purged : all) {
            purged.setState(BillSourceFileState.PURGED);
            purged.setPurgedAt(now);
        }
        files.saveAll(all);
    }

    private BillSourceFile require(UUID fileId) {
        return files.findById(fileId).orElseThrow(() -> new IllegalStateException("bill source file is missing"));
    }

    private static BillSourceFile newFile(
            byte[] bytes, String contentType, String originalName, String actor, BillSourceFileState state) {
        BillSourceFile file = new BillSourceFile();
        file.setSha256(sha256(bytes));
        file.setSizeBytes(bytes.length);
        file.setContentType(truncate(contentType, TYPE_LENGTH));
        file.setOriginalName(originalName == null ? null : truncate(originalName, NAME_LENGTH));
        file.setState(state);
        file.setCreatedBy(truncate(actor, 50));
        return file;
    }

    private static StoredFile stored(BillSourceFile file) {
        return new StoredFile(file.getFileId(), file.getSha256(), file.getSizeBytes(), file.getContentType());
    }

    /** The sha-256 of {@code bytes} in lower-case hex: the only thing about a file that logs and audit rows carry. */
    public static @NonNull String sha256(byte @NonNull [] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
